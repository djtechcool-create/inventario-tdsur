package com.tdsur.retrocam.film

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Aplica un [FilmLook] a una foto en CPU (resolución completa).
 * El orden de los pasos coincide con el shader de la vista previa ([PreviewEffects]).
 */
object FilmRenderer {

    fun render(source: Bitmap, opts: RenderOptions, maxSize: Int = 2560): Bitmap {
        val look = opts.look
        val t = opts.intensity.coerceIn(0f, 1f)

        var src = prepare(source, maxSize)
        if (look.softness > 0f && t > 0f) src = soften(src, look.softness * t)

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val hal = if (look.halation > 0f) HalationMap.build(src) else null
        val leaks = LeakGenerator.generate(look.leak, opts.seed)

        val inv = 1f / 255f
        val cx = w / 2f
        val cy = h / 2f
        val diagHalf = 0.5f * sqrt(w.toFloat() * w + h.toFloat() * h)
        val longSide = max(w, h).toFloat()
        val grainPx = max(1f, longSide / 2000f * look.grainSize)
        val seedI = opts.seed.toInt()
        val fadeScale = 1f - look.fade - look.crush
        val mw = look.monoWeights
        val st = look.shadowTint
        val ht = look.highlightTint
        val wm1 = max(1, w - 1).toFloat()
        val hm1 = max(1, h - 1).toFloat()

        for (y in 0 until h) {
            val v = y / hm1
            val dy = y - cy
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val r0 = (p shr 16 and 0xff) * inv
                val g0 = (p shr 8 and 0xff) * inv
                val b0 = (p and 0xff) * inv

                // 1. Exposición y contraste
                var r = (r0 + look.brightness - 0.5f) * look.contrast + 0.5f
                var g = (g0 + look.brightness - 0.5f) * look.contrast + 0.5f
                var b = (b0 + look.brightness - 0.5f) * look.contrast + 0.5f

                // 2. Balance de blancos
                r += look.warmth * 0.08f
                b -= look.warmth * 0.08f
                g += look.tint * 0.05f

                // 3. Blanco y negro
                if (look.mono > 0f) {
                    val m = r * mw.r + g * mw.g + b * mw.b
                    r += (m - r) * look.mono
                    g += (m - g) * look.mono
                    b += (m - b) * look.mono
                }

                // 4. Saturación
                var l = 0.299f * r + 0.587f * g + 0.114f * b
                r = clamp01(l + (r - l) * look.saturation)
                g = clamp01(l + (g - l) * look.saturation)
                b = clamp01(l + (b - l) * look.saturation)

                // 5. Tono dividido (sombras / luces)
                l = 0.299f * r + 0.587f * g + 0.114f * b
                val sh = (1f - l) * (1f - l)
                val hi = l * l
                r += st.r * sh + ht.r * hi
                g += st.g * sh + ht.g * hi
                b += st.b * sh + ht.b * hi

                // 6. Desvanecido (negros levantados, blancos apagados)
                r = look.fade + r * fadeScale
                g = look.fade + g * fadeScale
                b = look.fade + b * fadeScale

                // 7. Halación
                if (hal != null) {
                    val hv = hal.sample(x / wm1, v) * look.halation
                    r += hv * 0.9f
                    g += hv * 0.32f
                    b += hv * 0.12f
                }

                // 8. Fugas de luz (mezcla "screen")
                for (k in leaks.indices) {
                    val lk = leaks[k]
                    val lx = (x - lk.cx * w) / longSide
                    val ly = (y - lk.cy * h) / longSide
                    val dd = (lx * lx + ly * ly) / (lk.radius * lk.radius)
                    if (dd < 6f) {
                        val f = lk.strength * exp(-2f * dd)
                        r = 1f - (1f - clamp01(r)) * (1f - lk.color.r * f)
                        g = 1f - (1f - clamp01(g)) * (1f - lk.color.g * f)
                        b = 1f - (1f - clamp01(b)) * (1f - lk.color.b * f)
                    }
                }

                // 9. Viñeta
                if (look.vignette > 0f) {
                    val dx = x - cx
                    val d = sqrt(dx * dx + dy * dy) / diagHalf
                    val vg = 1f - look.vignette * smoothstep(0.35f, 1f, d)
                    r *= vg
                    g *= vg
                    b *= vg
                }

                // 10. Grano
                if (look.grain > 0f) {
                    val n = grainNoise(x, y, grainPx, seedI)
                    val weight = 1f - abs(l * 2f - 1f) * 0.6f
                    val gn = n * look.grain * weight
                    r += gn
                    g += gn
                    b += gn
                }

                // Intensidad: mezcla con la foto original
                r = r0 + (r - r0) * t
                g = g0 + (g - g0) * t
                b = b0 + (b - b0) * t

                px[i] = (0xff shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)

        if (look.dust > 0f && t > 0f) drawDust(out, look.dust * t, opts.seed)
        if (opts.dateStamp) drawDate(out, opts)
        return applyFrame(out, opts.frame, opts.seed)
    }

    // ---------------------------------------------------------------- utilidades

    /** Escala la imagen a [maxSize] y asegura un bitmap ARGB_8888 en memoria normal. */
    fun prepare(source: Bitmap, maxSize: Int): Bitmap {
        var bmp = source
        if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val long = max(bmp.width, bmp.height)
        if (long > maxSize) {
            val s = maxSize.toFloat() / long
            bmp = Bitmap.createScaledBitmap(
                bmp,
                max(1, (bmp.width * s).roundToInt()),
                max(1, (bmp.height * s).roundToInt()),
                true,
            )
        }
        return bmp
    }

    private fun soften(src: Bitmap, amount: Float): Bitmap {
        val f = 1f - 0.6f * amount.coerceIn(0f, 1f)
        val small = Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * f).roundToInt()),
            max(1, (src.height * f).roundToInt()),
            true,
        )
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    private fun to255(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535f
    }

    /** Ruido de grano con tamaño variable, aprox. en rango -1..1. */
    private fun grainNoise(x: Int, y: Int, gp: Float, s: Int): Float {
        val fx = x / gp
        val fy = y / gp
        val ix = fx.toInt()
        val iy = fy.toInt()
        val tx = fx - ix
        val ty = fy - iy
        val a = hash(ix, iy, s)
        val b = hash(ix + 1, iy, s)
        val c = hash(ix, iy + 1, s)
        val d = hash(ix + 1, iy + 1, s)
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        val vn = top + (bot - top) * ty
        val fine = hash(x, y, s xor 0x5bd1e995)
        return (vn * 0.7f + fine * 0.3f - 0.5f) * 2.6f
    }

    // ------------------------------------------------------------- polvo y rayones

    private fun drawDust(bmp: Bitmap, amount: Float, seed: Long) {
        val canvas = Canvas(bmp)
        val rnd = Random(seed xor 0x2545F491L)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val unit = max(w, h) / 1500f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Motas
        repeat((amount * 140).toInt()) {
            val light = rnd.nextFloat() < 0.6f
            paint.color = if (light) {
                Color.argb(110 + rnd.nextInt(130), 245, 240, 225)
            } else {
                Color.argb(90 + rnd.nextInt(120), 25, 18, 10)
            }
            canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, unit * (0.6f + rnd.nextFloat() * 2.2f), paint)
        }

        // Pelusas
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        repeat((amount * 7).toInt()) {
            val x0 = rnd.nextFloat() * w
            val y0 = rnd.nextFloat() * h
            val len = unit * (15f + rnd.nextFloat() * 45f)
            val path = Path().apply {
                moveTo(x0, y0)
                quadTo(
                    x0 + (rnd.nextFloat() - 0.5f) * len * 2f, y0 + (rnd.nextFloat() - 0.5f) * len * 2f,
                    x0 + (rnd.nextFloat() - 0.5f) * len * 2f, y0 + (rnd.nextFloat() - 0.5f) * len * 2f,
                )
            }
            paint.strokeWidth = unit * (0.6f + rnd.nextFloat() * 0.8f)
            paint.color = if (rnd.nextBoolean()) Color.argb(150, 240, 235, 220) else Color.argb(130, 20, 15, 10)
            canvas.drawPath(path, paint)
        }

        // Rayones verticales
        repeat((amount * 4).toInt() + 1) {
            var x = rnd.nextFloat() * w
            var y = rnd.nextFloat() * h * 0.3f
            val end = h * (0.6f + rnd.nextFloat() * 0.4f)
            val path = Path().apply { moveTo(x, y) }
            while (y < end) {
                y += unit * 40f
                x += (rnd.nextFloat() - 0.5f) * unit * 3f
                path.lineTo(x, y)
            }
            paint.strokeWidth = unit * (0.5f + rnd.nextFloat() * 0.6f)
            paint.color = Color.argb(60 + rnd.nextInt(80), 235, 230, 215)
            canvas.drawPath(path, paint)
        }
    }

    // ------------------------------------------------------------------- fecha

    private val dateFormat = DateTimeFormatter.ofPattern("''yy MM dd")

    private fun drawDate(bmp: Bitmap, opts: RenderOptions) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val m = min(w, h)
        val size = m * 0.055f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = size
            color = Color.rgb(255, 138, 30)
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.05f
            setShadowLayer(size * 0.25f, 0f, 0f, Color.argb(200, 255, 90, 0))
        }
        Canvas(bmp).drawText(opts.date.format(dateFormat), w - m * 0.06f, h - m * 0.06f, paint)
    }

    // ------------------------------------------------------------------- marcos

    private fun applyFrame(img: Bitmap, frame: FrameStyle, seed: Long): Bitmap = when (frame) {
        FrameStyle.NONE -> img
        FrameStyle.POLAROID -> polaroid(img)
        FrameStyle.FILM35 -> if (img.width >= img.height) {
            film35(img, seed)
        } else {
            // El negativo corre en vertical para fotos verticales
            val rotated = rotate(img, 90f)
            rotate(film35(rotated, seed), -90f)
        }
    }

    private fun rotate(bmp: Bitmap, deg: Float): Bitmap =
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)

    private fun polaroid(img: Bitmap): Bitmap {
        val m = min(img.width, img.height)
        val side = (m * 0.06f).roundToInt()
        val bottom = (m * 0.24f).roundToInt()
        val out = Bitmap.createBitmap(img.width + side * 2, img.height + side + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(244, 240, 232))
        c.drawBitmap(img, side.toFloat(), side.toFloat(), null)
        // Borde interior sutil
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, m * 0.002f)
            color = Color.argb(60, 0, 0, 0)
        }
        c.drawRect(side.toFloat(), side.toFloat(), (side + img.width).toFloat(), (side + img.height).toFloat(), p)
        return out
    }

    private fun film35(img: Bitmap, seed: Long): Bitmap {
        val w = img.width
        val h = img.height
        val band = (h * 0.14f).roundToInt()
        val side = (h * 0.03f).roundToInt()
        val out = Bitmap.createBitmap(w + side * 2, h + band * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(18, 16, 14))
        c.drawBitmap(img, side.toFloat(), band.toFloat(), null)

        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 218, 200) }
        val holeW = band * 0.28f
        val holeH = band * 0.38f
        val pitch = holeW * 2.1f
        val radius = holeW * 0.22f
        var x = pitch * 0.4f
        while (x + holeW < out.width) {
            val topY = band * 0.38f - holeH / 2f
            val botY = out.height - band * 0.38f - holeH / 2f
            c.drawRoundRect(RectF(x, topY, x + holeW, topY + holeH), radius, radius, hole)
            c.drawRoundRect(RectF(x, botY, x + holeW, botY + holeH), radius, radius, hole)
            x += pitch
        }

        val frameNo = 1 + Random(seed).nextInt(36)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(230, 140, 40)
            textSize = band * 0.16f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.15f
        }
        c.drawText("RETROCAM 400", side + band * 0.2f, band * 0.9f, text)
        c.drawText("$frameNo  ▸ ${frameNo}A", out.width * 0.6f, band * 0.9f, text)
        c.drawText("◂ ${frameNo + 1}", side + band * 0.2f, out.height - band * 0.78f, text)
        return out
    }
}

/** Mapa de luces fuertes difuminado, usado para la halación. */
private class HalationMap(private val data: FloatArray, private val w: Int, private val h: Int) {

    fun sample(u: Float, v: Float): Float {
        val fx = u * (w - 1)
        val fy = v * (h - 1)
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = data[y0 * w + x0]
        val b = data[y0 * w + x1]
        val c = data[y1 * w + x0]
        val d = data[y1 * w + x1]
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }

    companion object {
        fun build(src: Bitmap): HalationMap {
            val sw = max(8, src.width / 10)
            val sh = max(8, src.height / 10)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val d = FloatArray(sw * sh) { i ->
                val p = px[i]
                val l = (0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)) / 255f
                val v = ((l - 0.68f) / 0.32f).coerceIn(0f, 1f)
                v * v
            }
            val radius = max(1, max(sw, sh) / 60)
            repeat(3) { boxBlur(d, sw, sh, radius) }
            return HalationMap(d, sw, sh)
        }

        private fun boxBlur(d: FloatArray, w: Int, h: Int, r: Int) {
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
