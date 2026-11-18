package com.tdsur.retrocam.film

import java.time.LocalDate
import kotlin.random.Random

// Este archivo no usa clases de Android: se puede probar en una computadora normal.

/** Color RGB simple en rango 0..1 (también se usa como desplazamiento de color). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val ZERO = Rgb(0f, 0f, 0f)
        val LUMA = Rgb(0.299f, 0.587f, 0.114f)
    }
}

enum class FrameStyle(val label: String) {
    NONE("Sin marco"),
    SCAN("Escaneo"),
    POLAROID("Instantánea"),
    FILM35("Negativo");

    fun next(): FrameStyle = entries[(ordinal + 1) % entries.size]
}

enum class LookCategory(val label: String) {
    COLOR("Color"),
    CINE("Cine"),
    SLIDE("Diapositiva"),
    BW("Blanco y negro"),
    OTHER("Instantánea y más"),
}

/** Curva de tono: pares (x, y) en 0..1, de izquierda a derecha. */
class ToneCurve(vararg val points: Float) {
    init {
        require(points.size >= 4 && points.size % 2 == 0)
    }

    companion object {
        val LINEAR = ToneCurve(0f, 0f, 1f, 1f)
    }
}

/**
 * Describe el carácter de un rollo. El mismo pipeline se usa en la foto final
 * ([FilmEngine]) y en la vista previa en vivo (shader GLSL en [com.tdsur.retrocam.gl.Shaders]).
 */
data class FilmLook(
    val id: String,
    val name: String,
    val description: String,
    /** Color representativo para la interfaz (ARGB). */
    val swatch: Long,
    val category: LookCategory = LookCategory.COLOR,
    /** Exposición en pasos (+1 = el doble de luz). */
    val exposure: Float = 0f,
    /** Mezcla entre canales (3x3, por filas): imita la "impureza" de los tintes del rollo. */
    val mix: FloatArray = IDENTITY,
    /** 0 = color, 1 = blanco y negro. */
    val mono: Float = 0f,
    val monoWeights: Rgb = Rgb.LUMA,
    /** Curvas por canal: dan el contraste, el desvanecido y el color de cada rollo. */
    val curveR: ToneCurve = ToneCurve.LINEAR,
    val curveG: ToneCurve = ToneCurve.LINEAR,
    val curveB: ToneCurve = ToneCurve.LINEAR,
    val saturation: Float = 1f,
    /** Cuánto se desatura en las luces y en las sombras (como el rollo real). */
    val satRollHi: Float = 0.4f,
    val satRollLo: Float = 0.2f,
    val shadowTint: Rgb = Rgb.ZERO,
    val highlightTint: Rgb = Rgb.ZERO,
    /** Cuánto se reduce el contraste local del HDR del celular (0..1). */
    val flatten: Float = 0.3f,
    /** Pérdida de nitidez (radio en píxeles, para una foto de 1500 px). Poca: el rollo escaneado es nítido. */
    val softness: Float = 0.35f,
    /** Separación de colores en las orillas (lentes baratos). */
    val aberration: Float = 0.0015f,
    /** Brillo difuso alrededor de las luces. */
    val bloom: Float = 0.1f,
    /** Resplandor rojo-naranja alrededor de las luces fuertes. */
    val halation: Float = 0.2f,
    val grain: Float = 0.05f,
    /** Tamaño del grano (relativo). */
    val grainSize: Float = 1.0f,
    /** Cuánto color tiene el grano (0 = gris). */
    val grainColor: Float = 0.35f,
    val vignette: Float = 0.3f,
    /** Flash directo: centro más brillante y orillas oscuras. */
    val flash: Float = 0f,
    val leak: Float = 0f,
    val dust: Float = 0f,
    val defaultFrame: FrameStyle = FrameStyle.NONE,
    val defaultDate: Boolean = false,
) {
    override fun equals(other: Any?) = other is FilmLook && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

data class RenderOptions(
    val look: FilmLook,
    val intensity: Float = 1f,
    val dateStamp: Boolean = false,
    val frame: FrameStyle = FrameStyle.NONE,
    val seed: Long = 1L,
    val date: LocalDate = LocalDate.now(),
    /** Cuánto varía cada foto respecto a la anterior (0 = todas iguales, 1 = como un rollo real). */
    val variation: Float = 1f,
)

/**
 * Catálogo de rollos. Cada filtro imita el carácter del rollo cuyo nombre lleva
 * (aproximación artística: curvas, color, grano y textura típicos de cada uno).
 */
object FilmLooks {
    private fun c(vararg p: Float) = ToneCurve(*p)
    private val C = LookCategory.COLOR
    private val CINE = LookCategory.CINE
    private val SLIDE = LookCategory.SLIDE
    private val BW = LookCategory.BW
    private val OTHER = LookCategory.OTHER
    private val SCAN = FrameStyle.SCAN

    val ORIGINAL = FilmLook(
        id = "original", name = "Original", description = "Sin filtro",
        swatch = 0xFF9E9E9E,
        satRollHi = 0f, satRollLo = 0f, softness = 0f, aberration = 0f, bloom = 0f, halation = 0f,
        grain = 0f, vignette = 0f, flatten = 0f,
    )

    // ------------------------------------------------------------ Color negativo

    val PORTRA_160 = FilmLook(
        id = "portra160", name = "Portra 160", description = "Kodak · natural, fino y suave",
        swatch = 0xFFE6C9B0, category = C,
        exposure = 0.1f,
        curveR = c(0f, 0.05f, 0.25f, 0.27f, 0.5f, 0.55f, 0.75f, 0.80f, 1f, 0.96f),
        curveG = c(0f, 0.05f, 0.25f, 0.26f, 0.5f, 0.535f, 0.75f, 0.79f, 1f, 0.955f),
        curveB = c(0f, 0.06f, 0.25f, 0.26f, 0.5f, 0.52f, 0.75f, 0.77f, 1f, 0.93f),
        saturation = 0.9f, satRollHi = 0.55f, satRollLo = 0.25f,
        softness = 0.3f, bloom = 0.1f, halation = 0.1f, grain = 0.03f, vignette = 0.12f,
        defaultFrame = SCAN,
    )

    val PORTRA_400 = FilmLook(
        id = "portra400", name = "Portra 400", description = "Kodak · piel cálida y cremosa",
        swatch = 0xFFE8B4A0, category = C,
        exposure = 0.2f,
        mix = floatArrayOf(1.02f, 0f, -0.02f, -0.02f, 1.03f, -0.01f, 0f, -0.04f, 1.04f),
        curveR = c(0f, 0.06f, 0.25f, 0.30f, 0.5f, 0.58f, 0.75f, 0.81f, 1f, 0.96f),
        curveG = c(0f, 0.055f, 0.25f, 0.28f, 0.5f, 0.55f, 0.75f, 0.785f, 1f, 0.95f),
        curveB = c(0f, 0.07f, 0.25f, 0.27f, 0.5f, 0.51f, 0.75f, 0.73f, 1f, 0.90f),
        saturation = 0.88f, satRollHi = 0.6f, satRollLo = 0.3f,
        softness = 0.45f, bloom = 0.15f, halation = 0.15f, grain = 0.045f, vignette = 0.15f,
        defaultFrame = SCAN,
    )

    val PORTRA_400_OVER = FilmLook(
        id = "portra400over", name = "Portra 400 +2", description = "Kodak sobreexpuesto · pastel y nostálgico",
        swatch = 0xFFE8B89A, category = C,
        exposure = 0.3f,
        mix = floatArrayOf(1.02f, 0f, -0.02f, -0.02f, 1.02f, 0f, 0f, -0.03f, 1.03f),
        curveR = c(0f, 0.10f, 0.25f, 0.33f, 0.5f, 0.60f, 0.75f, 0.83f, 1f, 0.97f),
        curveG = c(0f, 0.10f, 0.25f, 0.31f, 0.5f, 0.56f, 0.75f, 0.79f, 1f, 0.93f),
        curveB = c(0f, 0.13f, 0.25f, 0.32f, 0.5f, 0.53f, 0.75f, 0.73f, 1f, 0.86f),
        saturation = 0.78f, satRollHi = 0.6f, satRollLo = 0.3f,
        softness = 0.45f, bloom = 0.25f, halation = 0.3f, grain = 0.05f, vignette = 0.22f,
        defaultFrame = SCAN,
    )

    val PORTRA_800 = FilmLook(
        id = "portra800", name = "Portra 800", description = "Kodak · cálido, con más grano",
        swatch = 0xFFD99A70, category = C,
        exposure = 0.1f,
        curveR = c(0f, 0.06f, 0.25f, 0.30f, 0.5f, 0.59f, 0.75f, 0.83f, 1f, 0.97f),
        curveG = c(0f, 0.055f, 0.25f, 0.275f, 0.5f, 0.55f, 0.75f, 0.79f, 1f, 0.95f),
        curveB = c(0f, 0.07f, 0.25f, 0.26f, 0.5f, 0.49f, 0.75f, 0.72f, 1f, 0.89f),
        saturation = 0.96f, satRollHi = 0.5f,
        softness = 0.45f, bloom = 0.15f, halation = 0.25f, grain = 0.07f, grainSize = 1.2f, vignette = 0.2f,
        defaultFrame = SCAN,
    )

    val GOLD_200 = FilmLook(
        id = "gold200", name = "Gold 200", description = "Kodak · amarillo cálido de los 90",
        swatch = 0xFFE0A040, category = C,
        exposure = 0.1f,
        mix = floatArrayOf(1.04f, -0.02f, -0.02f, -0.03f, 1.05f, -0.02f, -0.02f, -0.06f, 1.08f),
        curveR = c(0f, 0.07f, 0.25f, 0.29f, 0.5f, 0.58f, 0.75f, 0.83f, 1f, 0.97f),
        curveG = c(0f, 0.065f, 0.25f, 0.265f, 0.5f, 0.535f, 0.75f, 0.79f, 1f, 0.95f),
        curveB = c(0f, 0.09f, 0.25f, 0.245f, 0.5f, 0.46f, 0.75f, 0.69f, 1f, 0.86f),
        saturation = 1.0f,
        softness = 0.4f, bloom = 0.12f, halation = 0.3f, grain = 0.045f, vignette = 0.2f, leak = 0.15f,
        defaultFrame = SCAN,
    )

    val COLORPLUS_200 = FilmLook(
        id = "colorplus200", name = "ColorPlus 200", description = "Kodak · cálido, apagado y barato",
        swatch = 0xFFC8955E, category = C,
        exposure = 0.05f,
        curveR = c(0f, 0.07f, 0.25f, 0.29f, 0.5f, 0.57f, 0.75f, 0.81f, 1f, 0.95f),
        curveG = c(0f, 0.06f, 0.25f, 0.265f, 0.5f, 0.53f, 0.75f, 0.77f, 1f, 0.92f),
        curveB = c(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.46f, 0.75f, 0.67f, 1f, 0.84f),
        saturation = 0.85f, satRollLo = 0.35f,
        softness = 0.5f, bloom = 0.12f, halation = 0.2f, grain = 0.07f, grainSize = 1.2f, vignette = 0.25f,
        defaultFrame = SCAN,
    )

    val ULTRAMAX_400 = FilmLook(
        id = "ultramax400", name = "UltraMax 400", description = "Kodak · colores intensos de verano",
        swatch = 0xFF1FA3A0, category = C,
        exposure = 0.05f,
        mix = floatArrayOf(1.06f, -0.04f, -0.02f, -0.05f, 1.06f, -0.01f, -0.03f, 0.02f, 1.01f),
        curveR = c(0f, 0.06f, 0.25f, 0.25f, 0.5f, 0.55f, 0.75f, 0.82f, 1f, 0.97f),
        curveG = c(0f, 0.07f, 0.25f, 0.27f, 0.5f, 0.54f, 0.75f, 0.80f, 1f, 0.96f),
        curveB = c(0f, 0.09f, 0.25f, 0.28f, 0.5f, 0.52f, 0.75f, 0.75f, 1f, 0.92f),
        saturation = 1.06f, satRollHi = 0.55f, satRollLo = 0.2f,
        softness = 0.35f, bloom = 0.1f, halation = 0.25f, grain = 0.05f, vignette = 0.18f,
        defaultFrame = SCAN,
    )

    val EKTAR_100 = FilmLook(
        id = "ektar100", name = "Ektar 100", description = "Kodak · vívido, nítido y contrastado",
        swatch = 0xFFD6402F, category = C,
        mix = floatArrayOf(1.06f, -0.04f, -0.02f, -0.03f, 1.05f, -0.02f, -0.02f, -0.04f, 1.06f),
        curveR = c(0f, 0.03f, 0.25f, 0.22f, 0.5f, 0.53f, 0.75f, 0.83f, 1f, 0.98f),
        curveG = c(0f, 0.035f, 0.25f, 0.225f, 0.5f, 0.52f, 0.75f, 0.81f, 1f, 0.97f),
        curveB = c(0f, 0.05f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.79f, 1f, 0.95f),
        saturation = 1.15f, satRollHi = 0.4f,
        softness = 0.25f, flatten = 0.2f, bloom = 0.08f, halation = 0.12f, grain = 0.025f, vignette = 0.15f,
        defaultFrame = SCAN,
    )

    val SUPERIA_400 = FilmLook(
        id = "superia400", name = "Superia 400", description = "Fujifilm · verdes profundos y fríos",
        swatch = 0xFF5E8C5A, category = C,
        mix = floatArrayOf(1.03f, -0.03f, 0f, -0.04f, 1.02f, 0.02f, 0f, 0.05f, 0.95f),
        curveR = c(0f, 0.05f, 0.25f, 0.23f, 0.5f, 0.51f, 0.75f, 0.80f, 1f, 0.97f),
        curveG = c(0f, 0.07f, 0.25f, 0.27f, 0.5f, 0.54f, 0.75f, 0.79f, 1f, 0.95f),
        curveB = c(0f, 0.09f, 0.25f, 0.27f, 0.5f, 0.52f, 0.75f, 0.77f, 1f, 0.94f),
        saturation = 0.95f,
        softness = 0.35f, bloom = 0.1f, halation = 0.15f, grain = 0.05f, vignette = 0.18f,
        defaultFrame = SCAN,
    )

    val C200 = FilmLook(
        id = "c200", name = "Fujicolor C200", description = "Fujifilm · frío, suave y cotidiano",
        swatch = 0xFF8FB5A8, category = C,
        exposure = 0.05f,
        curveR = c(0f, 0.06f, 0.25f, 0.25f, 0.5f, 0.52f, 0.75f, 0.78f, 1f, 0.95f),
        curveG = c(0f, 0.07f, 0.25f, 0.28f, 0.5f, 0.55f, 0.75f, 0.80f, 1f, 0.95f),
        curveB = c(0f, 0.08f, 0.25f, 0.28f, 0.5f, 0.53f, 0.75f, 0.77f, 1f, 0.93f),
        saturation = 0.88f,
        softness = 0.4f, bloom = 0.1f, halation = 0.12f, grain = 0.055f, vignette = 0.18f,
        defaultFrame = SCAN,
    )

    val PRO_400H = FilmLook(
        id = "pro400h", name = "Pro 400H", description = "Fujifilm · pastel menta y luminoso",
        swatch = 0xFFA8D5C2, category = C,
        exposure = 0.25f,
        curveR = c(0f, 0.08f, 0.25f, 0.30f, 0.5f, 0.56f, 0.75f, 0.80f, 1f, 0.95f),
        curveG = c(0f, 0.09f, 0.25f, 0.32f, 0.5f, 0.585f, 0.75f, 0.82f, 1f, 0.96f),
        curveB = c(0f, 0.10f, 0.25f, 0.32f, 0.5f, 0.57f, 0.75f, 0.80f, 1f, 0.94f),
        saturation = 0.8f, satRollHi = 0.6f,
        softness = 0.4f, bloom = 0.2f, halation = 0.12f, grain = 0.04f, vignette = 0.15f,
        defaultFrame = SCAN,
    )

    val LOMO_800 = FilmLook(
        id = "lomo800", name = "Lomo 800", description = "Lomography · saturado, contrastado y viñeta",
        swatch = 0xFFB0306A, category = C,
        curveR = c(0f, 0.03f, 0.25f, 0.21f, 0.5f, 0.56f, 0.75f, 0.86f, 1f, 0.99f),
        curveG = c(0f, 0.03f, 0.25f, 0.20f, 0.5f, 0.52f, 0.75f, 0.82f, 1f, 0.97f),
        curveB = c(0f, 0.06f, 0.25f, 0.22f, 0.5f, 0.49f, 0.75f, 0.76f, 1f, 0.92f),
        saturation = 1.2f,
        softness = 0.5f, aberration = 0.003f, bloom = 0.12f, halation = 0.3f,
        grain = 0.08f, grainSize = 1.3f, vignette = 0.5f, leak = 0.25f,
    )

    val EXPIRED = FilmLook(
        id = "caducado", name = "Rollo caducado", description = "Vencido · colores raros y mucho grano",
        swatch = 0xFFB08AA0, category = C,
        exposure = 0.15f,
        curveR = c(0f, 0.12f, 0.25f, 0.33f, 0.5f, 0.60f, 0.75f, 0.82f, 1f, 0.94f),
        curveG = c(0f, 0.08f, 0.25f, 0.27f, 0.5f, 0.52f, 0.75f, 0.75f, 1f, 0.89f),
        curveB = c(0f, 0.13f, 0.25f, 0.30f, 0.5f, 0.52f, 0.75f, 0.72f, 1f, 0.86f),
        saturation = 0.75f, shadowTint = Rgb(0.03f, -0.01f, 0.03f),
        softness = 0.6f, bloom = 0.15f, halation = 0.2f, grain = 0.09f, grainSize = 1.4f, vignette = 0.3f, leak = 0.3f,
        defaultFrame = SCAN,
    )

    // ------------------------------------------------------------ Cine

    val CINESTILL_800T = FilmLook(
        id = "cinestill800t", name = "CineStill 800T", description = "Cine nocturno con halación roja",
        swatch = 0xFF3C7A8C, category = CINE,
        mix = floatArrayOf(0.94f, 0.04f, 0.02f, 0.02f, 0.98f, 0f, 0.04f, 0.04f, 0.98f),
        curveR = c(0f, 0.05f, 0.25f, 0.23f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.97f),
        curveG = c(0f, 0.07f, 0.25f, 0.26f, 0.5f, 0.53f, 0.75f, 0.79f, 1f, 0.96f),
        curveB = c(0f, 0.10f, 0.25f, 0.29f, 0.5f, 0.54f, 0.75f, 0.78f, 1f, 0.95f),
        saturation = 0.95f,
        softness = 0.5f, bloom = 0.2f, halation = 1.0f, grain = 0.065f, grainSize = 1.3f, vignette = 0.25f,
        defaultFrame = SCAN,
    )

    val CINESTILL_50D = FilmLook(
        id = "cinestill50d", name = "CineStill 50D", description = "Cine de día · limpio y fino",
        swatch = 0xFF7FA7C9, category = CINE,
        curveR = c(0f, 0.04f, 0.25f, 0.23f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.97f),
        curveG = c(0f, 0.045f, 0.25f, 0.24f, 0.5f, 0.525f, 0.75f, 0.80f, 1f, 0.97f),
        curveB = c(0f, 0.06f, 0.25f, 0.26f, 0.5f, 0.54f, 0.75f, 0.81f, 1f, 0.97f),
        saturation = 1.0f,
        softness = 0.3f, bloom = 0.1f, halation = 0.6f, grain = 0.025f, vignette = 0.12f,
        defaultFrame = SCAN,
    )

    val VISION3_500T = FilmLook(
        id = "vision500t", name = "Vision3 500T", description = "Kodak cine · suave y cinematográfico",
        swatch = 0xFF4E6E78, category = CINE,
        mix = floatArrayOf(0.97f, 0.02f, 0.01f, 0.01f, 0.98f, 0.01f, 0.02f, 0.03f, 0.95f),
        curveR = c(0f, 0.06f, 0.25f, 0.26f, 0.5f, 0.53f, 0.75f, 0.79f, 1f, 0.95f),
        curveG = c(0f, 0.065f, 0.25f, 0.265f, 0.5f, 0.53f, 0.75f, 0.785f, 1f, 0.95f),
        curveB = c(0f, 0.08f, 0.25f, 0.28f, 0.5f, 0.535f, 0.75f, 0.77f, 1f, 0.92f),
        saturation = 0.9f, satRollHi = 0.5f,
        shadowTint = Rgb(-0.01f, 0.01f, 0.02f), highlightTint = Rgb(0.02f, 0.01f, -0.01f),
        softness = 0.4f, bloom = 0.15f, halation = 0.5f, grain = 0.055f, vignette = 0.2f,
        defaultFrame = SCAN,
    )

    // ------------------------------------------------------------ Diapositiva

    val VELVIA_50 = FilmLook(
        id = "velvia50", name = "Velvia 50", description = "Fujifilm diapositiva · hipersaturado",
        swatch = 0xFF2E8B3A, category = SLIDE,
        mix = floatArrayOf(1.08f, -0.05f, -0.03f, -0.04f, 1.08f, -0.04f, -0.03f, -0.05f, 1.08f),
        curveR = c(0f, 0.01f, 0.25f, 0.18f, 0.5f, 0.52f, 0.75f, 0.85f, 1f, 0.99f),
        curveG = c(0f, 0.01f, 0.25f, 0.18f, 0.5f, 0.51f, 0.75f, 0.84f, 1f, 0.98f),
        curveB = c(0f, 0.03f, 0.25f, 0.20f, 0.5f, 0.51f, 0.75f, 0.82f, 1f, 0.97f),
        saturation = 1.35f, satRollHi = 0.3f, satRollLo = 0.1f,
        softness = 0.25f, flatten = 0.15f, bloom = 0.05f, halation = 0.05f, grain = 0.02f, vignette = 0.25f,
    )

    val EKTACHROME = FilmLook(
        id = "ektachrome", name = "Ektachrome E100", description = "Kodak diapositiva · limpio y azul",
        swatch = 0xFF2F6FD0, category = SLIDE,
        curveR = c(0f, 0.015f, 0.25f, 0.19f, 0.5f, 0.50f, 0.75f, 0.82f, 1f, 0.98f),
        curveG = c(0f, 0.02f, 0.25f, 0.20f, 0.5f, 0.51f, 0.75f, 0.82f, 1f, 0.98f),
        curveB = c(0f, 0.04f, 0.25f, 0.23f, 0.5f, 0.55f, 0.75f, 0.85f, 1f, 0.99f),
        saturation = 1.15f,
        softness = 0.25f, flatten = 0.2f, bloom = 0.05f, halation = 0.05f, grain = 0.025f, vignette = 0.2f,
    )

    // ------------------------------------------------------------ Blanco y negro

    val TRI_X_400 = FilmLook(
        id = "trix400", name = "Tri-X 400", description = "Kodak · contraste fuerte y grano marcado",
        swatch = 0xFF3A3A3A, category = BW,
        mono = 1f, monoWeights = Rgb(0.30f, 0.59f, 0.11f),
        curveR = c(0f, 0.03f, 0.25f, 0.18f, 0.5f, 0.52f, 0.75f, 0.85f, 1f, 0.98f),
        curveG = c(0f, 0.03f, 0.25f, 0.18f, 0.5f, 0.52f, 0.75f, 0.85f, 1f, 0.98f),
        curveB = c(0f, 0.03f, 0.25f, 0.18f, 0.5f, 0.52f, 0.75f, 0.85f, 1f, 0.98f),
        softness = 0.3f, bloom = 0.05f, halation = 0f,
        grain = 0.1f, grainSize = 1.3f, grainColor = 0f, vignette = 0.15f,
    )

    val HP5 = FilmLook(
        id = "hp5", name = "HP5 Plus 400", description = "Ilford · blanco y negro clásico de calle",
        swatch = 0xFF5A5A5A, category = BW,
        mono = 1f, monoWeights = Rgb(0.33f, 0.57f, 0.10f),
        curveR = c(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.96f),
        curveG = c(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.96f),
        curveB = c(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.96f),
        softness = 0.3f, bloom = 0.05f, halation = 0f,
        grain = 0.07f, grainColor = 0f, vignette = 0.12f,
    )

    val TMAX_100 = FilmLook(
        id = "tmax100", name = "T-Max 100", description = "Kodak · tonos suaves y grano finísimo",
        swatch = 0xFF8A8A8A, category = BW,
        mono = 1f, monoWeights = Rgb(0.30f, 0.60f, 0.10f),
        curveR = c(0f, 0.04f, 0.25f, 0.22f, 0.5f, 0.52f, 0.75f, 0.81f, 1f, 0.97f),
        curveG = c(0f, 0.04f, 0.25f, 0.22f, 0.5f, 0.52f, 0.75f, 0.81f, 1f, 0.97f),
        curveB = c(0f, 0.04f, 0.25f, 0.22f, 0.5f, 0.52f, 0.75f, 0.81f, 1f, 0.97f),
        softness = 0.25f, bloom = 0.04f, halation = 0f,
        grain = 0.035f, grainSize = 0.9f, grainColor = 0f, vignette = 0.1f,
    )

    val DELTA_3200 = FilmLook(
        id = "delta3200", name = "Delta 3200", description = "Ilford · noche, grano enorme",
        swatch = 0xFF2A2A2A, category = BW,
        mono = 1f, monoWeights = Rgb(0.32f, 0.58f, 0.10f),
        curveR = c(0f, 0.09f, 0.25f, 0.26f, 0.5f, 0.52f, 0.75f, 0.78f, 1f, 0.94f),
        curveG = c(0f, 0.09f, 0.25f, 0.26f, 0.5f, 0.52f, 0.75f, 0.78f, 1f, 0.94f),
        curveB = c(0f, 0.09f, 0.25f, 0.26f, 0.5f, 0.52f, 0.75f, 0.78f, 1f, 0.94f),
        softness = 0.6f, bloom = 0.1f, halation = 0f,
        grain = 0.17f, grainSize = 1.8f, grainColor = 0f, vignette = 0.2f,
    )

    // ------------------------------------------------------------ Instantánea y más

    val POLAROID_600 = FilmLook(
        id = "polaroid600", name = "Polaroid 600", description = "Instantánea con marco blanco",
        swatch = 0xFFB8D4D0, category = OTHER,
        exposure = 0.15f,
        curveR = c(0f, 0.10f, 0.25f, 0.30f, 0.5f, 0.56f, 0.75f, 0.80f, 1f, 0.93f),
        curveG = c(0f, 0.13f, 0.25f, 0.33f, 0.5f, 0.57f, 0.75f, 0.80f, 1f, 0.92f),
        curveB = c(0f, 0.15f, 0.25f, 0.33f, 0.5f, 0.53f, 0.75f, 0.72f, 1f, 0.84f),
        saturation = 0.82f, satRollHi = 0.5f,
        softness = 0.9f, aberration = 0.002f, bloom = 0.2f, halation = 0.1f, grain = 0.035f, vignette = 0.3f,
        defaultFrame = FrameStyle.POLAROID,
    )

    val INSTAX = FilmLook(
        id = "instax", name = "Instax Mini", description = "Fujifilm instantánea · clara y fresca",
        swatch = 0xFFCFE3F0, category = OTHER,
        exposure = 0.2f,
        curveR = c(0f, 0.07f, 0.25f, 0.29f, 0.5f, 0.57f, 0.75f, 0.82f, 1f, 0.97f),
        curveG = c(0f, 0.07f, 0.25f, 0.29f, 0.5f, 0.57f, 0.75f, 0.82f, 1f, 0.97f),
        curveB = c(0f, 0.09f, 0.25f, 0.31f, 0.5f, 0.58f, 0.75f, 0.82f, 1f, 0.96f),
        saturation = 0.9f,
        softness = 0.7f, bloom = 0.2f, halation = 0.08f, grain = 0.03f, vignette = 0.2f,
        defaultFrame = FrameStyle.POLAROID,
    )

    val FUNSAVER = FilmLook(
        id = "funsaver", name = "FunSaver 800", description = "Kodak desechable · flash y fecha",
        swatch = 0xFFFF6A2A, category = OTHER,
        exposure = 0.1f,
        curveR = c(0f, 0.03f, 0.25f, 0.22f, 0.5f, 0.56f, 0.75f, 0.86f, 1f, 0.98f),
        curveG = c(0f, 0.03f, 0.25f, 0.21f, 0.5f, 0.52f, 0.75f, 0.82f, 1f, 0.96f),
        curveB = c(0f, 0.06f, 0.25f, 0.21f, 0.5f, 0.47f, 0.75f, 0.74f, 1f, 0.90f),
        saturation = 1.08f,
        softness = 0.8f, aberration = 0.003f, bloom = 0.12f, halation = 0.3f,
        grain = 0.065f, vignette = 0.5f, flash = 0.4f, leak = 0.45f,
        defaultDate = true,
    )

    val SEPIA = FilmLook(
        id = "sepia", name = "Sepia 1920", description = "Foto antigua con polvo y rayones",
        swatch = 0xFF8C6A44, category = OTHER,
        mono = 1f,
        curveR = c(0f, 0.13f, 0.3f, 0.33f, 0.6f, 0.69f, 1f, 0.95f),
        curveG = c(0f, 0.09f, 0.3f, 0.25f, 0.6f, 0.60f, 1f, 0.88f),
        curveB = c(0f, 0.06f, 0.3f, 0.17f, 0.6f, 0.47f, 1f, 0.74f),
        softness = 1.4f, aberration = 0f, bloom = 0.25f, halation = 0f,
        grain = 0.08f, grainSize = 1.8f, grainColor = 0f, vignette = 0.6f, dust = 0.8f,
    )

    val all = listOf(
        ORIGINAL,
        PORTRA_160, PORTRA_400, PORTRA_400_OVER, PORTRA_800, GOLD_200, COLORPLUS_200, ULTRAMAX_400, EKTAR_100,
        SUPERIA_400, C200, PRO_400H, LOMO_800, EXPIRED,
        CINESTILL_800T, CINESTILL_50D, VISION3_500T,
        VELVIA_50, EKTACHROME,
        TRI_X_400, HP5, TMAX_100, DELTA_3200,
        POLAROID_600, INSTAX, FUNSAVER, SEPIA,
    )

    /** Filtros de una categoría (siempre con "Original" al inicio). */
    fun inCategory(category: LookCategory) = listOf(ORIGINAL) + all.filter { it.category == category && it != ORIGINAL }

    fun byId(id: String): FilmLook = all.firstOrNull { it.id == id } ?: PORTRA_400
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

    /** Siempre 2 fugas (la segunda puede tener fuerza 0) para que el shader tenga tamaño fijo. */
    fun generate(amount: Float, seed: Long): List<LeakSpec> {
        if (amount <= 0f) return emptyList()
        val rnd = Random(seed)
        val count = 1 + rnd.nextInt(2)
        return List(2) { i ->
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
                strength = if (i < count) amount * (0.55f + rnd.nextFloat() * 0.45f) else 0f,
            )
        }
    }
}
