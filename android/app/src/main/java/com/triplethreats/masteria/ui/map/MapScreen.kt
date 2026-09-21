package com.triplethreats.masteria.ui.map

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.MapDto
import com.triplethreats.masteria.data.MapTopicDto
import com.triplethreats.masteria.ui.LocalContainer
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.BottomSheet
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.Card
import com.triplethreats.masteria.ui.components.Dot
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.IconTile
import com.triplethreats.masteria.ui.components.LargeTitleScreen
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.MasteryMeter
import com.triplethreats.masteria.ui.components.Overlay
import com.triplethreats.masteria.ui.components.Pill
import com.triplethreats.masteria.ui.components.Ring
import com.triplethreats.masteria.ui.components.Skeleton
import com.triplethreats.masteria.ui.components.SwordIcon
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.components.symbol
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.forMastery
import com.triplethreats.masteria.ui.theme.masteryWord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MapViewModel(private val container: AppContainer) : ViewModel() {
    var map by mutableStateOf<MapDto?>(null)
        private set
    var error by mutableStateOf<ApiException?>(null)
        private set

    init {
        viewModelScope.launch { if (map == null) map = container.session.cachedMap() }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val m = container.api.map()
                map = m; error = null
                container.session.cacheMap(m)
            } catch (e: ApiException) {
                error = e
            }
        }
    }
}

@Composable
fun MapScreen(onStartQuest: (topicId: String?, boss: Boolean) -> Unit) {
    val vm = appViewModel { MapViewModel(it) }
    val version by LocalContainer.current.dataVersion.collectAsState()
    LaunchedEffect(version) { vm.refresh() }
    val c = Masteria.colors
    var selected by remember { mutableStateOf<MapTopicDto?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val map = vm.map

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(
            title = map?.track?.name ?: "Map",
            subtitle = map?.track?.let { "${it.world} · ${it.subject}" },
        ) {
            vm.error?.let { e ->
                item(key = "error") { ErrorCard(e.message.orEmpty(), { vm.refresh() }, Modifier.padding(top = 12.dp), offline = e.offline) }
            }
            if (map == null) {
                item(key = "skeleton") {
                    Skeleton(
                        Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                            .height(460.dp), radius = 26.dp,
                    )
                }
                return@LargeTitleScreen
            }
            item(key = "summary") { MapSummary(map) }
            item(key = "tree") {
                SkillTree(map.topics, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    selected = it; sheetOpen = true
                }
            }
            item(key = "legend") { Legend() }
        }

        Overlay {
            BottomSheet(visible = sheetOpen, onDismiss = { sheetOpen = false }) {
                selected?.let { t ->
                    TopicSheet(
                        topic = t,
                        prereqNames = map?.topics?.filter { it.id in t.prereqs }?.map { it.name }.orEmpty(),
                        onStart = { boss -> sheetOpen = false; onStartQuest(t.id, boss) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MapSummary(map: MapDto) {
    val c = Masteria.colors
    val mastered = map.topics.count { it.status == "mastered" }
    val open = map.topics.count { it.status == "available" }
    val locked = map.topics.count { it.status == "locked" }
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SummaryChip("Mastered", mastered, c.success, Icons.Rounded.CheckCircle, Modifier.weight(1f))
        SummaryChip("Open", open, c.brand, Icons.Rounded.PlayArrow, Modifier.weight(1f))
        SummaryChip("Locked", locked, c.secondaryLabel, Icons.Rounded.Lock, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryChip(label: String, value: Int, tint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    val c = Masteria.colors
    Card(modifier, padding = androidx.compose.foundation.layout.PaddingValues(12.dp), radius = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = Masteria.type.footnote, color = c.secondaryLabel)
        }
        Text("$value", style = Masteria.type.title2, color = c.label)
    }
}

private val NodeSize = 76.dp
private val RowGap = 150.dp

/**
 * The skill tree: tiers as rows, prerequisite edges as curves. Lit edges (prerequisite mastered)
 * are solid brand colour; the rest are dashed. Nodes arrive tier by tier on a spring.
 */
@Composable
private fun SkillTree(topics: List<MapTopicDto>, modifier: Modifier = Modifier, onTap: (MapTopicDto) -> Unit) {
    val c = Masteria.colors
    val reduced = Masteria.reducedMotion
    val tiers = topics.groupBy { it.tier }.toSortedMap()
    val tierCount = tiers.size
    val arrive = remember(topics.map { it.id }) { List(tierCount) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(arrive) {
        if (reduced) return@LaunchedEffect
        arrive.forEachIndexed { i, a -> launch { delay(110L * i); a.animateTo(1f, Springs.default()) } }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
            .background(c.card)
            .padding(vertical = 24.dp)
    ) {
        val width = maxWidth
        val treeHeight = RowGap * (tierCount - 1) + NodeSize + 40.dp
        // Position of each node's centre.
        val positions: Map<String, Pair<Dp, Dp>> = buildMap {
            tiers.values.forEachIndexed { row, list ->
                list.forEachIndexed { i, t ->
                    val x = width * ((i + 0.5f) / list.size)
                    val y = RowGap * row + NodeSize / 2
                    put(t.id, x to y)
                }
            }
        }
        val statusById = topics.associateBy { it.id }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(treeHeight)
        ) {
            topics.forEach { t ->
                val (tx, ty) = positions[t.id] ?: return@forEach
                t.prereqs.forEach { pid ->
                    val (px, py) = positions[pid] ?: return@forEach
                    val lit = statusById[pid]?.status == "mastered"
                    val start = Offset(px.toPx(), py.toPx() + NodeSize.toPx() / 2 + 4.dp.toPx())
                    val end = Offset(tx.toPx(), ty.toPx() - NodeSize.toPx() / 2 - 4.dp.toPx())
                    val midY = (start.y + end.y) / 2
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(start.x, midY, end.x, midY, end.x, end.y)
                    }
                    drawPath(
                        path,
                        color = if (lit) c.brand.copy(alpha = 0.7f) else c.separator,
                        style = Stroke(
                            width = if (lit) 3.dp.toPx() else 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(8f, 10f)),
                        ),
                    )
                }
            }
        }
        tiers.values.forEachIndexed { row, list ->
            list.forEach { t ->
                val (x, y) = positions.getValue(t.id)
                val p = arrive.getOrNull(row)?.value ?: 1f
                Box(
                    Modifier
                        .offset(x = x - 55.dp, y = y - NodeSize / 2)
                        .width(110.dp)
                        .graphicsLayer {
                            alpha = p
                            translationY = (1 - p) * 20.dp.toPx()
                            scaleX = 0.9f + 0.1f * p; scaleY = 0.9f + 0.1f * p
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    TopicNode(t) { onTap(t) }
                }
            }
        }
    }
}

@Composable
private fun TopicNode(t: MapTopicDto, onTap: () -> Unit) {
    val c = Masteria.colors
    val locked = t.status == "locked"
    val mastered = t.status == "mastered"
    val color = when {
        locked -> c.tertiaryLabel
        mastered -> c.success
        t.bossReady -> c.boss
        t.weak -> c.warning
        else -> c.forMastery(t.mastery)
    }
    // The weak spot breathes gently so the eye finds it; reduced motion keeps it still.
    val pulse = if (t.weak && !Masteria.reducedMotion) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            1f, 1.18f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "p",
        ).value
    } else 1f
    val status = when {
        locked -> "locked"
        mastered -> "mastered"
        t.bossReady -> "boss ready"
        t.weak -> "weak spot"
        else -> "open"
    }
    Column(
        Modifier
            .pressable(scale = 0.94f, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = "${t.name}, ${t.mastery} percent, $status" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (t.weak) {
                Box(
                    Modifier
                        .size(NodeSize)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = (1.18f - pulse) * 3f }
                        .clip(CircleShape)
                        .background(c.warning.copy(alpha = 0.35f))
                )
            }
            Box(
                Modifier
                    .size(NodeSize)
                    .shadow(if (locked) 0.dp else 10.dp, CircleShape, ambientColor = color, spotColor = color)
                    .clip(CircleShape)
                    .background(c.cardElevated),
                contentAlignment = Alignment.Center,
            ) {
                Ring(if (locked) 0f else t.mastery / 100f, color, Modifier.size(NodeSize), stroke = 5.dp) {
                    Icon(
                        if (locked) Icons.Rounded.Lock else symbol(t.icon), null,
                        tint = if (locked) c.tertiaryLabel else color,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            // Corner badge: state as a shape as well as a colour.
            val badge = when {
                mastered -> Icons.Rounded.CheckCircle
                t.bossReady -> SwordIcon
                t.reviewDue -> Icons.Rounded.History
                t.weak -> Icons.Rounded.WarningAmber
                else -> null
            }
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(badge, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            t.name, style = Masteria.type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = if (locked) c.secondaryLabel else c.label, textAlign = TextAlign.Center, maxLines = 2,
        )
        Text(
            if (locked) "Locked" else "${t.mastery}%",
            style = Masteria.type.caption, color = if (locked) c.tertiaryLabel else color,
        )
    }
}

@Composable
private fun Legend() {
    val c = Masteria.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(c.warning, "Weak")
        LegendItem(c.info, "Growing")
        LegendItem(c.success, "Mastered")
        LegendItem(c.boss, "Boss ready")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(5.dp))
        Text(label, style = Masteria.type.caption, color = Masteria.colors.secondaryLabel)
    }
}

@Composable
private fun TopicSheet(topic: MapTopicDto, prereqNames: List<String>, onStart: (boss: Boolean) -> Unit) {
    val c = Masteria.colors
    val locked = topic.status == "locked"
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (locked) Icons.Rounded.Lock else symbol(topic.icon), if (locked) c.secondaryLabel else c.brand, size = 52.dp, filled = !locked)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(topic.name, style = Masteria.type.title2, color = c.label)
                Text(topic.description, style = Masteria.type.subhead, color = c.secondaryLabel)
            }
        }
        Spacer(Modifier.height(18.dp))
        if (locked) {
            Card(color = c.cardElevated) {
                Text("Locked", style = Masteria.type.headline, color = c.label)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Defeat the boss of ${prereqNames.joinToString(" and ")} to unlock ${topic.name}.",
                    style = Masteria.type.subhead, color = c.secondaryLabel,
                )
            }
        } else {
            MasteryMeter(topic.mastery)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (topic.weak) Pill("Weak spot", c.warning, icon = Icons.Rounded.WarningAmber)
                if (topic.bossDefeated) Pill("Boss defeated", c.success, icon = Icons.Rounded.CheckCircle)
                if (topic.reviewDue) Pill("Review due", c.info, icon = Icons.Rounded.History)
                Pill("${topic.questionCount} questions", c.secondaryLabel)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    topic.bossDefeated -> "Mastered. Replay it as a review to keep it sharp."
                    topic.bossReady -> "Mastery is ${topic.mastery}%. The boss is ready: win 4 of 5 to master ${topic.name}."
                    else -> "Reach 80% mastery to face the boss. You're at ${topic.mastery}% (${masteryWord(topic.mastery).lowercase()})."
                },
                style = Masteria.type.footnote, color = c.secondaryLabel,
            )
            Spacer(Modifier.height(18.dp))
            if (topic.bossReady) {
                MButton("Face the boss", { onStart(true) }, Modifier.fillMaxWidth(), icon = SwordIcon, color = c.boss)
                Spacer(Modifier.height(8.dp))
                MButton("Practice first", { onStart(false) }, Modifier.fillMaxWidth(), style = ButtonStyle.Tinted)
            } else {
                MButton(
                    if (topic.bossDefeated) "Start review" else "Start quest",
                    { onStart(false) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
