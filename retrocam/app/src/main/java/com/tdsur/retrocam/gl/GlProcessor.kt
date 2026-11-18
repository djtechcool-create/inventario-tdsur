package com.tdsur.retrocam.gl

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.tdsur.retrocam.film.FilmEngine
import com.tdsur.retrocam.film.FilmLook
import com.tdsur.retrocam.film.FilmLooks
import com.tdsur.retrocam.film.LeakGenerator
import com.tdsur.retrocam.film.LeakSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.max

private const val TAG = "RetroCamGL"

/**
 * Procesa los cuadros de la cámara en la GPU (OpenGL ES 2) antes de mostrarlos o grabarlos.
 * - Modo FILM: filtro de rollo en vivo para la vista previa de fotos.
 * - Modo VHS: efecto de cinta VHS para la vista previa y el video grabado.
 *
 * Se crea una sola vez para toda la app ([get]) y vive en su propio hilo.
 */
class GlProcessor private constructor(private val assets: AssetManager) : SurfaceProcessor {

    enum class Mode { FILM, VHS }

    private val thread = HandlerThread("RetroCamGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { handler.post(it) }

    @Volatile var mode = Mode.FILM
    @Volatile var look: FilmLook = FilmLooks.PORTRA_400
    @Volatile var seed: Long = 1L
    @Volatile var vhsDamage = 0.35f
    @Volatile var vhsOsd = true
    /** Rotación del sensor; se usa cuando el búfer de salida viene acostado. */
    @Volatile var sensorRotation = 90

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var ready = false

    private lateinit var filmProgram: GlProgram
    private lateinit var vhsProgram: GlProgram
    private lateinit var quad: FloatBuffer
    private var curveTex = 0
    private var osdTex = 0
    private var curveLookId: String? = null
    private var osdKey = ""
    private var leaks: List<LeakSpec> = emptyList()
    private var leaksKey = ""

    private var input: SurfaceTexture? = null
    private var inputTex = 0
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private val startNs = System.nanoTime()

    init {
        handler.post {
            try {
                initGl()
                ready = true
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar OpenGL", e)
            }
        }
    }

    // ------------------------------------------------------------- CameraX

    override fun onInputSurface(request: SurfaceRequest) {
        if (!ready) {
            request.willNotProvideSurface()
            return
        }
        makeCurrent(pbuffer)
        val tex = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            makeCurrent(pbuffer)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            if (input === st) {
                input = null
                inputTex = 0
            }
        }
        st.setOnFrameAvailableListener({ drawFrame(it) }, handler)
        input = st
        inputTex = tex
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (!ready) {
            output.close()
            return
        }
        val surface = output.getSurface(executor) { removeOutput(output) }
        val eglSurface = try {
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo crear la superficie de salida", e)
            null
        }
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            output.close()
            return
        }
        outputs[output] = eglSurface
    }

    private fun removeOutput(output: SurfaceOutput) {
        outputs.remove(output)?.let {
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(eglDisplay, it)
        }
        output.close()
    }

    // ------------------------------------------------------------- dibujo

    private fun drawFrame(st: SurfaceTexture) {
        if (!ready || st !== input) return
        try {
            makeCurrent(pbuffer)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val time = ((System.nanoTime() - startNs) / 1_000_000_000.0 % 3600.0).toFloat()
            val mode = mode
            if (mode == Mode.FILM) prepareFilm() else prepareOsd()

            for ((output, eglSurface) in outputs.entries.toList()) {
                output.updateTransformMatrix(outMatrix, texMatrix)
                val w = output.size.width
                val h = output.size.height
                // En una app vertical, un búfer horizontal se muestra girado según el sensor.
                val rotation = if (w > h) sensorRotation else 0
                makeCurrent(eglSurface)
                GLES20.glViewport(0, 0, w, h)
                draw(mode, rotation, w, h, time)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al dibujar un cuadro", e)
        }
    }

    private fun draw(mode: Mode, rotation: Int, w: Int, h: Int, time: Float) {
        val p = if (mode == Mode.FILM) filmProgram else vhsProgram
        GLES20.glUseProgram(p.id)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quad)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
        p.int("uTex", 0)
        p.mat4("uTexMatrix", outMatrix)
        p.mat3("uToDisp", toDisplay(rotation))
        p.mat3("uToBuf", toBuffer(rotation))
        val dw = if (rotation == 90 || rotation == 270) h else w
        val dh = if (rotation == 90 || rotation == 270) w else h
        p.float2("uDispRes", dw.toFloat(), dh.toFloat())
        p.float("uTime", time)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (mode == Mode.FILM) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
            p.int("uCurves", 1)
            setFilmUniforms(p, max(dw, dh).toFloat())
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
            p.int("uOsd", 1)
            p.float("uDamage", vhsDamage)
            p.float("uOsdOn", if (vhsOsd) 1f else 0f)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setFilmUniforms(p: GlProgram, longSide: Float) {
        val look = look
        val unit = longSide / 1500f
        p.float("uGain", Math.pow(2.0, (look.exposure * 0.45f).toDouble()).toFloat())
        val m = look.mix
        // GLSL guarda las matrices por columnas
        p.mat3("uMix", floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))
        p.float("uMono", look.mono)
        p.float3("uMonoW", look.monoWeights.r, look.monoWeights.g, look.monoWeights.b)
        p.float("uSat", look.saturation)
        p.float("uRollHi", look.satRollHi)
        p.float("uRollLo", look.satRollLo)
        p.float3("uShadowTint", look.shadowTint.r, look.shadowTint.g, look.shadowTint.b)
        p.float3("uHighTint", look.highlightTint.r, look.highlightTint.g, look.highlightTint.b)
        p.float("uSoft", look.softness * unit * 0.8f)
        p.float("uCA", look.aberration)
        p.float("uBloom", look.bloom)
        p.float("uHal", look.halation)
        p.float("uGrain", look.grain)
        p.float("uGrainPx", max(1f, unit * look.grainSize * 0.9f))
        p.float("uGrainColor", look.grainColor)
        p.float("uVignette", look.vignette)
        p.float("uFlash", look.flash)
        val l0 = leaks.getOrNull(0)
        val l1 = leaks.getOrNull(1)
        p.float4("uLeak0", l0?.cx ?: 0f, l0?.cy ?: 0f, l0?.radius ?: 1f, l0?.strength ?: 0f)
        p.float3("uLeakC0", l0?.color?.r ?: 0f, l0?.color?.g ?: 0f, l0?.color?.b ?: 0f)
        p.float4("uLeak1", l1?.cx ?: 0f, l1?.cy ?: 0f, l1?.radius ?: 1f, l1?.strength ?: 0f)
        p.float3("uLeakC1", l1?.color?.r ?: 0f, l1?.color?.g ?: 0f, l1?.color?.b ?: 0f)
        p.float("uIntensity", if (look.id == FilmLooks.ORIGINAL.id) 0f else 1f)
    }

    private fun prepareFilm() {
        val look = look
        if (look.id != curveLookId) {
            val data = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
            data.put(FilmEngine.curveTexture(look)).position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data,
            )
            curveLookId = look.id
        }
        val key = "${look.id}:${seed}"
        if (key != leaksKey) {
            leaks = LeakGenerator.generate(look.leak, seed)
            leaksKey = key
        }
    }

    /** Textos en pantalla del VHS. Se redibujan solo cuando cambia el minuto. */
    private fun prepareOsd() {
        val now = LocalDateTime.now()
        val time = now.format(DateTimeFormatter.ofPattern("a h:mm", Locale.US)).uppercase(Locale.US)
        val date = now.format(DateTimeFormatter.ofPattern("MMM.dd yyyy", Locale.US)).uppercase(Locale.US)
        val key = "$time|$date"
        if (key == osdKey) return
        osdKey = key

        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 40f
            color = Color.WHITE
            letterSpacing = 0.08f
            setShadowLayer(3f, 3f, 3f, Color.argb(200, 0, 0, 0))
        }
        c.drawText("PLAY ▶", 44f, 80f, paint)
        c.drawText(time, 44f, 704f, paint)
        c.drawText(date, 44f, 752f, paint)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    // ------------------------------------------------------------- inicio

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }

        val recordable = 0x3142 // EGL_RECORDABLE_ANDROID
        val attempts = listOf(
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                recordable, 1, EGL14.EGL_NONE,
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
        )
        for (attrs in attempts) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                eglConfig = configs[0]
                break
            }
        }
        checkNotNull(eglConfig) { "Sin configuración EGL" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent(pbuffer)

        val vert = asset("shaders/quad.vert")
        filmProgram = GlProgram(vert, asset("shaders/film.frag"))
        vhsProgram = GlProgram(vert, asset("shaders/vhs.frag"))

        quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                )
            )
            position(0)
        }
        curveTex = createTexture(GLES20.GL_TEXTURE_2D)
        osdTex = createTexture(GLES20.GL_TEXTURE_2D)
    }

    private fun asset(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    private fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    companion object {
        @Volatile private var instance: GlProcessor? = null

        fun get(context: Context): GlProcessor =
            instance ?: synchronized(this) {
                instance ?: GlProcessor(context.applicationContext.assets).also { instance = it }
            }

        // Matrices 3x3 (por columnas) entre coordenadas del búfer y de la pantalla.
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private val ROT90 = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f)
        private val ROT180 = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, 1f)
        private val ROT270 = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, 1f)

        private fun toDisplay(rotation: Int) = when (rotation) {
            90 -> ROT90
            180 -> ROT180
            270 -> ROT270
            else -> IDENTITY
        }

        private fun toBuffer(rotation: Int) = when (rotation) {
            90 -> ROT270
            180 -> ROT180
            270 -> ROT90
            else -> IDENTITY
        }
    }
}

/** Efecto de CameraX que pasa los cuadros por [GlProcessor]. */
class RetroEffect(targets: Int, processor: GlProcessor) : CameraEffect(
    targets,
    processor.executor,
    processor,
    Consumer<Throwable> { Log.e(TAG, "Error en el efecto de cámara", it) },
)

/** Programa de shaders con caché de ubicaciones de uniforms. */
private class GlProgram(vertex: String, fragment: String) {
    val id: Int = GLES20.glCreateProgram()
    private val locations = HashMap<String, Int>()

    init {
        GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glBindAttribLocation(id, 0, "aPos")
        GLES20.glBindAttribLocation(id, 1, "aUv")
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Error al enlazar: " + GLES20.glGetProgramInfoLog(id) }
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Error al compilar shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun loc(name: String) = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun int(name: String, v: Int) = GLES20.glUniform1i(loc(name), v)
    fun float(name: String, v: Float) = GLES20.glUniform1f(loc(name), v)
    fun float2(name: String, a: Float, b: Float) = GLES20.glUniform2f(loc(name), a, b)
    fun float3(name: String, a: Float, b: Float, c: Float) = GLES20.glUniform3f(loc(name), a, b, c)
    fun float4(name: String, a: Float, b: Float, c: Float, d: Float) = GLES20.glUniform4f(loc(name), a, b, c, d)
    fun mat3(name: String, m: FloatArray) = GLES20.glUniformMatrix3fv(loc(name), 1, false, m, 0)
    fun mat4(name: String, m: FloatArray) = GLES20.glUniformMatrix4fv(loc(name), 1, false, m, 0)
}
