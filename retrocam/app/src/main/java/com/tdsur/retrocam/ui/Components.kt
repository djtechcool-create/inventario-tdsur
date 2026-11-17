package com.tdsur.retrocam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tdsur.retrocam.film.FilmLook
import com.tdsur.retrocam.film.FilmLooks
import com.tdsur.retrocam.film.FrameStyle
import com.tdsur.retrocam.film.LeakGenerator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

val Accent = Color(0xFFFF8A1E)
val Cream = Color(0xFFF2E8D5)
val Background = Color(0xFF0E0C0A)
val Surface = Color(0xFF1E1A16)

@Composable
fun RetroCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Background,
            surface = Surface,
            onBackground = Cream,
            onSurface = Cream,
        ),
        content = content,
    )
}

/** Estado compartido entre la cámara y el editor. */
class StudioState {
    var look by mutableStateOf(FilmLooks.DORADO)
        private set
    var dateStamp by mutableStateOf(FilmLooks.DORADO.defaultDate)
    var frame by mutableStateOf(FilmLooks.DORADO.defaultFrame)

    fun select(newLook: FilmLook) {
        look = newLook
        dateStamp = newLook.defaultDate
        frame = newLook.defaultFrame
    }
}

/** Carrusel horizontal de filtros. */
@Composable
fun LookCarousel(
    selected: FilmLook,
    onSelect: (FilmLook) -> Unit,
    thumbnails: Map<String, ImageBitmap>? = null,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(FilmLooks.all, key = { it.id }) { look ->
            val isSel = look.id == selected.id
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(68.dp)
                    .clickable { onSelect(look) },
            ) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(shape)
                        .border(if (isSel) 2.dp else 1.dp, if (isSel) Accent else Color(0x33FFFFFF), shape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(look.swatch), Color(look.swatch).copy(alpha = 0.35f), Color.Black)
                            )
                        ),
                ) {
                    val thumb = thumbnails?.get(look.id)
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = look.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    look.name,
                    fontSize = 11.sp,
                    color = if (isSel) Accent else Cream.copy(alpha = 0.8f),
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Chip de texto para opciones (fecha, marco...). */
@Composable
fun OptionChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (active) Accent else Color(0x55FFFFFF), RoundedCornerShape(50))
            .background(if (active) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (active) Accent else Cream, fontWeight = FontWeight.Medium)
    }
}

/**
 * Contenedor con la forma del marco elegido (instantánea o negativo).
 * El contenido interior siempre tiene proporción 3:4.
 */
@Composable
fun FramedViewfinder(frame: FrameStyle, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Tamaños relativos al ancho de la foto (igual que en FilmRenderer)
        val (sideF, topF, bottomF) = when (frame) {
            FrameStyle.NONE -> Triple(0f, 0f, 0f)
            FrameStyle.POLAROID -> Triple(0.06f, 0.06f, 0.24f)
            FrameStyle.FILM35 -> Triple(0.14f, 0.03f, 0.03f)
        }
        val totalW = 1f + sideF * 2
        val totalH = 4f / 3f + topF + bottomF
        // Ajuste para caber en el espacio disponible
        val byWidth = maxWidth / totalW
        val byHeight = maxHeight / totalH
        val photoW = if (byWidth < byHeight) byWidth else byHeight

        val bg = when (frame) {
            FrameStyle.POLAROID -> Color(0xFFF4F0E8)
            FrameStyle.FILM35 -> Color(0xFF12100E)
            FrameStyle.NONE -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .width(photoW * totalW)
                .height(photoW * totalH)
                .background(bg)
                .then(if (frame == FrameStyle.FILM35) Modifier.drawBehind { drawSprockets(photoW.toPx() * 0.14f) } else Modifier)
                .padding(
                    start = photoW * sideF,
                    end = photoW * sideF,
                    top = photoW * topF,
                    bottom = photoW * bottomF,
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(if (frame == FrameStyle.NONE) 6.dp else 0.dp))
                    .background(Color.Black)
            ) { content() }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSprockets(band: Float) {
    val holeW = band * 0.38f
    val holeH = band * 0.28f
    val pitch = holeH * 2.1f
    var y = pitch * 0.4f
    val hole = Color(0xFFE1DAC8)
    while (y + holeH < size.height) {
        val leftX = band * 0.38f - holeW / 2f
        val rightX = size.width - band * 0.38f - holeW / 2f
        drawRoundRect(hole, Offset(leftX, y), Size(holeW, holeH), CornerRadius(holeH * 0.22f))
        drawRoundRect(hole, Offset(rightX, y), Size(holeW, holeH), CornerRadius(holeH * 0.22f))
        y += pitch
    }
}

/**
 * Capas que se dibujan sobre la vista previa: viñeta, fugas de luz y fecha.
 * Coinciden con lo que [com.tdsur.retrocam.film.FilmRenderer] dibuja en la foto final.
 */
@Composable
fun FilmOverlay(look: FilmLook, intensity: Float, seed: Long, dateStamp: Boolean, modifier: Modifier = Modifier) {
    val leaks = remember(look.leak, seed) { LeakGenerator.generate(look.leak, seed) }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val long = max(w, h)
                    // Fugas de luz
                    for (lk in leaks) {
                        val r = lk.radius * long * 1.6f
                        val base = Color(lk.color.r, lk.color.g, lk.color.b)
                        val stops = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { x ->
                            val a = (lk.strength * intensity * exp(-2f * (x * 1.6f) * (x * 1.6f))).coerceIn(0f, 1f)
                            x to base.copy(alpha = a)
                        }
                        drawRect(
                            Brush.radialGradient(
                                *stops.toTypedArray(),
                                center = Offset(lk.cx * w, lk.cy * h),
                                radius = r,
                            )
                        )
                    }
                    // Viñeta: alfa = vignette * smoothstep(0.35, 1, d)
                    val v = look.vignette * intensity
                    if (v > 0f) {
                        val black = Color.Black
                        drawRect(
                            Brush.radialGradient(
                                0f to black.copy(alpha = 0f),
                                0.35f to black.copy(alpha = 0f),
                                0.6f to black.copy(alpha = v * 0.33f),
                                0.8f to black.copy(alpha = v * 0.77f),
                                1f to black.copy(alpha = v),
                                center = Offset(w / 2f, h / 2f),
                                radius = hypot(w, h) / 2f,
                            )
                        )
                    }
                }
        )
        if (dateStamp) {
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("''yy MM dd")),
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
                style = MaterialTheme.typography.bodyLarge.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(Color(0xCCFF5A00), blurRadius = 10f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 16.dp),
            )
        }
    }
}
