package com.triplethreats.masteria.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// API contract DTOs, copied from docs/API.md. Do not rename or drop fields; only add optional ones.

@Serializable data class ErrorDto(val error: String)

// ---------- auth ----------
@Serializable data class RegisterRequest(val name: String, val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class GuestRequest(val name: String = "Guest")
@Serializable data class AuthResponse(val token: String, val user: UserDto)
/** Firebase sign-in/sign-up: the app sends the Firebase ID token and gets a Masteria session token back. */
@Serializable data class FirebaseAuthRequest(val idToken: String, val name: String? = null)

@Serializable data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val onboarded: Boolean,
    val diagnosed: Boolean,
    val learnerType: String?,
    val goal: String?,
    val selfLevel: String?,
    val dailyMinutes: Int?,
    val trackId: String?,
    val level: Int,
    val xp: Int,
    val coins: Int,
    val streakDays: Int,
    val isGuest: Boolean,
)

// ---------- tracks / onboarding ----------
@Serializable data class TrackDto(
    val id: String,
    val name: String,
    val world: String,
    val subject: String,
    val description: String,
    val icon: String,
    val recommendedFor: List<String>,
    val topicCount: Int,
)

@Serializable data class OnboardingRequest(
    val learnerType: String, val goal: String, val selfLevel: String,
    val dailyMinutes: Int, val trackId: String,
    val isMinor: Boolean, val parentConsent: Boolean = false,
)

@Serializable data class QuestionDto(
    val id: String,
    val topicId: String,
    val topicName: String,
    val type: String,
    val difficulty: Int,
    val question: String,
    val options: List<String>,
    val hint: String,
    val source: String,
    val code: String? = null,
)

@Serializable data class DiagnosticDto(val trackId: String, val questions: List<QuestionDto>)

@Serializable data class AnswerDto(
    val questionId: String,
    val answerIndex: Int? = null,
    val answerText: String? = null,
    val timeMs: Long = 0,
    val usedHint: Boolean = false,
)
@Serializable data class DiagnosticSubmit(val trackId: String, val answers: List<AnswerDto>)

@Serializable data class TopicMasteryDto(
    val topicId: String, val name: String, val mastery: Int, val status: String,
    val weak: Boolean,
)
@Serializable data class DiagnosticResult(
    val trackId: String,
    val topics: List<TopicMasteryDto>,
    val weakestTopicId: String?,
    val correct: Int, val total: Int,
    val summary: String,
)

// ---------- home ----------
@Serializable data class RecommendationDto(
    val topicId: String, val topicName: String, val mastery: Int,
    val reason: String,
    val questTitle: String,
    val difficulty: Int, val xpReward: Int,
    val isBoss: Boolean, val isReview: Boolean,
)
@Serializable data class HomeDto(
    val user: UserDto,
    val track: TrackDto,
    val level: LevelDto,
    val recommended: RecommendationDto?,
    val bossReady: List<RecommendationDto>,
    val reviewsDue: List<RecommendationDto>,
    val minutesToday: Int, val dailyMinutes: Int,
    val questsToday: Int,
    val greeting: String,
)
@Serializable data class LevelDto(
    val level: Int, val xp: Int,
    val levelStartXp: Int, val nextLevelXp: Int,
    val cappedByBoss: Boolean,
    val capMessage: String?,
)

// ---------- map ----------
@Serializable data class MapTopicDto(
    val id: String, val name: String, val description: String, val icon: String,
    val tier: Int,
    val prereqs: List<String>,
    val mastery: Int,
    val status: String,
    val weak: Boolean,
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
    val total: Int,
    val index: Int,
    val question: QuestionDto,
    val hintFirst: Boolean,
    val masteryBefore: Int,
    val xpBase: Int,
)
@Serializable data class AnswerResult(
    val correct: Boolean,
    val correctIndex: Int?,
    val correctText: String?,
    val explanation: String,
    val masteryBefore: Int, val masteryAfter: Int,
    val difficultyChange: String,
    val rollingAccuracy: Int,
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
    val unlockedTopics: List<String>,
    val streakDays: Int,
    val nextStep: String,
)

// ---------- progress / profile ----------
@Serializable data class TopicProgressDto(
    val topicId: String, val name: String, val mastery: Int, val status: String,
    val trend: List<Int>,
    val accuracy: Int, val attempts: Int,
)
@Serializable data class ProgressDto(
    val trackId: String,
    val overallMastery: Int,
    val topics: List<TopicProgressDto>,
    val strongest: List<String>, val weakest: List<String>,
    val questsCompleted: Int, val totalAnswers: Int, val accuracy: Int,
    val targetZoneShare: Int,
    val xpLast7Days: List<Int>,
    val dayLabels: List<String>,
)
@Serializable data class ProfileDto(
    val user: UserDto, val level: LevelDto, val track: TrackDto,
    val badges: List<BadgeDto>,
    val topSkills: List<TopicMasteryDto>,
    val questsCompleted: Int, val bossesDefeated: Int,
    val tracks: List<TrackDto>,
)
@Serializable data class UpdateProfileRequest(
    val name: String? = null, val trackId: String? = null, val dailyMinutes: Int? = null,
    val learnerType: String? = null, val goal: String? = null,
)

// ---------- AI ----------
@Serializable data class ChatMessageDto(val role: String, val content: String)
@Serializable data class MentorRequest(
    val messages: List<ChatMessageDto>,
    val topicId: String? = null,
    val questionId: String? = null,
)
@Serializable data class ExplainRequest(val questionId: String, val answerIndex: Int? = null, val answerText: String? = null)
@Serializable data class ExplainResponse(val analysis: String, val source: String)
@Serializable data class ScanRequest(val text: String? = null, val imageBase64: String? = null)
@Serializable data class AiStatusDto(
    val online: Boolean, val chatModel: String, val visionModel: String, val lastLatencyMs: Long?,
    /** NIM keys configured and currently usable (key rotation). */
    val keys: Int? = null, val keysHealthy: Int? = null,
)

// ---------- small route bodies ----------
@Serializable data class HealthDto(
    val status: String,
    val ai: AiStatusDto,
    val store: String? = null,
    val firebase: Boolean = false,
    val websocket: Boolean = true,
    val uptimeSeconds: Long? = null,
)
@Serializable data class DeletedDto(val deleted: Boolean)
@Serializable data class StreakDto(val streakDays: Int, val activeToday: Boolean)
@Serializable data class ReportRequest(val reason: String? = null)
@Serializable data class ReportResponse(val flags: Int)

// ---------- mentor SSE events ----------
@Serializable data class MentorDelta(val delta: String, val source: String? = null)
@Serializable data class MentorDone(val done: Boolean = true, val source: String? = null)
@Serializable data class MentorError(val error: String)

// ---------- realtime (WebSocket /ws) ----------
/**
 * Client → server frame. A request mirrors an HTTP call (`method` + `path` + JSON `body`) and is answered by
 * a [WsFrame] with the same `id`. `{"id":..,"cancel":true}` cancels an in-flight request (e.g. a mentor stream).
 */
@Serializable data class WsRequest(
    val id: String? = null,
    val method: String = "GET",
    val path: String = "",
    val body: JsonElement? = null,
    val cancel: Boolean = false,
)

/**
 * Server → client frame. `type` is "ready" (after connecting), "response" (final answer to request `id`),
 * "stream" (one mentor SSE-equivalent event for request `id`), or "event" (server push, e.g. "changed").
 */
@Serializable data class WsFrame(
    val type: String,
    val id: String? = null,
    val status: Int? = null,
    val body: JsonElement? = null,
    val error: String? = null,
    val event: String? = null,
)
