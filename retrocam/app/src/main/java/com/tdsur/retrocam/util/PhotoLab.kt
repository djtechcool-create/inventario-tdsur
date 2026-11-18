package com.tdsur.retrocam.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.tdsur.retrocam.film.FilmRenderer
import com.tdsur.retrocam.film.RenderOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * "Laboratorio": revela las fotos en segundo plano, una tras otra,
 * para que la cámara quede libre enseguida para la siguiente foto.
 */
object PhotoLab {
    /** Cuántas fotos pueden esperar revelado a la vez (para no agotar la memoria). */
    const val MAX_PENDING = 3

    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val main = Handler(Looper.getMainLooper())

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    class Developed(val uri: Uri, val thumbnail: Bitmap)

    private val _last = MutableStateFlow<Developed?>(null)
    val last: StateFlow<Developed?> = _last

    fun develop(context: Context, photo: Bitmap, opts: RenderOptions, maxSize: Int) {
        val app = context.applicationContext
        _pending.update { it + 1 }
        scope.launch {
            try {
                val result = FilmRenderer.render(photo, opts, maxSize)
                val uri = MediaUtils.saveJpeg(app, result)
                if (uri != null) {
                    _last.value = Developed(uri, MediaUtils.scaleToMax(result, 240))
                } else {
                    toast(app, "No se pudo guardar la foto")
                }
            } catch (e: Throwable) {
                toast(app, "Error al revelar la foto")
            } finally {
                _pending.update { it - 1 }
            }
        }
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
