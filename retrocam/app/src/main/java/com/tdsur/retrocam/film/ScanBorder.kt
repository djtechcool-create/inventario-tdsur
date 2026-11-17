package com.tdsur.retrocam.film

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Borde de escaneo: imita una foto de rollo escaneada con el portanegativos visible.
 *
 * En la vida real ningún escaneo sale igual, así que cada foto elige al azar:
 * - Si se ve el borde completo, solo en uno o dos lados (a veces como una cuña porque la
 *   película quedó chueca), un borde grueso, o ningún borde.
 * - Cuánto está girada la película, el grosor de cada lado y lo áspero de la orilla.
 * - El tono del negro (nunca es parejo) y si hay luz que se cuela, con forma irregular.
 *
 * Kotlin puro (sin Android) para poder probarlo en la computadora.
 */
object ScanBorder {

    class Result(val pixels: IntArray, val width: Int, val height: Int)

    private enum class Kind { NONE, PARTIAL, THIN, THICK }

    fun apply(src: IntArray, w: Int, h: Int, seed: Long): Result {
        val m = min(w, h).toFloat()
        val rnd = Random(seed xor 0x5CA9L)
        fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

        val roll = rnd.nextFloat()
        val kind = when {
            roll < 0.15f -> Kind.NONE
            roll < 0.45f -> Kind.PARTIAL
            roll < 0.78f -> Kind.THIN
            else -> Kind.THICK
        }
        if (kind == Kind.NONE) return Result(src.copyOf(), w, h)

        // 0 = izquierda, 1 = arriba, 2 = derecha, 3 = abajo
        val visible = BooleanArray(4) { true }
        if (kind == Kind.PARTIAL) {
            val count = if (rnd.nextFloat() < 0.55f) 1 else 2
            visible.fill(false)
            val first = rnd.nextInt(4)
            visible[first] = true
            if (count == 2) visible[if (rnd.nextBoolean()) (first + 1) % 4 else (first + 2) % 4] = true
        }

        // Grosor del negro por lado
        val thickSide = rnd.nextInt(4)
        val t = FloatArray(4) { i ->
            when {
                kind == Kind.THICK && i == thickSide -> m * rf(0.012f, 0.035f)
                kind == Kind.THICK -> m * rf(0.003f, 0.012f)
                else -> m * rf(0.0015f, 0.008f)
            }
        }
        val paper = FloatArray(4) { m * rf(0.006f, 0.02f) }
        val offX = paper[0] + t[0]
        val offY = paper[1] + t[1]
        val fw = w + offX + paper[2] + t[2]
        val fh = h + offY + paper[3] + t[3]

        // Película girada respecto al escáner (a veces casi nada, a veces bastante)
        val maxAngle = if (rnd.nextFloat() < 0.3f) 0.08f else 0.6f
        val angle = Math.toRadians(rf(-maxAngle, maxAngle).toDouble()).toFloat()
        val ca = cos(angle)
        val sa = sin(angle)
        val pcx = offX + w / 2f
        val pcy = offY + h / 2f

        val outAngle = Math.toRadians(rf(-0.15f, 0.15f).toDouble()).toFloat()
        val oca = cos(outAngle)
        val osa = sin(outAngle)
        val ox0 = paper[0]
        val oy0 = paper[1]
        val ox1 = fw - paper[2]
        val oy1 = fh - paper[3]
        val ocx = (ox0 + ox1) / 2f
        val ocy = (oy0 + oy1) / 2f

        // Recorte final: los lados "no visibles" se recortan hacia adentro de la foto
        val crop = FloatArray(4) { i ->
            if (visible[i]) {
                paper[i] * rf(0f, 0.7f)
            } else {
                // Más allá del negro; a veces queda una cuña de negro por el giro
                paper[i] + t[i] + m * rf(0.002f, 0.02f)
            }
        }
        val cx0 = crop[0]
        val cy0 = crop[1]
        val ow = max(8, (fw - crop[0] - crop[2]).roundToInt())
        val oh = max(8, (fh - crop[1] - crop[3]).roundToInt())

        val inset = m * 0.004f
        val rIn = m * rf(0.002f, 0.012f)
        val rOut = m * rf(0.001f, 0.006f)
        val softBase = max(0.7f, m * rf(0.0006f, 0.0018f))
        val softOut = max(0.6f, m * 0.0004f)
        val roughAmp = m * rf(0.0004f, 0.0016f)
        val roughFreq = 1f / (m * rf(0.002f, 0.006f))
        val waveAmp = m * rf(0.0004f, 0.0018f)
        val waveFreq = 1f / (m * rf(0.03f, 0.1f))
        val s1 = rnd.nextInt(10000)
        val s2 = rnd.nextInt(10000)
        val s3 = rnd.nextInt(10000)

        // Negro de la película: cada rollo tiene su tono (café, morado o neutro)
        val baseR = rf(8f, 20f)
        val baseG = baseR * rf(0.75f, 1.0f)
        val baseB = baseR * rf(0.7f, 1.15f)
        // Neblina de la base junto a la foto (el negro se aclara un poco cerca de la imagen)
        val fogK = rf(0f, 30f)
        val fogW = m * rf(0.002f, 0.008f)
        val paperTone = if (rnd.nextFloat() < 0.15f) rf(225f, 240f) else rf(246f, 252f)

        // Luz que se cuela por un lado, con forma irregular
        val leak = rnd.nextFloat() < 0.45f
        val leakSide = rnd.nextInt(4)
        val leakPos = rf(0f, 1f)
        val leakLen = m * rf(0.1f, 0.5f)
        val leakDepth = m * rf(0.01f, 0.06f)
        val leakK = rf(0.3f, 0.85f)
        val leakG = rf(0.25f, 0.55f)

        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val fy = y + 0.5f + cy0
            for (x in 0 until ow) {
                val fx = x + 0.5f + cx0

                // Coordenadas dentro de la foto (girada)
                val dx = fx - pcx
                val dy = fy - pcy
                val px = ca * dx + sa * dy + w / 2f
                val py = -sa * dx + ca * dy + h / 2f

                // Orilla interior: ondulada y con suavidad que cambia a lo largo
                val wave = (noise1(px * waveFreq, s1) + noise1(py * waveFreq, s1 + 7) - 1f) * waveAmp
                val dIn = roundedRectSdf(px, py, inset, inset, w - inset, h - inset, rIn) + wave
                val softIn = softBase * (0.5f + 1.5f * noise1((px + py) * waveFreq * 2f, s3))
                val aImg = 1f - smooth(-softIn, softIn, dIn)

                // Orilla exterior: áspera, con muescas
                val ex = fx - ocx
                val ey = fy - ocy
                val qx = oca * ex + osa * ey + ocx
                val qy = -osa * ex + oca * ey + ocy
                val rough = (noise1(qx * roughFreq, s2) + noise1(qy * roughFreq, s2 + 3) - 1f) * roughAmp +
                    (noise1(qx * roughFreq * 0.12f, s2 + 5) + noise1(qy * roughFreq * 0.12f, s2 + 9) - 1f) * roughAmp * 1.5f
                val dOut = roundedRectSdf(qx, qy, ox0, oy0, ox1, oy1, rOut) + rough
                val aFilm = 1f - smooth(-softOut, softOut, dOut)

                var r = paperTone
                var g = paperTone
                var b = paperTone - 2f

                if (aFilm > 0f) {
                    // Negro nunca parejo: manchas suaves de densidad y textura fina
                    val blotch = (noise1(fx * 0.01f, s3) + noise1(fy * 0.013f, s3 + 4) - 1f) * 7f
                    val n = (FilmEngine.hash(x, y, s1) - 0.5f) * 7f + blotch
                    val fog = if (fogK > 0f) fogK * exp(-max(0f, dIn) / fogW) else 0f
                    var fr = baseR + n + fog
                    var fg = baseG + n + fog * 0.8f
                    var fb = baseB + n + fog * 0.6f
                    if (FilmEngine.hash(x / 2, y / 2, s2) > 0.9995f) {
                        fr = 190f; fg = 188f; fb = 182f
                    }
                    r += (fr - r) * aFilm
                    g += (fg - g) * aFilm
                    b += (fb - b) * aFilm
                }

                // El portanegativos recorta la película: la foto nunca se sale al papel
                val aPhoto = aImg * aFilm
                if (aPhoto > 0f) {
                    val sx = px.toInt().coerceIn(0, w - 1)
                    val sy = py.toInt().coerceIn(0, h - 1)
                    val p = src[sy * w + sx]
                    var pr = (p shr 16 and 0xff).toFloat()
                    var pg = (p shr 8 and 0xff).toFloat()
                    var pb = (p and 0xff).toFloat()
                    val k = smooth(-m * 0.012f, 0f, dIn)
                    val kk = k * k * 0.14f
                    pr *= 1f - kk
                    pg *= 1f - kk * 1.2f
                    pb *= 1f - kk * 1.4f
                    // Motas de polvo del escaneo (muy pocas)
                    val dustCell = max(2, (m / 700f).toInt())
                    if (FilmEngine.hash(x / dustCell, y / dustCell, s1 + 99) > 0.99985f) {
                        pr = 235f; pg = 233f; pb = 228f
                    }
                    r += (pr - r) * aPhoto
                    g += (pg - g) * aPhoto
                    b += (pb - b) * aPhoto
                }

                if (leak && aFilm > 0f) {
                    val along = when (leakSide) {
                        0, 2 -> py - leakPos * h
                        else -> px - leakPos * w
                    }
                    val fromSide = when (leakSide) {
                        0 -> px
                        1 -> py
                        2 -> w - px
                        else -> h - py
                    }
                    // Forma irregular: la profundidad cambia a lo largo del lado
                    val depth = leakDepth * (0.3f + 1.4f * noise1(along / (m * 0.05f), s3 + 21))
                    val fAlong = exp(-(along * along) / (leakLen * leakLen))
                    val fDepth = exp(-max(0f, fromSide - inset) / depth)
                    val f = (leakK * fAlong * fDepth * aFilm).coerceIn(0f, 1f)
                    if (f > 0.003f) {
                        r = 255f - (255f - r) * (1f - f * 0.92f)
                        g = 255f - (255f - g) * (1f - f * leakG)
                        b = 255f - (255f - b) * (1f - f * 0.06f)
                    }
                }
                out[y * ow + x] = (0xff shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
            }
        }
        return Result(out, ow, oh)
    }

    /** Distancia con signo a un rectángulo redondeado (negativa adentro). */
    private fun roundedRectSdf(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Float {
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val hx = (x1 - x0) / 2f - r
        val hy = (y1 - y0) / 2f - r
        val qx = abs(x - cx) - hx
        val qy = abs(y - cy) - hy
        val ax = max(qx, 0f)
        val ay = max(qy, 0f)
        return sqrt(ax * ax + ay * ay) + min(max(qx, qy), 0f) - r
    }

    private fun noise1(t: Float, s: Int): Float {
        val i = floor(t).toInt()
        val f = t - i
        val a = FilmEngine.hash(i, s, 3)
        val b = FilmEngine.hash(i + 1, s, 3)
        val u = f * f * (3f - 2f * f)
        return a + (b - a) * u
    }

    private fun smooth(e0: Float, e1: Float, x: Float) = FilmEngine.smoothstep(e0, e1, x)

    private fun clamp255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }
}
