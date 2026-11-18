package com.tdsur.retrocam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
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
import com.tdsur.retrocam.film.LookCategory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

enum class CaptureMode(val label: String) { PHOTO("FOTO"), VHS("VIDEO VHS") }

/** Estado compartido entre la cámara y el editor. */
class StudioState {
    var mode by mutableStateOf(CaptureMode.PHOTO)
    /** Desgaste de la cinta VHS: 0 = cinta nueva, 1 = muy gastada. */
    var vhsDamage by mutableStateOf(0.35f)
    var vhsOsd by mutableStateOf(true)

    var look by mutableStateOf(FilmLooks.PORTRA_400)
        private set
    var category by mutableStateOf(LookCategory.COLOR)
    var dateStamp by mutableStateOf(FilmLooks.PORTRA_400.defaultDate)
    var frame by mutableStateOf(FilmLooks.PORTRA_400.defaultFrame)

    fun select(newLook: FilmLook) {
        look = newLook
        dateStamp = newLook.defaultDate
        frame = newLook.defaultFrame
    }
}

/** Categorías de rollos + carrusel de filtros de la categoría elegida. */
@Composable
fun LookPicker(state: StudioState, thumbnails: Map<String, ImageBitmap>? = null) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(LookCategory.entries) { cat ->
            OptionChip(cat.label, cat == state.category) { state.category = cat }
        }
    }
    LookCarousel(
        looks = FilmLooks.inCategory(state.category),
        selected = state.look,
        onSelect = { state.select(it) },
        thumbnails = thumbnails,
    )
}

/** Carrusel horizontal de filtros. */
@Composable
fun LookCarousel(
    looks: List<FilmLook>,
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
        items(looks, key = { it.id }) { look ->
            val isSel = look.id == selected.id
            val view = LocalView.current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSelect(look)
                    },
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
fun FramedViewfinder(frame: FrameStyle, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Tamaños relativos al ancho de la foto (igual que en FilmRenderer)
        val (sideF, topF, bottomF) = when (frame) {
            FrameStyle.NONE -> Triple(0f, 0f, 0f)
            FrameStyle.SCAN -> Triple(0.02f, 0.02f, 0.02f)
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
            FrameStyle.SCAN -> Color(0xFFFAFAF8)
            FrameStyle.NONE -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .width(photoW * totalW)
                .height(photoW * totalH)
                .background(bg)
                .then(if (frame == FrameStyle.FILM35) Modifier.drawBehind { drawSprockets(photoW.toPx() * 0.14f) } else Modifier)
                .then(if (frame == FrameStyle.SCAN) Modifier.drawBehind { drawScanFilm(photoW.toPx()) } else Modifier)
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
                    .clip(RoundedCornerShape(if (frame == FrameStyle.NONE || frame == FrameStyle.SCAN) 6.dp else 0.dp))
                    .background(Color.Black)
            ) { content() }
        }
    }
}

/** Marco negro de la película dentro del papel blanco del escáner. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScanFilm(photoW: Float) {
    val paper = photoW * 0.011f
    drawRoundRect(
        Color(0xFF100E0C),
        Offset(paper, paper),
        Size(size.width - paper * 2, size.height - paper * 2),
        CornerRadius(photoW * 0.004f),
    )
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

/** Fecha naranja sobre la vista previa (en la foto final la dibuja FilmRenderer). */
@Composable
fun DateOverlay(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
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
