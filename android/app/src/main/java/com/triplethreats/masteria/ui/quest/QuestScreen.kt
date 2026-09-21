package com.triplethreats.masteria.ui.quest

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.ActivitySpinner
import com.triplethreats.masteria.ui.components.AnimatedNumber
import com.triplethreats.masteria.ui.components.BottomSheet
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.CircleGlassButton
import com.triplethreats.masteria.ui.components.ErrorCard
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.components.Pill
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.mentor.MentorConversation
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.forMastery
import com.triplethreats.masteria.ui.theme.rememberHaptics

@Composable
fun QuestScreen(
    topicId: String?,
    boss: Boolean,
    pending: Boolean,
    onClose: () -> Unit,
    onNextQuest: (topicId: String?, boss: Boolean) -> Unit,
) {
    val vm = appViewModel(key = "quest-$topicId-$boss-$pending") { QuestViewModel(it, topicId, boss, pending) }
    val c = Masteria.colors
    val isBoss = vm.session?.isBoss == true || boss
    var confirmLeave by remember { mutableStateOf(false) }

    val midQuest = vm.phase == QuestPhase.Playing && (vm.index > 0 || vm.result != null)
    BackHandler(enabled = midQuest) { confirmLeave = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (isBoss && vm.phase !is QuestPhase.Summary) bossBackground() else c.background)
    ) {
        AnimatedContent(
            targetState = vm.phase::class,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "questPhase",
        ) { _ ->
            when (val p = vm.phase) {
                QuestPhase.Loading -> CenterStatus(if (boss) "Summoning the boss…" else "Building your quest…")
                QuestPhase.Completing -> CenterStatus("Tallying your XP…")
                QuestPhase.BossIntro -> BossIntro(vm, onClose)
                QuestPhase.Playing -> Playing(vm, onClose = { if (midQuest) confirmLeave = true else onClose() })
                is QuestPhase.Summary -> QuestSummaryView(p.summary, onDone = onClose, onNext = { onNextQuest(null, false) })
                is QuestPhase.Failed -> Box(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ErrorCard(p.message, onRetry = { vm.start() }, offline = p.offline)
                        Spacer(Modifier.height(12.dp))
                        MButton("Close", onClose, style = ButtonStyle.Plain)
                    }
                }
            }
        }

        // "Ask mentor" about this question, without leaving the quest.
        BottomSheet(visible = vm.mentorOpen && vm.mentor != null, onDismiss = { vm.mentorOpen = false }) {
            vm.mentor?.let { chat ->
                Box(Modifier.fillMaxHeight(0.78f)) {
                    MentorConversation(
                        chat = chat,
                        title = null,
                        bottomChrome = 0.dp,
                        emptyTitle = "Stuck on this one?",
                        emptyBody = "Your mentor can see this question. It will guide you to the idea, not just give you the answer.",
                        suggestions = listOf("Give me a hint", "Explain the idea behind this", "Where did I go wrong?"),
                        topInset = false,
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("End this quest?", style = Masteria.type.headline) },
            text = {
                Text(
                    "Mastery from the questions you've answered is already saved. XP is only awarded when you finish.",
                    style = Masteria.type.subhead,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onClose() }) {
                    Text("End quest", color = c.danger, style = Masteria.type.headline)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text("Keep going", color = c.brand, style = Masteria.type.body)
                }
            },
            containerColor = c.card,
            shape = RoundedCornerShape(22.dp),
        )
    }
}

@Composable
private fun bossBackground(): Color = if (Masteria.colors.isDark) Color(0xFF12060C) else Color(0xFFFFF1F4)

@Composable
private fun CenterStatus(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ActivitySpinner(size = 28.dp)
            Spacer(Modifier.height(14.dp))
            Text(text, style = Masteria.type.headline, color = Masteria.colors.secondaryLabel)
        }
    }
}

@Composable
private fun Playing(vm: QuestViewModel, onClose: () -> Unit) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val reduced = Masteria.reducedMotion
    val s = vm.session ?: return
    val q = vm.question ?: return
    val r = vm.result

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar: close, segmented progress, live mastery
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleGlassButton(Icons.Rounded.Close, "Close quest", onClick = onClose)
            Row(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(s.total) { i ->
                    val done = i < vm.index || (i == vm.index && r != null)
                    val color by animateColorAsState(
                        when {
                            done -> if (s.isBoss) c.boss else c.brand
                            i == vm.index -> (if (s.isBoss) c.boss else c.brand).copy(alpha = 0.35f)
                            else -> c.fillStrong
                        },
                        Springs.default(), label = "seg",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
            MasteryChip(s.topicName, vm.mastery)
            Spacer(Modifier.width(8.dp))
        }

        Text(
            s.title,
            style = Masteria.type.footnote,
            color = if (s.isBoss) c.boss else c.secondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        AnimatedContent(
            targetState = q.id,
            transitionSpec = {
                if (reduced) fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                else (slideInHorizontally(Springs.default(IntOffset(1, 1))) { it } + fadeIn()) togetherWith
                    (slideOutHorizontally(Springs.default(IntOffset(1, 1))) { -it / 3 } + fadeOut(tween(140)))
            },
            modifier = Modifier.weight(1f),
            label = "question",
        ) { _ ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                QuestionBody(q, showTopic = s.isScan)
                Spacer(Modifier.height(16.dp))

                AnimatedVisibility(vm.hintShown, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    HintCallout(q.hint, auto = vm.hintFirst, modifier = Modifier.padding(bottom = 14.dp))
                }

                if (q.type == "numeric") {
                    val state = when {
                        r == null -> OptionState.Idle
                        r.correct -> OptionState.Correct
                        else -> OptionState.Wrong
                    }
                    NumericDisplay(vm.numeric, state, r?.correctText)
                    Spacer(Modifier.height(14.dp))
                    if (r == null) NumericKeypad(onKey = { vm.numeric = applyKey(vm.numeric, it) }, enabled = !vm.checking)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        q.options.forEachIndexed { i, text ->
                            val state = when {
                                r == null -> if (vm.selected == i) OptionState.Selected else OptionState.Idle
                                i == r.correctIndex -> OptionState.Correct
                                i == vm.selected -> OptionState.Wrong
                                else -> OptionState.Dimmed
                            }
                            OptionButton(i, text, state, enabled = r == null && !vm.checking) {
                                haptics.selection(); vm.selected = i
                            }
                        }
                    }
                }

                if (!vm.hintShown && r == null) {
                    Row(
                        Modifier
                            .padding(top = 16.dp)
                            .pressable { vm.showHint() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Lightbulb, null, tint = c.xp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Show a hint", style = Masteria.type.subhead, color = c.xp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        vm.answerError?.let {
            Text(it, style = Masteria.type.footnote, color = c.danger, modifier = Modifier.padding(horizontal = 20.dp))
        }

        // Bottom: "Check" until answered, then the feedback panel rises in its place.
        AnimatedContent(
            targetState = r != null,
            transitionSpec = {
                (slideInVertically(Springs.sheet(IntOffset(1, 1))) { it } + fadeIn()) togetherWith
                    (slideOutVertically(Springs.default(IntOffset(1, 1))) { it } + fadeOut(tween(120)))
            },
            label = "feedback",
        ) { answered ->
            if (!answered || r == null) {
                MButton(
                    "Check",
                    onClick = {
                        vm.check { correct -> if (correct) haptics.success() else haptics.error() }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = vm.canCheck(),
                    loading = vm.checking,
                    color = if (s.isBoss) c.boss else null,
                )
            } else {
                FeedbackPanel(vm)
            }
        }
    }
}

@Composable
private fun MasteryChip(topic: String, mastery: Int) {
    val c = Masteria.colors
    val color = c.forMastery(mastery)
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (c.isDark) 0.2f else 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$topic mastery $mastery percent"
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Psychology, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        AnimatedNumber(mastery, Masteria.type.headline, color, suffix = "%")
    }
}

@Composable
private fun FeedbackPanel(vm: QuestViewModel) {
    val c = Masteria.colors
    val r = vm.result ?: return
    val s = vm.session ?: return
    val good = r.correct
    val tone = if (good) c.success else c.danger
    val delta = r.masteryAfter - r.masteryBefore
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(if (good) c.successSoft else c.dangerSoft)
            .background(c.card.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (good) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, tint = tone, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (good) listOf("Correct!", "Nailed it", "Exactly right", "Spot on").random() else "Not quite",
                style = Masteria.type.title3, color = tone, modifier = Modifier.weight(1f),
            )
            Pill(
                (if (delta >= 0) "+$delta" else "$delta") + "% mastery",
                if (delta >= 0) c.success else c.warning,
                solid = false,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(r.explanation, style = Masteria.type.callout, color = c.label)

        val change = when (r.difficultyChange) {
            "up" -> "Levelling up the difficulty"
            "down" -> if (r.hintFirst) "Easing off a little, with a hint first" else "Easing off a little"
            else -> null
        }
        if (change != null && !r.done) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (r.difficultyChange == "up") Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                    null, tint = c.secondaryLabel, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("$change · accuracy ${r.rollingAccuracy}%", style = Masteria.type.footnote, color = c.secondaryLabel)
            }
        }

        if (!good) {
            Spacer(Modifier.height(10.dp))
            when {
                vm.analysis != null -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.card)
                        .padding(12.dp)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = c.brand, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(vm.analysis.orEmpty(), style = Masteria.type.subhead, color = c.label)
                }
                vm.analysisLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    ActivitySpinner(size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Analysing your answer…", style = Masteria.type.subhead, color = c.secondaryLabel)
                }
                else -> Row(
                    Modifier.pressable { vm.explain() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = c.brand, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Why was I wrong?", style = Masteria.type.headline, color = c.brand)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .pressable { vm.report() }
                    .semantics { contentDescription = if (vm.reported) "Reported" else "Report this question" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Flag, null, tint = if (vm.reported) c.danger else c.tertiaryLabel, modifier = Modifier.size(22.dp))
            }
            MButton(
                "Ask mentor", { vm.requestMentor() }, Modifier.weight(1f),
                style = ButtonStyle.Tinted, icon = Icons.Rounded.AutoAwesome, height = 50.dp,
            )
            MButton(
                if (r.done) "Finish" else "Continue", { vm.next() }, Modifier.weight(1f),
                color = if (s.isBoss) c.boss else if (good) c.success else c.brand, height = 50.dp,
            )
        }
    }
}
