package com.triplethreats.masteria.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.BadgeDto
import com.triplethreats.masteria.data.ProfileDto
import com.triplethreats.masteria.data.UpdateProfileRequest
import com.triplethreats.masteria.ui.LocalContainer
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.ActivitySpinner
import com.triplethreats.masteria.ui.components.Card
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.GroupedList
import com.triplethreats.masteria.ui.components.LargeTitleScreen
import com.triplethreats.masteria.ui.components.ListRow
import com.triplethreats.masteria.ui.components.MasteryMeter
import com.triplethreats.masteria.ui.components.ProgressBar
import com.triplethreats.masteria.ui.components.SectionHeader
import com.triplethreats.masteria.ui.components.Skeleton
import com.triplethreats.masteria.ui.components.StatTile
import com.triplethreats.masteria.ui.components.SwordIcon
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.components.symbol
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

class ProfileViewModel(private val container: AppContainer) : ViewModel() {
    var profile by mutableStateOf<ProfileDto?>(null)
        private set
    var error by mutableStateOf<ApiException?>(null)
        private set
    var switching by mutableStateOf<String?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            try {
                profile = container.api.profile(); error = null
                profile?.user?.let(container.session::setUser)
            } catch (e: ApiException) {
                error = e
            }
        }
    }

    /** Same engine, different world: switching tracks keeps XP and level. */
    fun switchTrack(trackId: String, onNeedsDiagnostic: () -> Unit) {
        if (switching != null || profile?.track?.id == trackId) return
        switching = trackId
        viewModelScope.launch {
            try {
                val p = container.api.updateProfile(UpdateProfileRequest(trackId = trackId))
                profile = p
                container.session.setUser(p.user)
                container.invalidate()
                if (!p.user.diagnosed) onNeedsDiagnostic()
            } catch (e: ApiException) {
                error = e
            } finally {
                switching = null
            }
        }
    }
}

private val learnerLabels = mapOf(
    "school" to "School student", "college" to "College student", "exam" to "Exam aspirant",
    "coding" to "Coding learner", "self" to "Self-learner", "career" to "Career switcher",
)
private val goalLabels = mapOf(
    "grades" to "Improve grades", "exam" to "Crack an exam", "skill" to "Learn a skill",
    "job" to "Get a job", "career" to "Build a career", "explore" to "Explore",
)

@Composable
fun ProfileScreen(
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
    onNeedsDiagnostic: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val vm = appViewModel { ProfileViewModel(it) }
    val version by LocalContainer.current.dataVersion.collectAsState()
    LaunchedEffect(version) { vm.load() }
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val p = vm.profile

    LargeTitleScreen(title = "Profile") {
        vm.error?.let { e -> item(key = "error") { ErrorCard(e.message.orEmpty(), { vm.load() }, Modifier.padding(top = 12.dp), offline = e.offline, onSettings = onOpenSettings) } }
        if (p == null) {
            item(key = "sk") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Skeleton(Modifier.fillMaxWidth().height(190.dp), radius = 22.dp)
                    Skeleton(Modifier.fillMaxWidth().height(120.dp), radius = 22.dp)
                }
            }
            return@LargeTitleScreen
        }
        val u = p.user

        item(key = "hero") {
            Card(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF8A7DFF), Color(0xFF3B2BC9)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            u.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                            style = Masteria.type.title2, color = Color.White,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name, style = Masteria.type.title2, color = c.label)
                        Text(
                            listOfNotNull(learnerLabels[u.learnerType], goalLabels[u.goal]).joinToString(" · "),
                            style = Masteria.type.subhead, color = c.secondaryLabel,
                        )
                        if (u.isGuest) Text("Guest account", style = Masteria.type.caption, color = c.warning)
                    }
                }
                Spacer(Modifier.height(16.dp))
                val lv = p.level
                val span = (lv.nextLevelXp - lv.levelStartXp).coerceAtLeast(1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Level ${lv.level}", style = Masteria.type.title3, color = c.label, modifier = Modifier.weight(1f))
                    Text("${lv.xp} / ${lv.nextLevelXp} XP", style = Masteria.type.footnote, color = c.secondaryLabel)
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(
                    (lv.xp - lv.levelStartXp).toFloat() / span, c.brand, height = 10.dp,
                    brush = Brush.horizontalGradient(listOf(Color(0xFF8A7DFF), c.brand)),
                )
                if (lv.cappedByBoss && lv.capMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(SwordIcon, null, tint = c.boss, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(lv.capMessage, style = Masteria.type.footnote, color = c.boss)
                    }
                }
            }
        }

        item(key = "stats") {
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Streak", "${u.streakDays}d", Icons.Rounded.LocalFireDepartment, c.warning, Modifier.weight(1f))
                StatTile("Coins", "${u.coins}", Icons.Rounded.Savings, c.xp, Modifier.weight(1f))
                StatTile("Bosses", "${p.bossesDefeated}", SwordIcon, c.boss, Modifier.weight(1f))
            }
        }

        if (p.topSkills.isNotEmpty()) {
            item(key = "skillsHeader") {
                SectionHeader("Top skills") {
                    Text("See all", style = Masteria.type.body, color = c.brand, modifier = Modifier.pressable(onClick = onOpenProgress))
                }
            }
            item(key = "skills") {
                Card(Modifier.padding(horizontal = 16.dp)) {
                    p.topSkills.take(4).forEachIndexed { i, s ->
                        Text(s.name, style = Masteria.type.subhead, color = c.secondaryLabel)
                        Spacer(Modifier.height(2.dp))
                        MasteryMeter(s.mastery)
                        if (i != p.topSkills.take(4).lastIndex) Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }

        item(key = "badgesHeader") {
            SectionHeader("Badges") {
                Text("${p.badges.count { it.earned }} of ${p.badges.size}", style = Masteria.type.subhead, color = c.secondaryLabel)
            }
        }
        item(key = "badges") {
            Card(Modifier.padding(horizontal = 16.dp)) {
                p.badges.chunked(4).forEachIndexed { r, row ->
                    if (r > 0) Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { b -> BadgeCell(b, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item(key = "worldsHeader") { SectionHeader("Worlds") }
        item(key = "worlds") {
            GroupedList {
                p.tracks.forEachIndexed { i, t ->
                    val current = t.id == p.track.id
                    ListRow(
                        title = t.name,
                        subtitle = "${t.world} · ${t.subject}",
                        icon = symbol(t.icon),
                        iconTint = listOf(c.brand, c.xp, c.info, c.success)[i % 4],
                        showDivider = i != p.tracks.lastIndex,
                        chevron = false,
                        trailing = {
                            when {
                                vm.switching == t.id -> ActivitySpinner(size = 18.dp)
                                current -> Icon(Icons.Rounded.Check, "Current world", tint = c.brand, modifier = Modifier.size(22.dp))
                            }
                        },
                        onClick = if (current) null else ({ haptics.selection(); vm.switchTrack(t.id, onNeedsDiagnostic) }),
                    )
                }
            }
            Text(
                "Same engine, same XP and level. Only the content changes. A new world starts with its own short diagnostic.",
                style = Masteria.type.footnote, color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }

        item(key = "more") {
            Spacer(Modifier.height(12.dp))
            GroupedList {
                ListRow("Progress", icon = Icons.Rounded.Insights, iconTint = c.success, onClick = onOpenProgress)
                ListRow("Settings", icon = Icons.Rounded.Settings, iconTint = c.secondaryLabel, onClick = onOpenSettings, showDivider = false)
            }
        }
    }
}

@Composable
private fun BadgeCell(b: BadgeDto, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "${b.name}, ${if (b.earned) "earned" else "not earned yet"}. ${b.description}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(
                    if (b.earned) Brush.linearGradient(listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A)))
                    else Brush.linearGradient(listOf(c.fill, c.fill))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol(b.icon), null,
                tint = if (b.earned) Color.White else c.tertiaryLabel,
                modifier = Modifier.size(26.dp),
            )
            if (!b.earned) {
                Icon(
                    Icons.Rounded.Lock, null, tint = c.secondaryLabel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(13.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            b.name, style = Masteria.type.caption,
            color = if (b.earned) c.label else c.secondaryLabel,
            textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.graphicsLayer { alpha = if (b.earned) 1f else 0.8f },
        )
    }
}
