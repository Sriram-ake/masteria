package com.triplethreats.masteria.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Lets a tab present sheets above app chrome (the floating tab bar), like an iOS modal.
 * The main scaffold renders whatever is registered here on top of everything else.
 */
class OverlayHost {
    val entries = mutableStateMapOf<Any, @Composable () -> Unit>()
}

val LocalOverlay = staticCompositionLocalOf<OverlayHost?> { null }

@Composable
fun OverlayHost.Render() {
    entries.values.forEach { it() }
}

/** Shows [content] in the nearest [OverlayHost] for as long as this call stays composed. */
@Composable
fun Overlay(content: @Composable () -> Unit) {
    val host = LocalOverlay.current
    if (host == null) {
        content(); return
    }
    val latest by rememberUpdatedState(content)
    DisposableEffect(host) {
        val token = Any()
        host.entries[token] = { latest() }
        onDispose { host.entries.remove(token) }
    }
}

/** Apple's momentum projection: where a flick would come to rest (decelerationRate ≈ 0.998). */
fun project(velocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
    (velocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)

/** Progressive resistance past a boundary: the further you pull, the less it follows. */
fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
    (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

/**
 * A bottom sheet that behaves like a physical card. It tracks the finger 1:1, rubber-bands when
 * pulled up past its resting point, and on release projects the flick's momentum to decide
 * whether to settle or dismiss, handing the finger's velocity to the spring so there is no seam.
 * Grab it mid-animation and it follows immediately (every animation starts from the live value).
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Masteria.colors
    val reduced = Masteria.reducedMotion
    val scope = rememberCoroutineScope()
    var height by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(10_000f) } // off-screen until measured
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(visible, height) {
        if (height == 0f) return@LaunchedEffect
        if (visible) {
            if (!shown) offset.snapTo(height)
            shown = true
            if (reduced) offset.snapTo(0f) else offset.animateTo(0f, Springs.sheet())
        } else if (shown) {
            if (reduced) offset.snapTo(height) else offset.animateTo(height, Springs.default())
            shown = false
        }
    }
    if (!visible && !shown) return

    BackHandler(enabled = visible) { onDismiss() }

    val openness = if (height > 0f) (1f - offset.value / height).coerceIn(0f, 1f) else 0f
    var rawDrag by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize()) {
        // Dim to focus: the scrim tracks the sheet's position continuously.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = openness }
                .background(c.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
                .semantics { contentDescription = "Dismiss" }
        )
        Column(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer { translationY = offset.value }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        // Track the raw finger position, then map it through the rubber band.
                        rawDrag += delta
                        val target = if (rawDrag < 0f) -rubberband(-rawDrag, height) else rawDrag
                        scope.launch { offset.snapTo(target) }
                    },
                    onDragStarted = {
                        offset.stop()
                        rawDrag = offset.value
                    },
                    onDragStopped = { velocity ->
                        val projected = offset.value + project(velocity)
                        if (projected > height * 0.5f) {
                            // Thrown away: carry the flick's speed all the way off screen.
                            offset.animateTo(height, Springs.snappy(), initialVelocity = velocity.coerceAtLeast(0f))
                            shown = false
                            onDismiss()
                        } else {
                            // Hand the finger's velocity to the spring: no seam between drag and settle.
                            offset.animateTo(0f, Springs.sheet(), initialVelocity = velocity)
                        }
                    },
                )
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.card)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(c.fillStrong)
                )
            }
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}
