package com.triplethreats.masteria.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EmojiObjects
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Rocket
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.OnboardingRequest
import com.triplethreats.masteria.data.TrackDto
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.ActivitySpinner
import com.triplethreats.masteria.ui.components.BackButton
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.IconTile
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.Pill
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.components.symbol
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Choice(val id: String, val title: String, val subtitle: String, val icon: ImageVector)

private val learnerTypes = listOf(
    Choice("school", "School student", "Classes 6 to 12", Icons.Rounded.School),
    Choice("college", "College student", "Degree subjects and placements", Icons.Rounded.AutoStories),
    Choice("exam", "Exam aspirant", "SSC, Banking, JEE, NEET, UPSC", Icons.Rounded.TrackChanges),
    Choice("coding", "Coding learner", "Java, Python, web, DSA", Icons.Rounded.Code),
    Choice("self", "Self-learner", "Any subject, at your pace", Icons.Rounded.Explore),
    Choice("career", "Career switcher", "Analyst, developer, designer", Icons.Rounded.Work),
)
private val goals = listOf(
    Choice("grades", "Improve my grades", "Do better in class tests and boards", Icons.Rounded.TrendingUp),
    Choice("exam", "Crack an exam", "Prepare against a date", Icons.Rounded.WorkspacePremium),
    Choice("skill", "Learn a new skill", "Build something real", Icons.Rounded.EmojiObjects),
    Choice("job", "Get a job", "Interview-ready fundamentals", Icons.Rounded.Work),
    Choice("explore", "Just explore", "Follow my curiosity", Icons.Rounded.Explore),
)
private val levels = listOf(
    Choice("beginner", "Beginner", "I'm just starting out", Icons.Rounded.Spa),
    Choice("intermediate", "Intermediate", "I know the basics", Icons.Rounded.Psychology),
    Choice("advanced", "Advanced", "I want a real challenge", Icons.Rounded.Rocket),
)
private val times = listOf(
    Choice("15", "15 minutes a day", "Casual · about 2 quests", Icons.Rounded.AccessTime),
    Choice("30", "30 minutes a day", "Regular · about 4 quests", Icons.Rounded.Bolt),
    Choice("60", "1 hour a day", "Serious · about 8 quests", Icons.Rounded.Whatshot),
    Choice("120", "2+ hours a day", "Intense · exam mode", Icons.Rounded.Rocket),
)

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    var step by mutableIntStateOf(0)
        private set
    var forward by mutableStateOf(true)
        private set
    var learnerType by mutableStateOf<String?>(null)
    var goal by mutableStateOf<String?>(null)
    var level by mutableStateOf<String?>(null)
    var minutes by mutableStateOf<String?>(null)
    var trackId by mutableStateOf<String?>(null)
    var isMinor by mutableStateOf<Boolean?>(null)
    var consent by mutableStateOf(false)

    var tracks by mutableStateOf<List<TrackDto>>(emptyList())
        private set
    var tracksError by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    val stepCount = 6

    init {
        loadTracks()
    }

    fun loadTracks() {
        tracksError = null
        viewModelScope.launch {
            try {
                tracks = container.api.tracks()
            } catch (e: ApiException) {
                tracksError = e.message
            }
        }
    }

    /** Tracks recommended for the chosen learner type come first. */
    fun orderedTracks(): List<TrackDto> =
        tracks.sortedByDescending { t -> learnerType != null && learnerType in t.recommendedFor }

    fun next() {
        forward = true
        if (step == 3 && trackId == null) {
            trackId = orderedTracks().firstOrNull()?.id
        }
        if (step < stepCount - 1) step++
    }

    fun back(): Boolean {
        if (step == 0) return false
        forward = false
        step--
        return true
    }

    fun canContinue(): Boolean = when (step) {
        0 -> learnerType != null
        1 -> goal != null
        2 -> level != null
        3 -> minutes != null
        4 -> trackId != null
        5 -> isMinor == false || (isMinor == true && consent)
        else -> false
    }

    fun finish(onDone: () -> Unit) {
        if (saving) return
        saving = true; saveError = null
        viewModelScope.launch {
            try {
                val user = container.api.onboard(
                    OnboardingRequest(
                        learnerType = learnerType!!, goal = goal!!, selfLevel = level!!,
                        dailyMinutes = minutes!!.toInt(), trackId = trackId!!,
                        isMinor = isMinor == true, parentConsent = consent,
                    )
                )
                container.session.setUser(user)
                onDone()
            } catch (e: ApiException) {
                saveError = e.message
            } finally {
                saving = false
            }
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val vm = appViewModel { OnboardingViewModel(it) }
    val c = Masteria.colors
    val reduced = Masteria.reducedMotion
    val haptics = rememberHaptics()

    BackHandler(enabled = vm.step > 0) { vm.back() }

    // Single-choice steps advance on their own shortly after a pick, so the choice is seen first.
    var pendingAdvance by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pendingAdvance) {
        if (pendingAdvance >= 0 && pendingAdvance == vm.step) {
            delay(280)
            if (vm.step == pendingAdvance) vm.next()
        }
    }
    fun pick(action: () -> Unit) {
        haptics.selection()
        action()
        if (vm.step <= 3) pendingAdvance = vm.step
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(88.dp)) {
                if (vm.step > 0) BackButton({ vm.back() })
            }
            StepDots(vm.step, vm.stepCount, Modifier.weight(1f))
            Spacer(Modifier.width(88.dp))
        }

        AnimatedContent(
            targetState = vm.step,
            transitionSpec = {
                val dir = if (vm.forward) 1 else -1
                if (reduced) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                else (slideInHorizontally(Springs.default(IntOffset(1, 1))) { it * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(Springs.default(IntOffset(1, 1))) { -it * dir / 3 } + fadeOut(tween(160)))
            },
            modifier = Modifier.weight(1f),
            label = "onboardingStep",
        ) { step ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                when (step) {
                    0 -> ChoiceStep("Who are you?", "Masteria shapes every world around you.", learnerTypes, vm.learnerType) {
                        pick { vm.learnerType = it }
                    }
                    1 -> ChoiceStep("What's your goal?", "We'll prioritise what gets you there.", goals, vm.goal) {
                        pick { vm.goal = it }
                    }
                    2 -> ChoiceStep("Where are you now?", "A starting guess. The diagnostic sharpens it.", levels, vm.level) {
                        pick { vm.level = it }
                    }
                    3 -> ChoiceStep("How much time a day?", "Quests are 5 questions, about 4 minutes each.", times, vm.minutes) {
                        pick { vm.minutes = it }
                    }
                    4 -> WorldStep(vm) { haptics.selection(); vm.trackId = it }
                    5 -> AgeStep(vm, onPick = { haptics.selection() })
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        if (vm.saveError != null) {
            Text(
                vm.saveError.orEmpty(), style = Masteria.type.footnote, color = c.danger,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.padding(16.dp)) {
            MButton(
                text = if (vm.step == vm.stepCount - 1) "Start my diagnostic" else "Continue",
                onClick = {
                    if (vm.step == vm.stepCount - 1) vm.finish(onDone) else vm.next()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.canContinue(),
                loading = vm.saving,
            )
        }
    }
}

@Composable
private fun StepDots(step: Int, count: Int, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(count) { i ->
            val w by animateFloatAsState(if (i == step) 22f else 7f, Springs.snappy(), label = "dot")
            val col by animateColorAsState(if (i <= step) c.brand else c.fillStrong, label = "dotColor")
            Box(
                Modifier
                    .height(7.dp)
                    .width(w.dp)
                    .clip(CircleShape)
                    .background(col)
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    val c = Masteria.colors
    Spacer(Modifier.height(16.dp))
    Text(title, style = Masteria.type.largeTitle, color = c.label, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = Masteria.type.body, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ChoiceStep(
    title: String, subtitle: String, choices: List<Choice>, selected: String?, onSelect: (String) -> Unit,
) {
    StepTitle(title, subtitle)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.forEach { ch ->
            ChoiceCard(ch.title, ch.subtitle, ch.icon, selected == ch.id) { onSelect(ch.id) }
        }
    }
}

@Composable
fun ChoiceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    tint: Color = Masteria.colors.brand,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val c = Masteria.colors
    val border by animateColorAsState(if (selected) tint else Color.Transparent, Springs.snappy(), label = "border")
    val check by animateFloatAsState(if (selected) 1f else 0f, Springs.bouncy(), label = "check")
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scale = 0.98f, haptic = false, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .clip(RoundedCornerShape(18.dp))
            .background(c.card)
            .border(2.dp, border, RoundedCornerShape(18.dp))
            .heightIn(min = 72.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Masteria.type.headline, color = c.label, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Pill(badge, c.success)
                }
            }
            Text(subtitle, style = Masteria.type.subhead, color = c.secondaryLabel)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (selected) tint else c.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check, null, tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { scaleX = check; scaleY = check; alpha = check },
            )
        }
    }
}

@Composable
private fun WorldStep(vm: OnboardingViewModel, onSelect: (String) -> Unit) {
    val c = Masteria.colors
    StepTitle("Pick your world", "One engine, different content. You can switch worlds any time.")
    when {
        vm.tracksError != null -> ErrorCard(vm.tracksError.orEmpty(), onRetry = { vm.loadTracks() }, offline = true)
        vm.tracks.isEmpty() -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ActivitySpinner() }
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val tints = listOf(c.brand, c.xp, c.info, c.success, c.boss)
            vm.orderedTracks().forEachIndexed { i, t ->
                ChoiceCard(
                    title = t.name,
                    subtitle = "${t.world} · ${t.subject}\n${t.description}",
                    icon = symbol(t.icon),
                    selected = vm.trackId == t.id,
                    tint = tints[i % tints.size],
                    badge = if (vm.learnerType != null && vm.learnerType in t.recommendedFor) "For you" else null,
                ) { onSelect(t.id) }
            }
        }
    }
}

@Composable
private fun AgeStep(vm: OnboardingViewModel, onPick: () -> Unit) {
    val c = Masteria.colors
    StepTitle("One last thing", "We collect only what's needed to personalise your learning.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceCard("I'm 18 or older", "Continue on my own", Icons.Rounded.Rocket, vm.isMinor == false) {
            onPick(); vm.isMinor = false
        }
        ChoiceCard("I'm under 18", "A parent or guardian will confirm", Icons.Rounded.FamilyRestroom, vm.isMinor == true, tint = c.info) {
            onPick(); vm.isMinor = true
        }
    }
    if (vm.isMinor == true) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.card)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Parent or guardian consent", style = Masteria.type.headline, color = c.label)
                Spacer(Modifier.height(4.dp))
                Text(
                    "I'm the parent or guardian and I agree to Masteria storing this learner's progress to personalise quests. " +
                        "The account and all its data can be deleted at any time from Settings.",
                    style = Masteria.type.footnote, color = c.secondaryLabel,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = vm.consent,
                onCheckedChange = { onPick(); vm.consent = it },
                colors = SwitchDefaults.colors(checkedTrackColor = c.success, checkedThumbColor = Color.White),
            )
        }
    }
}
