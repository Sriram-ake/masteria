package com.triplethreats.masteria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.ui.theme.Masteria
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * Translucent material. Screens mark their scrolling content with [glassSource]; bars and
 * floating chrome sample it through [GlassSurface]. Content scrolls under the chrome instead of
 * the chrome eating a fixed strip of the screen.
 */
@Stable
class GlassEnvironment(val state: HazeState)

val LocalGlass = staticCompositionLocalOf<GlassEnvironment?> { null }

fun Modifier.glassSource(): Modifier = composed {
    val env = LocalGlass.current
    if (env != null) hazeSource(env.state) else this
}

enum class GlassEdge { Outline, Top, Bottom, None }

/**
 * @param progress 0..1, how much material is present. Blur, tint and edge all scale together,
 * so the material "arrives" (scroll edge effect) rather than popping in.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    edge: GlassEdge = GlassEdge.Outline,
    progress: Float = 1f,
    blur: Dp = 24.dp,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = Masteria.colors
    val env = LocalGlass.current
    val p = progress.coerceIn(0f, 1f)
    val surface = Modifier
        .then(
            if (elevation > 0.dp && p > 0f) Modifier.shadow(
                elevation * p, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow,
            ) else Modifier
        )
        .clip(shape)
        .then(
            if (env != null) {
                Modifier.hazeEffect(
                    state = env.state,
                    style = HazeStyle(
                        backgroundColor = colors.background,
                        tints = listOf(HazeTint(colors.glassTint)),
                        blurRadius = blur,
                        noiseFactor = 0.04f,
                    ),
                ) {
                    alpha = p
                    blurRadius = blur * p
                }
            } else {
                Modifier.background(colors.glassFallback.copy(alpha = colors.glassFallback.alpha * p), shape)
            }
        )
        .then(
            if (p <= 0f) Modifier else when (edge) {
                GlassEdge.Outline -> Modifier.border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        0f to colors.specular.copy(alpha = (if (colors.isDark) 0.28f else 0.9f) * p),
                        0.5f to colors.separator.copy(alpha = 0.25f * p),
                        1f to colors.separator.copy(alpha = 0.25f * p),
                    ),
                    shape = shape,
                )
                GlassEdge.Top -> Modifier.drawWithContent {
                    drawContent()
                    drawLine(colors.separator.copy(alpha = p), Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx())
                }
                GlassEdge.Bottom -> Modifier.drawWithContent {
                    drawContent()
                    val y = size.height - 0.25.dp.toPx()
                    drawLine(colors.separator.copy(alpha = p), Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx())
                }
                GlassEdge.None -> Modifier
            }
        )
    Box(modifier = modifier.then(surface), content = content)
}
