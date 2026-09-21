package com.triplethreats.masteria.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI
import kotlin.math.pow

/**
 * Apple's two designer-facing spring parameters, mapped onto Compose's physics spring.
 *
 * - dampingRatio: 1.0 settles without overshoot; < 1 bounces.
 * - response: seconds to (roughly) reach the target. Not a duration: settle time emerges.
 *
 * With unit mass, stiffness = (2π / response)². Springs start from the current on-screen value
 * and carry velocity, so every animation here can be interrupted and reversed mid-flight.
 */
object Springs {
    fun stiffness(response: Float): Float = (2f * PI.toFloat() / response).pow(2)

    fun <T> apple(damping: Float = 1f, response: Float = 0.4f, visibilityThreshold: T? = null) =
        spring(dampingRatio = damping, stiffness = stiffness(response), visibilityThreshold = visibilityThreshold)

    /** Default for UI that moves on its own: critically damped, calm. */
    fun <T> default(threshold: T? = null) = apple(1f, 0.4f, threshold)

    /** Presses, toggles, small reactive changes. */
    fun <T> snappy(threshold: T? = null) = apple(1f, 0.28f, threshold)

    /** Sheets and drawers (Apple ships 0.8 / 0.3). */
    fun <T> sheet(threshold: T? = null) = apple(0.86f, 0.32f, threshold)

    /** Only after a gesture that carried momentum, or a celebratory moment. */
    fun <T> bouncy(threshold: T? = null) = apple(0.72f, 0.45f, threshold)

    /** Progress bars, rings, counters: smooth and a touch slower so the change is seen. */
    fun <T> progress(threshold: T? = null) = apple(1f, 0.75f, threshold)
}

/** Reduced motion swaps movement for a short cross-fade-friendly tween. */
fun <T> motion(reduced: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (reduced) tween(durationMillis = 180) else spec
