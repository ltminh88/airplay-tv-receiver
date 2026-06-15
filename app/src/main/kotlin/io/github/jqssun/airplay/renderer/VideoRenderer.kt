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
    private val MAX_PENDING = 8                            // bound the backlog; drop oldest beyond this

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

    var enforceSdr = false
    var keyAllowFrameDrop = true
    var realtimeDecoderPriority = true
    var operatingRateHint = false
    var scheduledOutputBufferRelease = false
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
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

            // (re)start codec on first frame or codec/profile switch
            if (codec == null || isH265 != currentH265) {
                stopCodec()
                startCodec(isH265)
                // bootstrap the fresh decoder with the cached keyframe so it can show a frame
                // immediately instead of waiting for the source's next IDR
                cachedKeyframe?.let { kf ->
                    if (cachedKeyframeH265 == isH265) _enqueue(kf, cachedKeyframePts / 1000)
                }
            }

            _enqueue(data, ntpTimeNs / 1000)
            _pumpInputs()
        }
    }

    /** Queue a frame for decoding; drop the oldest if the backlog grows unbounded. */
    private fun _enqueue(data: ByteArray, ptsUs: Long) {
        if (pendingFrames.size >= MAX_PENDING) {
            pendingFrames.pollFirst()
            droppedFrames++
        }
        pendingFrames.addLast(Frame(data, ptsUs))
    }

    /** Feed queued frames into any input buffers the codec has made available. Caller holds lock. */
    private fun _pumpInputs() {
        val c = codec ?: return
        while (availableInputs.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val idx = availableInputs.pollFirst()
            val frame = pendingFrames.pollFirst()
            try {
                val buf = c.getInputBuffer(idx) ?: continue
                buf.clear()
                buf.put(frame.data)
                c.queueInputBuffer(idx, 0, frame.data.size, frame.ptsUs, 0)
            } catch (e: IllegalStateException) {
                // codec was torn down mid-feed; stop touching it
                Log.w(TAG, "queueInputBuffer failed (codec stopping): ${e.message}")
                return
            }
        }
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                return if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        val s = surface ?: return
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        if (enforceSdr) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
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

        val thread = HandlerThread("VideoRendererCb").also { it.start() }
        callbackThread = thread
        val c = MediaCodec.createDecoderByType(mime)
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

        c.configure(format, s, null, 0)
        c.start()
        codec = c
        codecName = if (h265) "H.265" else "H.264"
        running = true
        Log.i(TAG, "Video codec started (async): $mime ${videoWidth}x${videoHeight}")
    }

    private fun stopCodec() {
        running = false
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
