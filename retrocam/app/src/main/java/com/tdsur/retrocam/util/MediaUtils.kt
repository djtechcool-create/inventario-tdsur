package com.tdsur.retrocam.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

object MediaUtils {

    /** Carga una imagen de la galería (respeta la orientación EXIF) y la reduce a [maxSize]. */
    suspend fun load(context: Context, uri: Uri, maxSize: Int): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > maxSize) {
                val s = maxSize.toFloat() / long
                decoder.setTargetSize(
                    max(1, (info.size.width * s).roundToInt()),
                    max(1, (info.size.height * s).roundToInt()),
                )
            }
        }
    }

    /** Gira, refleja (cámara frontal) y reduce una foto recién tomada. */
    fun orient(bmp: Bitmap, rotationDegrees: Int, mirror: Boolean, maxSize: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        val s = if (long > maxSize) maxSize.toFloat() / long else 1f
        val m = Matrix().apply {
            postScale(if (mirror) -s else s, s)
            postRotate(rotationDegrees.toFloat())
        }
        if (m.isIdentity) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun scaleToMax(bmp: Bitmap, maxSize: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        if (long <= maxSize) return bmp
        val s = maxSize.toFloat() / long
        return Bitmap.createScaledBitmap(
            bmp,
            max(1, (bmp.width * s).roundToInt()),
            max(1, (bmp.height * s).roundToInt()),
            true,
        )
    }

    /** Guarda la foto en Imágenes/RetroCam. */
    suspend fun saveJpeg(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val name = "RetroCam_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RetroCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                ?: error("No se pudo abrir el archivo")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
