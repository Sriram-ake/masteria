package com.triplethreats.masteria.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.triplethreats.masteria.ui.theme.Masteria
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Piece(
    val x0: Float, val y0: Float, val vx: Float, val vy: Float,
    val spin: Float, val color: Color, val w: Float, val h: Float, val delay: Float,
)

/**
 * A single burst of confetti from [origin] (fractions of the canvas), run on the display clock.
 * Physics, not keyframes: each piece has launch velocity, gravity and air drag, so the burst
 * reads as thrown. Skipped entirely under reduced motion. Change [trigger] to fire again.
 */
@Composable
fun ConfettiBurst(
    trigger: Int,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Masteria.colors.brand, Masteria.colors.xp, Masteria.colors.success,
        Masteria.colors.info, Masteria.colors.boss,
    ),
    count: Int = 90,
    origin: Offset = Offset(0.5f, 0.38f),
) {
    if (Masteria.reducedMotion || trigger == 0) return
    val pieces = remember(trigger) {
        val r = Random(trigger)
        List(count) {
            val angle = Math.toRadians(r.nextDouble(-160.0, -20.0)).toFloat()
            val speed = r.nextFloat() * 1500f + 700f
            Piece(
                x0 = origin.x, y0 = origin.y,
                vx = cos(angle) * speed, vy = sin(angle) * speed,
                spin = r.nextFloat() * 720f - 360f,
                color = colors[r.nextInt(colors.size)],
                w = r.nextFloat() * 8f + 6f, h = r.nextFloat() * 5f + 4f,
                delay = r.nextFloat() * 0.08f,
            )
        }
    }
    var elapsedNanos by remember(trigger) { mutableLongStateOf(0L) }
    LaunchedEffect(trigger) {
        val start = withFrameNanos { it }
        while (elapsedNanos < 3_200_000_000L) {
            withFrameNanos { elapsedNanos = it - start }
        }
    }
    Canvas(modifier) {
        val t0 = elapsedNanos / 1e9f
        val gravity = 2400f
        val drag = 1.6f
        pieces.forEach { p ->
            val t = (t0 - p.delay).coerceAtLeast(0f)
            // Closed-form motion with linear drag: v(t) = v0·e^(−kt), x(t) = v0(1 − e^(−kt))/k
            val decay = kotlin.math.exp(-drag * t)
            val x = p.x0 * size.width + p.vx * (1 - decay) / drag
            val y = p.y0 * size.height + p.vy * (1 - decay) / drag + gravity / drag * (t - (1 - decay) / drag)
            if (y > size.height + 40f) return@forEach
            val alpha = (1f - ((t - 2.2f) / 1f)).coerceIn(0f, 1f)
            val flip = cos(t * 9f + p.spin)
            rotate(p.spin * t, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x - p.w / 2, y - p.h / 2),
                    size = Size(p.w * density / 2.5f, p.h * density / 2.5f * kotlin.math.abs(flip).coerceAtLeast(0.2f)),
                )
            }
        }
    }
}
