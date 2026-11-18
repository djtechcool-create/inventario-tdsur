package com.tdsur.retrocam.film

import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Motor de emulación de rollo, en Kotlin puro (sin Android) para poder probarlo en la computadora.
 * Trabaja sobre píxeles ARGB empaquetados en un IntArray.
 *
 * Orden del pipeline (igual que el shader de la vista previa):
 *  suavidad → aberración cromática → exposición → mezcla de canales → B/N → curvas →
 *  saturación → tono dividido → bloom → halación → flash → fugas de luz → viñeta → grano.
 */
object FilmEngine {

    private const val LUT_SIZE = 1024

    fun process(src: IntArray, w: Int, h: Int, opts: RenderOptions): IntArray {
        val look = opts.look
        val t = opts.intensity.coerceIn(0f, 1f)
        val out = IntArray(w * h)
        if (t <= 0f) {
            src.copyInto(out)
            return out
        }

        val longSide = max(w, h).toFloat()
        val unit = longSide / 1500f

        // 1. Suavidad del rollo / lente
        val soft = if (look.softness > 0f) gaussianBlur(src, w, h, look.softness * unit * 0.8f * t) else src

        // 2. Mapas de luces para bloom y halación
        val glow = if (look.bloom > 0f || look.halation > 0f) GlowMaps.build(soft, w, h) else null

        // 2b. Quitar el "contraste local" del HDR del celular (sin quitar el detalle fino):
        // se mezcla un poco con una versión muy desenfocada.
        val flatten = look.flatten * t
        val wide = if (flatten > 0f) GlowMaps.localMean(soft, w, h) else null

        val lutR = curveLut(look.curveR, LUT_SIZE)
        val lutG = curveLut(look.curveG, LUT_SIZE)
        val lutB = curveLut(look.curveB, LUT_SIZE)
        val m = look.mix
        val mw = look.monoWeights
        // Cada foto del rollo sale un poco distinta: exposición, tono del laboratorio, grano y viñeta.
        val vr = kotlin.random.Random(opts.seed xor 0x7A3F1L)
        val vAmt = if (look.id == FilmLooks.ORIGINAL.id) 0f else opts.variation
        val expJitter = (vr.nextFloat() + vr.nextFloat() + vr.nextFloat() - 1.5f) * 0.4f * vAmt
        val castR = (vr.nextFloat() - 0.5f) * 0.045f * vAmt
        val castG = (vr.nextFloat() - 0.5f) * 0.03f * vAmt
        val castB = (vr.nextFloat() - 0.5f) * 0.05f * vAmt
        val grainAmt = look.grain * (1f + (max(0f, -expJitter) * 1.6f + vr.nextFloat() * 0.25f) * vAmt)
        val vigMul = 1f + (vr.nextFloat() - 0.5f) * 0.8f * vAmt
        val leakNoiseSeed = vr.nextInt()
        val gain = 2f.pow((look.exposure + expJitter) * 0.45f)
        val leaks = LeakGenerator.generate(look.leak, opts.seed)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val diagHalf = 0.5f * sqrt(w.toFloat() * w + h.toFloat() * h)
        val ca = look.aberration * t
        val grainPx = max(1f, unit * look.grainSize * 0.9f)
        val seedI = opts.seed.toInt()
        val inv = 1f / 255f
        val wm1 = max(1, w - 1).toFloat()
        val hm1 = max(1, h - 1).toFloat()
        val lutMax = (LUT_SIZE - 1).toFloat()
        val vignette = (look.vignette + look.flash * 0.25f) * vigMul

        // Filas en paralelo: aprovecha todos los núcleos del teléfono
        IntStream.range(0, h).parallel().forEach { y ->
            val dy = y - cy
            val v = y / hm1
            for (x in 0 until w) {
                val i = y * w + x
                val dx = x - cx
                val sp = soft[i]

                // 3. Aberración cromática: rojo hacia afuera, azul hacia adentro
                var r: Float
                var g = (sp shr 8 and 0xff) * inv
                var b: Float
                if (ca > 0f) {
                    r = sampleChannel(soft, w, h, cx + dx * (1f + ca), cy + dy * (1f + ca), 16)
                    b = sampleChannel(soft, w, h, cx + dx * (1f - ca), cy + dy * (1f - ca), 0)
                } else {
                    r = (sp shr 16 and 0xff) * inv
                    b = (sp and 0xff) * inv
                }
                if (wide != null) {
                    // Solo se reduce la diferencia de brillo local, no el color
                    val lw = wide.bloom(x / wm1, v)
                    val lc = 0.299f * r + 0.587f * g + 0.114f * b
                    val dl = (lw - lc) * flatten
                    r += dl; g += dl; b += dl
                }

                // 4. Exposición
                r *= gain; g *= gain; b *= gain

                // 5. Mezcla de canales
                val mr = m[0] * r + m[1] * g + m[2] * b
                val mg = m[3] * r + m[4] * g + m[5] * b
                val mb = m[6] * r + m[7] * g + m[8] * b
                r = mr; g = mg; b = mb

                // 6. Blanco y negro
                if (look.mono > 0f) {
                    val mono = r * mw.r + g * mw.g + b * mw.b
                    r += (mono - r) * look.mono
                    g += (mono - g) * look.mono
                    b += (mono - b) * look.mono
                }

                // 7. Curvas del rollo
                r = lookup(lutR, r, lutMax)
                g = lookup(lutG, g, lutMax)
                b = lookup(lutB, b, lutMax)

                // 8. Saturación (menor en luces y sombras)
                var l = 0.299f * r + 0.587f * g + 0.114f * b
                val s = look.saturation * (1f - look.satRollHi * smoothstep(0.55f, 1f, l) -
                    look.satRollLo * smoothstep(0.35f, 0f, l))
                r = l + (r - l) * s
                g = l + (g - l) * s
                b = l + (b - l) * s

                // 9. Tono dividido
                val sh = (1f - l) * (1f - l)
                val hi = l * l
                r += look.shadowTint.r * sh + look.highlightTint.r * hi + castR
                g += look.shadowTint.g * sh + look.highlightTint.g * hi + castG
                b += look.shadowTint.b * sh + look.highlightTint.b * hi + castB

                // 10. Bloom y halación
                if (glow != null) {
                    val u = x / wm1
                    if (look.bloom > 0f) {
                        val bl = glow.bloom(u, v) * look.bloom
                        r = 1f - (1f - clamp01(r)) * (1f - bl)
                        g = 1f - (1f - clamp01(g)) * (1f - bl * 0.96f)
                        b = 1f - (1f - clamp01(b)) * (1f - bl * 0.9f)
                    }
                    if (look.halation > 0f) {
                        val hv = glow.halation(u, v) * look.halation
                        r += hv * 1.0f
                        g += hv * 0.25f
                        b += hv * 0.05f
                    }
                }

                val d = sqrt(dx * dx + dy * dy) / diagHalf

                // 11. Flash directo
                if (look.flash > 0f) {
                    val f = 1f + look.flash * 0.35f * (1f - d * d)
                    r *= f; g *= f; b *= f
                }

                // 12. Fugas de luz
                for (k in leaks.indices) {
                    val lk = leaks[k]
                    if (lk.strength <= 0f) continue
                    val lx = (x - lk.cx * w) / longSide
                    val ly = (y - lk.cy * h) / longSide
                    val dd = (lx * lx + ly * ly) / (lk.radius * lk.radius)
                    if (dd < 6f) {
                        // Forma irregular, no una mancha redonda perfecta
                        val shape = 0.35f + 1.3f * valueNoise(x / (longSide * 0.07f), y / (longSide * 0.07f), leakNoiseSeed + k)
                        val f = (lk.strength * exp(-2f * dd) * shape).coerceIn(0f, 1f)
                        r = 1f - (1f - clamp01(r)) * (1f - lk.color.r * f)
                        g = 1f - (1f - clamp01(g)) * (1f - lk.color.g * f)
                        b = 1f - (1f - clamp01(b)) * (1f - lk.color.b * f)
                    }
                }

                // 13. Viñeta
                if (vignette > 0f) {
                    val vg = 1f - vignette * smoothstep(0.3f, 1.05f, d)
                    r *= vg; g *= vg; b *= vg
                }

                // 14. Grano: más visible en tonos medios, con un poco de color
                if (grainAmt > 0f) {
                    l = clamp01(0.299f * r + 0.587f * g + 0.114f * b)
                    val weight = (0.35f + 2.6f * l * (1f - l)) * grainAmt
                    val nl = grainNoise(x, y, grainPx, seedI)
                    val gc = look.grainColor
                    r += (nl + gc * (hash(x, y, seedI + 11) - 0.5f) * 1.6f) * weight
                    g += nl * weight
                    b += (nl + gc * (hash(x, y, seedI + 23) - 0.5f) * 1.6f) * weight
                }

                // Intensidad: mezcla con la foto original
                val p = src[i]
                val r0 = (p shr 16 and 0xff) * inv
                val g0 = (p shr 8 and 0xff) * inv
                val b0 = (p and 0xff) * inv
                r = r0 + (r - r0) * t
                g = g0 + (g - g0) * t
                b = b0 + (b - b0) * t

                out[i] = (0xff shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ curvas

    /** Curva monótona suave (Fritsch–Carlson) muestreada en [n] puntos. */
    fun curveLut(curve: ToneCurve, n: Int): FloatArray {
        val p = curve.points
        val k = p.size / 2
        val xs = FloatArray(k) { p[it * 2] }
        val ys = FloatArray(k) { p[it * 2 + 1] }
        val dk = FloatArray(k - 1) { (ys[it + 1] - ys[it]) / max(1e-6f, xs[it + 1] - xs[it]) }
        val tan = FloatArray(k)
        tan[0] = dk[0]
        tan[k - 1] = dk[k - 2]
        for (i in 1 until k - 1) {
            tan[i] = if (dk[i - 1] * dk[i] <= 0f) 0f else (dk[i - 1] + dk[i]) / 2f
        }
        for (i in 0 until k - 1) {
            if (dk[i] == 0f) {
                tan[i] = 0f; tan[i + 1] = 0f
            } else {
                val a = tan[i] / dk[i]
                val b = tan[i + 1] / dk[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / sqrt(s)
                    tan[i] = tau * a * dk[i]
                    tan[i + 1] = tau * b * dk[i]
                }
            }
        }
        return FloatArray(n) { idx ->
            val x = idx / (n - 1f)
            when {
                x <= xs[0] -> ys[0]
                x >= xs[k - 1] -> ys[k - 1]
                else -> {
                    var seg = 0
                    while (x > xs[seg + 1]) seg++
                    val hh = xs[seg + 1] - xs[seg]
                    val tt = (x - xs[seg]) / hh
                    val t2 = tt * tt
                    val t3 = t2 * tt
                    (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + tt) * hh * tan[seg] +
                        (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * hh * tan[seg + 1]
                }
            }
        }
    }

    /** Tabla de 256 colores con las 3 curvas, para subirla como textura al shader. */
    fun curveTexture(look: FilmLook): ByteArray {
        val r = curveLut(look.curveR, 256)
        val g = curveLut(look.curveG, 256)
        val b = curveLut(look.curveB, 256)
        val bytes = ByteArray(256 * 4)
        for (i in 0 until 256) {
            bytes[i * 4] = to255(r[i]).toByte()
            bytes[i * 4 + 1] = to255(g[i]).toByte()
            bytes[i * 4 + 2] = to255(b[i]).toByte()
            bytes[i * 4 + 3] = 255.toByte()
        }
        return bytes
    }

    private fun lookup(lut: FloatArray, v: Float, maxIdx: Float): Float {
        val f = clamp01(v) * maxIdx
        val i = f.toInt()
        if (i >= lut.size - 1) return lut[lut.size - 1]
        val fr = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * fr
    }

    // ------------------------------------------------------------- utilidades

    fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun to255(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }

    fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535f
    }

    private fun valueNoise(fx: Float, fy: Float, s: Int): Float {
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        val a = hash(ix, iy, s)
        val b = hash(ix + 1, iy, s)
        val c = hash(ix, iy + 1, s)
        val d = hash(ix + 1, iy + 1, s)
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }

    /** Grano "aglomerado": dos escalas de ruido más un poco de ruido fino. Rango aprox. -1..1. */
    private fun grainNoise(x: Int, y: Int, gp: Float, s: Int): Float {
        val n1 = valueNoise(x / gp, y / gp, s)
        val n2 = valueNoise(x / (gp * 2.3f), y / (gp * 2.3f), s + 101)
        val fine = hash(x, y, s + 7)
        return (n1 * 0.55f + n2 * 0.25f + fine * 0.2f - 0.5f) * 2.4f
    }

    /** Muestra un canal (desplazamiento 16 = rojo, 0 = azul) con interpolación bilineal. */
    private fun sampleChannel(px: IntArray, w: Int, h: Int, fx: Float, fy: Float, shift: Int): Float {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = x - x0
        val ty = y - y0
        val a = (px[y0 * w + x0] shr shift and 0xff)
        val b = (px[y0 * w + x1] shr shift and 0xff)
        val c = (px[y1 * w + x0] shr shift and 0xff)
        val d = (px[y1 * w + x1] shr shift and 0xff)
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return (top + (bot - top) * ty) / 255f
    }

    // --------------------------------------------------------------- desenfoque

    /** Desenfoque gaussiano aproximado con 3 pasadas de caja. Devuelve un arreglo nuevo. */
    fun gaussianBlur(src: IntArray, w: Int, h: Int, sigma: Float): IntArray {
        val out = src.copyOf()
        if (sigma < 0.3f) return out
        val tmp = IntArray(src.size)
        for (r in boxRadii(sigma)) {
            if (r <= 0) continue
            boxBlurH(out, tmp, w, h, r)
            boxBlurV(tmp, out, w, h, r)
        }
        return out
    }

    private fun boxRadii(sigma: Float): IntArray {
        val n = 3
        val wIdeal = sqrt(12f * sigma * sigma / n + 1f)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)
        val mm = mIdeal.roundToInt()
        return IntArray(n) { i -> ((if (i < mm) wl else wu) - 1) / 2 }
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = src[row + k.coerceIn(0, w - 1)]
                sr += p shr 16 and 0xff; sg += p shr 8 and 0xff; sb += p and 0xff
            }
            for (x in 0 until w) {
                dst[row + x] = (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += (pIn shr 16 and 0xff) - (pOut shr 16 and 0xff)
                sg += (pIn shr 8 and 0xff) - (pOut shr 8 and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        IntStream.range(0, w).parallel().forEach { x ->
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = src[k.coerceIn(0, h - 1) * w + x]
                sr += p shr 16 and 0xff; sg += p shr 8 and 0xff; sb += p and 0xff
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += (pIn shr 16 and 0xff) - (pOut shr 16 and 0xff)
                sg += (pIn shr 8 and 0xff) - (pOut shr 8 and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }
}

/** Mapas pequeños y difuminados de las zonas brillantes. */
internal class GlowMaps(
    private val bloomMap: FloatArray,
    private val halMap: FloatArray,
    private val w: Int,
    private val h: Int,
) {
    fun bloom(u: Float, v: Float) = sample(bloomMap, u, v)
    fun halation(u: Float, v: Float) = sample(halMap, u, v)

    private fun sample(d: FloatArray, u: Float, v: Float): Float {
        val fx = u * (w - 1)
        val fy = v * (h - 1)
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val top = d[y0 * w + x0] + (d[y0 * w + x1] - d[y0 * w + x0]) * tx
        val bot = d[y1 * w + x0] + (d[y1 * w + x1] - d[y1 * w + x0]) * tx
        return top + (bot - top) * ty
    }

    companion object {
        /** Brillo promedio de la zona (mapa pequeño y muy difuminado). Se lee con [bloom]. */
        fun localMean(px: IntArray, w: Int, h: Int): GlowMaps {
            val f = max(1, max(w, h) / 240)
            val sw = max(4, w / f)
            val sh = max(4, h / f)
            val lum = luminance(px, w, h, sw, sh)
            repeat(3) { blur(lum, sw, sh, 2) }
            return GlowMaps(lum, lum, sw, sh)
        }

        private fun luminance(px: IntArray, w: Int, h: Int, sw: Int, sh: Int): FloatArray {
            val lum = FloatArray(sw * sh)
            for (sy in 0 until sh) for (sx in 0 until sw) {
                var sum = 0f
                var n = 0
                val y0 = sy * h / sh
                val y1 = max(y0 + 1, (sy + 1) * h / sh)
                val x0 = sx * w / sw
                val x1 = max(x0 + 1, (sx + 1) * w / sw)
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    while (xx < x1) {
                        val p = px[yy * w + xx]
                        sum += 0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)
                        n++
                        xx += 2
                    }
                    yy += 2
                }
                lum[sy * sw + sx] = sum / max(1, n) / 255f
            }
            return lum
        }

        fun build(px: IntArray, w: Int, h: Int): GlowMaps {
            val f = max(1, max(w, h) / 240)
            val sw = max(4, w / f)
            val sh = max(4, h / f)
            // Luminancia promedio por bloque
            val lum = FloatArray(sw * sh)
            for (sy in 0 until sh) for (sx in 0 until sw) {
                var sum = 0f
                var n = 0
                val y0 = sy * h / sh
                val y1 = max(y0 + 1, (sy + 1) * h / sh)
                val x0 = sx * w / sw
                val x1 = max(x0 + 1, (sx + 1) * w / sw)
                var yy = y0
                while (yy < y1) {
                    var xx = x0
                    while (xx < x1) {
                        val p = px[yy * w + xx]
                        sum += 0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)
                        n++
                        xx += 2
                    }
                    yy += 2
                }
                lum[sy * sw + sx] = sum / max(1, n) / 255f
            }
            val bloom = FloatArray(lum.size) { val v = FilmEngine.smoothstep(0.55f, 1f, lum[it]); v * v }
            val hal = FloatArray(lum.size) { val v = FilmEngine.smoothstep(0.72f, 1f, lum[it]); v * v }
            val big = max(sw, sh)
            repeat(3) { blur(bloom, sw, sh, max(1, big / 25)) }
            repeat(3) { blur(hal, sw, sh, max(1, big / 45)) }
            for (i in hal.indices) hal[i] = min(1f, hal[i] * 1.6f)
            return GlowMaps(bloom, hal, sw, sh)
        }

        private fun blur(d: FloatArray, w: Int, h: Int, r: Int) {
            val tmp = FloatArray(d.size)
            val norm = 1f / (2 * r + 1)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (k in -r..r) s += d[y * w + (x + k).coerceIn(0, w - 1)]
                tmp[y * w + x] = s * norm
            }
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (k in -r..r) s += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                d[y * w + x] = s * norm
            }
        }
    }
}
