package io.github.jqssun.airplay.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * GLES2 unsharp-mask sharpening stage inserted between the MediaCodec decoder and the display
 * Surface. The decoder renders into an external-OES texture (via a SurfaceTexture/Surface pair),
 * and on each frame this class samples that texture through a 5-tap unsharp-mask kernel and
 * blits the result to the real output Surface.
 *
 * Why OES: MediaCodec always writes into a SurfaceTexture backed by GL_TEXTURE_EXTERNAL_OES;
 * we cannot change that. The #extension + samplerExternalOES handle the YUV→RGB conversion and
 * transform matrix that the platform inserts for color-space/crop correctness.
 *
 * Thread model: a single dedicated HandlerThread owns the EGL context for its entire lifetime.
 * All EGL/GL calls must be posted to [glHandler]. The public API is thread-safe (init/release
 * may be called from VideoRenderer's lock-protected code on arbitrary threads).
 *
 * Fallback contract: every public method wraps its work in try/catch. If any GL operation
 * throws, [isValid] returns false and VideoRenderer falls back to the direct-Surface path.
 */
class GlSharpenRenderer {

    // ---- public surface exposed to the codec ----------------------------------------------

    /** Surface the decoder should render into. Valid only after [init] succeeds. */
    var inputSurface: Surface? = null
        private set

    /** 0f = pass-through, ~1f = strong sharpening. May be set at any time from any thread. */
    @Volatile var strength: Float = 0.5f

    /** True while the GL pipeline is operational. */
    @Volatile var isValid: Boolean = false
        private set

    // ---- GL / EGL state (touched only on glThread) ----------------------------------------

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private val egl: EGL10 = EGLContext.getEGL() as EGL10

    private var surfaceTexture: SurfaceTexture? = null
    private var oesTexId: Int = 0
    private var shaderProgram: Int = 0

    // attribute / uniform locations cached after compile
    private var aPosition: Int = 0
    private var aTexCoord: Int = 0
    private var uTexMatrix: Int = 0
    private var uStrength: Int = 0
    private var uTexelSize: Int = 0

    private var texWidth: Int = 0
    private var texHeight: Int = 0

    // ---- vertex data (full-screen quad, CCW) -----------------------------------------------

    private val QUAD_VERTS = floatArrayOf(
        -1f, -1f,   0f, 0f,
         1f, -1f,   1f, 0f,
        -1f,  1f,   0f, 1f,
         1f,  1f,   1f, 1f
    )

    // ---- shaders --------------------------------------------------------------------------

    private val VERTEX_SRC = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            // Apply SurfaceTexture transform: handles crop, flip, and color-space transform
            // injected by the platform when the OES texture is updated.
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    // #extension must appear before any other statement per the GLSL ES spec.
    private val FRAGMENT_SRC = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTex;
        uniform float uStrength;
        uniform vec2 uTexelSize;   // (1/width, 1/height) in texture space

        void main() {
            // Unsharp mask: sharpen = center + amount * (center - blur)
            // blur approximated with 4-neighbor average (cheap, no extra texture lookup).
            // offset = strength * 1 texel so at strength=0 all samples collapse to center
            // (no sharpening) and at strength=1 the offset is exactly 1 texel (crisp TV look).
            vec2 off = uStrength * uTexelSize;
            vec4 center = texture2D(uTex, vTexCoord);
            vec4 up     = texture2D(uTex, vTexCoord + vec2(0.0,  off.y));
            vec4 down   = texture2D(uTex, vTexCoord + vec2(0.0, -off.y));
            vec4 left   = texture2D(uTex, vTexCoord + vec2(-off.x, 0.0));
            vec4 right  = texture2D(uTex, vTexCoord + vec2( off.x, 0.0));
            // result = center + amount*(5*center - up - down - left - right)
            // amount=0.25 at strength=1 gives a subtle but visible edge lift similar to TV
            // "Sharpness +2". Higher amount = overshoot/halo; keep it gentle.
            float amount = 0.25 * uStrength;
            vec4 sharp = center + amount * (5.0 * center - up - down - left - right);
            gl_FragColor = clamp(sharp, 0.0, 1.0);
        }
    """.trimIndent()

    // ---- public API -----------------------------------------------------------------------

    /**
     * Initialise EGL, compile shaders, create the OES texture and SurfaceTexture, and expose
     * [inputSurface]. Must NOT be called from the GL thread (it posts work and blocks briefly).
     *
     * @param outputSurface The display Surface (from SurfaceView / MirroringView).
     * @param width         Aligned video width (same value passed to MediaCodec configure).
     * @param height        Aligned video height.
     * @throws RuntimeException if any GL/EGL step fails (caller must catch and fall back).
     */
    fun init(outputSurface: Surface, width: Int, height: Int) {
        texWidth = width
        texHeight = height

        val thread = HandlerThread("GlSharpen").also { it.start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        // Run init synchronously on the GL thread; any exception propagates to the caller.
        var initException: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                _initGl(outputSurface, width, height)
            } catch (t: Throwable) {
                initException = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        initException?.let { throw RuntimeException("GlSharpen init failed: ${it.message}", it) }
        isValid = true
    }

    /**
     * Release all GL/EGL resources and stop the GL thread. Safe to call multiple times and from
     * any thread. After return, [isValid] is false and [inputSurface] is null.
     */
    fun release() {
        isValid = false   // stop new frame callbacks from doing GL work
        val handler = glHandler
        glHandler = null
        if (handler != null) {
            // Post teardown to the GL thread so it runs after any in-flight frame.
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                try { _releaseGl() } catch (_: Throwable) {}
                latch.countDown()
            }
            // Wait at most 500 ms; if the thread is wedged, proceed anyway.
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        glThread?.quitSafely()
        glThread = null
        inputSurface?.release()
        inputSurface = null
    }

    // ---- GL-thread internals --------------------------------------------------------------

    /** All EGL / GLES setup. Called on the GL thread from [init]. */
    private fun _initGl(outputSurface: Surface, width: Int, height: Int) {
        // --- EGL setup ---
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            ?: throw RuntimeException("eglGetDisplay failed")
        eglDisplay = display

        val version = IntArray(2)
        check(egl.eglInitialize(display, version)) { "eglInitialize failed" }

        // Request GLES2 context. EGL_RENDERABLE_TYPE = EGL_OPENGL_ES2_BIT (4).
        val attribList = intArrayOf(
            EGL10.EGL_RED_SIZE,   8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE,  8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 4,   // EGL_OPENGL_ES2_BIT
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(egl.eglChooseConfig(display, attribList, configs, 1, numConfigs) && numConfigs[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = configs[0]!!

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL10.EGL_NONE
        )
        val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, ctxAttribs)
        check(context != EGL10.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglContext = context

        val surface = egl.eglCreateWindowSurface(display, config, outputSurface, null)
        check(surface != EGL10.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        eglSurface = surface

        check(egl.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        // --- OES texture & SurfaceTexture ---
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTexId = texIds[0]
        // GL_TEXTURE_EXTERNAL_OES = 0x8D65
        GLES20.glBindTexture(0x8D65, oesTexId)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTexId)
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st

        // Wire frame-available callback -> post to GL thread for EGL-safe rendering.
        st.setOnFrameAvailableListener({ _onFrame() }, Handler(glThread!!.looper))

        inputSurface = Surface(st)

        // --- compile shaders ---
        shaderProgram = _buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        aPosition  = GLES20.glGetAttribLocation(shaderProgram,  "aPosition")
        aTexCoord  = GLES20.glGetAttribLocation(shaderProgram,  "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(shaderProgram, "uTexMatrix")
        uStrength  = GLES20.glGetUniformLocation(shaderProgram, "uStrength")
        uTexelSize = GLES20.glGetUniformLocation(shaderProgram, "uTexelSize")

        GLES20.glViewport(0, 0, width, height)
        Log.i(TAG, "GlSharpen init OK ${width}x${height}")
    }

    /** Called on the GL thread by the SurfaceTexture frame-available listener. */
    private fun _onFrame() {
        if (!isValid) return
        val st = surfaceTexture ?: return
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return
        val context = eglContext ?: return
        try {
            // Re-make current in case the GL thread context got lost (rare but possible after
            // display power cycle). The cost is one JNI call per frame; negligible vs GPU work.
            egl.eglMakeCurrent(display, surface, surface, context)

            st.updateTexImage()

            val texMatrix = FloatArray(16)
            st.getTransformMatrix(texMatrix)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(shaderProgram)

            // Upload SurfaceTexture transform (crop + flip baked in by the platform).
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform1f(uStrength, strength.coerceIn(0f, 1f))
            GLES20.glUniform2f(uTexelSize, 1f / texWidth, 1f / texHeight)

            // Bind OES texture to unit 0.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(0x8D65, oesTexId)   // GL_TEXTURE_EXTERNAL_OES

            // Interleaved vertex buffer: [x, y, u, v] per vertex, 4 bytes each.
            val buf = java.nio.ByteBuffer
                .allocateDirect(QUAD_VERTS.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { it.put(QUAD_VERTS); it.position(0) }
            val stride = 4 * 4   // 4 floats * 4 bytes
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, buf)
            GLES20.glEnableVertexAttribArray(aPosition)

            buf.position(2)   // skip past x,y to u,v
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, buf)
            GLES20.glEnableVertexAttribArray(aTexCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            egl.eglSwapBuffers(display, surface)
        } catch (t: Throwable) {
            // Do not crash the GL thread; mark invalid so VideoRenderer stops using the path.
            Log.e(TAG, "GL frame error: ${t.message}")
            isValid = false
        }
    }

    /** Compile + link a GLES2 program; throws on any error. Called on GL thread. */
    private fun _buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = _compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = _compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        check(prog != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(prog, vert)
        GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        return prog
    }

    private fun _compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader($type) failed" }
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed (type=$type): $log")
        }
        return shader
    }

    /** Tear down all GL resources. Called on the GL thread from [release]. */
    private fun _releaseGl() {
        surfaceTexture?.release()
        surfaceTexture = null

        if (oesTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
            oesTexId = 0
        }
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }

        val display = eglDisplay ?: return
        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        eglSurface?.let { egl.eglDestroySurface(display, it) }
        eglContext?.let { egl.eglDestroyContext(display, it) }
        egl.eglTerminate(display)
        eglDisplay = null
        eglContext = null
        eglSurface = null
        Log.i(TAG, "GlSharpen released")
    }

    companion object {
        private const val TAG = "GlSharpenRenderer"
    }
}
