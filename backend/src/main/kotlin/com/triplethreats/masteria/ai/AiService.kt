package com.triplethreats.masteria.ai

import com.triplethreats.masteria.ApiException
import com.triplethreats.masteria.AppConfig
import com.triplethreats.masteria.badRequest
import com.triplethreats.masteria.content.QView
import com.triplethreats.masteria.db.UserRecord
import com.triplethreats.masteria.learner.Levels
import com.triplethreats.masteria.learner.SkillTree
import com.triplethreats.masteria.notFound
import com.triplethreats.masteria.routes.AiStatusDto
import com.triplethreats.masteria.routes.ExplainRequest
import com.triplethreats.masteria.routes.ExplainResponse
import com.triplethreats.masteria.routes.MentorRequest
import com.triplethreats.masteria.routes.QuestSessionDto
import com.triplethreats.masteria.routes.ScanRequest
import com.triplethreats.masteria.service.GameService
import com.triplethreats.masteria.unprocessable
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID

/** How a mentor reply ended, for the route to emit the closing event. */
sealed interface MentorEnd {
    data object Ok : MentorEnd
    data object Fallback : MentorEnd
    data class Error(val message: String) : MentorEnd
}

class AiService(
    private val config: AppConfig,
    private val nim: NimClient,
    private val pipeline: QuestionPipeline,
    private val game: GameService,
) {
    private val log = LoggerFactory.getLogger(AiService::class.java)

    fun status() = AiStatusDto(nim.online, config.chatModel, config.visionModel, nim.lastLatencyMs, nim.keys.size, nim.keys.healthyCount())

    // ================================================================= mentor

    private suspend fun mentorSystemPrompt(user: UserRecord, topicId: String?, q: QView?): String {
        val track = game.trackFor(user)
        val views = game.topicSnapshot(user)
        val masteries = views.joinToString("\n") { v ->
            val flags = listOfNotNull(
                v.status,
                "weak".takeIf { v.weak },
                "boss ready".takeIf { v.bossReady },
            ).joinToString(", ")
            "- ${v.topic.name}: ${v.mastery}% ($flags)"
        }
        val weakest = views.filter { it.weak }.joinToString(", ") { it.topic.name }.ifEmpty { "none right now" }
        val level = Levels.xpLevel(user.xp)
        val focusTopic = (q?.topicId ?: topicId)?.let { game.topicName(it) }
        val school = user.learnerType == "school" || track.world == "School"
        return buildString {
            appendLine("You are the Masteria mentor, a warm and encouraging tutor inside an adaptive learning RPG.")
            appendLine("Learner: ${user.name}. World: ${track.world}. Track: ${track.name} (${track.subject}).")
            appendLine("Learner type: ${user.learnerType ?: "unknown"}. Self-rated level: ${user.selfLevel ?: "unknown"}. Game level: $level.")
            appendLine("Real mastery per topic from the learner model (0-100). Never invent or change these numbers:")
            appendLine(masteries)
            appendLine("Weakest topic(s): $weakest. Boss battles open at ${SkillTree.BOSS_THRESHOLD}% mastery.")
            if (focusTopic != null) appendLine("The learner is asking about: $focusTopic.")
            appendLine()
            appendLine("Rules:")
            appendLine("- Guide, don't give away answers. Ask a guiding question or give the next step, not the final answer.")
            if (school) appendLine("- This is a school learner: use short, simple sentences and show ONE worked example of a SIMILAR problem (different numbers), never the learner's own question.")
            else appendLine("- Explain at the learner's level; use one short example when it helps.")
            appendLine("- Keep replies under 120 words unless the learner asks for more.")
            appendLine("- Plain text with simple line breaks. No markdown tables, no LaTeX, no headings. Write maths inline like 3x + 5 = 20.")
            appendLine("- If asked what to study next, point to the weakest topic using the real mastery above.")
            if (q != null) {
                appendLine()
                appendLine("The learner is working on this question:")
                appendLine(q.question)
                q.code?.let { appendLine("Code:\n$it") }
                if (q.type == "mcq") q.options.forEachIndexed { i, o -> appendLine("${'A' + i}) $o") }
                appendLine("FOR YOUR EYES ONLY — never state this outright, lead the learner to it:")
                appendLine("Correct answer: ${q.correctText}. Explanation: ${q.explanation}")
            }
        }
    }

    /**
     * Streams a mentor reply through [emit] (visible text pieces). Returns how it ended; when NIM is
     * unreachable before anything was sent, a deterministic fallback built from vetted content is streamed.
     */
    suspend fun mentor(user: UserRecord, req: MentorRequest, emit: suspend (text: String, source: String?) -> Unit): MentorEnd {
        val q = req.questionId?.let { game.bank.get(it) }
        val history = req.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { it.content.isNotBlank() }
            .takeLast(12)
            .map { NimMessage(it.role, it.content.take(4000)) }
        val system = mentorSystemPrompt(user, req.topicId, q)
        val outcome = nim.stream(
            listOf(NimMessage("system", system)) + history,
            ChatOptions(nim.chatChain(), temperature = 0.6, maxTokens = 700, timeoutMs = 90_000),
            firstByteTimeoutMs = 20_000,
            onDelta = { emit(it, null) },
        )
        return when (outcome) {
            is StreamOutcome.Completed -> MentorEnd.Ok
            is StreamOutcome.FailedMidStream -> MentorEnd.Error(outcome.reason)
            is StreamOutcome.FailedBeforeStart -> {
                log.info("Mentor falling back to vetted content ({})", outcome.reason)
                for (chunk in chunks(fallbackReply(user, req, q))) emit(chunk, "fallback")
                MentorEnd.Fallback
            }
        }
    }

    private fun chunks(text: String): List<String> =
        Regex("\\S+\\s*").findAll(text).map { it.value }.chunked(4).map { it.joinToString("") }.toList()

    private suspend fun fallbackReply(user: UserRecord, req: MentorRequest, q: QView?): String {
        val topicId = q?.topicId ?: req.topicId ?: game.topicSnapshot(user).firstOrNull { it.weak }?.topic?.id
        val topicName = topicId?.let { game.topicName(it) }
        val example = topicId?.let { id ->
            game.bank.usableForTopic(id).filter { it.id != q?.id }.minByOrNull { it.difficulty }
        }
        return buildString {
            append("I can't reach my AI brain right now, but here's a nudge from the question bank.\n\n")
            if (q != null) {
                append("Hint: ${q.hint}\n\n")
            } else if (topicName != null) {
                val mastery = game.topicSnapshot(user).firstOrNull { it.topic.id == topicId }?.mastery
                append("Let's work on $topicName${mastery?.let { " (you're at $it%)" } ?: ""}.\n\n")
            }
            if (example != null) {
                append("A similar example: ${example.question}\n")
                if (example.type == "mcq") append("Answer: ${example.correctText}. ")
                else append("Answer: ${example.answerText}. ")
                append("${example.explanation}\n\n")
            }
            append("Try the same steps on your question, then ask me again in a moment.")
        }
    }

    // ================================================================= explain

    suspend fun explain(user: UserRecord, req: ExplainRequest): ExplainResponse {
        val q = game.bank.get(req.questionId) ?: notFound("Question not found.")
        val given = when {
            q.type == "mcq" && req.answerIndex != null -> q.options.getOrNull(req.answerIndex) ?: "(no valid option)"
            req.answerText != null -> req.answerText
            else -> "(no answer)"
        }
        val correct = q.correctText ?: ""
        val prompt = buildString {
            appendLine("Question: ${q.question}")
            q.code?.let { appendLine("Code:\n$it") }
            if (q.type == "mcq") q.options.forEachIndexed { i, o -> appendLine("${'A' + i}) $o") }
            appendLine("Learner's answer: $given")
            appendLine("Correct answer: $correct")
            appendLine("Reference explanation: ${q.explanation}")
            appendLine()
            append("In 2-3 short sentences: name the most likely misconception behind the learner's answer, then give one nudge that helps them next time. " +
                "Speak to the learner as \"you\". Plain text only, no markdown, no LaTeX.")
        }
        val reply = nim.chat(
            listOf(NimMessage("system", "You are a kind, precise tutor who diagnoses mistakes."), NimMessage("user", prompt)),
            ChatOptions(nim.chatChain(), temperature = 0.3, maxTokens = 300, timeoutMs = 15_000, deadlineAt = System.currentTimeMillis() + 15_000),
        )
        val text = reply?.text?.trim()?.takeIf { it.length >= 10 }
        return if (text != null) ExplainResponse(text.take(900), "nim")
        else ExplainResponse(q.explanation.ifBlank { "The correct answer is $correct." }, "fallback")
    }

    // ================================================================= scan-to-quest

    suspend fun scan(user: UserRecord, req: ScanRequest): QuestSessionDto {
        val deadline = System.currentTimeMillis() + 58_000
        var text = req.text?.trim().orEmpty()
        if (text.isEmpty()) {
            val image = req.imageBase64?.trim().orEmpty()
            if (image.isEmpty()) badRequest("Send the page text or a photo of it.")
            text = transcribe(image, deadline)
                ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "Couldn't read that photo. Try a clearer, well-lit shot of the page.")
        }
        text = text.replace(Regex("[ \\t]+"), " ").trim()
        if (text.length < 40) unprocessable("That's too little text to build a quest from. Scan a full paragraph or page.")
        if (text.length > 6000) text = text.take(6000)
        if (!nim.enabled) throw ApiException(HttpStatusCode.ServiceUnavailable, "Scan-to-Quest needs the AI, which isn't configured on this server.")

        val build = pipeline.buildFromText(text, deadline)
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "The AI is busy right now. Please try the scan again in a moment.")
        log.info("Scan: '{}' generated {}, {} survived validation", build.topicName, build.generated, build.questions.size)
        if (build.questions.size < 3) unprocessable("Couldn't build a reliable quest from that page. Try a clearer photo.")

        val topicId = "scan." + UUID.randomUUID().toString().replace("-", "").take(8)
        val stored = build.questions.sortedBy { it.difficulty }.map { pipeline.toStored(it, topicId, build.topicName, user.id) }
        stored.forEach { game.bank.addGenerated(it) }
        return game.startScanQuest(user, topicId, build.topicName, stored.map { it.id })
    }

    private suspend fun transcribe(imageBase64: String, deadline: Long): String? {
        val raw = imageBase64.substringAfter("base64,", imageBase64).replace(Regex("\\s"), "")
        val bytes = runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: badRequest("imageBase64 is not valid base64.")
        if (bytes.size > 1_500_000) throw ApiException(HttpStatusCode.PayloadTooLarge, "That photo is too large. Please send a JPEG under 1 MB.")
        val mime = if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
        val dataUrl = "data:$mime;base64,$raw"
        val reply = nim.chat(
            listOf(NimMessage("user",
                "Transcribe all readable text on this page exactly, in reading order. Include headings, equations and labels. " +
                    "Output only the transcribed text, no commentary.", dataUrl)),
            ChatOptions(nim.visionChain(), temperature = 0.0, maxTokens = 1500, timeoutMs = 35_000, deadlineAt = deadline - 20_000),
        )
        return reply?.text?.trim()?.takeIf { it.isNotEmpty() }
    }
}
