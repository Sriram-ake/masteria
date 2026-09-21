package com.triplethreats.masteria.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.forMastery
import com.triplethreats.masteria.ui.theme.masteryWord
import com.triplethreats.masteria.ui.theme.motion
import com.triplethreats.masteria.ui.theme.rememberHaptics

// ---------------------------------------------------------------------------------------------
// Press feedback: the control reacts the instant it's touched (scale + dim on a spring), and
// commits on release. Springs start from the live value, so fast repeated taps never jump.
// ---------------------------------------------------------------------------------------------

fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.97f,
    haptic: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = Masteria.reducedMotion
    val haptics = rememberHaptics()
    val s by animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = motion(reduced, Springs.snappy()),
        label = "press",
    )
    val a by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.82f else 1f,
        animationSpec = Springs.snappy(),
        label = "pressAlpha",
    )
    this
        .graphicsLayer {
            scaleX = s; scaleY = s; alpha = a
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
        ) {
            if (haptic) haptics.tap()
            onClick()
        }
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

enum class ButtonStyle { Filled, Tinted, Plain, Destructive }

@Composable
fun MButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color? = null,
    height: Dp = 54.dp,
) {
    val c = Masteria.colors
    val accent = color ?: if (style == ButtonStyle.Destructive) c.danger else c.brand
    val bg = when (style) {
        ButtonStyle.Filled -> accent
        ButtonStyle.Tinted, ButtonStyle.Destructive -> accent.copy(alpha = if (c.isDark) 0.22f else 0.12f)
        ButtonStyle.Plain -> Color.Transparent
    }
    val fg = if (style == ButtonStyle.Filled) Color.White else accent
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .heightIn(min = height)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .pressable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "buttonLoading",
        ) { isLoading ->
            if (isLoading) {
                ActivitySpinner(color = fg, size = 22.dp)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(text, style = Masteria.type.headline, color = fg, maxLines = 1)
                }
            }
        }
    }
}

/** iOS-style activity indicator: twelve fading spokes, rotating in steps. */
@Composable
fun ActivitySpinner(color: Color = Masteria.colors.secondaryLabel, size: Dp = 20.dp) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            rotation.snapTo((rotation.value + 30f) % 360f)
            kotlinx.coroutines.delay(83)
        }
    }
    Canvas(
        Modifier
            .size(size)
            .semantics { contentDescription = "Loading" }
            .graphicsLayer { rotationZ = rotation.value }
    ) {
        val r = this.size.minDimension / 2f
        val w = r * 0.18f
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30.0) - 90.0)
            val start = Offset(center.x + (r * 0.5f) * kotlin.math.cos(angle).toFloat(), center.y + (r * 0.5f) * kotlin.math.sin(angle).toFloat())
            val end = Offset(center.x + (r - w / 2) * kotlin.math.cos(angle).toFloat(), center.y + (r - w / 2) * kotlin.math.sin(angle).toFloat())
            drawLine(color.copy(alpha = 0.18f + 0.82f * (i / 11f)), start, end, w, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Grouped lists (Settings-style inset groups)
// ---------------------------------------------------------------------------------------------

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text,
            style = Masteria.type.title3,
            color = Masteria.colors.label,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color = Masteria.colors.card,
    radius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(scale = 0.98f, onClick = onClick) else Modifier)
            .clip(RoundedCornerShape(radius))
            .background(color)
            .padding(padding),
        content = content,
    )
}

@Composable
fun GroupedList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Masteria.colors.card),
        content = content,
    )
}

/** A row inside a [GroupedList]. The separator is inset to the text, like iOS. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Masteria.colors.brand,
    showDivider: Boolean = true,
    chevron: Boolean = true,
    titleColor: Color = Masteria.colors.label,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Masteria.colors
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(scale = 0.99f, onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconTile(icon, iconTint, size = 30.dp, radius = 8.dp, filled = true)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Masteria.type.body, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = Masteria.type.footnote, color = c.secondaryLabel, maxLines = 2)
                }
            }
            trailing?.invoke(this)
            if (chevron && onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForwardIos, null,
                    tint = c.tertiaryLabel, modifier = Modifier.size(14.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .padding(start = if (icon != null) 60.dp else 16.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(c.separator)
            )
        }
    }
}

@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    size: Dp = 44.dp,
    radius: Dp = 12.dp,
    filled: Boolean = false,
    iconSize: Dp = size * 0.55f,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(
                if (filled) Brush.verticalGradient(listOf(tint.copy(alpha = 0.92f), tint))
                else Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), tint.copy(alpha = 0.12f)))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Color.White else tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    solid: Boolean = false,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (solid) color else color.copy(alpha = if (Masteria.colors.isDark) 0.2f else 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (solid) Color.White else color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = Masteria.type.caption, color = if (solid) Color.White else color, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Progress: bars, rings. Values glide on a slow critically-damped spring so change is *seen*.
// ---------------------------------------------------------------------------------------------

@Composable
fun ProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    track: Color = Masteria.colors.fill,
    height: Dp = 8.dp,
    brush: Brush? = null,
) {
    val reduced = Masteria.reducedMotion
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), motion(reduced, Springs.progress()), label = "bar")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        if (p > 0f) {
            val w = (size.width * p).coerceAtLeast(size.height)
            if (brush != null) {
                drawRoundRect(brush, size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            } else {
                drawRoundRect(color, size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            }
        }
    }
}

@Composable
fun Ring(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 8.dp,
    track: Color = Masteria.colors.fill,
    content: @Composable () -> Unit = {},
) {
    val reduced = Masteria.reducedMotion
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), motion(reduced, Springs.progress()), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = stroke.toPx()
            val d = size.minDimension - s
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(s))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, topLeft, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Mastery with its number, a word and a bar: meaning is never carried by colour alone. */
@Composable
fun MasteryMeter(mastery: Int, modifier: Modifier = Modifier, showWord: Boolean = true) {
    val c = Masteria.colors
    val color = c.forMastery(mastery)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedNumber(mastery, style = Masteria.type.headline, color = c.label, suffix = "%")
            if (showWord) {
                Spacer(Modifier.width(8.dp))
                Text(masteryWord(mastery), style = Masteria.type.footnote, color = color)
            }
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(mastery / 100f, color, height = 6.dp)
    }
}

/** A number that counts to its new value on a spring (XP, mastery, streak). */
@Composable
fun AnimatedNumber(
    value: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    prefix: String = "",
    suffix: String = "",
    textAlign: TextAlign? = null,
) {
    val reduced = Masteria.reducedMotion
    val v by animateFloatAsState(value.toFloat(), motion(reduced, Springs.progress()), label = "number")
    Text(
        "$prefix${v.toInt()}$suffix",
        style = style,
        color = color,
        modifier = modifier,
        textAlign = textAlign,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------------------------
// Loading and error states
// ---------------------------------------------------------------------------------------------

/** Placeholder block with a slow shimmer; layout is reserved so nothing jumps when data lands. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    val c = Masteria.colors
    val reduced = Masteria.reducedMotion
    val shimmer = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        while (true) {
            shimmer.snapTo(0f)
            shimmer.animateTo(1f, tween(1400))
        }
    }
    Canvas(modifier.clip(RoundedCornerShape(radius))) {
        drawRect(c.fill)
        if (!reduced) {
            val x = -size.width + shimmer.value * size.width * 3
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, c.specular.copy(alpha = if (c.isDark) 0.06f else 0.5f), Color.Transparent),
                    startX = x, endX = x + size.width,
                )
            )
        }
    }
}

@Composable
fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
    onSettings: (() -> Unit)? = null,
) {
    val c = Masteria.colors
    Card(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.CloudOff, c.warning, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (offline) "You're offline" else "Couldn't load", style = Masteria.type.headline, color = c.label)
                Text(message, style = Masteria.type.footnote, color = c.secondaryLabel)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MButton("Try again", onRetry, Modifier.weight(1f), style = ButtonStyle.Tinted, height = 44.dp)
            if (onSettings != null) {
                MButton("Server settings", onSettings, Modifier.weight(1f), style = ButtonStyle.Plain, height = 44.dp)
            }
        }
    }
}

/** Small uppercase label above a value, used in stat tiles. */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    val c = Masteria.colors
    Card(modifier, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = Masteria.type.footnote, color = c.secondaryLabel, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = Masteria.type.title2, color = c.label, maxLines = 1)
        if (footnote != null) Text(footnote, style = Masteria.type.caption, color = c.secondaryLabel, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
