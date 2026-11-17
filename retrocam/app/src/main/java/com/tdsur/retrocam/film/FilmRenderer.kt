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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Aplica un [FilmLook] a una foto: el color y la textura los hace [FilmEngine];
 * aquí se agregan polvo, fecha y marco.
 */
object FilmRenderer {

    fun render(source: Bitmap, opts: RenderOptions, maxSize: Int = 3000): Bitmap {
        val src = prepare(source, maxSize)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val result = FilmEngine.process(px, w, h, opts)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)

        val t = opts.intensity.coerceIn(0f, 1f)
        if (opts.look.dust > 0f && t > 0f) drawDust(out, opts.look.dust * t, opts.seed)
        if (opts.dateStamp) drawDate(out, opts)
        return applyFrame(out, opts.frame, opts.seed)
    }

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

    // ------------------------------------------------------------- polvo y rayones

    private fun drawDust(bmp: Bitmap, amount: Float, seed: Long) {
        val canvas = Canvas(bmp)
        val rnd = Random(seed xor 0x2545F491L)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val unit = max(w, h) / 1500f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        repeat((amount * 140).toInt()) {
            val light = rnd.nextFloat() < 0.6f
            paint.color = if (light) {
                Color.argb(110 + rnd.nextInt(130), 245, 240, 225)
            } else {
                Color.argb(90 + rnd.nextInt(120), 25, 18, 10)
            }
            canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, unit * (0.6f + rnd.nextFloat() * 2.2f), paint)
        }

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
        FrameStyle.SCAN -> scan(img, seed)
        FrameStyle.POLAROID -> polaroid(img)
        FrameStyle.FILM35 -> if (img.width >= img.height) {
            film35(img, seed)
        } else {
            // El negativo corre en vertical para fotos verticales
            rotate(film35(rotate(img, 90f), seed), -90f)
        }
    }

    private fun rotate(bmp: Bitmap, deg: Float): Bitmap =
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)

    private fun scan(img: Bitmap, seed: Long): Bitmap {
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getPixels(px, 0, w, 0, 0, w, h)
        val r = ScanBorder.apply(px, w, h, seed)
        val out = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
        out.setPixels(r.pixels, 0, r.width, 0, 0, r.width, r.height)
        return out
    }

    private fun polaroid(img: Bitmap): Bitmap {
        val m = min(img.width, img.height)
        val side = (m * 0.06f).roundToInt()
        val bottom = (m * 0.24f).roundToInt()
        val out = Bitmap.createBitmap(img.width + side * 2, img.height + side + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(244, 240, 232))
        c.drawBitmap(img, side.toFloat(), side.toFloat(), null)
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
