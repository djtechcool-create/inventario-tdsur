package com.tdsur.retrocam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tdsur.retrocam.film.FilmRenderer
import com.tdsur.retrocam.film.PreviewEffects
import com.tdsur.retrocam.film.RenderOptions
import com.tdsur.retrocam.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private const val MAX_PHOTO_SIZE = 2560

@Composable
fun CameraScreen(state: StudioState, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        CameraContent(state, onOpenGallery)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("RetroCam necesita la cámara para tomar fotos.", color = Cream, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenGallery) { Text("Editar una foto de la galería") }
        }
    }
}

@Composable
private fun CameraContent(state: StudioState, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    // Semilla de la próxima foto: define fugas de luz, grano y polvo.
    var seed by remember { mutableLongStateOf(Random.nextLong()) }
    val flash = remember { Animatable(0f) }

    val resolution = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView: permite aplicar el shader a la vista previa
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolution)
            .build()
    }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(lensFacing) {
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            captureExecutor.shutdown()
        }
    }
    LaunchedEffect(flashOn) {
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val look = state.look
        val opts = RenderOptions(look = look, dateStamp = state.dateStamp, frame = state.frame, seed = seed)
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        scope.launch {
            flash.snapTo(0.85f)
            launch { flash.animateTo(0f, tween(300)) }
            try {
                val photo = capture(imageCapture, captureExecutor, mirror)
                val result = withContext(Dispatchers.Default) { FilmRenderer.render(photo, opts, MAX_PHOTO_SIZE) }
                val uri = MediaUtils.saveJpeg(context, result)
                if (uri != null) {
                    lastUri = uri
                    lastThumb = withContext(Dispatchers.Default) { MediaUtils.scaleToMax(result, 240) }
                    Toast.makeText(context, "Guardada en Imágenes/RetroCam", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No se pudo guardar la foto", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al tomar la foto", Toast.LENGTH_SHORT).show()
            } finally {
                seed = Random.nextLong()
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding(),
    ) {
        // Barra superior
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { flashOn = !flashOn }) {
                Icon(
                    if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "Flash",
                    tint = if (flashOn) Accent else Cream,
                )
            }
            Spacer(Modifier.weight(1f))
            OptionChip("FECHA", state.dateStamp) { state.dateStamp = !state.dateStamp }
            Spacer(Modifier.size(8.dp))
            OptionChip(state.frame.label, state.frame != com.tdsur.retrocam.film.FrameStyle.NONE) {
                state.frame = state.frame.next()
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            }) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Cambiar cámara", tint = Cream)
            }
        }

        // Visor
        FramedViewfinder(
            frame = state.frame,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AndroidView(
                factory = { previewView },
                update = { PreviewEffects.apply(it, state.look, 1f, seed) },
                modifier = Modifier.fillMaxSize(),
            )
            FilmOverlay(look = state.look, intensity = 1f, seed = seed, dateStamp = state.dateStamp)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flash.value))
            )
        }

        // Nombre del filtro
        Text(
            state.look.name.uppercase() + "  ·  " + state.look.description,
            color = Cream.copy(alpha = 0.75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp),
        )

        LookCarousel(selected = state.look, onSelect = { state.select(it) })

        // Controles inferiores
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Última foto
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp))
                    .background(Surface)
                    .clickable(enabled = lastUri != null) { lastUri?.let { openInViewer(context, it) } },
            ) {
                lastThumb?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "Última foto",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Obturador
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .border(4.dp, Cream, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (busy) Surface else Accent)
                    .clickable(enabled = !busy) { takePhoto() },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            }

            IconButton(onClick = onOpenGallery, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Editar foto de la galería", tint = Cream)
            }
        }
    }
}

/** Toma una foto y la devuelve ya girada y reducida. */
private suspend fun capture(
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    mirror: Boolean,
): Bitmap = suspendCancellableCoroutine { cont ->
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val rotation = image.imageInfo.rotationDegrees
                val raw = image.toBitmap()
                image.close()
                cont.resume(MediaUtils.orient(raw, rotation, mirror, MAX_PHOTO_SIZE))
            } catch (e: Exception) {
                image.close()
                cont.resumeWithException(e)
            }
        }

        override fun onError(exception: ImageCaptureException) {
            cont.resumeWithException(exception)
        }
    })
}

private fun openInViewer(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No hay una app para ver fotos", Toast.LENGTH_SHORT).show()
    }
}
