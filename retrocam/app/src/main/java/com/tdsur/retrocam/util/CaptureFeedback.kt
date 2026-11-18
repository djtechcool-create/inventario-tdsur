package com.tdsur.retrocam.util

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Sonido y vibración al tomar fotos y grabar video. */
class CaptureFeedback(context: Context) {
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** Obturador: un golpe fuerte y, enseguida, un toque suave como el avance del rollo. */
    fun shutter() {
        sound.play(MediaActionSound.SHUTTER_CLICK)
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
        handler.postDelayed({ vibrate(VibrationEffect.EFFECT_TICK) }, 160)
    }

    fun recordStart() {
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        vibrate(VibrationEffect.EFFECT_CLICK)
    }

    fun recordStop() {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    /** Toque suave para botones y cambios de filtro. */
    fun tick() = vibrate(VibrationEffect.EFFECT_TICK)

    private fun vibrate(effect: Int) {
        try {
            val v = vibrator ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(effect))
        } catch (_: Exception) {
        }
    }

    fun release() = sound.release()
}
