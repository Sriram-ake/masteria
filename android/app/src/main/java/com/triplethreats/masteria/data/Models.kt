package com.triplethreats.masteria.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// DTOs mirroring docs/API.md field-for-field (the backend's routes/Dtos.kt). Only add optional fields.

@Serializable data class ErrorDto(val error: String)

// ---------- auth ----------
@Serializable data class RegisterRequest(val name: String, val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class GuestRequest(val name: String = "Guest")
@Serializable data class AuthResponse(val token: String, val user: UserDto)

@Serializable data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val onboarded: Boolean,          // true after POST /onboarding
    val diagnosed: Boolean,          // true after the diagnostic for the CURRENT track
    val learnerType: String?,        // school | college | exam | coding | self | career
    val goal: String?,               // grades | exam | skill | job | career | explore
    val selfLevel: String?,          // beginner | intermediate | advanced
    val dailyMinutes: Int?,          // 15 | 30 | 60 | 120
    val trackId: String?,            // school-math | ssc-quant | java-oop
    val level: Int,
    val xp: Int,
    val coins: Int,
    val streakDays: Int,
    val isGuest: Boolean,
)

// ---------- tracks / onboarding ----------
@Serializable data class TrackDto(
    val id: String,                  // "school-math"
    val name: String,                // "Math Kingdom"
    val world: String,               // School | College | Exams | Skills | Career
    val subject: String,             // "Mathematics · Class 9"
    val description: String,
    val icon: String,                // symbolic name, see "Icons" below
    val recommendedFor: List<String>,// learner types this track is recommended for
    val topicCount: Int,
)

@Serializable data class OnboardingRequest(
    val learnerType: String, val goal: String, val selfLevel: String,
    val dailyMinutes: Int, val trackId: String,
    val isMinor: Boolean, val parentConsent: Boolean = false,
)

@Serializable data class QuestionDto(           // never contains the answer
    val id: String,
    val topicId: String,
    val topicName: String,
    val type: String,                // "mcq" | "numeric"
    val difficulty: Int,             // 1 easy, 2 medium, 3 hard
    val question: String,
    val options: List<String>,       // empty for numeric
    val hint: String,
    val source: String,              // "vetted" | "nim"
    val code: String? = null,        // optional code snippet shown monospace under the question
)

@Serializable data class DiagnosticDto(val trackId: String, val questions: List<QuestionDto>)

@Serializable data class AnswerDto(
    val questionId: String,
    val answerIndex: Int? = null,    // mcq
    val answerText: String? = null,  // numeric
    val timeMs: Long = 0,
    val usedHint: Boolean = false,
)
@Serializable data class DiagnosticSubmit(val trackId: String, val answers: List<AnswerDto>)

@Serializable data class TopicMasteryDto(
    val topicId: String, val name: String, val mastery: Int, val status: String, // locked|available|mastered
    val weak: Boolean,
)
@Serializable data class DiagnosticResult(
    val trackId: String,
    val topics: List<TopicMasteryDto>,
    val weakestTopicId: String?,
    val correct: Int, val total: Int,
    val summary: String,             // one-sentence, e.g. "Algebra needs work — your first quest is built around it."
)

// ---------- home ----------
@Serializable data class RecommendationDto(
    val topicId: String, val topicName: String, val mastery: Int,
    val reason: String,              // "Weakest topic on your path"
    val questTitle: String,          // RPG voice, e.g. "The Equation Gate"
    val difficulty: Int, val xpReward: Int,
    val isBoss: Boolean, val isReview: Boolean,
)
@Serializable data class HomeDto(
    val user: UserDto,
    val track: TrackDto,
    val level: LevelDto,
    val recommended: RecommendationDto?,
    val bossReady: List<RecommendationDto>,     // topics with mastery >= 80, boss not yet defeated
    val reviewsDue: List<RecommendationDto>,    // spaced review
    val minutesToday: Int, val dailyMinutes: Int,
    val questsToday: Int,
    val greeting: String,                        // "Good evening, Asha"
)
@Serializable data class LevelDto(
    val level: Int, val xp: Int,
    val levelStartXp: Int, val nextLevelXp: Int, // progress = (xp - start) / (next - start)
    val cappedByBoss: Boolean,                   // XP is enough for the next level but a boss win is required
    val capMessage: String?,                     // "Defeat a boss to reach Level 3"
)

// ---------- map ----------
@Serializable data class MapTopicDto(
    val id: String, val name: String, val description: String, val icon: String,
    val tier: Int,                   // 0 = root row, 1, 2 ... used for layout
    val prereqs: List<String>,
    val mastery: Int,
    val status: String,              // locked | available | mastered
    val weak: Boolean,               // lowest-mastery available topic(s)
    val bossReady: Boolean,
    val bossDefeated: Boolean,
    val reviewDue: Boolean,
    val questionCount: Int,
)
@Serializable data class MapDto(val track: TrackDto, val topics: List<MapTopicDto>)

// ---------- quests ----------
@Serializable data class StartQuestRequest(val topicId: String? = null, val boss: Boolean = false)
@Serializable data class QuestSessionDto(
    val id: String,
    val title: String,
    val topicId: String, val topicName: String,
    val isBoss: Boolean, val isReview: Boolean, val isScan: Boolean,
    val total: Int,                  // questions in the quest (5)
    val index: Int,                  // 0-based index of `question`
    val question: QuestionDto,
    val hintFirst: Boolean,          // learner model wants the hint shown before the options
    val masteryBefore: Int,
    val xpBase: Int,
)
@Serializable data class AnswerResult(
    val correct: Boolean,
    val correctIndex: Int?,          // mcq
    val correctText: String?,        // numeric
    val explanation: String,
    val masteryBefore: Int, val masteryAfter: Int,
    val difficultyChange: String,    // "up" | "down" | "same"
    val rollingAccuracy: Int,        // 0-100 over the last 5 answers on this topic
    val done: Boolean,
    val next: QuestionDto?,
    val nextIndex: Int?,
    val hintFirst: Boolean,
    val correctCount: Int, val answeredCount: Int,
)
@Serializable data class XpLineDto(val label: String, val xp: Int)
@Serializable data class BadgeDto(
    val id: String, val name: String, val description: String, val icon: String,
    val earned: Boolean, val earnedAt: Long? = null,
)
@Serializable data class QuestSummaryDto(
    val questId: String, val title: String, val topicName: String,
    val isBoss: Boolean, val bossDefeated: Boolean,
    val correct: Int, val total: Int, val accuracy: Int,
    val masteryBefore: Int, val masteryAfter: Int,
    val xpEarned: Int, val coinsEarned: Int,
    val breakdown: List<XpLineDto>,
    val levelBefore: Int, val levelAfter: Int, val leveledUp: Boolean,
    val level: LevelDto,
    val newBadges: List<BadgeDto>,
    val unlockedTopics: List<String>,   // topic names
    val streakDays: Int,
    val nextStep: String,               // "Algebra is at 62%. One more quest and the boss opens."
)

// ---------- progress / profile ----------
@Serializable data class TopicProgressDto(
    val topicId: String, val name: String, val mastery: Int, val status: String,
    val trend: List<Int>,               // mastery after each of the last ≤12 answers, oldest first
    val accuracy: Int, val attempts: Int,
)
@Serializable data class ProgressDto(
    val trackId: String,
    val overallMastery: Int,
    val topics: List<TopicProgressDto>,
    val strongest: List<String>, val weakest: List<String>,   // topic names
    val questsCompleted: Int, val totalAnswers: Int, val accuracy: Int,
    val targetZoneShare: Int,           // % of answers whose predicted success was within 70–85 %
    val xpLast7Days: List<Int>,         // oldest first, 7 values
    val dayLabels: List<String>,        // "Mon".."Sun" aligned with xpLast7Days
)
@Serializable data class ProfileDto(
    val user: UserDto, val level: LevelDto, val track: TrackDto,
    val badges: List<BadgeDto>,
    val topSkills: List<TopicMasteryDto>,
    val questsCompleted: Int, val bossesDefeated: Int,
    val tracks: List<TrackDto>,          // all tracks, for switching worlds
)
@Serializable data class UpdateProfileRequest(
    val name: String? = null, val trackId: String? = null, val dailyMinutes: Int? = null,
    val learnerType: String? = null, val goal: String? = null,
)

// ---------- AI ----------
@Serializable data class ChatMessageDto(val role: String, val content: String) // "user" | "assistant"
@Serializable data class MentorRequest(
    val messages: List<ChatMessageDto>,
    val topicId: String? = null,
    val questionId: String? = null,      // when asking about a specific question
)
@Serializable data class ExplainRequest(val questionId: String, val answerIndex: Int? = null, val answerText: String? = null)
@Serializable data class ExplainResponse(val analysis: String, val source: String) // source "nim" | "fallback"
@Serializable data class ScanRequest(val text: String? = null, val imageBase64: String? = null) // jpeg, ≤ 1 MB
@Serializable data class AiStatusDto(val online: Boolean, val chatModel: String, val visionModel: String, val lastLatencyMs: Long?)

// ---------- additions (all optional on the wire) ----------
/** Firebase sign-in/sign-up: exchange a Firebase ID token for a Masteria session token. */
@Serializable data class FirebaseAuthRequest(val idToken: String, val name: String? = null)
@Serializable data class HealthDto(
    val status: String,
    val ai: AiStatusDto? = null,
    val store: String? = null,
    val firebase: Boolean = false,
    val websocket: Boolean = false,
    val uptimeSeconds: Long? = null,
)
@Serializable data class StreakDto(val streakDays: Int, val activeToday: Boolean)
@Serializable data class ReportRequest(val reason: String? = null)
@Serializable data class ReportResponse(val flags: Int)
@Serializable data class DeletedDto(val deleted: Boolean)

/** Mentor stream event as sent over SSE (`data: {...}`) or as a WebSocket "stream" frame body. */
@Serializable data class MentorWireEvent(val delta: String? = null, val source: String? = null, val done: Boolean = false, val error: String? = null)

sealed interface MentorEvent {
    data class Delta(val text: String, val source: String? = null) : MentorEvent
    data class Failure(val message: String) : MentorEvent
    data object Done : MentorEvent
}

// ---------- realtime (WebSocket /ws) ----------
@Serializable data class WsRequest(val id: String, val method: String, val path: String, val body: JsonElement? = null, val cancel: Boolean = false)
@Serializable data class WsFrame(
    val type: String,
    val id: String? = null,
    val status: Int? = null,
    val body: JsonElement? = null,
    val error: String? = null,
    val event: String? = null,
)
