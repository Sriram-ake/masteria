package com.triplethreats.masteria.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.HomeDto
import com.triplethreats.masteria.data.LevelDto
import com.triplethreats.masteria.data.RecommendationDto
import com.triplethreats.masteria.ui.LocalContainer
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.AnimatedNumber
import com.triplethreats.masteria.ui.components.Card
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.IconTile
import com.triplethreats.masteria.ui.components.LargeTitleScreen
import com.triplethreats.masteria.ui.components.ListRow
import com.triplethreats.masteria.ui.components.GroupedList
import com.triplethreats.masteria.ui.components.MasteryMeter
import com.triplethreats.masteria.ui.components.Pill
import com.triplethreats.masteria.ui.components.ProgressBar
import com.triplethreats.masteria.ui.components.Ring
import com.triplethreats.masteria.ui.components.SectionHeader
import com.triplethreats.masteria.ui.components.Skeleton
import com.triplethreats.masteria.ui.components.SwordIcon
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.components.symbol
import com.triplethreats.masteria.ui.quest.DifficultyStars
import com.triplethreats.masteria.ui.theme.Masteria
import kotlinx.coroutines.launch

class TodayViewModel(private val container: AppContainer) : ViewModel() {
    var home by mutableStateOf<HomeDto?>(null)
        private set
    var error by mutableStateOf<ApiException?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            if (home == null) home = container.session.cachedHome()
        }
    }

    fun refresh(onNeedsDiagnostic: () -> Unit) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                val h = container.api.home()
                home = h; error = null
                container.session.setUser(h.user)
                container.session.cacheHome(h)
                if (!h.user.diagnosed) onNeedsDiagnostic()
            } catch (e: ApiException) {
                error = e
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun TodayScreen(
    onStartQuest: (topicId: String?, boss: Boolean) -> Unit,
    onOpenMap: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenMentor: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
    onNeedsDiagnostic: () -> Unit,
) {
    val vm = appViewModel { TodayViewModel(it) }
    val version by LocalContainer.current.dataVersion.collectAsState()
    LaunchedEffect(version) { vm.refresh(onNeedsDiagnostic) }
    val c = Masteria.colors
    val home = vm.home

    LargeTitleScreen(
        title = home?.greeting ?: "Today",
        subtitle = home?.let { "${it.track.name} · ${it.track.subject}" },
        actions = {
            if (home != null) StreakPill(home.user.streakDays)
        },
    ) {
        vm.error?.let { e ->
            item(key = "error") {
                ErrorCard(
                    e.message.orEmpty(), onRetry = { vm.refresh(onNeedsDiagnostic) },
                    offline = e.offline, onSettings = if (e.offline) onOpenSettings else null,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        if (home == null) {
            item(key = "skeleton") { TodaySkeleton() }
            return@LargeTitleScreen
        }

        item(key = "next") {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                val rec = home.recommended
                if (rec != null) NextQuestCard(rec) { onStartQuest(rec.topicId, rec.isBoss) }
                else AllClearCard(onOpenMap)
            }
        }

        item(key = "stats") {
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LevelCard(home.level, Modifier.weight(1.25f))
                DailyCard(home, Modifier.weight(1f))
            }
        }

        if (home.bossReady.isNotEmpty()) {
            item(key = "bossHeader") { SectionHeader("Boss battles") }
            items(home.bossReady.size, key = { "boss-${home.bossReady[it].topicId}" }) { i ->
                val b = home.bossReady[i]
                BossCard(b, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { onStartQuest(b.topicId, true) }
            }
        }

        if (home.reviewsDue.isNotEmpty()) {
            item(key = "reviewHeader") { SectionHeader("Due for review") }
            item(key = "reviews") {
                GroupedList {
                    home.reviewsDue.forEachIndexed { i, r ->
                        ListRow(
                            title = r.topicName,
                            subtitle = "${r.questTitle} · keeps it fresh",
                            icon = Icons.Rounded.History,
                            iconTint = c.info,
                            showDivider = i != home.reviewsDue.lastIndex,
                            trailing = { Pill("+${r.xpReward} XP", c.xp) },
                            onClick = { onStartQuest(r.topicId, false) },
                        )
                    }
                }
            }
        }

        item(key = "moreHeader") { SectionHeader("Keep going") }
        item(key = "more") {
            GroupedList {
                ListRow(
                    "Scan to Quest", subtitle = "Photograph a textbook page, get a 5-question quest",
                    icon = Icons.Rounded.DocumentScanner, iconTint = c.brand, onClick = onOpenScan,
                )
                ListRow(
                    "Ask your mentor", subtitle = "Explains at your level, guides instead of telling",
                    icon = Icons.Rounded.AutoAwesome, iconTint = c.boss, onClick = onOpenMentor,
                )
                ListRow(
                    "Your progress", subtitle = "Mastery per topic, trends and the target zone",
                    icon = Icons.Rounded.Insights, iconTint = c.success, onClick = onOpenProgress, showDivider = false,
                )
            }
        }
    }
}

@Composable
private fun StreakPill(days: Int) {
    val c = Masteria.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (days > 0) c.warningSoft else c.fill)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$days day streak" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (days > 0) c.warning else c.secondaryLabel, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(3.dp))
        AnimatedNumber(days, Masteria.type.headline, if (days > 0) c.warning else c.secondaryLabel)
    }
}

@Composable
private fun NextQuestCard(rec: RecommendationDto, onStart: () -> Unit) {
    val boss = rec.isBoss
    val top = if (boss) Color(0xFFE0245E) else Color(0xFF6D5DF6)
    val bottom = if (boss) Color(0xFF7A1FA2) else Color(0xFF3B2BC9)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(26.dp), ambientColor = bottom, spotColor = bottom)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(top, bottom)))
            .pressable(scale = 0.98f, onClick = onStart)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (boss) SwordIcon else if (rec.isReview) Icons.Rounded.History else Icons.Rounded.TrackChanges,
                null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                (if (boss) "Boss battle · " else "Your next quest · ") + rec.reason,
                style = Masteria.type.footnote, color = Color.White.copy(alpha = 0.85f), maxLines = 1,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol(topicIconFor(rec.topicId)), null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.questTitle, style = Masteria.type.title2, color = Color.White)
                Text(rec.topicName, style = Masteria.type.subhead, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mastery ${rec.mastery}%", style = Masteria.type.footnote, color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                ProgressBar(rec.mastery / 100f, Color.White, track = Color.White.copy(alpha = 0.22f), height = 6.dp)
            }
            Spacer(Modifier.width(10.dp))
            DifficultyStars(rec.difficulty, color = Color(0xFFFFD60A))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = bottom, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (boss) "Face the boss" else "Start quest", style = Masteria.type.headline, color = bottom)
            }
            Spacer(Modifier.weight(1f))
            Text("up to +${rec.xpReward} XP", style = Masteria.type.headline, color = Color(0xFFFFD60A))
        }
    }
}

/** Topic ids carry their subject; a fallback glyph when the recommendation has no icon. */
private fun topicIconFor(topicId: String): String = when {
    topicId.contains("linear") -> "function"
    topicId.contains("fraction") -> "divide"
    topicId.contains("integer") -> "plusminus"
    topicId.contains("geometry") -> "angle"
    topicId.contains("triangle") -> "triangle"
    topicId.contains("probab") -> "dice"
    topicId.contains("percent") -> "percent"
    topicId.contains("ratio") -> "scale"
    topicId.contains("average") -> "chart"
    topicId.contains("profit") -> "coins"
    topicId.contains("time_work") -> "clock"
    topicId.contains("interest") -> "bank"
    topicId.contains("class") -> "box"
    topicId.contains("encaps") -> "lock"
    topicId.contains("inherit") -> "layers"
    topicId.contains("poly") -> "shapes"
    topicId.contains("interface") -> "puzzle"
    topicId.startsWith("java") -> "code"
    topicId.startsWith("scan") -> "scan"
    else -> "spark"
}

@Composable
private fun AllClearCard(onOpenMap: () -> Unit) {
    val c = Masteria.colors
    Card(onClick = onOpenMap) {
        Text("Every open topic is mastered", style = Masteria.type.title3, color = c.label)
        Spacer(Modifier.height(4.dp))
        Text("Open the map to replay a topic or switch worlds from your profile.", style = Masteria.type.subhead, color = c.secondaryLabel)
    }
}

@Composable
private fun LevelCard(level: LevelDto, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    val span = (level.nextLevelXp - level.levelStartXp).coerceAtLeast(1)
    val progress = ((level.xp - level.levelStartXp).toFloat() / span).coerceIn(0f, 1f)
    Card(modifier, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(progress, c.brand, Modifier.size(54.dp), stroke = 6.dp) {
                Text("${level.level}", style = Masteria.type.title2, color = c.label)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Level ${level.level}", style = Masteria.type.headline, color = c.label)
                AnimatedNumber(level.xp, Masteria.type.footnote, c.secondaryLabel, suffix = " XP")
                Text(
                    if (level.cappedByBoss) "Boss needed" else "${(level.nextLevelXp - level.xp).coerceAtLeast(0)} to next",
                    style = Masteria.type.caption,
                    color = if (level.cappedByBoss) c.boss else c.tertiaryLabel,
                )
            }
        }
    }
}

@Composable
private fun DailyCard(home: HomeDto, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    val progress = (home.minutesToday.toFloat() / home.dailyMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
    Card(modifier, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(progress, c.success, Modifier.size(54.dp), stroke = 6.dp) {
                Icon(Icons.Rounded.Timer, null, tint = c.success, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Today", style = Masteria.type.headline, color = c.label)
                Text("${home.minutesToday}/${home.dailyMinutes} min", style = Masteria.type.footnote, color = c.secondaryLabel)
                Text("${home.questsToday} quests", style = Masteria.type.caption, color = c.tertiaryLabel)
            }
        }
    }
}

@Composable
private fun BossCard(b: RecommendationDto, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Masteria.colors
    Card(modifier, onClick = onClick, color = c.card) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(SwordIcon, c.boss, size = 44.dp, filled = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(b.questTitle, style = Masteria.type.headline, color = c.label)
                Text("${b.topicName} · win 4 of 5 to master it", style = Masteria.type.footnote, color = c.secondaryLabel)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.boss, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        MasteryMeter(b.mastery)
    }
}

@Composable
private fun TodaySkeleton() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Skeleton(Modifier.fillMaxWidth().height(230.dp), radius = 26.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Skeleton(Modifier.weight(1.25f).height(86.dp), radius = 22.dp)
            Skeleton(Modifier.weight(1f).height(86.dp), radius = 22.dp)
        }
        Skeleton(Modifier.fillMaxWidth().height(170.dp), radius = 18.dp)
    }
}
