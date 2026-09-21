package com.triplethreats.masteria.ui.mentor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.triplethreats.masteria.data.Api
import com.triplethreats.masteria.data.ChatMessageDto
import com.triplethreats.masteria.data.MentorEvent
import com.triplethreats.masteria.data.MentorRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val failed: Boolean = false,
)

/**
 * One mentor conversation. Replies stream in token by token from NVIDIA NIM (through the
 * backend); the latest reply can be cancelled by sending again or leaving.
 */
class MentorChat(
    private val api: Api,
    private val scope: CoroutineScope,
    private val topicId: String? = null,
    private val questionId: String? = null,
) {
    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var nextId = 1L

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        job?.cancel()
        finishStreaming()
        messages += ChatMessage(nextId++, fromUser = true, text = trimmed)
        val replyId = nextId++
        messages += ChatMessage(replyId, fromUser = false, text = "", streaming = true)
        busy = true
        val history = messages
            .filter { !it.streaming && !it.failed && it.text.isNotBlank() }
            .takeLast(12)
            .map { ChatMessageDto(if (it.fromUser) "user" else "assistant", it.text) }
        job = scope.launch {
            try {
                api.mentor(MentorRequest(history, topicId = topicId, questionId = questionId)).collect { event ->
                    when (event) {
                        is MentorEvent.Delta -> update(replyId) { it.copy(text = it.text + event.text) }
                        is MentorEvent.Failure -> update(replyId) {
                            if (it.text.isBlank()) it.copy(text = event.message, failed = true, streaming = false)
                            else it.copy(streaming = false)
                        }
                        MentorEvent.Done -> update(replyId) { it.copy(streaming = false, text = it.text.trim()) }
                    }
                }
            } finally {
                update(replyId) {
                    if (it.text.isBlank() && !it.failed) it.copy(text = "No reply this time. Try asking again.", failed = true, streaming = false)
                    else it.copy(streaming = false)
                }
                // A newer send may already own the conversation; only the latest reply clears busy.
                if (messages.lastOrNull()?.id == replyId) busy = false
            }
        }
    }

    fun retryLast() {
        val lastUser = messages.lastOrNull { it.fromUser } ?: return
        val idx = messages.indexOf(lastUser)
        while (messages.size > idx) messages.removeAt(messages.lastIndex)
        send(lastUser.text)
    }

    fun cancel() {
        job?.cancel()
        finishStreaming()
        busy = false
    }

    private fun finishStreaming() {
        messages.indices.forEach { i ->
            if (messages[i].streaming) messages[i] = messages[i].copy(streaming = false)
        }
    }

    private fun update(id: Long, transform: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = transform(messages[i])
    }
}

/**
 * The mentor is asked for plain text, but models sometimes add light markdown anyway:
 * render **bold**, drop stray markers and heading hashes rather than showing them.
 */
fun mentorText(raw: String): AnnotatedString = buildAnnotatedString {
    val cleaned = raw.lines().joinToString("\n") { line ->
        line.replace(Regex("^#{1,6}\\s*"), "").replace(Regex("^\\s*[*-]\\s+"), "• ")
    }
    var i = 0
    while (i < cleaned.length) {
        val start = cleaned.indexOf("**", i)
        if (start < 0) {
            append(cleaned.substring(i)); break
        }
        val end = cleaned.indexOf("**", start + 2)
        if (end < 0) {
            append(cleaned.substring(i).replace("**", "")); break
        }
        append(cleaned.substring(i, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(cleaned.substring(start + 2, end)) }
        i = end + 2
    }
}
