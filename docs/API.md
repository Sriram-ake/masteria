# Masteria API contract (v1)

The Android app and the Ktor backend both code against this file. JSON only, `camelCase` keys,
`kotlinx.serialization` with `ignoreUnknownKeys = true` and `explicitNulls = false` on both sides.
Every route except `/health`, `/auth/register`, `/auth/login`, `/auth/guest` and `/tracks` needs
`Authorization: Bearer <jwt>`.

Errors are always `HTTP 4xx/5xx` with body `{"error": "Human readable message"}`.

Base URL in development: `http://10.0.2.2:8080` (Android emulator → host) or `http://<LAN-IP>:8080`.

---

## Shared DTOs (Kotlin)

```kotlin
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
```

---

## Routes

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/health` | – | `{"status":"ok","ai":AiStatusDto}` |
| POST | `/auth/register` | RegisterRequest | AuthResponse |
| POST | `/auth/login` | LoginRequest | AuthResponse |
| POST | `/auth/guest` | GuestRequest | AuthResponse (random email `guest-xxxx@masteria.local`) |
| GET | `/auth/me` | – | UserDto |
| DELETE | `/auth/me` | – | `{"deleted":true}` (DPDP: deletes user + all data) |
| GET | `/tracks` | – | `List<TrackDto>` |
| POST | `/onboarding` | OnboardingRequest | UserDto |
| GET | `/onboarding/diagnostic?trackId=` | – | DiagnosticDto (defaults to the user's track) |
| POST | `/onboarding/diagnostic` | DiagnosticSubmit | DiagnosticResult |
| GET | `/home` | – | HomeDto |
| GET | `/map` | – | MapDto (user's current track) |
| POST | `/quests/start` | StartQuestRequest | QuestSessionDto (`topicId` null → learner model picks) |
| GET | `/quests/{id}` | – | QuestSessionDto (current question) |
| POST | `/quests/{id}/answer` | AnswerDto | AnswerResult (< 200 ms, no AI call) |
| POST | `/quests/{id}/complete` | – | QuestSummaryDto |
| GET | `/progress` | – | ProgressDto |
| GET | `/profile` | – | ProfileDto |
| PUT | `/profile` | UpdateProfileRequest | ProfileDto |
| GET | `/streak` | – | `{"streakDays":Int,"activeToday":Boolean}` |
| POST | `/ai/mentor` | MentorRequest | **SSE** stream, see below |
| POST | `/ai/explain` | ExplainRequest | ExplainResponse |
| POST | `/ai/scan` | ScanRequest | QuestSessionDto (a 5-question quest, `isScan = true`) |
| GET | `/ai/status` | – | AiStatusDto |
| POST | `/questions/{id}/report` | `{"reason":String?}` | `{"flags":Int}` |

### Mentor stream (`POST /ai/mentor`)
`Content-Type: text/event-stream`. Each event is one line `data: <json>` followed by a blank line:
```
data: {"delta":"Think of "}

data: {"delta":"the equation as a balance…"}

data: {"done":true}
```
On failure mid-stream: `data: {"error":"message"}` then `data: {"done":true}`.
If NIM is unreachable the server still streams a helpful fallback reply (source-marked) — it never
leaves the learner with nothing.

### Icons
Symbolic names the app maps to vector icons: `function`, `divide`, `plusminus`, `triangle`, `angle`,
`dice`, `percent`, `scale`, `coins`, `clock`, `chart`, `bank`, `code`, `box`, `layers`, `shapes`,
`lock`, `puzzle`, `text`, `atom`, `book`, `spark`, `flame`, `trophy`, `sword`, `crown`, `target`,
`brain`, `rocket`, `star`, `scan`, `medal`.

---

## Additions (v1.1)

| Method | Path | Body | Returns |
|---|---|---|---|
| GET / HEAD | `/health` | – | HealthDto (+ `store`, `firebase`, `websocket`, `uptimeSeconds`). No DB/AI work: use it for keep-alive pings |
| GET / HEAD | `/ping` | – | `pong` (text) |
| POST | `/auth/firebase` | `{"idToken":String,"name":String?}` | AuthResponse. Verifies the Firebase ID token; creates, links (verified email) or, when called with a guest's `Authorization`, upgrades that guest keeping progress. 503 if `FIREBASE_PROJECT_ID` is unset |
| GET (upgrade) | `/ws` | – | WebSocket, see below. Auth: `Authorization: Bearer <jwt>` (or `?token=`) |

Auth routes (`/auth/register|login|guest|firebase`) are rate-limited per client IP (default 30/min, 429 after).
`AiStatusDto` gains optional `keys` / `keysHealthy` (NIM key rotation).

### WebSocket `/ws`
Client → server: `{"id":"r1","method":"GET","path":"/home","body":{...}}` — any route above, same body and JSON.
`{"id":"r1","cancel":true}` cancels an in-flight request (e.g. a mentor reply).

Server → client:
- `{"type":"ready","body":UserDto}` once connected
- `{"type":"response","id":"r1","status":200,"body":{...}}` or `{"type":"response","id":"r1","status":409,"error":"..."}`
- `{"type":"stream","id":"m1","body":{"delta":"..."}}` for `POST /ai/mentor` (same events as SSE, ending with `{"done":true}`), then a `response`
- `{"type":"event","event":"changed"}` when the same account's progress changed elsewhere (refetch)

The server pings every 20 s. The app falls back to HTTP whenever the socket is not connected.
