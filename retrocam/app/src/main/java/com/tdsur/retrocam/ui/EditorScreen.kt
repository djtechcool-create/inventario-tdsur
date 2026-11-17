package com.tdsur.retrocam.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tdsur.retrocam.film.FilmLooks
import com.tdsur.retrocam.film.FilmRenderer
import com.tdsur.retrocam.film.FrameStyle
import com.tdsur.retrocam.film.RenderOptions
import com.tdsur.retrocam.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val PREVIEW_SIZE = 1280
private const val FULL_SIZE = 2560

@Composable
fun EditorScreen(uri: Uri, state: StudioState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var previewSource by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var rendered by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var thumbnails by remember(uri) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var intensity by remember { mutableFloatStateOf(1f) }
    var seed by remember { mutableLongStateOf(Random.nextLong()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            val full = MediaUtils.load(context, uri, FULL_SIZE)
            source = full
            val preview = withContext(Dispatchers.Default) { MediaUtils.scaleToMax(full, PREVIEW_SIZE) }
            previewSource = preview
            thumbnails = withContext(Dispatchers.Default) {
                val small = MediaUtils.scaleToMax(preview, 180)
                FilmLooks.all.associate { look ->
                    look.id to FilmRenderer.render(small, RenderOptions(look = look, seed = 7L), 180).asImageBitmap()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir la foto", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }

    LaunchedEffect(previewSource, state.look, intensity, state.dateStamp, state.frame, seed) {
        val src = previewSource ?: return@LaunchedEffect
        delay(60) // espera a que el usuario suelte el control para no saturar el CPU
        val opts = RenderOptions(state.look, intensity, state.dateStamp, state.frame, seed)
        rendered = withContext(Dispatchers.Default) { FilmRenderer.render(src, opts, PREVIEW_SIZE).asImageBitmap() }
    }

    fun save() {
        val src = source ?: return
        saving = true
        val opts = RenderOptions(state.look, intensity, state.dateStamp, state.frame, seed)
        scope.launch {
            val result = withContext(Dispatchers.Default) { FilmRenderer.render(src, opts, FULL_SIZE) }
            val saved = MediaUtils.saveJpeg(context, result)
            saving = false
            Toast.makeText(
                context,
                if (saved != null) "Guardada en Imágenes/RetroCam" else "No se pudo guardar la foto",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Cream)
            }
            Text("Editar", color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { seed = Random.nextLong() }) {
                Icon(Icons.Filled.Casino, contentDescription = "Otra variación", tint = Cream)
            }
            Button(onClick = { save() }, enabled = !saving && source != null) {
                Text(if (saving) "Guardando…" else "Guardar")
            }
            Spacer(Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val img = rendered
            if (img != null) {
                Image(
                    img,
                    contentDescription = "Foto con filtro",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(color = Accent)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Intensidad", color = Cream.copy(alpha = 0.8f), fontSize = 12.sp)
            Slider(
                value = intensity,
                onValueChange = { intensity = it },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OptionChip("FECHA", state.dateStamp) { state.dateStamp = !state.dateStamp }
            OptionChip(state.frame.label, state.frame != FrameStyle.NONE) { state.frame = state.frame.next() }
        }
        Spacer(Modifier.height(8.dp))
        LookCarousel(selected = state.look, onSelect = { state.select(it) }, thumbnails = thumbnails)
        Spacer(Modifier.size(16.dp))
    }
}
