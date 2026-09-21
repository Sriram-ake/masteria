package com.triplethreats.masteria.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.AnswerDto
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.DiagnosticDto
import com.triplethreats.masteria.data.DiagnosticResult
import com.triplethreats.masteria.data.DiagnosticSubmit
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.ActivitySpinner
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.Card
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.IconTile
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.Pill
import com.triplethreats.masteria.ui.components.ProgressBar
import com.triplethreats.masteria.ui.quest.NumericDisplay
import com.triplethreats.masteria.ui.quest.NumericKeypad
import com.triplethreats.masteria.ui.quest.OptionButton
import com.triplethreats.masteria.ui.quest.OptionState
import com.triplethreats.masteria.ui.quest.QuestionBody
import com.triplethreats.masteria.ui.quest.applyKey
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.forMastery
import com.triplethreats.masteria.ui.theme.masteryWord
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DiagnosticViewModel(private val container: AppContainer) : ViewModel() {
    var diagnostic by mutableStateOf<DiagnosticDto?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<Int?>(null)
    var numeric by mutableStateOf("")
    val answers = mutableStateListOf<AnswerDto>()
    var submitting by mutableStateOf(false)
        private set
    var result by mutableStateOf<DiagnosticResult?>(null)
        private set
    var submitError by mutableStateOf<String?>(null)
        private set
    private var shownAt by mutableLongStateOf(System.currentTimeMillis())

    init {
        load()
    }

    fun load() {
        loadError = null
        viewModelScope.launch {
            try {
                diagnostic = container.api.diagnostic(container.session.user.value?.trackId)
                shownAt = System.currentTimeMillis()
            } catch (e: ApiException) {
                loadError = e.message
            }
        }
    }

    fun canAnswer(): Boolean {
        val q = diagnostic?.questions?.getOrNull(index) ?: return false
        return if (q.type == "numeric") numeric.isNotBlank() && numeric != "-" else selected != null
    }

    /** Records the answer (or a skip) and moves on; after the last one, submits everything. */
    fun next(skip: Boolean) {
        val d = diagnostic ?: return
        val q = d.questions[index]
        answers += AnswerDto(
            questionId = q.id,
            answerIndex = if (skip || q.type == "numeric") null else selected,
            answerText = if (skip || q.type != "numeric") null else numeric,
            timeMs = System.currentTimeMillis() - shownAt,
        )
        selected = null; numeric = ""
        shownAt = System.currentTimeMillis()
        if (index < d.questions.lastIndex) index++ else submit()
    }

    fun submit() {
        val d = diagnostic ?: return
        submitting = true; submitError = null
        viewModelScope.launch {
            try {
                result = container.api.submitDiagnostic(DiagnosticSubmit(d.trackId, answers.toList()))
                runCatching { container.session.setUser(container.api.me()) }
                container.invalidate()
            } catch (e: ApiException) {
                submitError = e.message
            } finally {
                submitting = false
            }
        }
    }
}

@Composable
fun DiagnosticScreen(onDone: () -> Unit) {
    val vm = appViewModel { DiagnosticViewModel(it) }
    val c = Masteria.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val d = vm.diagnostic
        when {
            vm.result != null -> DiagnosticResultView(vm.result!!, onDone)
            vm.loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorCard(vm.loadError.orEmpty(), onRetry = { vm.load() }, offline = true)
            }
            d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ActivitySpinner(size = 28.dp) }
            vm.submitting || vm.submitError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (vm.submitError != null) ErrorCard(vm.submitError.orEmpty(), onRetry = { vm.submit() })
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ActivitySpinner(size = 28.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Mapping what you know…", style = Masteria.type.headline, color = c.secondaryLabel)
                }
            }
            else -> DiagnosticQuestions(vm, d)
        }
    }
}

@Composable
private fun DiagnosticQuestions(vm: DiagnosticViewModel, d: DiagnosticDto) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val reduced = Masteria.reducedMotion
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TrackChanges, null, tint = c.brand, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Diagnostic", style = Masteria.type.headline, color = c.label, modifier = Modifier.weight(1f))
                Text("${vm.index + 1} of ${d.questions.size}", style = Masteria.type.subhead, color = c.secondaryLabel)
            }
            Spacer(Modifier.height(10.dp))
            ProgressBar((vm.index + 1f) / d.questions.size, c.brand, height = 6.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                "No XP here, and no pressure. Answer what you can and skip what you don't know.",
                style = Masteria.type.footnote, color = c.secondaryLabel,
            )
        }
        AnimatedContent(
            targetState = vm.index,
            transitionSpec = {
                if (reduced) fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                else (slideInHorizontally(Springs.default(IntOffset(1, 1))) { it } + fadeIn()) togetherWith
                    (slideOutHorizontally(Springs.default(IntOffset(1, 1))) { -it / 3 } + fadeOut(tween(150)))
            },
            modifier = Modifier.weight(1f),
            label = "diagQuestion",
        ) { i ->
            val q = d.questions[i]
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                QuestionBody(q)
                Spacer(Modifier.height(22.dp))
                if (q.type == "numeric") {
                    NumericDisplay(vm.numeric, OptionState.Idle, null)
                    Spacer(Modifier.height(14.dp))
                    NumericKeypad(onKey = { vm.numeric = applyKey(vm.numeric, it) }, enabled = true)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        q.options.forEachIndexed { oi, text ->
                            OptionButton(oi, text, if (vm.selected == oi) OptionState.Selected else OptionState.Idle, enabled = true) {
                                haptics.selection(); vm.selected = oi
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MButton("I don't know", { vm.next(skip = true) }, Modifier.weight(1f), style = ButtonStyle.Tinted, color = c.secondaryLabel)
            MButton(
                if (vm.index == d.questions.lastIndex) "Finish" else "Next",
                { vm.next(skip = false) },
                Modifier.weight(1f),
                enabled = vm.canAnswer(),
            )
        }
    }
}

@Composable
private fun DiagnosticResultView(result: DiagnosticResult, onDone: () -> Unit) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val reduced = Masteria.reducedMotion
    // Bars fill one after another, then the weak spot is called out.
    val reveal = remember { result.topics.map { Animatable(if (reduced) 1f else 0f) } }
    val headline = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        launch { headline.animateTo(1f, Springs.default()) }
        delay(300)
        reveal.forEachIndexed { i, a ->
            launch { a.animateTo(1f, Springs.progress()) }
            delay(140)
            if (i == reveal.lastIndex) haptics.success()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Column(Modifier.graphicsLayer { alpha = headline.value; translationY = (1 - headline.value) * 30f }) {
                Text("Your map is ready", style = Masteria.type.largeTitle, color = c.label)
                Spacer(Modifier.height(8.dp))
                Text(result.summary, style = Masteria.type.body, color = c.secondaryLabel)
                Spacer(Modifier.height(4.dp))
                Text("${result.correct} of ${result.total} correct", style = Masteria.type.footnote, color = c.tertiaryLabel)
            }
            Spacer(Modifier.height(24.dp))
            Card {
                result.topics.forEachIndexed { i, t ->
                    val p = reveal[i].value
                    val weak = t.topicId == result.weakestTopicId
                    Column(Modifier.graphicsLayer { alpha = 0.3f + 0.7f * p }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = when (t.status) {
                                "mastered" -> Icons.Rounded.CheckCircle
                                "locked" -> Icons.Rounded.Lock
                                else -> if (weak) Icons.Rounded.WarningAmber else Icons.Rounded.TrackChanges
                            }
                            val tint = when {
                                t.status == "locked" -> c.tertiaryLabel
                                weak -> c.warning
                                else -> c.forMastery(t.mastery)
                            }
                            IconTile(icon, tint, size = 34.dp, radius = 10.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.name, style = Masteria.type.headline, color = c.label)
                                    if (weak) {
                                        Spacer(Modifier.width(8.dp)); Pill("Weak spot", c.warning, solid = true)
                                    }
                                }
                                Text(
                                    if (t.status == "locked") "Locked · unlocks as you progress"
                                    else "${(t.mastery * p).toInt()}% · ${masteryWord(t.mastery)}",
                                    style = Masteria.type.footnote, color = c.secondaryLabel,
                                )
                            }
                        }
                        if (t.status != "locked") {
                            Spacer(Modifier.height(8.dp))
                            ProgressBar(t.mastery / 100f * p, c.forMastery(t.mastery), height = 6.dp)
                        }
                        if (i != result.topics.lastIndex) Spacer(Modifier.height(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        MButton("Enter the map", onDone, Modifier
            .fillMaxWidth()
            .padding(16.dp))
    }
}
