package com.triplethreats.masteria.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * Haptics with a purpose (causality, harmony, utility): fired on the same frame as the visual
 * change they belong to, and reserved for meaningful moments. Rich composed patterns on devices
 * with a capable actuator (iQOO's X-axis motors), graceful fallbacks elsewhere.
 */
class Haptics(private val view: View) {
    private val vibrator: Vibrator? = run {
        val ctx = view.context
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }

    /** Light tick on press-down of a primary control. */
    fun tap() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Choosing an option, flipping a segment. */
    fun selection() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    )

    fun success() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    )

    fun error() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    )

    /** A rising swell and a landing thud: level-up and boss victory. */
    fun celebrate() {
        val v = vibrator ?: return success()
        if (Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_THUD,
            )
        ) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 40)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 60)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 80)
                    .compose()
            )
        } else if (v.hasAmplitudeControl()) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 50, 25, 60, 60), intArrayOf(0, 120, 0, 180, 0, 255), -1))
        } else {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 30, 60, 60), -1))
        }
    }

    /** Heavy, low: a boss appears. */
    fun rumble() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 120)
                    .compose()
            )
        } else {
            v.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

@Composable
fun rememberHaptics(): Haptics {
    LocalHaptics.current?.let { return it }
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
