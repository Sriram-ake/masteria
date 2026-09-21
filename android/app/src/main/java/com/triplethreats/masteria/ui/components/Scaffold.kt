package com.triplethreats.masteria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.ui.theme.Masteria

val NavBarHeight = 44.dp
/** Floating tab bar (64dp) plus its 8dp margins above and below. */
val TabBarHeight = 80.dp

/**
 * iOS large-title screen. The title scrolls with content; as it passes under the navigation bar
 * the bar's glass material arrives (blur + tint + hairline together) and the inline title fades
 * in. Content scrolls beneath the translucent bars.
 */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    bottomInset: Dp = TabBarHeight,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val c = Masteria.colors
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val titleSpanPx = with(density) { 52.dp.toPx() }
    val progress by remember(state) {
        derivedStateOf {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / titleSpanPx).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .glassSource(),
            contentPadding = PaddingValues(top = status + NavBarHeight, bottom = bottomInset + nav + 24.dp),
        ) {
            item(key = "__large_title") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
                        .graphicsLayer { alpha = 1f - progress * 0.9f }
                ) {
                    Text(
                        title,
                        style = Masteria.type.largeTitle,
                        color = c.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = Masteria.type.subhead, color = c.secondaryLabel, maxLines = 2)
                    }
                }
            }
            content()
        }

        // Navigation bar: glass arrives as the large title leaves.
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(status + NavBarHeight),
            edge = GlassEdge.Bottom,
            progress = progress,
        ) {}
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = status)
                .height(NavBarHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                BackButton(onBack)
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    title,
                    style = Masteria.type.headline,
                    color = c.label,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = progress },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit, label: String = "Back") {
    val c = Masteria.colors
    Row(
        Modifier
            .clip(CircleShape)
            .pressable(onClick = onBack)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = c.brand, modifier = Modifier.size(20.dp))
        Text(label, style = Masteria.type.body, color = c.brand)
    }
}

/** Round glass button for floating chrome (close, more). 44dp hit target. */
@Composable
fun CircleGlassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Close,
    description: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = Masteria.colors.label,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .pressable(scale = 0.92f, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.size(34.dp), shape = CircleShape) {
            Icon(icon, null, tint = tint, modifier = Modifier
                .size(18.dp)
                .align(Alignment.Center))
        }
    }
}
