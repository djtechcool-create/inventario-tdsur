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

object FilmLooks {
    val ORIGINAL = FilmLook(
        id = "original", name = "Original", description = "Sin filtro",
        swatch = 0xFF9E9E9E,
        satRollHi = 0f, satRollLo = 0f, softness = 0f, aberration = 0f, bloom = 0f, halation = 0f,
        grain = 0f, vignette = 0f, flatten = 0f,
    )

    val DORADO = FilmLook(
        id = "dorado", name = "Dorado 200", description = "Rollo cálido de los 90",
        swatch = 0xFFE0A040,
        exposure = 0.1f,
        mix = floatArrayOf(
            1.04f, -0.02f, -0.02f,
            -0.03f, 1.05f, -0.02f,
            -0.02f, -0.06f, 1.08f,
        ),
        curveR = ToneCurve(0f, 0.07f, 0.25f, 0.29f, 0.5f, 0.58f, 0.75f, 0.83f, 1f, 0.97f),
        curveG = ToneCurve(0f, 0.065f, 0.25f, 0.265f, 0.5f, 0.535f, 0.75f, 0.79f, 1f, 0.95f),
        curveB = ToneCurve(0f, 0.09f, 0.25f, 0.245f, 0.5f, 0.46f, 0.75f, 0.69f, 1f, 0.86f),
        saturation = 1.0f,
        softness = 0.4f, bloom = 0.12f, halation = 0.3f,
        grain = 0.045f, vignette = 0.2f, leak = 0.15f,
        defaultFrame = FrameStyle.SCAN,
    )

    /** Inspirado en fotos de verano con rollo de color: agua turquesa, piel cálida, mucho contraste. */
    val VERANO = FilmLook(
        id = "verano", name = "Verano 400", description = "Colores intensos de verano",
        swatch = 0xFF1FA3A0,
        exposure = 0.05f,
        mix = floatArrayOf(
            1.06f, -0.04f, -0.02f,
            -0.05f, 1.06f, -0.01f,
            -0.03f, 0.02f, 1.01f,
        ),
        curveR = ToneCurve(0f, 0.06f, 0.25f, 0.25f, 0.5f, 0.55f, 0.75f, 0.82f, 1f, 0.97f),
        curveG = ToneCurve(0f, 0.07f, 0.25f, 0.27f, 0.5f, 0.54f, 0.75f, 0.80f, 1f, 0.96f),
        curveB = ToneCurve(0f, 0.09f, 0.25f, 0.28f, 0.5f, 0.52f, 0.75f, 0.75f, 1f, 0.92f),
        saturation = 1.06f, satRollHi = 0.55f, satRollLo = 0.2f,
        softness = 0.35f, bloom = 0.1f, halation = 0.25f,
        grain = 0.045f, vignette = 0.18f,
        defaultFrame = FrameStyle.SCAN,
    )

    /** Inspirado en fotos de playa al atardecer: desvanecido, luces rosadas y sombras frías. */
    val ATARDECER = FilmLook(
        id = "atardecer", name = "Atardecer", description = "Desvanecido, cálido y nostálgico",
        swatch = 0xFFE8B89A,
        exposure = 0.3f,
        mix = floatArrayOf(
            1.02f, 0f, -0.02f,
            -0.02f, 1.02f, 0f,
            0f, -0.03f, 1.03f,
        ),
        curveR = ToneCurve(0f, 0.10f, 0.25f, 0.33f, 0.5f, 0.60f, 0.75f, 0.83f, 1f, 0.97f),
        curveG = ToneCurve(0f, 0.10f, 0.25f, 0.31f, 0.5f, 0.56f, 0.75f, 0.79f, 1f, 0.93f),
        curveB = ToneCurve(0f, 0.13f, 0.25f, 0.32f, 0.5f, 0.53f, 0.75f, 0.73f, 1f, 0.86f),
        saturation = 0.78f, satRollHi = 0.6f, satRollLo = 0.3f,
        softness = 0.45f, bloom = 0.25f, halation = 0.3f,
        grain = 0.05f, vignette = 0.22f,
        defaultFrame = FrameStyle.SCAN,
    )

    val RETRATO = FilmLook(
        id = "retrato", name = "Retrato 400", description = "Suave y cremoso para personas",
        swatch = 0xFFE8B4A0,
        exposure = 0.2f,
        mix = floatArrayOf(
            1.02f, 0f, -0.02f,
            -0.02f, 1.03f, -0.01f,
            0f, -0.04f, 1.04f,
        ),
        curveR = ToneCurve(0f, 0.06f, 0.25f, 0.30f, 0.5f, 0.58f, 0.75f, 0.81f, 1f, 0.96f),
        curveG = ToneCurve(0f, 0.055f, 0.25f, 0.28f, 0.5f, 0.55f, 0.75f, 0.785f, 1f, 0.95f),
        curveB = ToneCurve(0f, 0.07f, 0.25f, 0.27f, 0.5f, 0.51f, 0.75f, 0.73f, 1f, 0.90f),
        saturation = 0.88f, satRollHi = 0.6f, satRollLo = 0.3f,
        softness = 0.45f, bloom = 0.15f, halation = 0.15f,
        grain = 0.04f, vignette = 0.15f,
        defaultFrame = FrameStyle.SCAN,
    )

    val CINE = FilmLook(
        id = "cine", name = "Cine 800T", description = "Cine nocturno con halación roja",
        swatch = 0xFF3C7A8C,
        mix = floatArrayOf(
            0.94f, 0.04f, 0.02f,
            0.02f, 0.98f, 0f,
            0.04f, 0.04f, 0.98f,
        ),
        curveR = ToneCurve(0f, 0.05f, 0.25f, 0.23f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.97f),
        curveG = ToneCurve(0f, 0.07f, 0.25f, 0.26f, 0.5f, 0.53f, 0.75f, 0.79f, 1f, 0.96f),
        curveB = ToneCurve(0f, 0.10f, 0.25f, 0.29f, 0.5f, 0.54f, 0.75f, 0.78f, 1f, 0.95f),
        saturation = 0.95f,
        softness = 0.5f, bloom = 0.2f, halation = 1.0f,
        grain = 0.065f, grainSize = 1.3f, vignette = 0.25f,
        defaultFrame = FrameStyle.SCAN,
    )

    val VERDE = FilmLook(
        id = "verde", name = "Verde 100", description = "Verdes profundos y tonos fríos",
        swatch = 0xFF5E8C5A,
        mix = floatArrayOf(
            1.03f, -0.03f, 0f,
            -0.04f, 1.02f, 0.02f,
            0f, 0.05f, 0.95f,
        ),
        curveR = ToneCurve(0f, 0.05f, 0.25f, 0.23f, 0.5f, 0.51f, 0.75f, 0.80f, 1f, 0.97f),
        curveG = ToneCurve(0f, 0.07f, 0.25f, 0.27f, 0.5f, 0.54f, 0.75f, 0.79f, 1f, 0.95f),
        curveB = ToneCurve(0f, 0.09f, 0.25f, 0.27f, 0.5f, 0.52f, 0.75f, 0.77f, 1f, 0.94f),
        saturation = 0.95f,
        softness = 0.35f, bloom = 0.1f, halation = 0.15f,
        grain = 0.04f, vignette = 0.18f,
        defaultFrame = FrameStyle.SCAN,
    )

    val DESECHABLE = FilmLook(
        id = "desechable", name = "Desechable 98", description = "Cámara desechable con flash y fecha",
        swatch = 0xFFFF6A2A,
        exposure = 0.1f,
        curveR = ToneCurve(0f, 0.03f, 0.25f, 0.22f, 0.5f, 0.56f, 0.75f, 0.86f, 1f, 0.98f),
        curveG = ToneCurve(0f, 0.03f, 0.25f, 0.21f, 0.5f, 0.52f, 0.75f, 0.82f, 1f, 0.96f),
        curveB = ToneCurve(0f, 0.06f, 0.25f, 0.21f, 0.5f, 0.47f, 0.75f, 0.74f, 1f, 0.90f),
        saturation = 1.08f,
        softness = 0.8f, aberration = 0.003f, bloom = 0.12f, halation = 0.3f,
        grain = 0.065f, vignette = 0.5f, flash = 0.4f, leak = 0.45f,
        defaultDate = true,
    )

    val INSTANTANEA = FilmLook(
        id = "instantanea", name = "Instantánea", description = "Foto instantánea con marco blanco",
        swatch = 0xFFB8D4D0,
        exposure = 0.15f,
        curveR = ToneCurve(0f, 0.10f, 0.25f, 0.30f, 0.5f, 0.56f, 0.75f, 0.80f, 1f, 0.93f),
        curveG = ToneCurve(0f, 0.13f, 0.25f, 0.33f, 0.5f, 0.57f, 0.75f, 0.80f, 1f, 0.92f),
        curveB = ToneCurve(0f, 0.15f, 0.25f, 0.33f, 0.5f, 0.53f, 0.75f, 0.72f, 1f, 0.84f),
        saturation = 0.82f, satRollHi = 0.5f,
        softness = 0.9f, aberration = 0.002f, bloom = 0.2f, halation = 0.1f,
        grain = 0.035f, vignette = 0.3f,
        defaultFrame = FrameStyle.POLAROID,
    )

    /** Blanco y negro de calle: tonos plateados, negros apenas levantados, grano fino. */
    val PLATA = FilmLook(
        id = "plata", name = "Plata B&N", description = "Blanco y negro clásico de calle",
        swatch = 0xFF5A5A5A,
        mono = 1f, monoWeights = Rgb(0.33f, 0.57f, 0.10f),
        curveR = ToneCurve(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.96f),
        curveG = ToneCurve(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.96f),
        curveB = ToneCurve(0f, 0.07f, 0.25f, 0.24f, 0.5f, 0.52f, 0.75f, 0.80f, 1f, 0.96f),
        softness = 0.3f, bloom = 0.05f, halation = 0f,
        grain = 0.07f, grainSize = 1.0f, grainColor = 0f, vignette = 0.12f,
    )

    val SEPIA = FilmLook(
        id = "sepia", name = "Sepia 1920", description = "Foto antigua con polvo y rayones",
        swatch = 0xFF8C6A44,
        mono = 1f,
        curveR = ToneCurve(0f, 0.13f, 0.3f, 0.33f, 0.6f, 0.69f, 1f, 0.95f),
        curveG = ToneCurve(0f, 0.09f, 0.3f, 0.25f, 0.6f, 0.60f, 1f, 0.88f),
        curveB = ToneCurve(0f, 0.06f, 0.3f, 0.17f, 0.6f, 0.47f, 1f, 0.74f),
        softness = 1.4f, aberration = 0f, bloom = 0.25f, halation = 0f,
        grain = 0.08f, grainSize = 1.8f, grainColor = 0f, vignette = 0.6f, dust = 0.8f,
    )

    val all = listOf(ORIGINAL, DORADO, VERANO, ATARDECER, RETRATO, CINE, VERDE, DESECHABLE, INSTANTANEA, PLATA, SEPIA)

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
