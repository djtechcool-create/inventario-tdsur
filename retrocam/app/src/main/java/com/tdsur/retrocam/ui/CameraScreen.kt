package com.tdsur.retrocam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tdsur.retrocam.film.FilmRenderer
import com.tdsur.retrocam.film.FrameStyle
import com.tdsur.retrocam.film.RenderOptions
import com.tdsur.retrocam.gl.GlProcessor
import com.tdsur.retrocam.gl.RetroEffect
import com.tdsur.retrocam.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private const val MAX_PHOTO_SIZE = 2048

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
            Text("RetroCam necesita la cámara para tomar fotos y videos.", color = Cream, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenGallery) { Text("Editar una foto de la galería") }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraContent(state: StudioState, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val processor = remember { GlProcessor.get(context) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var lastIsVideo by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordSeconds by remember { mutableLongStateOf(0L) }
    // Semilla de la próxima foto: define fugas de luz, grano y polvo.
    var seed by remember { mutableLongStateOf(Random.nextLong()) }
    val flash = remember { Animatable(0f) }
    val isVideo = state.mode == CaptureMode.VHS

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(isVideo) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (isVideo && !granted) audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val resolution = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
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
    // Video en calidad SD 4:3, como una cinta VHS
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .setAspectRatio(AspectRatio.RATIO_4_3)
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val photoEffect = remember { RetroEffect(CameraEffect.PREVIEW, processor) }
    val videoEffect = remember { RetroEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, processor) }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Pasa los ajustes actuales al procesador de la GPU
    SideEffect {
        processor.mode = if (isVideo) GlProcessor.Mode.VHS else GlProcessor.Mode.FILM
        processor.look = state.look
        processor.seed = seed
        processor.vhsDamage = state.vhsDamage
        processor.vhsOsd = state.vhsOsd
    }

    LaunchedEffect(lensFacing, isVideo) {
        val p = cameraProvider(context)
        provider = p
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val group = UseCaseGroup.Builder().addUseCase(preview)
        if (isVideo) {
            group.addUseCase(videoCapture).addEffect(videoEffect)
        } else {
            group.addUseCase(imageCapture).addEffect(photoEffect)
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            p.unbindAll()
            val camera = p.bindToLifecycle(lifecycleOwner, selector, group.build())
            processor.sensorRotation = camera.cameraInfo.sensorRotationDegrees
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            provider?.unbindAll()
            captureExecutor.shutdown()
        }
    }
    LaunchedEffect(flashOn) {
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }
    // Contador de grabación
    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording != null) {
            delay(1000)
            recordSeconds++
        }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val opts = RenderOptions(look = state.look, dateStamp = state.dateStamp, frame = state.frame, seed = seed)
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
                    lastIsVideo = false
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

    fun toggleRecording() {
        val current = recording
        if (current != null) {
            current.stop()
            return
        }
        val name = "RetroCam_VHS_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RetroCam")
        }
        val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = recorder.prepareRecording(context, output)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE &&
                    event.outputResults.outputUri == Uri.EMPTY
                ) {
                    Toast.makeText(context, "No se pudo guardar el video", Toast.LENGTH_SHORT).show()
                } else {
                    lastUri = event.outputResults.outputUri
                    lastIsVideo = true
                    lastThumb = null
                    Toast.makeText(context, "Video guardado en Películas/RetroCam", Toast.LENGTH_SHORT).show()
                }
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
            if (isVideo) {
                Spacer(Modifier.size(48.dp))
            } else {
                IconButton(onClick = { flashOn = !flashOn }) {
                    Icon(
                        if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Flash",
                        tint = if (flashOn) Accent else Cream,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (isVideo) {
                val worn = state.vhsDamage > 0.5f
                OptionChip(if (worn) "CINTA GASTADA" else "CINTA NUEVA", worn) {
                    state.vhsDamage = if (worn) 0.35f else 0.85f
                }
                Spacer(Modifier.size(8.dp))
                OptionChip("TEXTOS", state.vhsOsd) { state.vhsOsd = !state.vhsOsd }
            } else {
                OptionChip("FECHA", state.dateStamp) { state.dateStamp = !state.dateStamp }
                Spacer(Modifier.size(8.dp))
                OptionChip(state.frame.label, state.frame != FrameStyle.NONE) { state.frame = state.frame.next() }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                enabled = recording == null,
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Cambiar cámara", tint = Cream)
            }
        }

        // Visor (el filtro lo aplica la GPU antes de mostrarlo)
        FramedViewfinder(
            frame = if (isVideo) FrameStyle.NONE else state.frame,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (!isVideo && state.dateStamp) DateOverlay()
            if (recording != null) RecIndicator(recordSeconds, Modifier.align(Alignment.TopEnd))
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flash.value))
            )
        }

        // Selector de modo
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CaptureMode.entries.forEach { m ->
                val sel = m == state.mode
                Text(
                    m.label,
                    color = if (sel) Accent else Cream.copy(alpha = 0.6f),
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = recording == null && !busy) { state.mode = m }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        if (isVideo) {
            Text(
                "Cinta VHS de los 90 · el efecto se graba en el video",
                color = Cream.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 26.dp),
            )
        } else {
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
        }

        // Controles inferiores
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Última foto o video
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp))
                    .background(Surface)
                    .clickable(enabled = lastUri != null) {
                        lastUri?.let { openInViewer(context, it, if (lastIsVideo) "video/mp4" else "image/jpeg") }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val thumb = lastThumb
                if (thumb != null) {
                    Image(
                        thumb.asImageBitmap(),
                        contentDescription = "Última foto",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (lastIsVideo) {
                    Icon(Icons.Filled.Videocam, contentDescription = "Último video", tint = Cream)
                }
            }

            // Obturador
            if (isVideo) {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Cream, CircleShape)
                        .clickable { toggleRecording() },
                    contentAlignment = Alignment.Center,
                ) {
                    val rec = recording != null
                    Box(
                        Modifier
                            .size(if (rec) 28.dp else 56.dp)
                            .clip(if (rec) RoundedCornerShape(6.dp) else CircleShape)
                            .background(Color(0xFFE53935))
                    )
                }
            } else {
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
            }

            IconButton(onClick = onOpenGallery, enabled = recording == null, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Editar foto de la galería", tint = Cream)
            }
        }
    }
}

@Composable
private fun RecIndicator(seconds: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (seconds % 2 == 0L) Color(0xFFE53935) else Color(0x66E53935))
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "REC %02d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
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

private fun openInViewer(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No hay una app para abrirlo", Toast.LENGTH_SHORT).show()
    }
}
