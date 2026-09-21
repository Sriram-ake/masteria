package com.triplethreats.masteria.ui.quest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.triplethreats.masteria.data.QuestionDto
import com.triplethreats.masteria.ui.components.Pill
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs
import com.triplethreats.masteria.ui.theme.rememberHaptics

enum class OptionState { Idle, Selected, Correct, Wrong, Dimmed }

fun difficultyLabel(d: Int) = when (d) {
    1 -> "Easy"; 2 -> "Medium"; else -> "Hard"
}

@Composable
fun DifficultyStars(difficulty: Int, color: Color = Masteria.colors.xp) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Difficulty: ${difficultyLabel(difficulty)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Icon(
                Icons.Rounded.Star, null,
                tint = if (i < difficulty) color else Masteria.colors.fillStrong,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(difficultyLabel(difficulty), style = Masteria.type.caption, color = Masteria.colors.secondaryLabel)
    }
}

@Composable
fun QuestionBody(question: QuestionDto, modifier: Modifier = Modifier, showTopic: Boolean = true) {
    val c = Masteria.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showTopic) {
                Pill(question.topicName, c.brand)
                Spacer(Modifier.width(10.dp))
            }
            DifficultyStars(question.difficulty)
            if (question.source == "nim") {
                Spacer(Modifier.width(10.dp))
                Pill("AI-generated · verified", c.info)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(question.question, style = Masteria.type.title2, color = c.label)
        question.code?.takeIf { it.isNotBlank() }?.let { code ->
            Spacer(Modifier.height(14.dp))
            CodeBlock(code)
        }
    }
}

@Composable
fun CodeBlock(code: String) {
    val c = Masteria.colors
    val bg = if (c.isDark) Color(0xFF111114) else Color(0xFF1C1C1E)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .horizontalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text(code, style = Masteria.type.mono, color = Color(0xFFE5E5EA), softWrap = false)
    }
}

@Composable
fun HintCallout(hint: String, auto: Boolean, modifier: Modifier = Modifier) {
    val c = Masteria.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.xpSoft)
            .padding(14.dp),
    ) {
        Icon(Icons.Rounded.Lightbulb, null, tint = c.xp, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (auto) "Hint first" else "Hint", style = Masteria.type.headline, color = c.label)
            if (auto) {
                Text(
                    "The last few were tough, so here's a nudge before you answer.",
                    style = Masteria.type.footnote, color = c.secondaryLabel,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(hint, style = Masteria.type.callout, color = c.label)
        }
    }
}

@Composable
fun OptionButton(
    index: Int,
    text: String,
    state: OptionState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = Masteria.colors
    val accent = when (state) {
        OptionState.Correct -> c.success
        OptionState.Wrong -> c.danger
        OptionState.Selected -> c.brand
        else -> c.separator
    }
    val border by animateColorAsState(
        if (state == OptionState.Idle || state == OptionState.Dimmed) Color.Transparent else accent,
        Springs.snappy(), label = "optBorder",
    )
    val fill by animateColorAsState(
        when (state) {
            OptionState.Correct -> c.successSoft
            OptionState.Wrong -> c.dangerSoft
            OptionState.Selected -> c.brandSoft
            else -> c.card
        },
        Springs.snappy(), label = "optFill",
    )
    val alpha by animateFloatAsState(if (state == OptionState.Dimmed) 0.45f else 1f, Springs.default(), label = "optAlpha")
    // A small physical "pop" when the verdict lands; a head-shake for wrong.
    val pop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val reduced = Masteria.reducedMotion
    LaunchedEffect(state) {
        if (reduced) return@LaunchedEffect
        when (state) {
            OptionState.Correct -> {
                pop.snapTo(0.96f); pop.animateTo(1f, Springs.bouncy())
            }
            OptionState.Wrong -> {
                shake.snapTo(0f)
                shake.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.25f, stiffness = 900f), initialVelocity = 900f)
            }
            else -> Unit
        }
    }
    val letter = ('A' + index).toString()
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = pop.value; scaleY = pop.value
                translationX = shake.value
            }
            .pressable(enabled = enabled, scale = 0.98f, haptic = false, role = Role.RadioButton, onClick = onClick)
            .semantics {
                selected = state == OptionState.Selected
                stateDescription = when (state) {
                    OptionState.Correct -> "Correct answer"
                    OptionState.Wrong -> "Your answer, incorrect"
                    OptionState.Selected -> "Selected"
                    else -> ""
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .border(2.dp, border, RoundedCornerShape(16.dp))
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        OptionState.Correct -> c.success
                        OptionState.Wrong -> c.danger
                        OptionState.Selected -> c.brand
                        else -> c.fill
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                OptionState.Correct -> Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                OptionState.Wrong -> Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                else -> Text(
                    letter, style = Masteria.type.headline,
                    color = if (state == OptionState.Selected) Color.White else c.secondaryLabel,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = Masteria.type.body, color = c.label, modifier = Modifier.weight(1f))
    }
}

/** The typed answer for numeric questions, displayed large above the keypad. */
@Composable
fun NumericDisplay(value: String, state: OptionState, correctText: String?) {
    val c = Masteria.colors
    val accent = when (state) {
        OptionState.Correct -> c.success
        OptionState.Wrong -> c.danger
        else -> c.brand
    }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.card)
                .border(2.dp, if (value.isEmpty() && state == OptionState.Idle) Color.Transparent else accent, RoundedCornerShape(18.dp))
                .heightIn(min = 72.dp)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                value.ifEmpty { "Your answer" },
                style = Masteria.type.title1,
                color = if (value.isEmpty()) c.tertiaryLabel else c.label,
            )
        }
        AnimatedVisibility(state == OptionState.Wrong && correctText != null, enter = fadeIn() + expandVertically()) {
            Text(
                "Correct answer: $correctText",
                style = Masteria.type.headline, color = c.success,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
        }
    }
}

/** A calculator-style keypad: no system keyboard jumping over the question. */
@Composable
fun NumericKeypad(onKey: (String) -> Unit, enabled: Boolean) {
    val c = Masteria.colors
    val haptics = rememberHaptics()
    val rows = listOf(
        listOf("1", "2", "3", "⌫"),
        listOf("4", "5", "6", "−"),
        listOf("7", "8", "9", "/"),
        listOf(".", "0", "00", "C"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val isAction = key in setOf("⌫", "−", "/", "C")
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(2.1f)
                            .pressable(enabled = enabled, scale = 0.94f, haptic = false) {
                                haptics.selection(); onKey(key)
                            }
                            .semantics {
                                contentDescription = when (key) {
                                    "⌫" -> "Delete"; "−" -> "Minus"; "/" -> "Fraction bar"; "C" -> "Clear"; "." -> "Decimal point"
                                    else -> key
                                }
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isAction) c.fillStrong else c.card),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") {
                            Icon(Icons.AutoMirrored.Rounded.Backspace, null, tint = c.label, modifier = Modifier.size(22.dp))
                        } else {
                            Text(key, style = Masteria.type.title2, color = c.label)
                        }
                    }
                }
            }
        }
    }
}

/** Applies a keypad key to the current numeric input. */
fun applyKey(current: String, key: String): String = when (key) {
    "⌫" -> current.dropLast(1)
    "C" -> ""
    "−" -> if (current.startsWith("-")) current.drop(1) else "-$current"
    "." -> if (current.substringAfterLast('/').contains('.')) current else current + (if (current.isEmpty() || current.endsWith("/") || current == "-") "0." else ".")
    "/" -> if (current.contains('/') || current.isEmpty() || current == "-") current else "$current/"
    else -> if (current.length >= 12) current else current + key
}
