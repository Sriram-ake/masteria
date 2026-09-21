package com.triplethreats.masteria.content

import com.triplethreats.masteria.db.QuestionStat
import com.triplethreats.masteria.db.Store
import com.triplethreats.masteria.db.StoredQuestion
import com.triplethreats.masteria.learner.Adaptive
import com.triplethreats.masteria.learner.Candidate
import com.triplethreats.masteria.learner.Elo
import com.triplethreats.masteria.routes.QuestionDto
import java.util.concurrent.ConcurrentHashMap

/** A question from either source, with its live rating and flag count. */
data class QView(
    val id: String,
    val topicId: String,
    val topicName: String?,
    val type: String,
    val difficulty: Int,
    val question: String,
    val options: List<String>,
    val answerIndex: Int?,
    val answerText: String?,
    val explanation: String,
    val hint: String,
    val code: String?,
    val source: String,
    val rating: Double,
    val flags: Int,
) {
    fun toCandidate() = Candidate(id, difficulty, rating, flags)
    fun toDto(topicName: String) = QuestionDto(
        id = id, topicId = topicId, topicName = topicName, type = type, difficulty = difficulty,
        question = question, options = if (type == "mcq") options else emptyList(), hint = hint, source = source, code = code,
    )
    val correctText: String? get() = if (type == "mcq") answerIndex?.let { options.getOrNull(it) } else answerText
}

/** Vetted bank (from content/) merged with approved AI questions, cached in memory with write-through. */
class QuestionBank(private val content: ContentRepository, private val store: Store) {
    private val generated = ConcurrentHashMap<String, StoredQuestion>()
    private val stats = ConcurrentHashMap<String, QuestionStat>()

    suspend fun init() {
        store.listGeneratedQuestions().forEach { generated[it.id] = it }
        store.listQuestionStats().forEach { stats[it.questionId] = it }
    }

    fun get(id: String): QView? {
        content.vettedById[id]?.let { return view(it) }
        return generated[id]?.takeIf { it.status == "approved" }?.let { view(it) }
    }

    fun forTopic(topicId: String): List<QView> {
        val vetted = content.vettedByTopic[topicId].orEmpty().map { view(it) }
        val gen = generated.values.filter { it.topicId == topicId && it.status == "approved" }.map { view(it) }
        return vetted + gen
    }

    /** Usable (not pulled for review) questions on a topic. */
    fun usableForTopic(topicId: String) = forTopic(topicId).filter { it.flags < Adaptive.FLAG_LIMIT }

    fun generatedTopicName(topicId: String): String? =
        generated.values.firstOrNull { it.topicId == topicId && it.topicName != null }?.topicName

    private fun stat(id: String, difficulty: Int) = stats[id] ?: QuestionStat(id, Elo.baseQuestionRating(difficulty))

    private fun view(q: BankQuestion): QView {
        val s = stat(q.id, q.difficulty)
        return QView(q.id, q.topicId, null, q.type, q.difficulty, q.question, q.options, q.answerIndex, q.answerText,
            q.explanation, q.hint, q.code, "vetted", s.rating, s.flags)
    }

    private fun view(q: StoredQuestion): QView {
        val s = stat(q.id, q.difficulty)
        return QView(q.id, q.topicId, q.topicName, q.type, q.difficulty, q.question, q.options, q.answerIndex, q.answerText,
            q.explanation, q.hint, q.code, q.source, s.rating, s.flags)
    }

    suspend fun updateRating(q: QView, newRating: Double) {
        val s = stat(q.id, q.difficulty).copy(rating = newRating)
        stats[q.id] = s
        store.saveQuestionStat(s)
    }

    /** One flag per user per question; returns the current flag count. */
    suspend fun report(q: QView, userId: String, reason: String?): Int {
        val s = stat(q.id, q.difficulty)
        if (userId in s.reporters) return s.flags
        val updated = s.copy(
            flags = s.flags + 1,
            reporters = s.reporters + userId,
            reasons = (s.reasons + listOfNotNull(reason?.take(300)?.takeIf { it.isNotBlank() })).takeLast(20),
        )
        stats[q.id] = updated
        store.saveQuestionStat(updated)
        return updated.flags
    }

    suspend fun addGenerated(q: StoredQuestion) {
        generated[q.id] = q
        store.saveGeneratedQuestion(q)
    }

    fun existingTexts(topicId: String): List<String> = forTopic(topicId).map { it.question }

    fun isDuplicate(topicId: String, text: String): Boolean {
        val norm = normalize(text)
        return forTopic(topicId).any { normalize(it.question) == norm }
    }

    /** Local cache cleanup after the store deleted a user's owned (scan) questions. */
    fun forgetOwnedBy(userId: String) {
        val ids = generated.values.filter { it.ownerUserId == userId }.map { it.id }
        ids.forEach { generated.remove(it); stats.remove(it) }
    }

    fun forgetReporter(userId: String) {
        stats.replaceAll { _, s -> if (userId in s.reporters) s.copy(reporters = s.reporters - userId) else s }
    }

    companion object {
        fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
