package com.triplethreats.masteria.ui.quest

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.data.QuestSummaryDto
import com.triplethreats.masteria.ui.components.AnimatedNumber
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.Card
import com.triplethreats.masteria.ui.components.CircleGlassButton
import com.triplethreats.masteria.ui.components.ConfettiBurst
import com.triplethreats.masteria.ui.components.CrownIcon
import com.triplethreats.masteria.ui.components.IconTile
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.ProgressBar
import com.triplethreats.masteria.ui.components.Ring
import com.triplethreats.masteria.ui.components.SwordIcon
import com.triplethreats.masteria.ui.components.symbol
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.forMastery
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BossIntro(vm: QuestViewModel, onClose: () -> Unit) {
    val c = Masteria.colors
    val s = vm.session ?: return
    val haptics = rememberHaptics()
    val reduced = Masteria.reducedMotion
    val rise = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        haptics.rumble()
        if (!reduced) rise.animateTo(1f, Springs.bouncy())
    }
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        CircleGlassButton(Icons.Rounded.Close, "Leave", Modifier.padding(8.dp), onClick = onClose)
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = 0.6f + 0.4f * rise.value; scaleY = 0.6f + 0.4f * rise.value
                        alpha = rise.value
                    }
                    .size(132.dp)
                    .shadow(40.dp, CircleShape, ambientColor = c.boss, spotColor = c.boss)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFF375F), Color(0xFF7A1FA2)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SwordIcon, null, tint = Color.White, modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("BOSS BATTLE", style = Masteria.type.caption2, color = c.boss)
            Spacer(Modifier.height(6.dp))
            Text(s.title, style = Masteria.type.largeTitle, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(
                "A ${s.total}-question mastery test on ${s.topicName}. Win ${(s.total * 0.8).toInt()} of ${s.total} to master it and unlock what's next.",
                style = Masteria.type.body, color = c.secondaryLabel, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text("+500 XP · Boss Slayer badge", style = Masteria.type.headline, color = c.xp)
            Spacer(Modifier.height(36.dp))
            MButton("Begin", { haptics.tap(); vm.beginBoss() }, Modifier.fillMaxWidth(), color = c.boss, icon = Icons.Rounded.PlayArrow)
        }
    }
}

@Composable
fun QuestSummaryView(summary: QuestSummaryDto, onDone: () -> Unit, onNext: () -> Unit) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val reduced = Masteria.reducedMotion
    val big = summary.leveledUp || summary.bossDefeated
    var confetti by remember { mutableIntStateOf(0) }
    // Staggered arrival: hero, stats, then each XP line, badges, unlocks.
    val steps = remember { List(6 + summary.breakdown.size) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(Unit) {
        if (big || summary.correct == summary.total) {
            haptics.celebrate(); confetti++
        } else {
            haptics.success()
        }
        if (reduced) return@LaunchedEffect
        steps.forEachIndexed { i, a ->
            launch { a.animateTo(1f, if (i == 0 && big) Springs.bouncy() else Springs.default()) }
            delay(if (i == 0) 180 else 90)
        }
    }
    fun Modifier.arrive(i: Int) = graphicsLayer {
        val p = steps.getOrNull(i)?.value ?: 1f
        alpha = p
        translationY = (1 - p) * 18.dp.toPx()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(28.dp))
                // Hero
                Column(
                    Modifier.graphicsLayer {
                        val p = steps[0].value
                        alpha = p
                        scaleX = 0.7f + 0.3f * p; scaleY = 0.7f + 0.3f * p
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        summary.bossDefeated -> HeroBadge(CrownIcon, listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A)))
                        summary.leveledUp -> Box(
                            Modifier
                                .size(120.dp)
                                .shadow(30.dp, CircleShape, ambientColor = c.brand, spotColor = c.brand)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF8A7DFF), Color(0xFF3B2BC9)))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${summary.levelAfter}", style = Masteria.type.hero, color = Color.White)
                        }
                        else -> Ring(summary.accuracy / 100f, c.forMastery(summary.accuracy), Modifier.size(120.dp), stroke = 10.dp) {
                            Text("${summary.correct}/${summary.total}", style = Masteria.type.title1, color = c.label)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        when {
                            summary.bossDefeated -> "Boss defeated!"
                            summary.isBoss -> "The boss held on"
                            summary.leveledUp -> "Level ${summary.levelAfter}!"
                            summary.correct == summary.total -> "Perfect quest!"
                            else -> "Quest complete"
                        },
                        style = Masteria.type.largeTitle, color = c.label, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(summary.title, style = Masteria.type.subhead, color = c.secondaryLabel)
                }
                Spacer(Modifier.height(22.dp))

                // XP
                Card(Modifier.arrive(1)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("XP earned", style = Masteria.type.headline, color = c.label, modifier = Modifier.weight(1f))
                        AnimatedNumber(summary.xpEarned, Masteria.type.title1, c.xp, prefix = "+")
                    }
                    Spacer(Modifier.height(8.dp))
                    summary.breakdown.forEachIndexed { i, line ->
                        Row(Modifier.arrive(6 + i).padding(vertical = 3.dp)) {
                            Text(line.label, style = Masteria.type.subhead, color = c.secondaryLabel, modifier = Modifier.weight(1f))
                            Text("+${line.xp}", style = Masteria.type.subhead, color = c.label)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val lv = summary.level
                    val span = (lv.nextLevelXp - lv.levelStartXp).coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Level ${lv.level}", style = Masteria.type.footnote, color = c.secondaryLabel)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            ProgressBar(((lv.xp - lv.levelStartXp).toFloat() / span), c.brand, height = 6.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${summary.coinsEarned} coins", style = Masteria.type.footnote, color = c.secondaryLabel)
                    }
                    lv.capMessage?.takeIf { lv.cappedByBoss }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = Masteria.type.footnote, color = c.boss)
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Mastery
                Card(Modifier.arrive(2)) {
                    val delta = summary.masteryAfter - summary.masteryBefore
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${summary.topicName} mastery", style = Masteria.type.headline, color = c.label, modifier = Modifier.weight(1f))
                        Text(
                            (if (delta >= 0) "+$delta" else "$delta") + "%",
                            style = Masteria.type.headline, color = if (delta >= 0) c.success else c.warning,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${summary.masteryBefore}%", style = Masteria.type.footnote, color = c.tertiaryLabel)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            ProgressBar(summary.masteryAfter / 100f, c.forMastery(summary.masteryAfter), height = 10.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        AnimatedNumber(summary.masteryAfter, Masteria.type.headline, c.label, suffix = "%")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(summary.nextStep, style = Masteria.type.subhead, color = c.secondaryLabel)
                }

                if (summary.newBadges.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.arrive(3)) {
                        Text("New badges", style = Masteria.type.headline, color = c.label)
                        Spacer(Modifier.height(10.dp))
                        summary.newBadges.forEach { b ->
                            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconTile(symbol(b.icon), c.xp, size = 44.dp, filled = true)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(b.name, style = Masteria.type.headline, color = c.label)
                                    Text(b.description, style = Masteria.type.footnote, color = c.secondaryLabel)
                                }
                            }
                        }
                    }
                }
                if (summary.unlockedTopics.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.arrive(4), color = c.successSoft) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LockOpen, null, tint = c.success, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Unlocked: ${summary.unlockedTopics.joinToString()}", style = Masteria.type.headline, color = c.label)
                                Text("New territory on your map.", style = Masteria.type.footnote, color = c.secondaryLabel)
                            }
                        }
                    }
                }
                if (summary.streakDays > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.arrive(5), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = c.warning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${summary.streakDays}-day streak", style = Masteria.type.headline, color = c.warning)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MButton("Done", onDone, Modifier.weight(1f), style = ButtonStyle.Tinted)
                MButton("Next quest", onNext, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
            }
        }
        ConfettiBurst(confetti, Modifier.fillMaxSize())
    }
}

@Composable
private fun HeroBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, colors: List<Color>) {
    Box(
        Modifier
            .size(120.dp)
            .shadow(30.dp, RoundedCornerShape(36.dp), ambientColor = colors.last(), spotColor = colors.last())
            .clip(RoundedCornerShape(36.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
    }
}
