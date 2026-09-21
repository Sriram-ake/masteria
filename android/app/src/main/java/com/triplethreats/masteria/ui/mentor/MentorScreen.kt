package com.triplethreats.masteria.ui.mentor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.GlassEdge
import com.triplethreats.masteria.ui.components.GlassSurface
import com.triplethreats.masteria.ui.components.NavBarHeight
import com.triplethreats.masteria.ui.components.TabBarHeight
import com.triplethreats.masteria.ui.components.glassSource
import com.triplethreats.masteria.ui.components.pressable
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.rememberHaptics

class MentorViewModel(container: AppContainer) : ViewModel() {
    val chat = MentorChat(container.api, viewModelScope)
    override fun onCleared() {
        chat.cancel()
    }
}

private val suggestions = listOf(
    "Explain my weakest topic simply",
    "Give me one practice question",
    "How do I solve 3x + 5 = 20?",
    "Make me a plan for this week",
)

@Composable
fun MentorScreen() {
    val vm = appViewModel { MentorViewModel(it) }
    MentorConversation(
        chat = vm.chat,
        title = "Mentor",
        bottomChrome = TabBarHeight,
        emptyTitle = "Your mentor",
        emptyBody = "Ask anything about what you're learning. It explains at your level and nudges you to the answer instead of handing it over.",
        suggestions = suggestions,
    )
}

/** Shared conversation UI: the Mentor tab and the in-quest "Ask mentor" sheet both use it. */
@Composable
fun MentorConversation(
    chat: MentorChat,
    title: String?,
    bottomChrome: Dp,
    emptyTitle: String,
    emptyBody: String,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    topInset: Boolean = true,
) {
    val c = Masteria.colors
    val density = LocalDensity.current
    val status = if (topInset) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 0.dp
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // Sit on the tab bar when the keyboard is down, on the keyboard when it's up.
    val bottom = maxOf(ime, nav + bottomChrome)
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val haptics = rememberHaptics()

    val lastText = chat.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(chat.messages.size, lastText) {
        if (chat.messages.isNotEmpty()) list.animateScrollToItem(chat.messages.size)
    }

    fun send(text: String) {
        haptics.tap()
        chat.send(text)
        input = ""
    }

    Box(
        modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .glassSource(),
            contentPadding = PaddingValues(
                top = status + (if (title != null) NavBarHeight else 0.dp) + 12.dp,
                bottom = bottom + 76.dp,
                start = 12.dp, end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "intro") {
                if (chat.messages.isEmpty()) EmptyMentor(emptyTitle, emptyBody, suggestions, ::send)
            }
            items(chat.messages, key = { it.id }) { m ->
                Bubble(m, onRetry = { chat.retryLast() })
            }
        }

        if (title != null) {
            GlassSurface(
                Modifier
                    .fillMaxWidth()
                    .height(status + NavBarHeight),
                edge = GlassEdge.Bottom,
            ) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .height(NavBarHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = c.brand, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, style = Masteria.type.headline, color = c.label)
                        Text("NVIDIA NIM · guides, doesn't tell", style = Masteria.type.caption2, color = c.secondaryLabel)
                    }
                }
            }
        }

        // Composer
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottom)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            GlassSurface(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = 8.dp,
            ) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                    if (input.isEmpty()) Text("Ask your mentor", style = Masteria.type.body, color = c.tertiaryLabel)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = Masteria.type.body.copy(color = c.label),
                        cursorBrush = SolidColor(c.brand),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !chat.busy) send(input) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val canSend = input.isNotBlank()
            Box(
                Modifier
                    .size(48.dp)
                    .pressable(scale = 0.9f, haptic = false) {
                        if (chat.busy && !canSend) chat.cancel() else if (canSend) send(input)
                    }
                    .semantics { contentDescription = if (chat.busy && !canSend) "Stop reply" else "Send" }
                    .clip(CircleShape)
                    .background(if (canSend || chat.busy) c.brand else c.fillStrong),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (chat.busy && !canSend) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    null, tint = Color.White, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyMentor(title: String, body: String, suggestions: List<String>, onPick: (String) -> Unit) {
    val c = Masteria.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8A7DFF), Color(0xFFE0245E)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = Masteria.type.title2, color = c.label)
        Spacer(Modifier.height(6.dp))
        Text(body, style = Masteria.type.subhead, color = c.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            suggestions.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(scale = 0.98f) { onPick(s) }
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.card)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s, style = Masteria.type.callout, color = c.label, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.brand, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onRetry: () -> Unit) {
    val c = Masteria.colors
    val shape = if (m.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        // New bubbles grow out of their tail corner.
        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
            enter = fadeIn() + scaleIn(initialScale = 0.92f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (m.fromUser) 1f else 0f, 1f)),
            exit = fadeOut(),
        ) {
            Column(
                Modifier
                    .widthIn(max = 310.dp)
                    .clip(shape)
                    .background(
                        when {
                            m.fromUser -> c.brand
                            m.failed -> c.dangerSoft
                            else -> c.card
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (!m.fromUser && m.streaming && m.text.isEmpty()) {
                    TypingDots()
                } else {
                    Text(
                        if (m.fromUser) androidx.compose.ui.text.AnnotatedString(m.text) else mentorText(m.text),
                        style = Masteria.type.body,
                        color = when {
                            m.fromUser -> Color.White
                            m.failed -> c.danger
                            else -> c.label
                        },
                    )
                }
                if (m.failed) {
                    Row(
                        Modifier
                            .padding(top = 8.dp)
                            .pressable(onClick = onRetry),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = c.brand, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Try again", style = Masteria.type.subhead, color = c.brand)
                    }
                }
            }
        }
    }
}

@Composable
fun TypingDots() {
    val c = Masteria.colors
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .height(22.dp)
            .semantics { contentDescription = "Mentor is typing" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(520, delayMillis = i * 140), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = a; scaleX = 0.8f + 0.2f * a; scaleY = 0.8f + 0.2f * a }
                    .clip(CircleShape)
                    .background(c.secondaryLabel)
            )
        }
    }
}
