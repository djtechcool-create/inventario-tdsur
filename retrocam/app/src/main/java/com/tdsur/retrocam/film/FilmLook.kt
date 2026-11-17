package com.tdsur.retrocam.film

import java.time.LocalDate
import kotlin.random.Random

/** Color RGB simple en rango 0..1 (también se usa como desplazamiento de color). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val ZERO = Rgb(0f, 0f, 0f)
        val LUMA = Rgb(0.299f, 0.587f, 0.114f)
    }
}

enum class FrameStyle(val label: String) {
    NONE("Sin marco"),
    POLAROID("Instantánea"),
    FILM35("Negativo");

    fun next(): FrameStyle = entries[(ordinal + 1) % entries.size]
}

/**
 * Describe el "carácter" de un rollo. Todos los filtros usan el mismo pipeline
 * (ver [FilmRenderer] y [PreviewEffects]), solo cambian estos parámetros.
 */
data class FilmLook(
    val id: String,
    val name: String,
    val description: String,
    /** Color representativo para la interfaz (ARGB). */
    val swatch: Long,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val saturation: Float = 1f,
    /** + cálido / - frío */
    val warmth: Float = 0f,
    /** + verde / - magenta */
    val tint: Float = 0f,
    /** Levanta los negros (look desvaído). */
    val fade: Float = 0f,
    /** Baja los blancos. */
    val crush: Float = 0f,
    val shadowTint: Rgb = Rgb.ZERO,
    val highlightTint: Rgb = Rgb.ZERO,
    /** 0 = color, 1 = blanco y negro. */
    val mono: Float = 0f,
    val monoWeights: Rgb = Rgb.LUMA,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 1.5f,
    /** Resplandor rojizo alrededor de las luces. */
    val halation: Float = 0f,
    /** Fugas de luz. */
    val leak: Float = 0f,
    /** Polvo, pelusas y rayones. */
    val dust: Float = 0f,
    /** Pérdida de nitidez de lentes viejos. */
    val softness: Float = 0f,
    val defaultFrame: FrameStyle = FrameStyle.NONE,
    val defaultDate: Boolean = false,
)

data class RenderOptions(
    val look: FilmLook,
    val intensity: Float = 1f,
    val dateStamp: Boolean = false,
    val frame: FrameStyle = FrameStyle.NONE,
    val seed: Long = 1L,
    val date: LocalDate = LocalDate.now(),
)

object FilmLooks {
    val ORIGINAL = FilmLook(
        id = "original", name = "Original", description = "Sin filtro",
        swatch = 0xFF9E9E9E,
    )

    val DORADO = FilmLook(
        id = "dorado", name = "Dorado 200", description = "Rollo cálido de los 90",
        swatch = 0xFFE0A040,
        contrast = 1.08f, brightness = 0.02f, saturation = 1.12f,
        warmth = 0.7f, tint = 0.1f, fade = 0.05f, crush = 0.03f,
        shadowTint = Rgb(0.03f, 0.01f, -0.02f), highlightTint = Rgb(0.04f, 0.02f, -0.03f),
        vignette = 0.35f, grain = 0.06f, halation = 0.15f, leak = 0.25f,
    )

    val RETRATO = FilmLook(
        id = "retrato", name = "Retrato 400", description = "Suave y pastel para personas",
        swatch = 0xFFE8B4A0,
        contrast = 0.92f, brightness = 0.03f, saturation = 0.88f,
        warmth = 0.35f, fade = 0.08f, crush = 0.04f,
        shadowTint = Rgb(0f, 0.02f, 0.04f), highlightTint = Rgb(0.04f, 0.02f, 0f),
        vignette = 0.2f, grain = 0.05f, halation = 0.1f,
    )

    val CINE = FilmLook(
        id = "cine", name = "Cine 800T", description = "Cine nocturno con halación",
        swatch = 0xFF3C7A8C,
        contrast = 1.12f, brightness = -0.01f, saturation = 0.95f,
        warmth = -0.6f, tint = -0.05f, fade = 0.04f,
        shadowTint = Rgb(-0.03f, 0.03f, 0.06f), highlightTint = Rgb(0.06f, 0.03f, -0.02f),
        vignette = 0.4f, grain = 0.1f, grainSize = 2f, halation = 0.7f,
    )

    val VERDE = FilmLook(
        id = "verde", name = "Verde 100", description = "Verdes profundos, tonos fríos",
        swatch = 0xFF5E8C5A,
        contrast = 1.05f, saturation = 1.05f,
        warmth = -0.15f, tint = 0.5f, fade = 0.06f,
        shadowTint = Rgb(-0.02f, 0.04f, 0.02f), highlightTint = Rgb(0.03f, 0f, 0.03f),
        vignette = 0.25f, grain = 0.07f,
    )

    val DESECHABLE = FilmLook(
        id = "desechable", name = "Desechable 98", description = "Cámara desechable con flash y fecha",
        swatch = 0xFFFF6A2A,
        contrast = 1.18f, brightness = 0.03f, saturation = 1.25f,
        warmth = 0.3f, tint = 0.05f, fade = 0.04f,
        shadowTint = Rgb(0f, 0.02f, 0.03f),
        vignette = 0.5f, grain = 0.09f, halation = 0.2f, leak = 0.5f,
        defaultDate = true,
    )

    val INSTANTANEA = FilmLook(
        id = "instantanea", name = "Instantánea", description = "Foto instantánea con marco blanco",
        swatch = 0xFFB8D4D0,
        contrast = 0.9f, brightness = 0.04f, saturation = 0.85f,
        warmth = 0.25f, fade = 0.12f, crush = 0.06f,
        shadowTint = Rgb(-0.02f, 0.03f, 0.05f), highlightTint = Rgb(0.05f, 0.03f, -0.02f),
        vignette = 0.3f, grain = 0.04f,
        defaultFrame = FrameStyle.POLAROID,
    )

    val PLATA = FilmLook(
        id = "plata", name = "Plata B&N", description = "Blanco y negro con mucho grano",
        swatch = 0xFF5A5A5A,
        contrast = 1.3f, mono = 1f, monoWeights = Rgb(0.35f, 0.5f, 0.15f),
        fade = 0.03f, vignette = 0.45f, grain = 0.14f, grainSize = 2f,
    )

    val SEPIA = FilmLook(
        id = "sepia", name = "Sepia 1920", description = "Foto antigua con polvo y rayones",
        swatch = 0xFF8C6A44,
        contrast = 1.05f, brightness = 0.02f, mono = 1f,
        fade = 0.14f, crush = 0.08f,
        shadowTint = Rgb(0.06f, 0.02f, -0.04f), highlightTint = Rgb(0.10f, 0.05f, -0.06f),
        vignette = 0.6f, grain = 0.12f, grainSize = 2.5f, dust = 0.8f, softness = 0.35f,
    )

    val all = listOf(ORIGINAL, DORADO, RETRATO, CINE, VERDE, DESECHABLE, INSTANTANEA, PLATA, SEPIA)

    fun byId(id: String): FilmLook = all.firstOrNull { it.id == id } ?: DORADO
}

/** Una fuga de luz en coordenadas normalizadas (0..1). El radio es relativo al lado largo. */
data class LeakSpec(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val color: Rgb,
    val strength: Float,
)

object LeakGenerator {
    private val palette = listOf(
        Rgb(1f, 0.45f, 0.12f),
        Rgb(1f, 0.22f, 0.10f),
        Rgb(1f, 0.72f, 0.30f),
        Rgb(0.95f, 0.30f, 0.45f),
    )

    fun generate(amount: Float, seed: Long): List<LeakSpec> {
        if (amount <= 0f) return emptyList()
        val rnd = Random(seed)
        val count = 1 + rnd.nextInt(2)
        return List(count) {
            val t = rnd.nextFloat()
            val (cx, cy) = when (rnd.nextInt(4)) {
                0 -> -0.05f to t
                1 -> 1.05f to t
                2 -> t to -0.05f
                else -> t to 1.05f
            }
            LeakSpec(
                cx = cx,
                cy = cy,
                radius = 0.25f + rnd.nextFloat() * 0.35f,
                color = palette[rnd.nextInt(palette.size)],
                strength = amount * (0.55f + rnd.nextFloat() * 0.45f),
            )
        }
    }
}
