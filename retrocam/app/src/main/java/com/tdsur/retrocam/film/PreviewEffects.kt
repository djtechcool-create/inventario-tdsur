package com.tdsur.retrocam.film

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.max

/**
 * Filtro en tiempo real para la vista previa de la cámara (se ejecuta en la GPU).
 *
 * - Android 13+: shader AGSL con el mismo pipeline de color que [FilmRenderer], más grano y halación aproximada.
 * - Android 10–12: aproximación con una matriz de color (sin grano).
 *
 * La viñeta, las fugas de luz y la fecha se dibujan encima con Compose.
 */
object PreviewEffects {

    fun apply(view: View, look: FilmLook, intensity: Float, seed: Long) {
        if (look.id == FilmLooks.ORIGINAL.id || intensity <= 0f) {
            clear(view)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            applyShader(view, look, intensity, seed)
        } else {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix(look, intensity))
            })
        }
    }

    private fun clear(view: View) {
        if (Build.VERSION.SDK_INT >= 31) view.setRenderEffect(null)
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    @RequiresApi(33)
    private fun applyShader(view: View, look: FilmLook, intensity: Float, seed: Long) {
        val longSide = max(view.resources.displayMetrics.widthPixels, view.resources.displayMetrics.heightPixels)
        val shader = RuntimeShader(AGSL).apply {
            setFloatUniform("brightness", look.brightness)
            setFloatUniform("contrast", look.contrast)
            setFloatUniform("saturation", look.saturation)
            setFloatUniform("warmth", look.warmth)
            setFloatUniform("tint", look.tint)
            setFloatUniform("fade", look.fade)
            setFloatUniform("crush", look.crush)
            setFloatUniform("shadowTint", look.shadowTint.r, look.shadowTint.g, look.shadowTint.b)
            setFloatUniform("highlightTint", look.highlightTint.r, look.highlightTint.g, look.highlightTint.b)
            setFloatUniform("mono", look.mono)
            setFloatUniform("monoWeights", look.monoWeights.r, look.monoWeights.g, look.monoWeights.b)
            setFloatUniform("halation", look.halation)
            setFloatUniform("grain", look.grain)
            setFloatUniform("grainPx", max(1f, longSide / 2000f * look.grainSize))
            setFloatUniform("seed", (seed % 997L).toFloat())
            setFloatUniform("intensity", intensity)
        }
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "image"))
    }

    private const val AGSL = """
        uniform shader image;
        uniform float brightness;
        uniform float contrast;
        uniform float saturation;
        uniform float warmth;
        uniform float tint;
        uniform float fade;
        uniform float crush;
        uniform float3 shadowTint;
        uniform float3 highlightTint;
        uniform float mono;
        uniform float3 monoWeights;
        uniform float halation;
        uniform float grain;
        uniform float grainPx;
        uniform float seed;
        uniform float intensity;

        float rand(float2 p) {
            p = mod(p, 1024.0);
            return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        }

        half4 main(float2 coord) {
            half4 src = image.eval(coord);
            float3 o = float3(src.rgb);
            float3 c = (o + brightness - 0.5) * contrast + 0.5;
            c.r += warmth * 0.08;
            c.b -= warmth * 0.08;
            c.g += tint * 0.05;

            float m = dot(c, monoWeights);
            c = mix(c, float3(m), mono);

            const float3 LUMA = float3(0.299, 0.587, 0.114);
            float l = dot(c, LUMA);
            c = clamp(mix(float3(l), c, saturation), 0.0, 1.0);

            l = dot(c, LUMA);
            c += shadowTint * (1.0 - l) * (1.0 - l) + highlightTint * l * l;
            c = fade + c * (1.0 - fade - crush);

            float hv = smoothstep(0.7, 1.0, dot(o, LUMA)) * halation;
            c += float3(0.9, 0.32, 0.12) * hv * 0.5;

            float2 g = floor(coord / grainPx);
            float n = rand(g + seed) + rand(g * 1.37 + seed + 7.0) - 1.0;
            c += n * grain * (1.0 - abs(l * 2.0 - 1.0) * 0.6);

            c = clamp(mix(o, c, intensity), 0.0, 1.0);
            return half4(half3(c), src.a);
        }
    """

    // ------------------------------------------- matriz de color (Android 10–12)

    /** Transformación afín de color: c' = M·c + o (M de 3x3, por filas). */
    private class Affine(val m: FloatArray, val o: FloatArray) {
        /** Aplica `this` y luego `n`. */
        fun then(n: Affine): Affine {
            val r = FloatArray(9)
            for (i in 0..2) for (j in 0..2) {
                r[i * 3 + j] = n.m[i * 3] * m[j] + n.m[i * 3 + 1] * m[3 + j] + n.m[i * 3 + 2] * m[6 + j]
            }
            val ro = FloatArray(3) { i ->
                n.m[i * 3] * o[0] + n.m[i * 3 + 1] * o[1] + n.m[i * 3 + 2] * o[2] + n.o[i]
            }
            return Affine(r, ro)
        }

        companion object {
            fun identity() = scale(1f)
            fun scale(k: Float, off: Float = 0f) =
                Affine(floatArrayOf(k, 0f, 0f, 0f, k, 0f, 0f, 0f, k), floatArrayOf(off, off, off))
            fun offset(r: Float, g: Float, b: Float) =
                Affine(identity().m, floatArrayOf(r, g, b))
            /** mix(c, dot(c, w), t) */
            fun mixToGray(w: Rgb, t: Float): Affine {
                val m = FloatArray(9)
                for (i in 0..2) {
                    m[i * 3] = t * w.r
                    m[i * 3 + 1] = t * w.g
                    m[i * 3 + 2] = t * w.b
                    m[i * 3 + i] += 1f - t
                }
                return Affine(m, floatArrayOf(0f, 0f, 0f))
            }
        }
    }

    private fun colorMatrix(look: FilmLook, intensity: Float): ColorMatrix {
        val a = Affine.offset(look.brightness, look.brightness, look.brightness)
            .then(Affine.scale(look.contrast, 0.5f * (1f - look.contrast)))
            .then(Affine.offset(look.warmth * 0.08f, look.tint * 0.05f, -look.warmth * 0.08f))
            .then(Affine.mixToGray(look.monoWeights, look.mono))
            .then(Affine.mixToGray(Rgb.LUMA, 1f - look.saturation))
            .then(Affine.scale(1f - look.fade - look.crush, look.fade))
            // Promedio del tono dividido como desplazamiento fijo
            .then(
                Affine.offset(
                    (look.shadowTint.r + look.highlightTint.r) * 0.3f,
                    (look.shadowTint.g + look.highlightTint.g) * 0.3f,
                    (look.shadowTint.b + look.highlightTint.b) * 0.3f,
                )
            )
        val id = Affine.identity()
        val t = intensity
        val m = FloatArray(9) { id.m[it] + (a.m[it] - id.m[it]) * t }
        val o = FloatArray(3) { a.o[it] * t * 255f }
        return ColorMatrix(
            floatArrayOf(
                m[0], m[1], m[2], 0f, o[0],
                m[3], m[4], m[5], 0f, o[1],
                m[6], m[7], m[8], 0f, o[2],
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }
}
