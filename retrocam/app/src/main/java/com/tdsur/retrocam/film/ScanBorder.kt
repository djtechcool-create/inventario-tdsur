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
 * Lo que la hace parecer real (observado en escaneos de verdad):
 * - El borde negro casi siempre es delgado y solo un lado es grueso.
 * - La película queda un poco chueca en el escáner: el borde se adelgaza de un extremo al otro.
 * - La orilla exterior del negro es áspera, con muescas pequeñas.
 * - El margen blanco del escáner es delgado y limpio.
 * - Cuando hay luz que se cuela, tiñe de naranja el borde y la orilla de la foto a lo largo de un lado.
 *
 * Kotlin puro (sin Android) para poder probarlo en la computadora.
 */
object ScanBorder {

    class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun apply(src: IntArray, w: Int, h: Int, seed: Long): Result {
        val m = min(w, h).toFloat()
        val rnd = Random(seed xor 0x5CA9L)
        fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

        // Grosor visible del negro por lado: delgado, salvo un lado grueso
        val thick = rnd.nextInt(4)
        val t = FloatArray(4) { i -> if (i == thick) m * rf(0.010f, 0.024f) else m * rf(0.0025f, 0.006f) }
        // Margen blanco del escáner por lado
        val paper = FloatArray(4) { m * rf(0.008f, 0.016f) }
        // 0 = izquierda, 1 = arriba, 2 = derecha, 3 = abajo
        val offX = paper[0] + t[0]
        val offY = paper[1] + t[1]
        val ow = (w + offX + paper[2] + t[2]).roundToInt()
        val oh = (h + offY + paper[3] + t[3]).roundToInt()

        // La película gira un poco respecto al escáner
        val angle = Math.toRadians(rf(-0.45f, 0.45f).toDouble()).toFloat()
        val ca = cos(angle)
        val sa = sin(angle)
        val pcx = offX + w / 2f
        val pcy = offY + h / 2f

        // Borde exterior del negro (casi recto, con su propia pequeña inclinación)
        val outAngle = Math.toRadians(rf(-0.12f, 0.12f).toDouble()).toFloat()
        val oca = cos(outAngle)
        val osa = sin(outAngle)
        val ox0 = paper[0]
        val oy0 = paper[1]
        val ox1 = ow - paper[2]
        val oy1 = oh - paper[3]
        val ocx = (ox0 + ox1) / 2f
        val ocy = (oy0 + oy1) / 2f

        // Área de la imagen dentro de la foto (un poco recortada para que el giro no muestre huecos)
        val inset = m * 0.006f
        val rIn = m * rf(0.006f, 0.011f)
        val rOut = m * 0.004f
        val softIn = max(0.7f, m * 0.0007f)
        val softOut = max(0.6f, m * 0.0004f)
        val roughAmp = m * 0.0011f
        val roughFreq = 1f / (m * 0.0035f)
        val waveAmp = m * 0.0012f
        val waveFreq = 1f / (m * 0.06f)
        val s1 = rnd.nextInt(10000)
        val s2 = rnd.nextInt(10000)

        // Luz que se cuela a lo largo de un lado (55% de las veces)
        val leak = rnd.nextFloat() < 0.55f
        val leakSide = rnd.nextInt(4)
        val leakPos = rf(0.0f, 1.0f)
        val leakLen = m * rf(0.15f, 0.45f)
        val leakDepth = m * rf(0.015f, 0.045f)
        val leakK = rf(0.35f, 0.8f)

        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val fy = y + 0.5f
            for (x in 0 until ow) {
                val fx = x + 0.5f

                // Coordenadas dentro de la foto (girada)
                val dx = fx - pcx
                val dy = fy - pcy
                val px = ca * dx + sa * dy + w / 2f
                val py = -sa * dx + ca * dy + h / 2f

                // Orilla interior: limpia, apenas ondulada
                val wave = (noise1(px * waveFreq, s1) + noise1(py * waveFreq, s1 + 7) - 1f) * waveAmp
                val dIn = roundedRectSdf(px, py, inset, inset, w - inset, h - inset, rIn) + wave
                val aImg = 1f - smooth(-softIn, softIn, dIn)

                // Orilla exterior: áspera, con muescas
                val ex = fx - ocx
                val ey = fy - ocy
                val qx = oca * ex + osa * ey + ocx
                val qy = -osa * ex + oca * ey + ocy
                val rough = (noise1(qx * roughFreq, s2) + noise1(qy * roughFreq, s2 + 3) - 1f) * roughAmp +
                    (noise1(qx * roughFreq * 0.15f, s2 + 5) + noise1(qy * roughFreq * 0.15f, s2 + 9) - 1f) * roughAmp
                val dOut = roundedRectSdf(qx, qy, ox0, oy0, ox1, oy1, rOut) + rough
                val aFilm = 1f - smooth(-softOut, softOut, dOut)

                // Papel del escáner
                var r = 250f
                var g = 250f
                var b = 248f

                if (aFilm > 0f) {
                    // Base de la película: negro cálido, no perfecto
                    val n = (FilmEngine.hash(x, y, s1) - 0.5f) * 7f +
                        (noise1(fx * 0.02f, s2 + 11) - 0.5f) * 6f
                    var fr = 13f + n
                    var fg = 11f + n
                    var fb = 10f + n
                    // Motas de polvo blanco sobre el negro
                    if (FilmEngine.hash(x / 2, y / 2, s2) > 0.9994f) {
                        fr = 200f; fg = 198f; fb = 192f
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
                    // Leve oscurecimiento junto a la orilla
                    val k = smooth(-m * 0.012f, 0f, dIn)
                    val kk = k * k * 0.14f
                    pr *= 1f - kk
                    pg *= 1f - kk * 1.2f
                    pb *= 1f - kk * 1.4f
                    // Motas de polvo del escaneo sobre la foto (muy pocas)
                    val dustCell = max(2, (m / 700f).toInt())
                    if (FilmEngine.hash(x / dustCell, y / dustCell, s1 + 99) > 0.99985f) {
                        pr = 235f; pg = 233f; pb = 228f
                    }
                    r += (pr - r) * aPhoto
                    g += (pg - g) * aPhoto
                    b += (pb - b) * aPhoto
                }

                if (leak && aFilm > 0f) {
                    // Distancia a lo largo del lado elegido
                    val along = when (leakSide) {
                        0, 2 -> py - leakPos * h
                        else -> px - leakPos * w
                    }
                    val fAlong = exp(-(along * along) / (leakLen * leakLen))
                    // Distancia hacia adentro desde el lado elegido (negativa = en el borde negro)
                    val fromSide = when (leakSide) {
                        0 -> px
                        1 -> py
                        2 -> w - px
                        else -> h - py
                    }
                    val fDepth = exp(-max(0f, fromSide - inset) / leakDepth)
                    val f = (leakK * fAlong * fDepth * aFilm).coerceIn(0f, 1f)
                    if (f > 0.003f) {
                        r = 255f - (255f - r) * (1f - f * 0.92f)
                        g = 255f - (255f - g) * (1f - f * 0.36f)
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
