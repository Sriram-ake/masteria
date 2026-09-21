# Masteria: Project Context

> **Learn. Quest. Level Up.**
> An adaptive learning RPG for every learner.

| | |
|---|---|
| **Product** | Masteria |
| **Team** | Triple Threats |
| **Event** | iQOO Hackathon 2026, theme: Smart Education |
| **Platform** | Native Android app (mobile-first) |
| **Language** | Kotlin, for both the app and the backend |
| **AI** | NVIDIA NIM |

This file is the single source of truth for what Masteria is, what it must do and how it is built. If something here conflicts with older notes, the Education RPG spec or the slides, follow this file.

---

## 1. The idea in one line

**The AI finds each learner's weak spot and builds their next quest around it.**

Masteria turns learning into an RPG. Every concept is a quest, and XP comes from measured improvement, not from time spent. One engine powers every kind of learner: school, college, competitive exams, coding and careers.

## 2. The problem

Two students both score 60% in maths. One is weak at algebra and the other at geometry. Static courses and generic quiz apps send both to the same next chapter, so neither fixes their real gap.

Learners usually don't know:
- what to learn next
- which concepts they are weak in
- what difficulty to practise at
- whether they are actually improving
- how to stay consistent
- how today's practice connects to their goal

Most platforms reward **completion**. Masteria rewards **improvement**.

## 3. The solution

```
Set goal → Diagnostic → Personal map → Quest → Answer questions
   → Learner model updates mastery → Weak spot found
   → Next quest built around it → Earn XP → Boss battle → Level up → Unlock
```

**Core loop:** Learn → Practice → Improve → Level up → Unlock → Repeat.

**Key design choice: the model decides and the AI writes.**
- A **deterministic learner model** (plain Kotlin code, no AI) decides what comes next: mastery per topic, difficulty and review timing. It is fast, explainable and testable.
- **NVIDIA NIM** writes the content: questions, hints, explanations, mentor chat, quest names and roadmaps.

This split answers the judges' question "what if the AI is wrong?" The AI never decides progression, and every question it generates is checked before it reaches a learner.

## 4. Target users: five worlds, one engine

| World | Who | Example content |
|---|---|---|
| School | Classes 6–12 | Maths, Science, English, Social Studies |
| College | Degree students | DBMS, Java, Aptitude, Communication |
| Exams | Competitive-exam aspirants | SSC, Banking, JEE, NEET, UPSC, Railway |
| Skills | Coding learners | Python, Web development, Data structures, AI/ML |
| Career | Career switchers | Data analyst, Developer, Designer |

All worlds share the same quest engine, learner model, XP system, skill tree and AI layer. Only the content changes.

**Hero persona for the demo:** a Class 9 student who is weak at algebra.

## 5. Differentiation

| Existing product | Strong at | What Masteria adds |
|---|---|---|
| Duolingo | Streaks, XP, habit loops | Adapts across subjects, with a different path per learner |
| Khan Academy / Khanmigo | Mastery learning, AI tutor | Game progression and goal-based roadmaps on top of mastery |
| Classcraft, Habitica | RPG mechanics | Adaptive content behind the game layer |
| Exam-prep apps | Exam content, mock tests | Rewards improvement, not completion |

**Positioning:** one engine across subjects, where progress comes from measured mastery and not from minutes spent.

---

## 6. Functional requirements

Priority: **P0** = must be in the hackathon demo, **P1** = build if time allows, **P2** = pitch only.

### 6.1 Accounts and onboarding
| ID | Requirement | Priority |
|---|---|---|
| F-01 | Register and log in with email and password, using JWT sessions | P0 |
| F-02 | Choose a learner type: School, College, Exam, Coding, Self or Career | P0 |
| F-03 | Choose a goal: improve grades, crack an exam, learn a skill or get a job | P0 |
| F-04 | Self-rate the current level: Beginner, Intermediate or Advanced | P0 |
| F-05 | Choose daily time: 15 min, 30 min, 1 h or 2 h+ | P0 |
| F-06 | Take a 5–10 question diagnostic that sets the starting mastery per topic | P0 |
| F-07 | Parental consent step for learners under 18 | P1 |

### 6.2 Learner model (adaptive core)
| ID | Requirement | Priority |
|---|---|---|
| F-10 | Mastery score from 0 to 100 per topic, updated after every answer | P0 |
| F-11 | Elo-style rating: learners and questions both have ratings, so a hard question answered right counts for more | P0 |
| F-12 | Target success rate of 70–85%: above it difficulty rises, below it difficulty drops and a hint comes first | P0 |
| F-13 | Weak-topic detection: the lowest-mastery topics on the current path get priority | P0 |
| F-14 | Spaced review: mastered topics come back as short revisit quests after 1, 3, 7 and 21 days | P1 |
| F-15 | Goal mode with a target date, which reprioritises topics as the deadline nears | P1 |

### 6.3 Quests and gamification
| ID | Requirement | Priority |
|---|---|---|
| F-20 | A quest is a set of questions on one topic at a chosen difficulty, with an XP reward | P0 |
| F-21 | Question types: multiple choice and numeric answer (P0); coding, reading comprehension and debugging (P1) | P0 |
| F-22 | Base XP: easy 50, medium 100, hard 200, boss 500, daily streak 25, perfect score 100, skill mastery 250 | P0 |
| F-23 | XP is weighted by mastery gained. Improving a weak topic earns a bonus, and replaying mastered content earns less | P0 |
| F-24 | Levels need both XP and a **boss battle** (a mastery test) on the gating topic | P0 |
| F-25 | A skill tree or learning map that lights up from mastery scores and shows locked and unlocked nodes | P0 |
| F-26 | Streaks that count days with real learning activity | P1 |
| F-27 | Badges: First Quest, 7-Day Streak, Boss Slayer, Concept Master and others | P1 |
| F-28 | Coins, spent only on cosmetics such as avatars and themes | P2 |
| F-29 | Leaderboard, multiplayer and team quests | P2 |

### 6.4 AI features (NVIDIA NIM)
| ID | Requirement | Priority |
|---|---|---|
| F-30 | AI mentor chat that explains at the learner's level and guides rather than just giving answers | P0 |
| F-31 | Question generation as structured JSON (see §9.4) | P0 |
| F-32 | Hints and mistake analysis after a wrong answer | P0 |
| F-33 | **Scan-to-Quest:** photograph a textbook page or notes and get a 5-question quest on it | P0 |
| F-34 | Quest naming and roadmap text in the RPG voice | P1 |
| F-35 | Voice mentor in English, Hindi and Telugu | P2 |

### 6.5 Progress and dashboards
| ID | Requirement | Priority |
|---|---|---|
| F-40 | Profile: level, XP bar, streak, top skills and badges | P0 |
| F-41 | Progress screen: mastery per topic, strongest and weakest topics, and the trend over time | P0 |
| F-42 | Read-only parent or teacher view of weak topics and streaks | P2 |

### 6.6 Mobile-native (the iQOO angle)
| ID | Requirement | Priority |
|---|---|---|
| F-50 | Haptics and animations on correct answers, level-ups and boss wins | P0 |
| F-51 | Camera capture for Scan-to-Quest | P0 |
| F-52 | Offline mode: cached quests can be played offline, and XP and answers sync later | P1 |
| F-53 | High-refresh-rate animations, smooth at 120 Hz | P1 |

## 7. Non-functional requirements

| Area | Requirement |
|---|---|
| Performance | Answer feedback in under 200 ms (the learner model runs locally or on the server with no AI call). Mentor replies start streaming within about 2 s. |
| Reliability | If NIM is unavailable, quests still work from the vetted question bank. |
| Correctness | No AI-generated question reaches a learner without passing validation (§9.5). |
| Security | The NIM API key lives **only on the backend**, never in the APK. Passwords are hashed with bcrypt or argon2. JWTs have expiry and refresh. |
| Privacy | India's DPDP Act requires verifiable parental consent for users under 18. Collect only the data needed, and allow account deletion. |
| Accessibility | Readable type scale, TalkBack labels, a dyslexia-friendly font option, and no information carried by colour alone. |
| Device support | Android 8.0+ (API 26+), tuned for iQOO devices. |
| Cost | Use the NIM trial or free endpoints for development, and cache generated content to limit calls. |

---

## 8. Architecture

```
┌──────────────────────────────┐
│  Android app (Kotlin)        │   Jetpack Compose UI, Room cache,
│  Learner on an iQOO phone    │   CameraX + ML Kit OCR, haptics
└──────────────┬───────────────┘
               │ HTTPS / JSON (JWT)
┌──────────────▼───────────────┐
│  Backend (Kotlin + Ktor)     │   Auth, quests, XP, levels,
│                              │   badges, streaks, progress
│  ┌────────────────────────┐  │
│  │ Learner model (Kotlin) │  │   Mastery, Elo, difficulty,
│  └────────────────────────┘  │   spaced review
└───────┬───────────────┬──────┘
        │               │ HTTPS (OpenAI-compatible)
┌───────▼──────┐ ┌──────▼─────────────────────┐
│ MongoDB Atlas│ │ NVIDIA NIM                 │
│ users, quests│ │ LLM: mentor, questions     │
│ attempts ... │ │ VLM: Scan-to-Quest         │
└──────────────┘ │ (optional) Riva ASR/TTS    │
                 └────────────────────────────┘
```

### 8.1 Tech stack

**Android app (frontend)**
| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (custom Masteria theme) |
| Architecture | MVVM + unidirectional data flow (ViewModel + StateFlow) |
| DI | Hilt |
| Networking | Retrofit + OkHttp, or Ktor Client, with kotlinx.serialization |
| Local storage | Room (quest cache, pending answers), DataStore (settings, tokens) |
| Background sync | WorkManager |
| Camera | CameraX |
| On-device OCR | ML Kit Text Recognition, which reads text before it is sent to NIM |
| Animation | Compose animation APIs, plus Lottie for level-up and boss effects |
| Haptics | `HapticFeedback` / `VibrationEffect` |
| Images | Coil |

**Backend**
| Concern | Choice |
|---|---|
| Language | Kotlin (JVM 17+) |
| Framework | Ktor Server (Netty) |
| Serialization | kotlinx.serialization |
| Auth | `ktor-server-auth-jwt` |
| Database | MongoDB Atlas via the official **MongoDB Kotlin Coroutine Driver** |
| AI client | Ktor Client calling the NIM REST API |
| Build | Gradle (Kotlin DSL) |
| Deploy | Docker image on Render or Railway, with Atlas for the database |

**Shared tooling:** Git and GitHub, Android Studio, IntelliJ IDEA, Postman or Bruno, Figma.

### 8.2 Suggested repository layout
```
masteria/
├── android/                    # Android app
│   └── app/src/main/java/com/triplethreats/masteria/
│       ├── ui/                 # Compose screens: onboarding, map, quest, mentor, profile
│       ├── data/               # repositories, Retrofit/Ktor APIs, Room DAOs
│       ├── domain/             # use cases, models
│       └── di/                 # Hilt modules
├── backend/                    # Ktor server
│   └── src/main/kotlin/com/triplethreats/masteria/
│       ├── routes/             # auth, users, quests, progress, ai
│       ├── learner/            # learner model: Elo, mastery, difficulty, review
│       ├── ai/                 # NIM client, prompts, validators
│       ├── db/                 # Mongo collections and repositories
│       └── Application.kt
├── content/                    # vetted question bank (JSON) for demo paths
└── context.md                  # this file
```

---

## 9. AI layer: NVIDIA NIM

### 9.1 How we use NIM
NIM provides NVIDIA-optimised model endpoints behind an **OpenAI-compatible REST API**. During the hackathon we call NVIDIA's hosted endpoints from the API Catalog (build.nvidia.com) with an API key. The same code can later point at a self-hosted NIM container by changing the base URL.

| Setting | Value |
|---|---|
| Base URL (hosted) | `https://integrate.api.nvidia.com/v1` |
| Auth | `Authorization: Bearer $NVIDIA_API_KEY` |
| Main endpoint | `POST /chat/completions` (OpenAI format, supports `stream: true`) |
| Key storage | Backend environment variable `NVIDIA_API_KEY`, never in the app |

> The hosted endpoints are meant for prototyping. Check current quotas, rate limits and production terms on build.nvidia.com before launch.

### 9.2 Model roles
Pick the exact model IDs from the build.nvidia.com catalogue on build day, because the catalogue changes. Keep the IDs in config, not hard-coded.

| Role | Kind of model | Example to evaluate |
|---|---|---|
| Mentor chat, hints, explanations | Instruction-tuned LLM, mid-to-large | Llama 3.x Instruct or Nemotron family |
| Question generation (JSON) | Same LLM, low temperature | Same as above |
| Answer self-check | A second LLM call, ideally a different model | A Nemotron, Qwen or DeepSeek family model |
| Scan-to-Quest | Vision-language model (VLM) | Llama 3.2 Vision Instruct family |
| Voice (P2) | Riva ASR/TTS NIM | Check Indian-language support first. Fall back to Android's on-device speech recogniser. |

### 9.3 Kotlin client (backend)
```kotlin
@Serializable data class ChatMessage(val role: String, val content: String)
@Serializable data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false,
)
@Serializable data class ChatChoice(val message: ChatMessage)
@Serializable data class ChatResponse(val choices: List<ChatChoice>)

class NimClient(private val http: HttpClient, private val apiKey: String) {
    private val baseUrl = "https://integrate.api.nvidia.com/v1"

    suspend fun chat(req: ChatRequest): String =
        http.post("$baseUrl/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body<ChatResponse>().choices.first().message.content
}
```
For the mentor, set `stream = true` and forward the server-sent events to the app so text appears as it is generated.

### 9.4 Question generation contract
The LLM must return **only** this JSON, which is then validated:
```json
{
  "topic": "algebra.linear_equations",
  "difficulty": 2,
  "type": "mcq",
  "question": "Solve for x: 3x + 5 = 20",
  "options": ["3", "5", "15", "25/3"],
  "answerIndex": 1,
  "explanation": "Subtract 5 from both sides to get 3x = 15, then divide by 3.",
  "hint": "First isolate the term with x."
}
```

### 9.5 Validation pipeline (no bad question reaches a learner)
1. **Schema check:** parse with kotlinx.serialization and reject anything malformed.
2. **Independent solve:** a second NIM call answers the question without seeing the key. If its answer differs from `answerIndex`, discard the question.
3. **Maths check in code:** for numeric or algebra items, evaluate the answer in Kotlin with an expression library such as exp4j or a simple equation solver.
4. **Store as pending, then approve.** Only validated questions enter the `questions` collection.
5. **Report button:** learners can flag a question, and 2+ flags pull it for review.
6. **Demo safety:** demo paths use the **vetted bank** in `content/`, and AI generation runs on top of it.

### 9.6 Prompting rules
- The system prompt sets the learner's world, level and topic, and says: *"Guide, don't give away answers."*
- Explain at the learner's level. For school learners, use short sentences and a worked example.
- The mentor never invents a learner's scores. It gets real mastery data from the backend in the prompt.
- Keep temperature low (0.1–0.3) for questions and moderate (0.5–0.7) for mentor chat.

---

## 10. Learner model (deterministic, in Kotlin)

```kotlin
// Ratings: learner-topic and question both start near 1000.
fun expected(learner: Double, question: Double): Double =
    1.0 / (1.0 + 10.0.pow((question - learner) / 400.0))

fun updateRating(learner: Double, question: Double, correct: Boolean, k: Double = 32.0): Double =
    learner + k * ((if (correct) 1.0 else 0.0) - expected(learner, question))

// Mastery 0–100 shown in the UI (rating 800 → 0, 1600 → 100).
fun mastery(rating: Double): Int = ((rating - 800) / 8).roundToInt().coerceIn(0, 100)

// Pick the question whose predicted success is closest to the 70–85% target zone.
fun pickNext(learner: Double, pool: List<Question>, target: Double = 0.775): Question =
    pool.minBy { abs(expected(learner, it.rating) - target) }
```

Rules:
- If rolling accuracy over the last 5 answers is **above 85%**, step difficulty up. If it is **below 70%**, step down and show a hint first.
- A **boss battle** unlocks when topic mastery is at least 80, and it is passed at 80% or more.
- **Spaced review:** schedule revisit quests 1, 3, 7 and 21 days after mastery.
- **XP multiplier:** `xp = base × (1 + masteryGain / 20)`, reduced to ×0.5 for topics already mastered.

---

## 11. Data model (MongoDB)

```kotlin
@Serializable data class User(
    @SerialName("_id") val id: String,
    val name: String, val email: String, val passwordHash: String,
    val learnerType: LearnerType, val goal: Goal, val dailyMinutes: Int,
    val level: Int = 1, val xp: Int = 0, val coins: Int = 0,
    val streakDays: Int = 0, val badges: List<String> = emptyList(),
    val isMinor: Boolean, val parentConsentAt: Long? = null,
)

@Serializable data class TopicMastery(       // collection: mastery
    val userId: String, val topicId: String,
    val rating: Double, val mastery: Int,
    val lastPracticedAt: Long, val nextReviewAt: Long?,
)

@Serializable data class Question(           // collection: questions
    @SerialName("_id") val id: String,
    val topicId: String, val type: String, val difficulty: Int, val rating: Double,
    val question: String, val options: List<String>, val answerIndex: Int,
    val explanation: String, val hint: String,
    val source: String,                      // "vetted" | "nim"
    val flags: Int = 0,
)

@Serializable data class Quest(              // collection: quests
    @SerialName("_id") val id: String,
    val title: String, val topicId: String, val isBoss: Boolean,
    val questionIds: List<String>, val xpReward: Int,
)

@Serializable data class Attempt(            // collection: attempts
    val userId: String, val questionId: String, val questId: String,
    val correct: Boolean, val timeMs: Long, val usedHint: Boolean, val at: Long,
)
```
Other collections: `topics` (world, subject, parent and prerequisites, which form the skill tree), `goals` and `badges`.

## 12. API (Ktor routes)

```
POST /auth/register             POST /auth/login           GET  /auth/me
POST /onboarding                GET  /onboarding/diagnostic
POST /onboarding/diagnostic     → returns the starting mastery map

GET  /map                       → skill tree with mastery and lock state
GET  /quests/next               → learner model picks the next quest
GET  /quests/{id}
POST /quests/{id}/answer        → correctness, new mastery, hint, XP
POST /quests/{id}/complete      → XP, level-up, badges, unlocks

GET  /progress                  GET  /profile              GET /streak

POST /ai/mentor                 → streaming mentor reply (NIM)
POST /ai/scan                   → image or OCR text → validated 5-question quest (NIM VLM/LLM)
POST /questions/{id}/report
```

---

## 13. Team and ownership: Triple Threats

| Member | Role | Owns |
|---|---|---|
| **K. Satya Harshith** | Team lead · Backend | Ktor server, MongoDB, JWT auth, XP, levels, badges, streaks, progress APIs, deployment, integration and the demo run |
| **Ake Sri Ram** | Frontend (Android) | Compose UI, onboarding, learning map and skill tree, quest screens, mentor chat UI, profile, haptics and animations, offline cache |
| **P. Nikhil Durga Reddy** | AI | NIM client, prompts, question generation and validation pipeline, mentor, Scan-to-Quest, learner-model tuning, vetted question bank |

Shared: the API contract (§12), which is agreed on day 1 and changed only with all three agreeing.

## 14. Hackathon scope

**Build fully (P0):** onboarding and diagnostic, per-topic mastery, the quest loop, XP and levels, boss battle, weak-topic detection, AI mentor, Scan-to-Quest, haptics.
**Build lightly (P1):** streaks, badges, a skill tree lit by mastery, offline cache.
**Pitch only (P2):** leaderboard, multiplayer, career roadmaps, voice, parent dashboard.

**Three demos, one engine:**
1. **School:** Maths → Algebra (the hero path, built in depth)
2. **Competitive exam:** SSC → Quantitative Aptitude (same engine, different content)
3. **Technical:** Java → OOP (same engine, different content)

### 14.1 Build order
1. **Foundation:** repos, Ktor skeleton, Atlas, auth, Compose theme, API contract
2. **Core loop:** onboarding, diagnostic, learner model, quest play, XP
3. **AI:** NIM client, mentor, question generation with validation, Scan-to-Quest
4. **Game feel:** map and skill tree, boss battle, level-up animation and haptics
5. **Polish:** vetted bank for the 3 demo paths, offline cache, bug fixes, demo rehearsal

## 15. Demo script (3 minutes)

1. **Hook (20 s):** "Two students got 60% in maths. One is weak at algebra, one at geometry. Every app gives them the same next chapter."
2. **Onboarding (30 s):** a Class 9 student takes the diagnostic. The map shows Algebra at 40%, marked weak.
3. **Adaptive quest (60 s):** two wrong answers, then the mentor gives a hint rather than the answer. Difficulty drops, then climbs, and the mastery bar moves live.
4. **Boss battle (30 s):** a win triggers haptics and the level-up animation, and Geometry unlocks.
5. **Scan-to-Quest (30 s):** photograph a physics page and get an instant quest.
6. **Universality (20 s):** switch the profile to an SSC aspirant. It's the same engine with different content.
7. **Close (10 s):** "Masteria: the AI finds your weak spot and builds your next quest around it."

## 16. Success metrics

- **Learning:** mastery gain on weak topics, and accuracy improvement after personalised quests
- **Engagement:** quest completion rate, day-7 return rate, streaks kept
- **Adaptation:** share of answers inside the 70–85% target zone
- **Quality:** share of AI questions rejected by validation, and flagged questions per 1,000 served

## 17. Business model (pitch)

- **Free:** core quests, daily streaks, a limited number of mentor messages
- **Paid plan:** unlimited AI mentor, mock tests, parent reports
- **Institutions:** licences for schools and coaching centres

## 18. Risks and mitigations

| Risk | Mitigation |
|---|---|
| AI generates a wrong answer key | Validation pipeline (§9.5), a vetted bank for the demo, and the report button |
| NIM rate limits or outage during the demo | Pre-generate and cache demo content, and fall back to the vetted bank |
| API key leaks from the app | All NIM calls go through the backend, and the key is in server environment variables only |
| Learners farm easy XP | XP weighted by mastery gain, and levels gated by boss battles |
| Scope creep with 3 people | Strict P0/P1/P2 list, with one path built in depth |
| Minors' data | Parental consent, minimal data, deletion on request |

## 19. Glossary

- **Quest:** a short set of questions on one topic, with an XP reward.
- **Boss battle:** a mastery test that gates a level-up or topic unlock.
- **Mastery:** a 0–100 score per topic from the learner model.
- **World:** a learner category (School, College, Exams, Skills, Career).
- **Learner model:** deterministic Kotlin code that decides difficulty and what comes next.
- **NIM:** NVIDIA Inference Microservices, the AI endpoints used for all generation.
- **Vetted bank:** hand-checked questions used for the demo paths and as a fallback.
