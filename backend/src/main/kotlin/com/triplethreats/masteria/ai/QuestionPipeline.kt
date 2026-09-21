package com.triplethreats.masteria.ai

import com.triplethreats.masteria.content.ContentRepository
import com.triplethreats.masteria.content.QuestionBank
import com.triplethreats.masteria.db.StoredQuestion
import com.triplethreats.masteria.learner.Answers
import com.triplethreats.masteria.learner.Elo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

data class ScanBuild(val topicName: String, val questions: List<GeneratedQuestion>, val generated: Int)

/**
 * Generation + validation pipeline (context.md §9.5):
 * generate JSON → (1) schema check → (2) independent solve by a second model without the key →
 * (3) Kotlin maths check for linear equations → (4) only survivors are stored as approved "nim" questions.
 */
class QuestionPipeline(
    private val nim: NimClient,
    private val bank: QuestionBank,
    private val content: ContentRepository,
) {
    private val log = LoggerFactory.getLogger(QuestionPipeline::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    val generatedCount = AtomicInteger()
    val rejectedCount = AtomicInteger()

    companion object {
        const val GEN_TIMEOUT_MS = 45_000L
        const val VERIFY_TIMEOUT_MS = 30_000L

        const val GEN_SYSTEM = "You write accurate practice questions for an adaptive learning app. " +
            "You output ONLY valid JSON, with no prose before or after it."

        fun rules(isJava: Boolean) = buildString {
            appendLine("Rules:")
            appendLine("- Exactly 4 distinct options and exactly one correct option. Never use \"all of the above\" or \"none of the above\".")
            appendLine("- Double-check every calculation. The keyed answer must be exactly right.")
            appendLine("- answerIndex is 0-based (0..3).")
            appendLine("- hint: one short nudge that does NOT reveal the answer.")
            appendLine("- explanation: 1-2 sentences showing the key step.")
            appendLine("- Plain text only: no LaTeX, no markdown, no emojis. Write powers as x^2 and fractions as 3/4.")
            if (isJava) appendLine("- You may put a short Java snippet (max 12 lines) in \"code\"; otherwise use null.")
            else appendLine("- \"code\" must be null.")
        }
    }

    // ------------------------------------------------------------------ topic generation

    /** Launches at most one background top-up job per topic. Never blocks the caller. */
    fun topUpAsync(topicId: String, learnerRating: Double, count: Int = 4) {
        if (!nim.enabled || topicId.startsWith("scan.")) return
        if (!inFlight.add(topicId)) return
        scope.launch {
            try {
                val difficulty = Elo.difficultyFor(learnerRating)
                val stored = generateForTopic(topicId, difficulty, count)
                log.info("Top-up for {}: {} new approved question(s) at difficulty {}", topicId, stored.size, difficulty)
            } catch (e: Exception) {
                log.warn("Top-up for {} failed: {}", topicId, e.javaClass.simpleName)
            } finally {
                inFlight.remove(topicId)
            }
        }
    }

    fun isToppingUp(topicId: String) = topicId in inFlight

    suspend fun generateForTopic(topicId: String, difficulty: Int, count: Int, deadlineAt: Long? = null): List<StoredQuestion> {
        val topic = content.topic(topicId) ?: return emptyList()
        val track = content.trackOfTopic(topicId) ?: return emptyList()
        val isJava = topicId.startsWith("java.")
        val avoid = bank.existingTexts(topicId).shuffled().take(12)
        val prompt = buildString {
            appendLine("Write $count multiple-choice questions on the topic \"${topic.name}\" (${topic.description}) for learners studying ${track.subject}.")
            appendLine("Target difficulty: $difficulty (1 = easy, one step; 2 = medium, two or three steps; 3 = hard, multi-step or a common trap). Stay within ${difficulty - 1}..${difficulty + 1} but prefer $difficulty.")
            append(rules(isJava))
            if (avoid.isNotEmpty()) {
                appendLine("Do not repeat or trivially reword these existing questions:")
                avoid.forEach { appendLine("- ${it.take(160)}") }
            }
            appendLine()
            appendLine("Return ONLY this JSON:")
            appendLine("{\"questions\":[{\"topic\":\"$topicId\",\"difficulty\":$difficulty,\"type\":\"mcq\",\"question\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answerIndex\":0,\"explanation\":\"...\",\"hint\":\"...\",\"code\":null}]}")
        }
        val reply = nim.chat(
            listOf(NimMessage("system", GEN_SYSTEM), NimMessage("user", prompt)),
            ChatOptions(nim.chatChain(), temperature = 0.2, maxTokens = 3500, timeoutMs = GEN_TIMEOUT_MS, deadlineAt = deadlineAt),
        ) ?: return emptyList()
        val parsed = AiJson.parseQuestions(reply.text).map { it.copy(difficulty = it.difficulty.coerceIn(1, 3)) }
        val survivors = validateAll(parsed, context = null, deadlineAt = deadlineAt)
        val stored = mutableListOf<StoredQuestion>()
        for (q in survivors) {
            if (bank.isDuplicate(topicId, q.question)) continue
            val sq = toStored(q, topicId, topicName = null, owner = null)
            bank.addGenerated(sq)
            stored += sq
        }
        return stored
    }

    // ------------------------------------------------------------------ scan-to-quest

    suspend fun buildFromText(text: String, deadlineAt: Long): ScanBuild? {
        val prompt = buildString {
            appendLine("Here is text from a learner's textbook page or notes:")
            appendLine("\"\"\"")
            appendLine(text)
            appendLine("\"\"\"")
            appendLine()
            appendLine("1) Give a short topic name for it (2-5 words, e.g. \"Newton's First Law\").")
            appendLine("2) Write 6 multiple-choice questions that check understanding of THIS text: 3 at difficulty 1 (recall) and 3 at difficulty 2 (apply or reason). Each must be answerable from the text plus basic reasoning.")
            append(rules(isJava = false))
            appendLine()
            appendLine("Return ONLY this JSON:")
            appendLine("{\"topicName\":\"...\",\"questions\":[{\"difficulty\":1,\"type\":\"mcq\",\"question\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answerIndex\":0,\"explanation\":\"...\",\"hint\":\"...\",\"code\":null}]}")
        }
        val reply = nim.chat(
            listOf(NimMessage("system", GEN_SYSTEM), NimMessage("user", prompt)),
            ChatOptions(nim.chatChain(), temperature = 0.2, maxTokens = 3500, timeoutMs = GEN_TIMEOUT_MS, deadlineAt = deadlineAt),
        ) ?: return null
        val topicName = AiJson.topicName(reply.text)?.take(60) ?: "Your Notes"
        val parsed = AiJson.parseQuestions(reply.text)
            .filter { it.type == "mcq" }
            .map { it.copy(difficulty = it.difficulty.coerceIn(1, 2), code = null) }
        val survivors = validateAll(parsed, context = text, deadlineAt = deadlineAt)
        return ScanBuild(topicName, survivors.take(5), parsed.size)
    }

    fun toStored(q: GeneratedQuestion, topicId: String, topicName: String?, owner: String?): StoredQuestion {
        val shuffled = shuffleOptions(q)
        return StoredQuestion(
            id = "nim-" + UUID.randomUUID().toString().replace("-", "").take(12),
            topicId = topicId,
            topicName = topicName,
            type = shuffled.type,
            difficulty = shuffled.difficulty,
            question = shuffled.question,
            options = shuffled.options,
            answerIndex = shuffled.answerIndex,
            answerText = shuffled.answerText,
            explanation = shuffled.explanation,
            hint = shuffled.hint,
            code = shuffled.code,
            source = "nim",
            status = "approved",
            ownerUserId = owner,
        )
    }

    /** Models over-key option B; shuffle unless options refer to each other. */
    private fun shuffleOptions(q: GeneratedQuestion): GeneratedQuestion {
        if (q.type != "mcq" || q.answerIndex == null) return q
        if (q.options.any { Regex("(?i)\\b(both|neither|above|below)\\b").containsMatchIn(it) }) return q
        if (q.options.all { Answers.parseNumber(it) != null }) {
            // numeric options read best in ascending order
            val sorted = q.options.sortedBy { Answers.parseNumber(it)!! }
            return q.copy(options = sorted, answerIndex = sorted.indexOf(q.options[q.answerIndex]))
        }
        val order = q.options.indices.shuffled(Random(q.question.hashCode()))
        val opts = order.map { q.options[it] }
        return q.copy(options = opts, answerIndex = order.indexOf(q.answerIndex))
    }

    // ------------------------------------------------------------------ validation

    private suspend fun validateAll(items: List<GeneratedQuestion>, context: String?, deadlineAt: Long?): List<GeneratedQuestion> = coroutineScope {
        items.map { q -> async { q to validate(q, context, deadlineAt) } }.awaitAll()
            .filter { (q, reason) ->
                if (reason != null) {
                    rejectedCount.incrementAndGet()
                    log.info("Rejected generated question ({}): {}", reason, q.question.take(80))
                    false
                } else {
                    generatedCount.incrementAndGet()
                    true
                }
            }
            .map { it.first }
            .distinctBy { QuestionBank.normalize(it.question) }
    }

    /** Returns null if the question survives, else the rejection reason. */
    suspend fun validate(q: GeneratedQuestion, context: String?, deadlineAt: Long?): String? {
        QuestionValidator.schemaProblem(q)?.let { return "schema: $it" }
        if (QuestionValidator.linearCheck(q) == QuestionValidator.MathCheck.FAIL) return "maths check failed"
        return independentSolve(q, context, deadlineAt)
    }

    private suspend fun independentSolve(q: GeneratedQuestion, context: String?, deadlineAt: Long?): String? {
        val prompt = buildString {
            if (context != null) {
                appendLine("Reference text:")
                appendLine("\"\"\"")
                appendLine(context.take(4000))
                appendLine("\"\"\"")
                appendLine()
            }
            appendLine("Question: ${q.question}")
            if (q.code != null) {
                appendLine("Code:")
                appendLine(q.code)
            }
            if (q.type == "mcq") {
                appendLine("Options:")
                q.options.forEachIndexed { i, o -> appendLine("${i + 1}. $o") }
                appendLine()
                append("Solve it yourself carefully. Reply with ONLY the number (1-4) of the correct option.")
            } else {
                append("Solve it yourself carefully. Reply with ONLY the final number.")
            }
        }
        val reply = nim.chat(
            listOf(NimMessage("system", "You are a careful examiner who solves questions exactly."), NimMessage("user", prompt)),
            ChatOptions(nim.verifyChain(), temperature = 0.0, maxTokens = 2000, timeoutMs = VERIFY_TIMEOUT_MS, thinking = true, deadlineAt = deadlineAt),
        ) ?: return "verifier unavailable"
        return if (q.type == "mcq") {
            val choice = QuestionValidator.parseVerifierChoice(reply.text) ?: return "verifier reply unreadable"
            if (choice == q.answerIndex) null else "verifier chose ${choice + 1}, key was ${q.answerIndex!! + 1}"
        } else {
            val n = QuestionValidator.parseVerifierNumber(reply.text) ?: return "verifier reply unreadable"
            val key = Answers.parseNumber(q.answerText) ?: return "bad key"
            if (abs(n - key) <= 1e-6) null else "verifier got $n, key was $key"
        }
    }
}
