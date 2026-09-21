# Masteria backend

Ktor 3.6 (Kotlin 2.4, JDK 17) server for the Masteria Android app. The API contract is
[`docs/API.md`](../docs/API.md); tracks and the vetted question bank live in [`content/`](../content)
and are bundled into the jar.

## Run

```bash
cd backend
cp .env.example .env          # then set NVIDIA_API_KEY
./gradlew run                 # dev server on http://0.0.0.0:8080
./gradlew test                # 50 unit + API-flow tests
./gradlew buildFatJar         # build/libs/masteria-backend.jar
java -jar build/libs/masteria-backend.jar
python scripts/smoke.py       # end-to-end check against a running server (uses live NIM)
```

Docker (build from the repo root, because the image bundles `content/`):

```bash
docker build -f backend/Dockerfile -t masteria-backend .
docker run -p 8080:8080 -e NVIDIA_API_KEY=nvapi-... -v masteria-data:/app/data masteria-backend
```

### Connecting the app
- Emulator: `http://10.0.2.2:8080` (the default when `BACKEND_URL` is unset).
- Phone over USB: `adb reverse tcp:8080 tcp:8080` and use `http://127.0.0.1:8080`.
- Phone over Wi-Fi: `http://<PC LAN IP>:8080` (allow Java through the Windows firewall).

Set the build default with `BACKEND_URL=...` in `android/local.properties`, or change it at runtime
from the app's Settings screen.

## Environment

Real environment variables win over `backend/.env`.

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | Binds `0.0.0.0` |
| `NVIDIA_API_KEY` | – | Without it every AI route serves its deterministic fallback |
| `NIM_BASE_URL` | `https://integrate.api.nvidia.com/v1` | OpenAI-compatible |
| `NIM_CHAT_MODEL` | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` | Mentor, explain, generation |
| `NIM_FALLBACK_MODELS` | `openai/gpt-oss-20b,meta/llama-3.2-11b-vision-instruct` | Tried in order on 404/429/5xx/timeout |
| `NIM_VERIFY_MODEL` | `openai/gpt-oss-20b` | Independently re-solves generated questions |
| `NIM_VISION_MODEL` | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` | Scan-to-Quest image transcription |
| `JWT_SECRET` | generated | Persisted to `DATA_DIR/jwt-secret.txt` when absent |
| `DATA_DIR` | `./data` | JSON store `masteria-db.json` |
| `MONGODB_URI` | – | Optional; switches the store to MongoDB |
| `TIMEZONE` | `Asia/Kolkata` | Streaks, "today", greetings |
| `CONTENT_DIR` | – | Read content from disk instead of the classpath |

## Model notes
- Nemotron gets `chat_template_kwargs: {enable_thinking: false}`, which brings replies to 1–12 s. Any
  `<think>` blocks are stripped before use.
- `gpt-oss-20b` may put its answer in `reasoning_content` when `max_tokens` is small, so it gets ≥ 1500.
- `llama-3.2-11b-vision` is fast and reads images, but it is weak at maths, so it is never the verifier.
- `/quests/{id}/answer` never calls AI. Generation runs in background top-up jobs, deduplicated per topic.

Smoke-test figures (live NIM, local server): `/answer` ≤ 35 ms, mentor first token ≈ 0.5 s,
explain ≈ 1.4 s, 5-question scan quest from text ≈ 20 s.

## Learner model (`learner/`)
- **Elo.** Learner rating vs. question rating, `mastery = (rating − 800) / 8` clamped to 0–100.
  Learner K is 128 for a topic's first 15 answers, then 72. Questions drift with K = 8. Base question
  ratings are 1000 / 1200 / 1400 for difficulty 1 / 2 / 3.
- **Next question.** Aims for a predicted success of 0.775. If the last 5 answers are above 85 %
  correct it aims for 0.65 (harder); below 70 % it aims for 0.88 (easier) and shows the hint first.
- **Diagnostic.** 3 questions (difficulty 1/2/3) per diagnostic topic, weighted score →
  `mastery = 15 + 75 × score`. Mastery ≥ 85 tests the topic out.
- **Progression.** Boss opens at mastery 80 and needs 4/5 correct. Beating it unlocks dependent
  topics and schedules spaced reviews at 1, 3, 7 and 21 days. Level is capped at 2 + bosses defeated.
- **Question safety.** AI questions pass a schema check, an independent solve by a second model and,
  for linear equations, an exact Kotlin solve before a learner sees them. Learners can report
  questions, and a question with 2 reports is retired.
