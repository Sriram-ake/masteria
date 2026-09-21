package com.triplethreats.masteria.ui.welcome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.R
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.CircleGlassButton
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.symbol
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun WelcomeScreen(onStart: () -> Unit, onSignIn: () -> Unit, onSettings: () -> Unit) {
    val c = Masteria.colors
    val reduced = Masteria.reducedMotion

    // Staggered arrival: hero, title, then each feature row, each on a calm spring.
    val steps = remember { List(6) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        steps.forEachIndexed { i, a ->
            launch { delay(90L * i + 120); a.animateTo(1f, Springs.default()) }
        }
    }
    fun Modifier.arrive(i: Int) = graphicsLayer {
        val p = steps[i].value
        alpha = p
        translationY = (1f - p) * 24.dp.toPx()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))
            Box(Modifier.arrive(0), contentAlignment = Alignment.Center) { HeroOrbit() }
            Spacer(Modifier.height(28.dp))
            Text(
                "Masteria",
                style = Masteria.type.largeTitle.copy(fontSize = Masteria.type.largeTitle.fontSize * 1.15f),
                color = c.label,
                modifier = Modifier.arrive(1),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Learn. Quest. Level up.",
                style = Masteria.type.title3,
                color = c.secondaryLabel,
                modifier = Modifier.arrive(1),
            )
            Spacer(Modifier.height(36.dp))
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Feature(Icons.Rounded.TrackChanges, c.warning, "Finds your weak spot",
                    "A short diagnostic maps what you know, topic by topic.", Modifier.arrive(2))
                Feature(Icons.Rounded.AutoAwesome, c.brand, "Builds your next quest around it",
                    "Questions adapt as you answer, so practice stays just hard enough.", Modifier.arrive(3))
                Feature(Icons.Rounded.Insights, c.success, "Rewards real improvement",
                    "XP comes from mastery gained, not minutes spent.", Modifier.arrive(4))
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.arrive(5)) {
                MButton("Get started", onStart, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                MButton("I already have an account", onSignIn, Modifier.fillMaxWidth(), style = ButtonStyle.Plain)
            }
            Spacer(Modifier.height(12.dp))
        }
        CircleGlassButton(
            icon = Icons.Rounded.Settings,
            description = "Server settings",
            tint = c.secondaryLabel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
            onClick = onSettings,
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, tint: Color, title: String, body: String, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Masteria.type.headline, color = c.label)
            Text(body, style = Masteria.type.subhead, color = c.secondaryLabel)
        }
    }
}

/** The app mark with subject glyphs in slow orbit: one engine, many worlds. */
@Composable
private fun HeroOrbit() {
    val c = Masteria.colors
    val reduced = Masteria.reducedMotion
    val spin = rememberInfiniteTransition(label = "orbit")
    val angle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val glyphs = listOf("function", "atom", "code", "percent", "book", "dice")
    val tints = listOf(c.brand, c.success, c.info, c.xp, c.boss, c.warning)
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            drawCircle(c.separator.copy(alpha = 0.5f), radius = size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        glyphs.forEachIndexed { i, g ->
            val a = Math.toRadians((if (reduced) 0f else angle) + i * 60.0)
            val r = 100.dp
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (r.toPx() * cos(a)).roundToInt(),
                            (r.toPx() * sin(a)).roundToInt(),
                        )
                    }
                    .size(38.dp)
                    .shadow(6.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(CircleShape)
                    .background(c.card),
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol(g), null, tint = tints[i], modifier = Modifier.size(20.dp))
            }
        }
        Box(
            Modifier
                .size(112.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp), ambientColor = c.brand, spotColor = c.brand)
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6D5DF6), Color(0xFF3B2BC9)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Masteria",
                tint = Color.Unspecified,
                modifier = Modifier.size(150.dp),
            )
        }
    }
}
