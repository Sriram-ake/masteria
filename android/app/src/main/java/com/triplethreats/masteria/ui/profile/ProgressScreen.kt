package com.triplethreats.masteria.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.ProgressDto
import com.triplethreats.masteria.data.TopicProgressDto
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.Card
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.LargeTitleScreen
import com.triplethreats.masteria.ui.components.ProgressBar
import com.triplethreats.masteria.ui.components.Ring
import com.triplethreats.masteria.ui.components.SectionHeader
import com.triplethreats.masteria.ui.components.Skeleton
import com.triplethreats.masteria.ui.components.StatTile
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.forMastery
import com.triplethreats.masteria.ui.theme.masteryWord
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

class ProgressViewModel(private val container: AppContainer) : ViewModel() {
    var progress by mutableStateOf<ProgressDto?>(null)
        private set
    var error by mutableStateOf<ApiException?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            try {
                progress = container.api.progress(); error = null
            } catch (e: ApiException) {
                error = e
            }
        }
    }
}

@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val vm = appViewModel { ProgressViewModel(it) }
    LaunchedEffect(Unit) { vm.load() }
    val c = Masteria.colors
    val p = vm.progress

    LargeTitleScreen(title = "Progress", onBack = onBack, bottomInset = 0.dp) {
        vm.error?.let { e -> item(key = "error") { ErrorCard(e.message.orEmpty(), { vm.load() }, Modifier.padding(top = 12.dp), offline = e.offline) } }
        if (p == null) {
            item(key = "sk") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Skeleton(Modifier.fillMaxWidth().height(160.dp), radius = 22.dp)
                    Skeleton(Modifier.fillMaxWidth().height(200.dp), radius = 22.dp)
                }
            }
            return@LargeTitleScreen
        }

        item(key = "overall") {
            Card(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(p.overallMastery / 100f, c.forMastery(p.overallMastery), Modifier.size(96.dp), stroke = 10.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${p.overallMastery}%", style = Masteria.type.title2, color = c.label)
                            Text("overall", style = Masteria.type.caption, color = c.secondaryLabel)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        if (p.strongest.isNotEmpty()) {
                            Labelled(Icons.Rounded.CheckCircle, c.success, "Strongest", p.strongest.joinToString())
                            Spacer(Modifier.height(10.dp))
                        }
                        if (p.weakest.isNotEmpty()) {
                            Labelled(Icons.Rounded.WarningAmber, c.warning, "Needs practice", p.weakest.joinToString())
                        }
                    }
                }
            }
        }

        item(key = "tiles") {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Accuracy", "${p.accuracy}%", Icons.Rounded.CheckCircle, c.success, Modifier.weight(1f), footnote = "${p.totalAnswers} answers")
                    StatTile("Quests", "${p.questsCompleted}", Icons.Rounded.EmojiEvents, c.xp, Modifier.weight(1f), footnote = "completed")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        "Target zone", "${p.targetZoneShare}%", Icons.Rounded.TrackChanges, c.brand, Modifier.weight(1f),
                        footnote = "at 70–85% predicted",
                    )
                    StatTile("Answers", "${p.totalAnswers}", Icons.Rounded.QuestionAnswer, c.info, Modifier.weight(1f), footnote = "all time")
                }
                Text(
                    "Target zone is the share of questions the learner model pitched where you had a 70–85% chance: hard enough to learn from, easy enough to keep going.",
                    style = Masteria.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item(key = "xpHeader") { SectionHeader("XP, last 7 days") }
        item(key = "xp") {
            Card(Modifier.padding(horizontal = 16.dp)) { XpBars(p.xpLast7Days, p.dayLabels) }
        }

        item(key = "topicsHeader") { SectionHeader("Mastery by topic") }
        items(p.topics.size, key = { "t-${p.topics[it].topicId}" }) { i ->
            TopicProgressRow(p.topics[i], Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun Labelled(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String, value: String) {
    val c = Masteria.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = Masteria.type.footnote, color = c.secondaryLabel)
            Text(value, style = Masteria.type.headline, color = c.label)
        }
    }
}

/**
 * One series, so no legend: the section title names it. Bars are anchored to the baseline with
 * rounded data-ends and a gap between them; only today's value is labelled directly, and tapping
 * any bar reveals its value (touch has no hover).
 */
@Composable
private fun XpBars(values: List<Int>, labels: List<String>) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    var picked by remember { mutableIntStateOf(values.lastIndex) }
    // Bars grow from the baseline once, on a slow critically damped spring.
    val reduced = Masteria.reducedMotion
    val growAnim = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { growAnim.animateTo(1f, Springs.progress()) }
    val grow = growAnim.value
    val description = labels.zip(values).joinToString { (d, v) -> "$d $v XP" }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "XP per day: $description" }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${values.getOrElse(picked) { 0 }} XP", style = Masteria.type.title2, color = c.label)
            Spacer(Modifier.width(8.dp))
            Text(labels.getOrElse(picked) { "" }.let { if (picked == values.lastIndex) "today" else it }, style = Masteria.type.subhead, color = c.secondaryLabel)
        }
        Spacer(Modifier.height(14.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(values) {
                    detectTapGestures { pos ->
                        val slot = size.width / values.size.coerceAtLeast(1)
                        val i = (pos.x / slot).toInt().coerceIn(0, values.lastIndex)
                        if (i != picked) haptics.selection()
                        picked = i
                    }
                }
        ) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            val r = 4.dp.toPx()
            // Recessive baseline
            drawLine(c.separator, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = (size.height - 2.dp.toPx()) * (v.toFloat() / max) * grow
                val x = slot * i + (slot - barW) / 2
                val color = if (i == picked) c.brand else c.brand.copy(alpha = 0.35f)
                if (v > 0) {
                    // Rounded top only: square the bottom by overdrawing a plain rect under the radius.
                    drawRoundRect(color, Offset(x, size.height - h - 2.dp.toPx()), Size(barW, h), CornerRadius(r))
                    if (h > r) drawRect(color, Offset(x, size.height - 2.dp.toPx() - r), Size(barW, r))
                } else {
                    drawRoundRect(c.fill, Offset(x, size.height - 4.dp.toPx() - 2.dp.toPx()), Size(barW, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            labels.forEachIndexed { i, l ->
                Text(
                    l, style = Masteria.type.caption,
                    color = if (i == picked) c.label else c.secondaryLabel,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TopicProgressRow(t: TopicProgressDto, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    val color = c.forMastery(t.mastery)
    Card(modifier, padding = PaddingValues(14.dp), radius = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = Masteria.type.headline, color = c.label)
                Text(
                    if (t.status == "locked") "Locked"
                    else "${t.mastery}% · ${masteryWord(t.mastery)} · ${t.accuracy}% accuracy over ${t.attempts}",
                    style = Masteria.type.footnote, color = c.secondaryLabel,
                )
            }
            if (t.trend.size >= 2) {
                Sparkline(t.trend, color, Modifier.width(72.dp).height(30.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(t.mastery / 100f, color, height = 6.dp)
    }
}

/** Mastery after each recent answer: a 2px line, with the latest point marked. */
@Composable
private fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    Canvas(modifier.semantics { contentDescription = "Trend from ${values.first()} to ${values.last()} percent" }) {
        val lo = (values.minOrNull() ?: 0).toFloat()
        val hi = (values.maxOrNull() ?: 100).toFloat().coerceAtLeast(lo + 1f)
        val stepX = size.width / (values.size - 1)
        fun y(v: Int) = size.height - 4.dp.toPx() - (v - lo) / (hi - lo) * (size.height - 8.dp.toPx())
        val path = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(stepX * i, y(v)) }
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = Offset(stepX * (values.size - 1), y(values.last()))
        drawCircle(c.card, 5.dp.toPx(), last)
        drawCircle(color, 3.5.dp.toPx(), last)
    }
}
