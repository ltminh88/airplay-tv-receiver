package io.github.jqssun.airplay.renderer

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * Async (callback-based) H.264/H.265 decoder → Surface.
 *
 * Why async: the previous synchronous design only drained the decoder's output buffers as a
 * side-effect of feeding a new input frame. For low-frame-rate / static mirror content (idle
 * desktop, home screen) the decoded frames sat in the output buffers and were never released to
 * the Surface, so the screen stayed black or froze on the first frame. With MediaCodec.setCallback,
 * onOutputBufferAvailable fires whenever a frame finishes decoding — independent of input cadence —
 * so frames are rendered continuously even when the source sends them sparsely.
 */
class VideoRenderer {

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var currentH265 = false
    @Volatile private var running = false
    private var videoWidth = 0
    private var videoHeight = 0

    // async plumbing
    private var callbackThread: HandlerThread? = null
    private val availableInputs = ArrayDeque<Int>()        // input buffer indices the codec handed us
    private val pendingFrames = ArrayDeque<Frame>()        // frames waiting for an input buffer
    private data class Frame(val data: ByteArray, val ptsUs: Long)
    // Bound the backlog. Sized to absorb a high-bitrate burst: when macOS ramps the mirror bitrate up
    // (the sharp moments), frames briefly arrive faster than the decoder hands back input buffers. With
    // a small bound (8) that transient burst overflowed -> we panic-cleared and waited for the next IDR,
    // which macOS sends rarely -> the screen froze until a manual reconnect. A larger backlog rides out
    // the burst so the decoder catches up instead of freezing. The Amlogic 1080p HW decoder easily
    // sustains multi-Mbps (it does 4K HEVC), so the bottleneck was this queue, not decode throughput.
    //
    // INPUT backlog bound. We never drop an input frame under normal load (dropping input breaks the
    // H.264 reference chain → corruption, since AirPlay-1 sends IDRs rarely). Latency is kept low NOT by
    // shrinking this queue but by the OUTPUT-side skip (see LATENCY_SKIP_THRESHOLD / onOutputBufferAvailable):
    // when backlogged, the decoder still decodes every frame (refs intact) but stale frames are released
    // WITHOUT display to fast-forward. So this only needs headroom for the decode-vs-arrival transient;
    // if it still fills (genuine wedge) _enqueue backpressures then hard-resyncs.
    private val MAX_PENDING = 16
    // When more than this many INPUT frames are still backlogged, the decoder's current OUTPUT frame is
    // already stale → release it WITHOUT displaying (decode kept for references) to fast-forward to the
    // newest frame. Small so catch-up is aggressive and typing stays responsive; 0-2 in steady state.
    private val LATENCY_SKIP_THRESHOLD = 2
    // How long _enqueue will backpressure (block the TCP feed) before deciding the decoder is truly
    // wedged and hard-resyncing. Long enough to ride out a GL/decoder hiccup, short enough that a real
    // wedge recovers quickly.
    private val BACKPRESSURE_MAX_MS = 1500

    // cache last keyframe so decoder can bootstrap after late surface attach / codec restart
    private var cachedKeyframe: ByteArray? = null
    private var cachedKeyframePts: Long = 0
    private var cachedKeyframeH265 = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set
    // diagnostic: frames actually released to the surface (onOutputBufferAvailable)
    @Volatile private var _renderedTotal = 0L
    private var _renderedAtLastReset = 0L
    // decoder OUTPUT frames = displayed + skipped-for-latency. The stall watchdog keys off THIS (not
    // _renderedTotal) so that intentionally skipping stale display frames during catch-up is never
    // mistaken for a decoder wedge.
    @Volatile private var _outputTotal = 0L
    private var _outputAtLastReset = 0L
    // live snapshot of pendingFrames.size, read off-lock in the output callback for the latency skip
    @Volatile private var _pendingDepth = 0
    // stall watchdog: the Amlogic decoder can wedge after a long session (keeps accepting input but
    // stops emitting output). Detect "fed but nothing rendered" for N seconds and auto-restart the
    // codec so the user doesn't have to disconnect/reconnect by hand.
    private var _stalledSecs = 0
    @Volatile private var _stallRestartNeeded = false
    // frames actually handed to the codec (queueInputBuffer). The stall watchdog keys off this, not
    // "frames received": while we're cleanly skipping to a keyframe we feed nothing, so that gap must
    // NOT look like a stall — only "fed the codec but it emitted nothing" is a real decoder wedge.
    @Volatile private var _queuedTotal = 0L
    private var _queuedAtLastReset = 0L
    // after a reset/overflow, decode must resume only from a fresh IDR (keyframe); feeding P-frames
    // with a broken reference chain produces cursor ghosting/trails and re-stalls the decoder.
    @Volatile private var _waitingForKeyframe = false
    private var _waitingSecs = 0   // bound the keyframe-resync wait so it can never freeze forever
    private var _consecutiveRestarts = 0   // cap watchdog restarts so a true HW wedge can't thrash-loop

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var realtimeDecoderPriority = true
    var operatingRateHint = false
    var scheduledOutputBufferRelease = false
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null

    // GL sharpening: when enabled, decoded frames are routed through a GLES2 unsharp-mask stage
    // before reaching the display Surface. On any GL failure the path falls back to direct-Surface
    // rendering so the screen never goes black.
    var sharpenEnabled = true
    var sharpenStrength = 0.5f
    var sharpenContrast = 1.0f      // 1.0 = unchanged; >1 = punchier
    var sharpenSaturation = 1.0f    // 1.0 = unchanged; >1 = richer color (fixes washed-out look)
    private var glSharpen: GlSharpenRenderer? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
    }

    fun setSurface(surface: Surface?) = synchronized(lock) {
        val changed = this.surface !== surface
        this.surface = surface
        if (!changed) return@synchronized
        if (codec != null) stopCodec()
        // Proactively bootstrap on a fresh surface: if the stream already started (we have a cached
        // keyframe + known resolution) but the source isn't sending a new frame yet (static screen),
        // start the decoder now and feed the keyframe so the first connect shows an image instead of
        // staying black until the next IDR / reconnect.
        if (surface != null && cachedKeyframe != null && videoWidth > 0 && videoHeight > 0) {
            startCodec(cachedKeyframeH265)
            cachedKeyframe?.let { _enqueue(it, cachedKeyframePts / 1000) }
            _pumpInputs()
        }
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            val renderedThisSec = _renderedTotal - _renderedAtLastReset
            _renderedAtLastReset = _renderedTotal
            // decoder OUTPUT this second (displayed + latency-skipped). Wedge detection keys off this, not
            // renderedThisSec, because during latency catch-up we intentionally skip displaying frames.
            val outputThisSec = _outputTotal - _outputAtLastReset
            _outputAtLastReset = _outputTotal
            // stall watchdog: frames fed but none rendered => decoder wedged. After 2 such seconds,
            // flag a codec restart (handled in feedFrame under lock).
            // wedge signal: frames keep ARRIVING from the source (fps>0) but the decoder renders
            // nothing. When the Amlogic decoder wedges it stops both emitting output AND handing back
            // input buffers, so a "queued-based" signal goes blind — keying off received frames
            // catches it. The old restart-thrash that motivated avoiding this is gone now: after a
            // restart we skip to a clean keyframe before feeding, so the fresh codec can't re-stall
            // on a broken chain. 3s threshold tolerates the brief keyframe-resync gap.
            val queuedThisSec = _queuedTotal - _queuedAtLastReset
            _queuedAtLastReset = _queuedTotal
            // Safety bound: if we've been skipping while waiting for a keyframe for too long (the
            // source's IDR is late, or detection missed it), stop waiting and resume feeding. A brief
            // visual glitch is far better than a permanent freeze (queued stays 0 -> nothing decodes).
            if (_waitingForKeyframe) {
                _waitingSecs++
                // We only enter keyframe-wait on codec (re)start or the rare hard-resync after a genuine
                // decoder wedge (backpressure now prevents the overflow-drop that used to trigger this
                // constantly). While waiting we skip feeding, so the Surface holds its last cleanly-
                // decoded frame. macOS normally sends an IDR within a second or two and the wait clears
                // itself. The timeout is only a safety against a missed/very-late IDR: resume feeding
                // (at worst a brief self-healing glitch until the next IDR) rather than freezing forever.
                // NO restart-loop here — restarting does not summon an IDR and only produced a minute of
                // stale/frozen frames.
                if (_waitingSecs >= 3) {
                    _waitingForKeyframe = false
                    _waitingSecs = 0
                    Log.w(TAG, "keyframe wait timed out -> resume feed")
                }
            } else {
                _waitingSecs = 0
            }
            if (outputThisSec > 0L) _consecutiveRestarts = 0   // confirmed recovery (decoder producing)
            // A real wedge = "fed the codec but it emitted NO output at all" (not merely "didn't display").
            // While _waitingForKeyframe we deliberately SKIP feeding (waiting for a clean IDR), so nothing
            // is produced by design — NOT a stall. During latency catch-up we produce output but skip
            // displaying it — also NOT a stall (outputThisSec>0). Only zero decoder output while frames
            // arrive is a genuine wedge.
            if (fps > 0 && outputThisSec == 0L && !_waitingForKeyframe) {
                _stalledSecs++
                // Recover faster: detect the stall at 2s (was 3s) so a burst-induced freeze clears
                // before the user reaches for reconnect. Allow more restart attempts (6, was 4) — each
                // restart re-bootstraps from the cached keyframe, which often un-sticks the decoder
                // without a manual reconnect. A true HW wedge still ultimately needs a client reconnect.
                if (_stalledSecs >= 2 && _consecutiveRestarts < 6) {
                    _stallRestartNeeded = true
                    _consecutiveRestarts++
                    Log.w(TAG, "decoder stall (${_stalledSecs}s, restart #$_consecutiveRestarts: fed=$fps queued=$queuedThisSec) -> restarting codec")
                } else if (_consecutiveRestarts >= 6) {
                    Log.w(TAG, "decoder wedged after $_consecutiveRestarts restarts; needs client reconnect")
                }
            } else {
                _stalledSecs = 0
            }
            // always-on lightweight diagnostic: fed vs rendered tells us if frames arrive but never
            // reach the surface (decoder stall) vs simply aren't being sent (source idle)
            Log.i(TAG, "video: fed/s=$fps out/s=$outputThisSec shown/s=$renderedThisSec bitrate=${bitrateBps / 1000}kbps " +
                "queue=$_pendingDepth totalFed=$frameCount totalShown=$_renderedTotal dropped=$droppedFrames " +
                "codec=$codecName ${videoWidth}x${videoHeight}")
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        _updateStats(data.size)

        // always cache keyframes, even without a surface
        if (_isKeyframe(data, isH265)) {
            cachedKeyframe = data.copyOf()
            cachedKeyframePts = ntpTimeNs
            cachedKeyframeH265 = isH265
        }

        synchronized(lock) {
            if (surface == null) return

            // watchdog-triggered recovery: tear down the wedged codec so the block below restarts it
            // and re-bootstraps from the cached keyframe.
            if (_stallRestartNeeded) {
                _stallRestartNeeded = false
                _stalledSecs = 0
                stopCodec()
            }

            // (re)start codec on first frame or codec/profile switch
            if (codec == null || isH265 != currentH265) {
                stopCodec()
                startCodec(isH265)
                // bootstrap the fresh decoder with the cached keyframe so it shows a frame
                // immediately, then resync to the source's next real IDR before decoding live frames
                cachedKeyframe?.let { kf ->
                    if (cachedKeyframeH265 == isH265) _enqueue(kf, cachedKeyframePts / 1000)
                }
                _waitingForKeyframe = true
            }

            // resync gate: until a fresh keyframe arrives, skip frames instead of feeding P-frames
            // onto a broken reference chain (which causes cursor trails and re-stalls)
            if (_waitingForKeyframe) {
                if (_isKeyframe(data, isH265)) {
                    _waitingForKeyframe = false
                } else {
                    _pumpInputs()   // still push any already-queued bootstrap keyframe
                    return
                }
            }

            _enqueue(data, ntpTimeNs / 1000)
            _pumpInputs()
        }
    }

    /**
     * Queue a frame. Mirror video arrives over TCP (reliable, IN-ORDER) — no frame is ever lost on the
     * wire, so the ONLY way the H.264 reference chain breaks (→ macroblock corruption / "vỡ hình") is if
     * WE drop a frame here. Therefore we NEVER drop under normal backpressure: if the decode+GL pipeline
     * has fallen behind (queue full), we WAIT for it to drain. Blocking this (JNI mirror) thread
     * backpressures the TCP socket, so macOS simply throttles its send rate instead of us corrupting the
     * stream. Only a GENUINE HW-decoder wedge (output permanently stops for BACKPRESSURE_MAX_MS) forces a
     * hard resync — that is rare and unavoidable. Caller holds [lock]; lock.wait() releases it so the
     * codec's input/output callbacks can run and drain the queue, then notifyAll() wakes us.
     */
    private fun _enqueue(data: ByteArray, ptsUs: Long) {
        if (pendingFrames.size >= MAX_PENDING) {
            var waitedMs = 0
            while (pendingFrames.size >= MAX_PENDING && waitedMs < BACKPRESSURE_MAX_MS && codec != null) {
                try { lock.wait(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                waitedMs += 20
            }
            if (pendingFrames.size >= MAX_PENDING) {
                // Still full after the cap → hard-resync: drop the backlog and wait for the next keyframe.
                // Rare (only a genuine decoder stall). We deliberately do NOT force a codec restart here:
                // restart-on-overflow thrashes (the fresh codec needs an IDR + re-init time before it can
                // output, but frames pile up again immediately → restart again → the decoder never gets to
                // stabilise). The stall watchdog handles a truly stuck decoder; a hardware-degraded decoder
                // (after long box uptime) needs a box reboot, not a software restart.
                pendingFrames.clear()
                _pendingDepth = 0
                _waitingForKeyframe = true
                droppedFrames++
                Log.w(TAG, "backlog full ${BACKPRESSURE_MAX_MS}ms -> hard resync to keyframe")
                return
            }
        }
        pendingFrames.addLast(Frame(data, ptsUs))
        _pendingDepth = pendingFrames.size
    }

    /** Feed queued frames into any input buffers the codec has made available. Caller holds lock. */
    private fun _pumpInputs() {
        val c = codec ?: return
        var drained = false
        while (availableInputs.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val idx = availableInputs.pollFirst()
            val frame = pendingFrames.pollFirst()
            try {
                val buf = c.getInputBuffer(idx) ?: continue
                buf.clear()
                buf.put(frame.data)
                c.queueInputBuffer(idx, 0, frame.data.size, frame.ptsUs, 0)
                _queuedTotal++
                drained = true
            } catch (e: IllegalStateException) {
                // codec was torn down mid-feed; stop touching it
                Log.w(TAG, "queueInputBuffer failed (codec stopping): ${e.message}")
                return
            }
        }
        _pendingDepth = pendingFrames.size
        // Wake any feedFrame thread blocked in _enqueue backpressure now that space has freed.
        if (drained) lock.notifyAll()
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 4) return false
        var i = 0
        // Scan for a NAL start code. Match the 3-byte form (00 00 01); the 4-byte form
        // (00 00 00 01) contains it at offset+1, so this covers both. macOS emits mid-stream IDRs
        // with 3-byte start codes — only matching 4-byte missed them, so the keyframe-resync gate
        // never cleared and the stream froze.
        while (i <= data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val nal = data[i + 3].toInt()
                val isKey = if (isH265) {
                    val type = (nal shr 1) and 0x3F
                    type == 19 || type == 20 || type == 32 || type == 33   // IDR / VPS / SPS
                } else {
                    val type = nal and 0x1F
                    type == 5 || type == 7                                  // IDR / SPS
                }
                if (isKey) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        val s = surface ?: return
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        // Configure with 16-aligned (macroblock) dimensions. H.264 codes frames in 16px macroblocks
        // and signals the real size via an SPS crop rect (e.g. iPhone portrait 498x1080 -> coded
        // 512x1088, crop to 498x1080). Passing the non-aligned 498 here makes the Amlogic decoder
        // accept input but never emit output (rendered stays 0). Rounding up lets it decode; the
        // decoder applies the SPS crop so the displayed image is still correct.
        val alignedW = (videoWidth + 15) and 15.inv()
        val alignedH = (videoHeight + 15) and 15.inv()
        val format = MediaFormat.createVideoFormat(mime, alignedW, alignedH)
        // Size the input buffer to a full luma plane (w*h). The old flat 1 MB cap truncated large
        // 1080p keyframes -> blocky/soft decode that persisted until the next full IDR. Generous
        // sizing lets whole compressed frames through so detail (e.g. small text) stays sharp.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (alignedW * alignedH).coerceAtLeast(1024 * 1024))
        if (enforceSdr) {
            // Desktop screen mirroring (macOS/iOS) encodes FULL-range BT.709 (computer graphics are
            // full-range 0-255). Tagging LIMITED washed colors out; tagging nothing let the decoder
            // assume limited and over-expand -> harsh/oversaturated. FULL + BT709 matches the source.
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        // Amlogic-specific decoder tuning, matched to AirScreen (decompiled ea/d.java): for an
        // OMX.amlogic.avc.decoder it sets "4k-osd"=0. On this box that is the SAME decoder we use
        // (createDecoderByType -> OMX.amlogic.avc.decoder.awesome, the only HW AVC decoder present), so we
        // mirror its one Amlogic-specific format key. "4k-osd"=0 keeps the decoder off the 4K-OSD scaling
        // path for a 1080p stream. ("low-latency"/"vdec-lowlatency" in that file are gated to MediaTek
        // decoders only, NOT Amlogic, so we don't set them here.) Harmless if the decoder ignores the key.
        format.setInteger("4k-osd", 0)
        if (operatingRateHint && android.os.Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }

        availableInputs.clear()
        pendingFrames.clear()
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L

        // Attempt to set up the GL sharpening path. The decoder will render into glSharpen's
        // inputSurface (an OES SurfaceTexture), and GlSharpenRenderer blits the sharpened result
        // to the real display Surface. On any failure we fall through to the direct-Surface path
        // so the screen never goes black — this is entirely additive.
        val decodeTarget: Surface = if (sharpenEnabled && alignedW > 0 && alignedH > 0) {
            val gl = GlSharpenRenderer()
            gl.strength = sharpenStrength
            gl.contrast = sharpenContrast
            gl.saturation = sharpenSaturation
            try {
                gl.init(s, alignedW, alignedH)
                glSharpen = gl
                Log.i(TAG, "GL sharpen path active (strength=$sharpenStrength)")
                gl.inputSurface!!   // expression value -> decodeTarget
            } catch (e: Exception) {
                Log.w(TAG, "GL sharpen init failed, falling back to direct surface: ${e.message}")
                gl.release()   // clean up the partially initialised renderer
                glSharpen = null
                s   // fall back to the raw display Surface
            }
        } else {
            glSharpen = null
            s
        }

        val thread = HandlerThread("VideoRendererCb").also { it.start() }
        callbackThread = thread
        // Hardware decoder: it sustains full 1080p60 and stays sharp (software can't keep up -> drops
        // -> trails, since the AirPlay stream sends IDRs only rarely so any dropped frame ghosts until
        // the next keyframe). The right strategy is therefore to feed EVERY frame in order and never
        // drop/skip while the decoder keeps up. The rare HW-decoder wedge is handled by the watchdog.
        val c = MediaCodec.createDecoderByType(mime)
        Log.i(TAG, "Decoder: default-hw($mime)")
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                synchronized(lock) {
                    if (codec !== mc) return
                    availableInputs.addLast(index)
                    _pumpInputs()
                }
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                // render outside the lock to avoid blocking input feeding / stopCodec
                try {
                    if (codec !== mc) { mc.releaseOutputBuffer(index, false); return }
                    _outputTotal++   // decoder produced output (watchdog liveness, counts even if skipped)
                    // Latency catch-up: if input frames are still backlogged, this decoded frame is
                    // already stale -> release WITHOUT displaying to fast-forward to the newest frame.
                    // The decoder already decoded it so the reference chain stays intact (no corruption);
                    // we just skip showing an out-of-date frame. Keeps input->display latency low during
                    // macOS bursts without ever dropping an INPUT frame.
                    if (_pendingDepth > LATENCY_SKIP_THRESHOLD) {
                        mc.releaseOutputBuffer(index, false)
                        return
                    }
                    if (scheduledOutputBufferRelease) {
                        val ptsUs = info.presentationTimeUs
                        synchronized(lock) {
                            if (_ptsBaseUs == Long.MIN_VALUE) {
                                _ptsBaseUs = ptsUs; _wallBaseNs = System.nanoTime()
                            }
                        }
                        mc.releaseOutputBuffer(index, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
                    } else {
                        mc.releaseOutputBuffer(index, true)   // render immediately
                    }
                    _renderedTotal++
                    _recordOutputFrameTime()
                } catch (e: IllegalStateException) {
                    // codec stopped between callback dispatch and release; ignore
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format changed: $format")
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Codec error: ${e.message} (recoverable=${e.isRecoverable} transient=${e.isTransient})")
            }
        }, Handler(thread.looper))

        c.configure(format, decodeTarget, null, 0)
        c.start()
        codec = c
        codecName = if (h265) "H.265" else "H.264"
        running = true
        Log.i(TAG, "Video codec started (async): $mime ${videoWidth}x${videoHeight}")
    }

    private fun stopCodec() {
        running = false
        _stalledSecs = 0
        _stallRestartNeeded = false
        _waitingForKeyframe = false
        _waitingSecs = 0
        _renderedAtLastReset = _renderedTotal
        _outputAtLastReset = _outputTotal
        _pendingDepth = 0
        _queuedAtLastReset = _queuedTotal
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        val c = codec
        codec = null   // null first so in-flight callbacks bail out (they check codec !== mc)
        c?.let {
            try {
                it.setCallback(null)
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        callbackThread?.quitSafely()
        callbackThread = null
        availableInputs.clear()
        pendingFrames.clear()
        // Release the GL stage AFTER the codec has been fully stopped. This ensures no
        // in-flight onOutputBufferAvailable call is still rendering into the OES texture when
        // we tear down the EGL context.
        glSharpen?.release()
        glSharpen = null
    }

    fun release() = synchronized(lock) {
        stopCodec()
        cachedKeyframe = null
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _frameIntervalIdx = 0; _frameIntervalCount = 0; _lastOutputFrameNs = 0L
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        synchronized(lock) {
            if (_lastOutputFrameNs > 0) {
                _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
                _frameIntervalIdx++
                _frameIntervalCount++
            }
            _lastOutputFrameNs = now
        }
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    /** Find a software decoder for the mime (c2.android.* / OMX.google.*), or null if none. */
    private fun _findSoftwareDecoder(mime: String): String? {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        return list.codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (info.name.startsWith("c2.android.", true) || info.name.startsWith("OMX.google.", true))
        }?.name
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"

        fun supportsH265(): Boolean {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            return list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        }
    }
}
