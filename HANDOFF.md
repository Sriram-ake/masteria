# Masteria — Handoff

**Snapshot date:** 2026-09-19
**Team:** Triple Threats (iQOO Hackathon 2026, theme: Smart Education)
**Tagline:** Learn. Quest. Level Up.

This document is for whoever picks up the project next, whether a teammate or a new AI session. It
covers what exists, how it fits together, what has been verified, what hasn't, and what to do next.
For step-by-step commands, see **[RUN_GUIDE.md](RUN_GUIDE.md)**.

---

## Contents

1. [Status at a glance](#1-status-at-a-glance)
2. [What the product does](#2-what-the-product-does)
3. [Repository layout](#3-repository-layout)
4. [Backend in detail](#4-backend-in-detail)
5. [Android app in detail](#5-android-app-in-detail)
6. [Content (tracks and question bank)](#6-content-tracks-and-question-bank)
7. [AI layer (NVIDIA NIM)](#7-ai-layer-nvidia-nim)
8. [Learner model and game rules](#8-learner-model-and-game-rules)
9. [What has been verified](#9-what-has-been-verified)
10. [Known gaps, risks and caveats](#10-known-gaps-risks-and-caveats)
11. [Recommended next steps](#11-recommended-next-steps)
12. [Demo script mapped to the build](#12-demo-script-mapped-to-the-build)
13. [Decisions log](#13-decisions-log)
14. [Where to find things (file map)](#14-where-to-find-things-file-map)

---

## 1. Status at a glance

| Area | State | Evidence |
|---|---|---|
| Backend (Ktor) | ✅ Complete against the API contract | 50/50 tests pass; live smoke test passes end to end |
| NVIDIA NIM integration | ✅ Working with the team key | Mentor first token ≈ 0.5 s, explain ≈ 1.4 s, scan quest ≈ 20 s |
| Question bank | ✅ 180 questions, keys machine-verified | 179 checked by Python/javac, 1 by hand |
| Android app | ✅ Builds; runs on the Motorola Edge 50 Pro | Guest sign-in → onboarding → diagnostic → Today → Mentor → Map → Profile all returned HTTP 200 on the phone |
| Quest / boss / scan UI on a real device | ⚠ Not yet walked through on the phone | Backend side verified by smoke test; see §10 |
| Docker image | ⚠ Written, not built (Docker not installed on dev PC) | `backend/Dockerfile` |
| Version control | ⚠ Folder is **not a git repository** yet | `.gitignore` is ready |

**One-line summary:** everything needed for the demo exists and the core path works on the phone.
What's left is a full on-device pass of the quest, boss and scan screens, demo rehearsal, and polish.

---

## 2. What the product does

Masteria is an adaptive learning RPG. A learner takes a short diagnostic, the app finds their
weakest topic, and it builds bite-sized "quests" around it. Questions adapt in difficulty as they
answer, mastery bars move live, and XP is earned by **mastery gained**, not time spent. Bosses gate
level-ups and unlock the next topics on a skill-tree map.

One engine, three demo worlds:

| Track id | Name | World | Subject |
|---|---|---|---|
| `school-math` | Math Kingdom | School | Mathematics · Class 9 (the hero path) |
| `ssc-quant` | SSC Quant Arena | Exams | SSC CGL Quantitative Aptitude |
| `java-oop` | Java Citadel | Skills | Java Object-Oriented Programming |

AI features (all via the backend, powered by NVIDIA NIM):

- **Mentor:** streaming chat tutor that knows the learner's real mastery and guides without
  giving answers away.
- **Mistake analysis:** after a wrong answer, 2–3 sentences on the likely misconception.
- **Scan-to-Quest:** photograph a textbook page and get a validated 5-question quest.
- **Question generation:** tops up the question pool in the background. Every AI question is
  independently re-solved by a second model before a learner sees it.

The full product spec is in [`context.md`](context.md) (source of truth) and the original brief is in
[`Education_RPG_Universal_Product_Specifications.md`](Education_RPG_Universal_Product_Specifications.md).

---

## 3. Repository layout

```
AKUBHAAI/
├── RUN_GUIDE.md                 ← how to run everything (commands)
├── HANDOFF.md                   ← this file
├── context.md                   ← product spec, source of truth
├── Education_RPG_Universal_Product_Specifications.md
├── .gitignore                   ← covers backend/.env, data, build dirs, local.properties, *.apk
├── docs/
│   └── API.md                   ← the API contract both sides code against
├── content/
│   ├── tracks.json              ← 3 tracks × 6 topics, prerequisites, icons
│   └── questions/
│       ├── school-math.json     ← 60 vetted questions
│       ├── ssc-quant.json       ← 60 vetted questions
│       └── java-oop.json        ← 60 vetted questions (with code snippets)
├── backend/                     ← Kotlin/Ktor server
│   ├── .env                     ← secrets (git-ignored)
│   ├── .env.example
│   ├── Dockerfile
│   ├── README.md
│   ├── scripts/smoke.py         ← end-to-end test against a live server
│   ├── data/                    ← runtime database (git-ignored)
│   └── src/main/kotlin/com/triplethreats/masteria/...
├── android/                     ← Jetpack Compose app
│   ├── local.properties         ← sdk.dir + BACKEND_URL (git-ignored)
│   └── app/src/main/java/com/triplethreats/masteria/...
├── .agents/skills/apple-design  ← design skill used for the UI (installed via `npx skills`)
└── .claude/                     ← Claude Code project config (links the skill)
```

---

## 4. Backend in detail

**Stack:** Kotlin 2.4.20 · Ktor 3.6.0 (Netty) · kotlinx.serialization · JWT (HS256) · bcrypt ·
Logback · JDK 17 · Gradle 9.7.1 wrapper. Optional MongoDB driver 5.5.

**Package:** `com.triplethreats.masteria` in `backend/src/main/kotlin/com/triplethreats/masteria/`

| File | Responsibility |
|---|---|
| `Application.kt` | Server bootstrap: plugins (JSON, auth, status pages, call logging), wiring, binds `0.0.0.0:PORT` |
| `Config.kt` | Reads env vars, falls back to a tiny `.env` parser; generates/persists `JWT_SECRET` |
| `Common.kt` | Shared helpers and error types |
| `routes/Routes.kt` | Every HTTP route in `docs/API.md` |
| `routes/Dtos.kt` | Request/response DTOs matching the contract field-for-field |
| `service/GameService.kt` | Game logic: onboarding, diagnostic, quests, XP, levels, badges, streaks, progress |
| `learner/Elo.kt` | Elo ratings ↔ mastery maths |
| `learner/Adaptive.kt` | Next-question selection (target success rate, hint-first, repeat avoidance) |
| `learner/Progression.kt` | XP, levels, boss cap, streaks, spaced review |
| `learner/SkillTree.kt` | Topic status (locked / available / mastered), weak topics, boss readiness |
| `content/Content.kt`, `content/QuestionBank.kt` | Loads tracks and questions from the classpath (or `CONTENT_DIR`) |
| `ai/NimClient.kt` | NIM HTTP client: model fallback chain, timeouts, streaming, online/latency tracking |
| `ai/AiService.kt` | Mentor prompt building + SSE, explain, scan, deterministic fallbacks |
| `ai/QuestionPipeline.kt` | Generate → schema check → independent solve → Kotlin solve → store; background top-up |
| `ai/Validation.kt` | Schema checks, JSON extraction, `<think>` stripping, linear-equation solver |
| `db/Store.kt` | Storage interface |
| `db/JsonFileStore.kt` | Default store: in-memory + debounced atomic writes to `DATA_DIR/masteria-db.json` |
| `db/MongoStore.kt` | Used when `MONGODB_URI` is set |
| `db/Models.kt` | Persisted records (users, topic mastery, attempts, quests, badges, daily activity) |

**Tests** (`backend/src/test/kotlin/...`): `LearnerModelTest.kt` (39 tests), `ValidationTest.kt` (9),
`ApiFlowTest.kt` (2 full HTTP flows using fixture content in `src/test/resources/fixtures`).

**Key behaviours**

- `/quests/{id}/answer` never calls AI and returns in < 200 ms (measured ≤ 33 ms).
- `/quests/{id}/complete` is idempotent. Calling it twice returns the same summary.
- Numeric answers accept `5`, `5.0`, `+5`, `10/4`, `1,000`, compared with tolerance 1e-6.
- Under-18 learners need `parentConsent: true` at onboarding (400 otherwise).
- `DELETE /auth/me` removes the user and all their data (DPDP requirement).
- Errors are always `{"error": "..."}` with a sensible status code. Bodies and keys are never logged.

**Persistence:** JSON file store is the default and the one that has been tested. It is a single
process store, so run one server instance only. MongoDB support exists but has not been exercised
(no MongoDB on the dev machine).

---

## 5. Android app in detail

**Stack:** Kotlin 2.4.20 · AGP 9.4.1 · Jetpack Compose (BOM 2026.09.00) · Material 3 (heavily
restyled) · Navigation Compose · DataStore · OkHttp 4.12 (incl. SSE) · kotlinx.serialization ·
Haze 1.7.3 (backdrop blur) · CameraX 1.6.2 · ML Kit text recognition 16.0.1.
`minSdk 26`, `targetSdk 36`, `compileSdk 37`. Package `com.triplethreats.masteria`. ~8,000 lines.

### 5.1 Screens and flow

```
Welcome ─▶ Auth (sign up / sign in / guest) ─▶ Onboarding (6 steps) ─▶ Diagnostic (9 Qs + results)
                                                                          │
   ┌──────────────────────────────────────────────────────────────────────┘
   ▼
Main (floating glass tab bar)
 ├─ Today    – greeting, level ring, recommended quest, boss-ready, reviews due, daily goal, Scan card
 ├─ Map      – skill tree lit by mastery; tap a topic → draggable bottom sheet → start quest / boss
 ├─ Scan     – CameraX viewfinder, on-device OCR, gallery fallback, built-in "Sample" page
 ├─ Mentor   – iMessage-style streaming chat
 └─ Profile  – level, badges, top skills, track switcher, → Progress, → Settings

Quest (full-screen push) – question → feedback panel (+ AI mistake analysis, mentor) → next …
   └─ Boss intro (for boss quests) and Summary (XP breakdown, confetti, level-up, unlocks)
Progress – 7-day XP bar chart, mastery sparklines per topic, target-zone share
Settings – server address + Test, NIM status, sign out, delete account
```

Onboarding steps: learner type → goal → level → daily minutes → world (track) → under-18 consent.

### 5.2 Source map (`android/app/src/main/java/com/triplethreats/masteria/`)

| Path | What's there |
|---|---|
| `MasteriaApp.kt`, `MainActivity.kt` | App container (Session + Api), splash, edge-to-edge |
| `data/Api.kt` | All HTTP calls, error mapping, `/health` ping, mentor SSE as a Flow |
| `data/Models.kt` | DTOs mirroring `docs/API.md` |
| `data/Session.kt` | DataStore: JWT, server URL, cached Home and Map (offline fallback) |
| `ui/AppNav.kt` | Navigation graph with iOS-style push and modal transitions |
| `ui/theme/` | `Theme.kt` (iOS system colours, light/dark), `Type.kt` (Inter variable font with optical sizing), `Motion.kt` (Apple spring parameters), `Haptics.kt` |
| `ui/components/` | `Components.kt` (buttons, cards, rings, bars, error card), `Glass.kt` (blurred glass surfaces), `Sheet.kt` (gesture-driven bottom sheet), `Scaffold.kt` (large-title screens), `Fields.kt`, `Icons.kt` (API icon names → vectors), `Celebration.kt` (confetti) |
| `ui/welcome/` | Welcome, Auth |
| `ui/onboarding/` | Onboarding steps, Diagnostic + animated results |
| `ui/home/TodayScreen.kt` | Today tab |
| `ui/map/MapScreen.kt` | Skill tree + topic sheet |
| `ui/quest/` | `QuestViewModel.kt`, `QuestScreen.kt`, `QuestionViews.kt` (MCQ, custom numeric keypad, code block, hint), `QuestSummary.kt` |
| `ui/mentor/` | `MentorChat.kt` (reusable stream chat), `MentorScreen.kt` |
| `ui/scan/ScanScreen.kt` | Camera + OCR + review + sample page |
| `ui/profile/` | Profile, Progress (charts), Settings |

### 5.3 Design system (Apple/iOS-level, from the `apple-design` skill)

- **Springs, not durations** (`Motion.kt`): Apple's damping + response mapped to Compose springs:
  `default` (1.0/0.4 s), `snappy` (1.0/0.28), `sheet` (0.86/0.32), `bouncy` (0.72/0.45, only after
  momentum or celebrations), `progress` (1.0/0.75). All are interruptible and carry velocity.
- **Bottom sheet** (`Sheet.kt`): 1:1 finger tracking, momentum projection (`project()` with
  deceleration 0.998), rubber-banding at the edges (`rubberband()`, constant 0.55), velocity handoff
  on release.
- **Materials:** Haze backdrop blur on the tab bar, nav bars and sheets.
- **Colour:** iOS system palette (grouped backgrounds, label/secondaryLabel/separator), with light
  and dark themes that follow the phone's system setting.
- **Type:** Inter variable font with optical sizing and iOS-like tracking.
- **Haptics** (`Haptics.kt`): tap, selection, success, error, celebrate (pattern), rumble (boss).
- **Reduced motion:** respected globally. Springs become short cross-fades.
- **Feedback on press:** buttons scale on pointer-down, not on release.

### 5.4 Networking

- Server URL = saved value in Settings, else `BuildConfig.BACKEND_URL` (from `local.properties`,
  default `http://10.0.2.2:8080`). The current build uses `http://127.0.0.1:8080` with
  `adb reverse` (see RUN_GUIDE §7).
- Cleartext HTTP is allowed for development (`res/xml/network_security_config.xml`).
- The NVIDIA key is **never** in the app.

---

## 6. Content (tracks and question bank)

- `content/tracks.json`: 3 tracks × 6 topics. Each topic has `id`, `name`, `description`, `icon`,
  `prereqs`, and `diagnostic: true` for the three root topics used by the diagnostic.

  | Track | Topics (prerequisites) |
  |---|---|
  | school-math | integers*, fractions*, linear_equations* ("Algebra") → geometry (linear_equations) → triangles (geometry); probability (fractions + linear_equations) |
  | ssc-quant | percentages*, ratio*, averages* → profit_loss (percentages) → interest (profit_loss); time_work (ratio) |
  | java-oop | basics*, classes*, encapsulation* → inheritance (classes) → polymorphism, interfaces (inheritance) |

  `*` = diagnostic / root topic.

- `content/questions/*.json`: 10 questions per topic (4 easy, 3 medium, 3 hard) = 180 total.
  ~30 % numeric in the maths tracks; Java is all MCQ with ≥ 4 code-snippet questions per topic.
  Answer positions are spread evenly. Indian context (₹, Indian names, SSC phrasing).
- **Verification:** every key was re-solved independently. 179 were checked by machine (Python
  arithmetic/sympy, and every Java snippet compiled and run with javac 17), 1 by hand. The
  checker scripts lived in the previous session's scratch folder and are not in the repo.
- The backend bundles `content/` into the jar at build time, so **rebuild the jar after editing content**.

---

## 7. AI layer (NVIDIA NIM)

**Endpoint:** `https://integrate.api.nvidia.com/v1/chat/completions` (OpenAI-compatible, Bearer auth).

**Model choices** (tested against this account on 2026-09-19; many catalogue models return 404 or
hang for 45–60 s on this key):

| Role | Model | Notes |
|---|---|---|
| Chat / mentor / explain / generation | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` | Sent with `chat_template_kwargs.enable_thinking=false` → 1–12 s; first stream token ~0.5 s |
| Fallback chain | `openai/gpt-oss-20b`, then `meta/llama-3.2-11b-vision-instruct` | gpt-oss needs `max_tokens ≥ 1500` or it returns empty `content` |
| Verifier (re-solves generated questions) | `openai/gpt-oss-20b` | Different model from the generator on purpose |
| Vision (Scan) | nemotron omni (~25 s with an image), fallback llama-3.2-11b-vision | llama vision is fast but weak at maths, so it is never the verifier |

**Timeouts:** connect 5 s · generation 45 s · verify 30 s · explain 15 s · mentor first byte 20 s.
**Temperatures:** generation 0.2 · verify 0.0 · mentor 0.6 · explain 0.3.

**Validation pipeline** (no unchecked AI question reaches a learner):
1. Schema check (4 distinct options, index in range, lengths).
2. Independent solve by the verifier **without** the key. Discarded if its answer differs.
3. If it looks like a linear equation in x, it is solved exactly in Kotlin and compared.
4. Only survivors are stored (`source: "nim"`). Learner reports: 2 flags retire a question.

**Background top-up:** when a quest starts on a topic with fewer than 4 unseen approved questions,
one background job per topic generates 4 more near the learner's level. Quests never wait on AI.

**Fallbacks:** if NIM is down, the mentor streams a deterministic reply built from the topic's
vetted hint/explanation, and explain returns the stored explanation with `source: "fallback"`.
The demo therefore still works offline from NVIDIA, just less smart.

---

## 8. Learner model and game rules

All of this is plain Kotlin in `backend/.../learner/` with unit tests.

| Rule | Value |
|---|---|
| Mastery from rating | `mastery = clamp((rating − 800) / 8, 0, 100)`, so rating = 800 + 8 × mastery |
| Question base rating | difficulty 1 → 1000, 2 → 1200, 3 → 1400; drifts with Elo K = 8 |
| Learner K | 128 for the first 15 answers on a topic, then 72 (so mastery visibly moves within one quest) |
| Target success | 0.775 by default. Rolling accuracy (last 5) > 85 % → 0.65 (harder); < 70 % → 0.88 (easier) **and hint shown first** |
| Question choice | Skips questions already served in the session and questions with ≥ 2 flags, prefers ones not seen in the last 30 attempts |
| Diagnostic | 3 Qs per root topic (difficulty 1/2/3) = 9. Score weighted 1/2/3 ÷ 6; mastery = 15 + 75 × score. ≥ 85 → tested out (counts as mastered) |
| Non-diagnostic topics | Start at mastery 15 |
| Topic status | mastered if boss defeated; available if all prereqs mastered; else locked |
| Boss | Ready at mastery ≥ 80. 5 questions of difficulty ≥ 2. Pass = 4/5 → unlocks dependents |
| Spaced review | After a boss: 1, 3, 7, 21 days. ≥ 80 % advances the stage, else back to 1 day |
| Quest | 5 questions, one topic |
| XP | base by avg difficulty (50/100/200); boss 500 win / 50 loss; × (1 + mastery gain / 20); × 0.5 if topic already mastered; +100 perfect; +25 first quest of the day; +250 boss defeated. Coins = XP / 10 |
| Levels | thresholds 0, 500, 1000, 2000, 3500, 5500, 8000, 11000, 14500, 18500, 23000, then +5000. Level capped at 2 + bosses defeated ("Defeat a boss to reach Level N") |
| Streak | Consecutive days (Asia/Kolkata) with ≥ 1 completed quest |
| Badges | First Quest, 7-Day Streak, Boss Slayer, Concept Master (any topic ≥ 90), Perfect Quest, Fast Learner (+15 mastery in one quest), Scan Explorer, Goal Getter |

---

## 9. What has been verified

**Backend unit/API tests:** `gradlew test` → 50/50 pass.

**Live smoke test** (`backend/scripts/smoke.py`, real server + real NIM, 2026-09-19), all checks passed:

```
weakest = math.linear_equations: Algebra needs work — your first quest is built around it.
home recommends Algebra (The Balance Trial)
q1 d1 mastery 28->35 · q2 d1 35->42 · q3 d1 42->47 (diff up) · q4 d2 47->55 · q5 d2 55->62
/answer latency max 33 ms
complete: +260 XP  [Quest reward 50, Mastery gain +34 → 85, Perfect score 100, Daily streak 25]
badges: First Quest, Concept Master, Perfect Quest, Fast Learner
map: integers/fractions mastered (tested out), Algebra available, geometry/probability/triangles locked
locked topic → 403 · minor without consent → 400 · duplicate email → 409 · short scan → 422
mentor streamed, first delta 516 ms · explain (nim) 1377 ms · scan quest "Scroll of Newton's First Law" 20 s
report → flags=1 · delete account → token rejected · guest login OK
```

**On the phone** (Motorola Edge 50 Pro, Android 16, via `adb reverse`): debug APK installed and
launched with no crashes. The Welcome screen renders correctly. Server logs show a full user pass
with every request 200: guest → tracks → onboarding → diagnostic (GET + submit) → home → mentor
stream (1.5 s) → map → profile.

---

## 10. Known gaps, risks and caveats

**Not yet verified on a device**
- Quest play, feedback panel, AI mistake analysis in-quest, boss intro, quest summary / level-up
  celebration, Progress charts, Settings server test, and Scan-to-Quest with the **real camera**.
  All of these are verified on the backend side, but nobody has walked through the screens on the
  phone yet. **This is the top priority.**
- Release build (`assembleRelease`) has not been built this session.
- Light theme has not been reviewed on the device.

**Operational**
- **USB tunnel is fragile:** `adb reverse` resets on unplug/sleep. For the demo, either re-run it
  before presenting, or switch to Wi-Fi / hotspot (RUN_GUIDE §7B) and set the URL in Settings.
- **The dev PC's IP was `192.168.1.2`** on the current Wi-Fi. It will differ on the venue network.
- JSON store is single-instance. Don't run two servers against the same `data/` folder.
- A saved server URL in the app overrides the build default. Clear with `adb shell pm clear com.triplethreats.masteria`.
- Phone screenshots over adb come out black when the display is off (not an app bug).

**Security**
- The NVIDIA key is in `backend/.env` (git-ignored). It was also pasted into an AI chat session
  while building, so **rotate the key after the hackathon** or before making the repo public.
- Cleartext HTTP is enabled for dev. Use HTTPS and remove it for any real deployment.
- Release APK is signed with the debug key (deliberate, for easy demo installs). Not for Play Store.

**Behaviour to watch**
- For general questions (not tied to a quest question) the mentor may show a full worked solution,
  e.g. "x = 4" for "How do I solve 2x + 3 = 11?". Guidance-only mode is enforced most strongly when
  the app sends a `questionId`. Tune the system prompt in `ai/AiService.kt` if judges should see
  "hint, not answer" for free-form questions too.
- NIM latency varies by time of day. Scan quests can take up to ~60 s on a slow run. The built-in
  **Sample** page on the Scan tab is the safe demo path.
- MongoDB store is implemented but untested.

**Repo hygiene**
- No git repository yet. Run `git init` and make a first commit (`.gitignore` is ready and already
  excludes `.env`, `data/`, `local.properties`, build output and APKs).

---

## 11. Recommended next steps

In priority order:

1. **Full on-device pass** on the Motorola: a normal quest (get 2 wrong on purpose → check hint-first
   and difficulty drop), mistake analysis, mentor from inside a quest, quest summary, raise Algebra
   to ≥ 80 and fight the boss (win → Geometry unlocks, level-up animation, haptics), Progress charts,
   Scan with the camera and with the Sample page, switch track to SSC in Profile, Settings → Test.
   Fix anything that looks off.
2. **Prepare a demo account** so the boss is ready on stage without grinding: play Algebra quests
   until mastery ≥ 80 (or script it with the API like `smoke.py` does), then leave it one boss away.
3. **Demo network plan:** decide USB-reverse vs hotspot. Rehearse the full 3-minute script (§12) twice.
4. `git init` + first commit, and push to the team remote.
5. Build and install `assembleRelease` for smoother animations on stage.
6. Optional polish: mentor "hint, not answer" tuning; light-theme review; pre-generate a few AI
   questions per demo topic so the "AI-generated" badge shows up during the demo.
7. After the event: rotate the NVIDIA key.

---

## 12. Demo script mapped to the build

From `context.md` §15 (3 minutes):

| Beat | What to show | Where in the app | Backend route |
|---|---|---|---|
| Hook (20 s) | Two students, same 60 %, different weak spots | – | – |
| Onboarding (30 s) | Class 9 learner takes the diagnostic; Algebra flagged weak | Onboarding → Diagnostic → results | `/onboarding`, `/onboarding/diagnostic` |
| Adaptive quest (60 s) | 2 wrong answers → hint first, difficulty drops then climbs, mastery bar moves live; mentor hints | Today → recommended quest → Quest | `/quests/start`, `/answer`, `/ai/explain`, `/ai/mentor` |
| Boss battle (30 s) | Win → haptics, level-up, Geometry unlocks | Map → Algebra → Boss | `/quests/start {boss:true}`, `/complete` |
| Scan-to-Quest (30 s) | Photograph a physics page → instant quest | Scan tab (camera, or **Sample**) | `/ai/scan` |
| Universality (20 s) | Switch to SSC aspirant: same engine, new content | Profile → track switcher | `PUT /profile` |
| Close (10 s) | "The AI finds your weak spot and builds your next quest around it." | – | – |

---

## 13. Decisions log

| Decision | Why |
|---|---|
| Native Android (Compose) instead of the original "mobile-first web" spec | `context.md` targets the iQOO phone angle: haptics, camera, on-device OCR, native motion |
| Ktor + Kotlin backend | Same language as the app; DTOs mirror 1:1 |
| JSON file store by default, MongoDB optional | Zero-setup demo; no database server needed on the laptop |
| All AI through the backend | Key never ships in the APK; fallbacks and validation in one place |
| Nemotron nano omni with thinking disabled | Fastest reliable model on this account, handles text + images; many others 404 or hang |
| Separate verifier model | A model re-checking its own output misses its own mistakes |
| Vetted bank + AI top-up (never blocking) | A wrong key in front of judges is the worst outcome; AI adds variety without risk |
| Elo-based mastery with high early K | Mastery must visibly move within one 5-question quest for the demo |
| XP from mastery gain, levels capped by bosses | Stops XP farming on easy questions (spec risk table) |
| On-device OCR before sending to NIM | Faster and cheaper; the photo itself is only uploaded when OCR finds under 40 characters |
| `adb reverse` for phone testing | Works regardless of Wi-Fi/firewall; phone reaches the PC as `127.0.0.1` |

---

## 14. Where to find things (file map)

| I want to… | Go to |
|---|---|
| Run anything | [`RUN_GUIDE.md`](RUN_GUIDE.md) |
| See every endpoint and DTO | [`docs/API.md`](docs/API.md) |
| Understand the product and pitch | [`context.md`](context.md) |
| Change a route | `backend/src/main/kotlin/com/triplethreats/masteria/routes/Routes.kt` |
| Change XP / levels / badges / streaks | `backend/.../learner/Progression.kt`, `backend/.../service/GameService.kt` |
| Tune adaptivity | `backend/.../learner/Elo.kt`, `backend/.../learner/Adaptive.kt` |
| Change AI prompts or models | `backend/.../ai/AiService.kt`, `backend/.../ai/QuestionPipeline.kt`, `backend/.env` |
| Add or fix questions | `content/questions/*.json` (then rebuild the backend jar) |
| Add a topic or track | `content/tracks.json` + questions + quest titles in `backend/.../learner/SkillTree.kt` |
| Change colours, type or motion | `android/.../ui/theme/` |
| Change a screen | `android/.../ui/<area>/` |
| Change the default server URL | `android/local.properties` → `BACKEND_URL` (rebuild) |
| Backend env var reference | `backend/.env.example`, `backend/README.md` |
| End-to-end test | `backend/scripts/smoke.py` |
