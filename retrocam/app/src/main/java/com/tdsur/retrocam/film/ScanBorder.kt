package com.tdsur.retrocam.film

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Borde de escaneo: imita una foto de rollo escaneada con el marco del portanegativos.
 * - Fondo blanco del escáner alrededor.
 * - Marco negro de la película, de grosor desigual en cada lado.
 * - Orilla interior irregular, suave y con esquinas redondeadas.
 * - Un poco de quemado cálido en la orilla y, a veces, luz que se cuela por un lado.
 *
 * Kotlin puro (sin Android) para poder probarlo en la computadora.
 */
object ScanBorder {

    class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun apply(src: IntArray, w: Int, h: Int, seed: Long): Result {
        val m = min(w, h).toFloat()
        val rnd = Random(seed xor 0x5CA9L)

        val paper = m * 0.028f
        val film = m * 0.02f
        val pad = (paper + film).roundToInt()
        val ow = w + pad * 2
        val oh = h + pad * 2

        // Grosor del marco negro en cada lado (desigual, como en un escaneo real)
        fun side(base: Float) = base * (0.55f + rnd.nextFloat() * 0.9f)
        val bl = side(film)
        val bt = side(film)
        val br = side(film)
        val bb = side(film)
        // Orilla exterior del negro (también un poco irregular)
        val ox0 = paper * (0.8f + rnd.nextFloat() * 0.4f)
        val oy0 = paper * (0.8f + rnd.nextFloat() * 0.4f)
        val ox1 = ow - paper * (0.8f + rnd.nextFloat() * 0.4f)
        val oy1 = oh - paper * (0.8f + rnd.nextFloat() * 0.4f)
        // Área de la imagen
        // Área de la imagen (siempre dentro de la foto, para no repetir píxeles de la orilla)
        val ix0 = pad + (bl - 0.55f * film)
        val iy0 = pad + (bt - 0.55f * film)
        val ix1 = ow - pad - (br - 0.55f * film)
        val iy1 = oh - pad - (bb - 0.55f * film)

        val rIn = m * 0.012f
        val rOut = m * 0.006f
        val soft = max(0.8f, m * 0.0012f)
        val amp = m * 0.0035f
        val freq = 1f / (m * 0.035f)
        val burnW = m * 0.03f
        val s1 = rnd.nextInt(1000)
        val s2 = rnd.nextInt(1000)

        // Luz que se cuela por la orilla (60% de las veces)
        val bleed = rnd.nextFloat() < 0.6f
        val bleedSide = rnd.nextInt(4)
        val bleedT = 0.1f + rnd.nextFloat() * 0.8f
        val bleedX = when (bleedSide) { 0 -> ix0; 1 -> ix1; else -> ix0 + (ix1 - ix0) * bleedT }
        val bleedY = when (bleedSide) { 2 -> iy0; 3 -> iy1; else -> iy0 + (iy1 - iy0) * bleedT }
        val bleedR = m * (0.08f + rnd.nextFloat() * 0.12f)
        val bleedK = 0.35f + rnd.nextFloat() * 0.45f

        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val fy = y + 0.5f
            val sy = (y - pad).coerceIn(0, h - 1)
            for (x in 0 until ow) {
                val fx = x + 0.5f
                val nIn = edgeNoise(fx * freq, fy * freq, s1) * amp
                val nOut = edgeNoise(fx * freq * 0.7f, fy * freq * 0.7f, s2) * amp * 0.6f
                val dIn = roundedRectSdf(fx, fy, ix0, iy0, ix1, iy1, rIn) + nIn
                val dOut = roundedRectSdf(fx, fy, ox0, oy0, ox1, oy1, rOut) + nOut
                val aImg = 1f - smooth(-soft, soft, dIn)
                val aFilm = 1f - smooth(-soft, soft, dOut)

                // Papel del escáner
                var r = 243f
                var g = 241f
                var b = 236f

                if (aFilm > 0f) {
                    // Base de la película: casi negra, con un poco de textura
                    val tex = (FilmEngine.hash(x, y, s1) - 0.5f) * 6f
                    val fr = 16f + tex
                    val fg = 14f + tex
                    val fb = 12f + tex
                    r += (fr - r) * aFilm
                    g += (fg - g) * aFilm
                    b += (fb - b) * aFilm
                }
                if (aImg > 0f) {
                    val p = src[sy * w + (x - pad).coerceIn(0, w - 1)]
                    var pr = (p shr 16 and 0xff).toFloat()
                    var pg = (p shr 8 and 0xff).toFloat()
                    var pb = (p and 0xff).toFloat()
                    // Quemado cálido y oscuro junto a la orilla
                    val k = smooth(-burnW, 0f, dIn)
                    val kk = k * k
                    pr *= 1f - 0.18f * kk
                    pg *= 1f - 0.26f * kk
                    pb *= 1f - 0.34f * kk
                    r += (pr - r) * aImg
                    g += (pg - g) * aImg
                    b += (pb - b) * aImg
                }
                if (bleed && aFilm > 0f) {
                    val dx = fx - bleedX
                    val dy = fy - bleedY
                    val f = bleedK * exp(-(dx * dx + dy * dy) / (bleedR * bleedR)) * aFilm
                    if (f > 0.002f) {
                        r = 255f - (255f - r) * (1f - f * 1.0f)
                        g = 255f - (255f - g) * (1f - f * 0.55f)
                        b = 255f - (255f - b) * (1f - f * 0.15f)
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
        val qx = kotlin.math.abs(x - cx) - hx
        val qy = kotlin.math.abs(y - cy) - hy
        val ax = max(qx, 0f)
        val ay = max(qy, 0f)
        return sqrt(ax * ax + ay * ay) + min(max(qx, qy), 0f) - r
    }

    /** Ruido suave para que la orilla no sea una línea perfecta. Rango aprox. -1..1. */
    private fun edgeNoise(u: Float, v: Float, s: Int): Float =
        (noise1(u, s) + noise1(v, s + 31) + 0.5f * noise1(u * 3.1f, s + 7) + 0.5f * noise1(v * 3.1f, s + 13) +
            0.3f * noise1(u * 11f, s + 17) + 0.3f * noise1(v * 11f, s + 19)) / 3.6f * 2f - 1f

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
