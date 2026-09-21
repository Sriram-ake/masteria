package com.triplethreats.masteria.db

import com.triplethreats.masteria.routes.AnswerResult
import com.triplethreats.masteria.routes.QuestSummaryDto
import kotlinx.serialization.Serializable

@Serializable
data class UserRecord(
    val id: String,
    val name: String,
    val email: String,
    val passwordHash: String,
    val isGuest: Boolean = false,
    val onboarded: Boolean = false,
    val learnerType: String? = null,
    val goal: String? = null,
    val selfLevel: String? = null,
    val dailyMinutes: Int? = null,
    val trackId: String? = null,
    val diagnosedTracks: List<String> = emptyList(),
    val xp: Int = 0,
    val coins: Int = 0,
    val isMinor: Boolean = false,
    val parentConsentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Firebase Authentication uid for accounts that sign in through Firebase. */
    val firebaseUid: String? = null,
    /** "password" (legacy email + bcrypt), "guest" or "firebase". */
    val authProvider: String = if (isGuest) "guest" else "password",
)

@Serializable
data class TopicMasteryRecord(
    val userId: String,
    val topicId: String,
    val rating: Double,
    val mastery: Int,
    val bossDefeated: Boolean = false,
    val testedOut: Boolean = false,
    val lastPracticedAt: Long? = null,
    val reviewStage: Int? = null,
    val nextReviewAt: Long? = null,
    val answerCount: Int = 0,
)

@Serializable
data class AttemptRecord(
    val userId: String,
    val questionId: String,
    val questId: String,
    val topicId: String,
    val correct: Boolean,
    val timeMs: Long,
    val usedHint: Boolean,
    val at: Long,
    val masteryAfter: Int,
    val predicted: Double? = null,
)

/** AI-generated question (source "nim"), stored only after it passed validation. */
@Serializable
data class StoredQuestion(
    val id: String,
    val topicId: String,
    val topicName: String? = null,
    val type: String = "mcq",
    val difficulty: Int,
    val question: String,
    val options: List<String>,
    val answerIndex: Int? = null,
    val answerText: String? = null,
    val explanation: String,
    val hint: String,
    val code: String? = null,
    val source: String = "nim",
    val status: String = "approved",
    val ownerUserId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Elo rating drift + report flags for any question (vetted or generated). */
@Serializable
data class QuestionStat(
    val questionId: String,
    val rating: Double,
    val flags: Int = 0,
    val reporters: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
)

@Serializable
data class SessionAnswer(val questionId: String, val correct: Boolean, val result: AnswerResult)

@Serializable
data class QuestSession(
    val id: String,
    val userId: String,
    val trackId: String?,
    val topicId: String,
    val topicName: String,
    val title: String,
    val isBoss: Boolean,
    val isReview: Boolean,
    val isScan: Boolean,
    val total: Int,
    /** Questions served so far, in order; the last one is the current question until it is answered. */
    val servedIds: List<String>,
    /** Fixed question order (scan quests); empty = learner model picks one at a time. */
    val plannedIds: List<String> = emptyList(),
    val answers: List<SessionAnswer> = emptyList(),
    val hintFirst: Boolean = false,
    val masteryBefore: Int,
    val xpBase: Int,
    val wasMasteredBefore: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
    val summary: QuestSummaryDto? = null,
)

@Serializable
data class BadgeRecord(val userId: String, val badgeId: String, val earnedAt: Long)

@Serializable
data class DailyActivity(
    val userId: String,
    val day: String, // yyyy-MM-dd in the configured TIMEZONE
    val xp: Int = 0,
    val activeMs: Long = 0,
    val quests: Int = 0,
)
