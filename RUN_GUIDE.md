# Masteria — Run Guide

How to run the backend, build and install the Android app, and connect the two. Every command is
listed with what it does and when you need it.

> All paths are relative to the project root `D:\Hackathons\AKUBHAAI`.
> Commands are given for **PowerShell** (Windows default). Where Git Bash differs, it is noted.

---

## Contents

1. [What you are running](#1-what-you-are-running)
2. [Prerequisites](#2-prerequisites)
3. [First-time setup](#3-first-time-setup)
4. [Run the backend](#4-run-the-backend)
5. [Check the backend is healthy](#5-check-the-backend-is-healthy)
6. [Build and install the Android app](#6-build-and-install-the-android-app)
7. [Connect the app to the backend](#7-connect-the-app-to-the-backend)
8. [Daily workflow (quick start)](#8-daily-workflow-quick-start)
9. [Testing](#9-testing)
10. [Resetting data](#10-resetting-data)
11. [Docker](#11-docker)
12. [Troubleshooting](#12-troubleshooting)
13. [Command reference](#13-command-reference)

---

## 1. What you are running

```
┌──────────────────────┐   HTTP/JSON + SSE    ┌──────────────────────┐   HTTPS    ┌──────────────────┐
│  Android app         │ ───────────────────▶ │  Ktor backend        │ ─────────▶ │  NVIDIA NIM API  │
│  (phone / emulator)  │   port 8080          │  (your PC, JDK 17)   │            │  (cloud LLMs)    │
└──────────────────────┘                      └──────────┬───────────┘            └──────────────────┘
                                                         │
                                              backend/data/masteria-db.json
                                              (users, mastery, quests, XP)
```

- **Android app** (`android/`): the Compose UI. It never talks to NVIDIA directly and never holds
  the API key. Every AI feature goes through the backend.
- **Backend** (`backend/`): Kotlin + Ktor server. Handles accounts, the adaptive learner model,
  quests, XP, badges and all NVIDIA NIM calls. Stores data in a JSON file by default.
- **Content** (`content/`): the three learning tracks and the 180 hand-checked questions. The
  backend bundles this folder into its jar at build time.

The only network link you have to set up yourself is **app → backend on port 8080**.

---

## 2. Prerequisites

| Tool | Version used | Check with | Needed for |
|---|---|---|---|
| JDK | 17 (`C:\Program Files\Java\jdk-17.0.20.1`) | `java -version` | Backend and Android build |
| Android SDK | platforms 36/37, build-tools 36 | Android Studio → SDK Manager | Android build |
| adb | 1.0.41 | `adb version` | Installing on a phone, USB tunnel |
| Python | 3.11 | `python --version` | Smoke test only |
| NVIDIA API key | `nvapi-...` | – | AI features (mentor, explain, scan, question generation) |

**Gradle is not needed.** Both projects ship a Gradle wrapper (`gradlew` / `gradlew.bat`) that
downloads Gradle 9.7.1 automatically on first use.

The Android SDK location must be in `android/local.properties` (see §3). On this machine it is
`C:\Users\nandu\AppData\Local\Android\Sdk`.

---

## 3. First-time setup

### 3.1 Backend secrets: `backend/.env`

The backend reads configuration from real environment variables first, then from `backend/.env`.

```powershell
cd D:\Hackathons\AKUBHAAI\backend
Copy-Item .env.example .env        # only if .env does not exist yet
notepad .env                       # set NVIDIA_API_KEY=nvapi-...
```

Minimum `.env`:

```ini
PORT=8080
NVIDIA_API_KEY=nvapi-your-key-here
NIM_BASE_URL=https://integrate.api.nvidia.com/v1
NIM_CHAT_MODEL=nvidia/nemotron-3-nano-omni-30b-a3b-reasoning
NIM_FALLBACK_MODELS=openai/gpt-oss-20b,meta/llama-3.2-11b-vision-instruct
NIM_VERIFY_MODEL=openai/gpt-oss-20b
NIM_VISION_MODEL=nvidia/nemotron-3-nano-omni-30b-a3b-reasoning
NIM_VISION_FALLBACK_MODELS=meta/llama-3.2-11b-vision-instruct
DATA_DIR=./data
TIMEZONE=Asia/Kolkata
```

All variables:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | Port the server listens on (always on all interfaces, `0.0.0.0`) |
| `NVIDIA_API_KEY` | – | NIM key. Without it the server still runs, and every AI route returns a built-in fallback answer |
| `NIM_BASE_URL` | `https://integrate.api.nvidia.com/v1` | OpenAI-compatible NIM endpoint |
| `NIM_CHAT_MODEL` | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` | Mentor, mistake analysis, question generation |
| `NIM_FALLBACK_MODELS` | `openai/gpt-oss-20b,meta/llama-3.2-11b-vision-instruct` | Tried in order if the main model fails or times out |
| `NIM_VERIFY_MODEL` | `openai/gpt-oss-20b` | Second model that re-solves AI-generated questions |
| `NIM_VISION_MODEL` | `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning` | Reads photos for Scan-to-Quest |
| `NIM_VISION_FALLBACK_MODELS` | `meta/llama-3.2-11b-vision-instruct` | Backup vision model |
| `JWT_SECRET` | auto-generated | Login token signing key; saved to `DATA_DIR/jwt-secret.txt` the first time |
| `DATA_DIR` | `./data` | Where the database file lives (relative to the folder you start the server from) |
| `MONGODB_URI` | – | Optional. If set, MongoDB is used instead of the JSON file |
| `TIMEZONE` | `Asia/Kolkata` | Used for streaks, "today" and greetings |
| `CONTENT_DIR` | – | Optional. Read tracks/questions from disk (e.g. `../content`) instead of the jar |

> `backend/.env` is git-ignored. Never commit it and never put the key in the Android app.

### 3.2 Android SDK path: `android/local.properties`

```properties
sdk.dir=C\:/Users/nandu/AppData/Local/Android/Sdk
BACKEND_URL=http://127.0.0.1:8080
```

- `sdk.dir` tells Gradle where the Android SDK is. Android Studio writes it automatically when you
  open the project. Note the escaped `C\:` and forward slashes.
- `BACKEND_URL` is the server address baked into the APK as the default. It is optional: without
  it the default is `http://10.0.2.2:8080`, which only works on the emulator. See §7 for which value
  to use.

This file is git-ignored because it is machine-specific.

---

## 4. Run the backend

Always start the server **from the `backend` folder**, so `.env` and `./data` resolve correctly.

### Option A: development mode (compiles and runs)

```powershell
cd D:\Hackathons\AKUBHAAI\backend
.\gradlew.bat run
```

- First run downloads Gradle and dependencies (a few minutes). Later runs start in ~20 s.
- Use this while changing backend code.
- Stop with `Ctrl + C`.

### Option B: fat jar (fastest start, recommended for demos)

```powershell
cd D:\Hackathons\AKUBHAAI\backend
.\gradlew.bat buildFatJar                 # builds build\libs\masteria-backend.jar (~45 MB)
java -jar build\libs\masteria-backend.jar # starts in under a second
```

- The jar contains everything, including `content/`. You can copy it to another machine and run it
  with just a JDK 17 and a `.env` (or environment variables).
- **Rebuild the jar** after any change to backend code or to `content/`.

### What a good start looks like

```
INFO  i.k.s.Application - Application started in 0.159 seconds.
INFO  i.k.s.Application - Responding at http://127.0.0.1:8080
INFO  c.t.m.ai.NimClient - NIM nvidia/nemotron-3-nano-omni-30b-a3b-reasoning ok in 1164 ms
INFO  c.t.m.ai.NimClient - NIM probe: online (nvidia/nemotron-3-nano-omni-30b-a3b-reasoning, 1164 ms)
```

- "Responding at http://127.0.0.1:8080" is just Ktor's log line. The server really listens on all
  interfaces (`0.0.0.0:8080`), so phones on your Wi-Fi can reach it.
- "NIM probe: online" means the API key works. If it says offline, the server still works and AI
  features use their fallbacks. Check the key and your internet connection.

### Stopping a server that runs in the background

```powershell
netstat -ano | findstr :8080          # last column is the PID
taskkill /PID <pid> /F
```

---

## 5. Check the backend is healthy

```powershell
curl.exe http://localhost:8080/health
```

Expected:

```json
{"status":"ok","ai":{"online":true,"chatModel":"nvidia/nemotron-3-nano-omni-30b-a3b-reasoning","visionModel":"nvidia/nemotron-3-nano-omni-30b-a3b-reasoning","lastLatencyMs":1164}}
```

> In PowerShell use `curl.exe`, not `curl`. Plain `curl` is an alias for `Invoke-WebRequest`
> and prints differently.

For a full end-to-end check, run the smoke test (§9.2).

---

## 6. Build and install the Android app

### 6.1 Build

```powershell
cd D:\Hackathons\AKUBHAAI\android
.\gradlew.bat assembleDebug
```

Output: `android\app\build\outputs\apk\debug\app-debug.apk` (~70 MB, debug build).

Release build (smaller and faster; signed with the debug key so it installs directly onto a demo phone):

```powershell
.\gradlew.bat assembleRelease
# android\app\build\outputs\apk\release\app-release.apk
```

> `BACKEND_URL` from `local.properties` is read **at build time**. If you change it, rebuild.

### 6.2 Enable USB debugging on the phone (one-time)

1. **Settings → About phone** → tap **Build number** 7 times. Developer options are now enabled.
2. **Settings → System → Developer options** → turn on **USB debugging**.
3. Plug the phone in via USB and accept the **"Allow USB debugging?"** prompt on the phone
   (tick "Always allow from this computer").

Check it is connected:

```powershell
adb devices
```

```
List of devices attached
ZD222NB59D      device
```

`ZD222NB59D` is the team's Motorola Edge 50 Pro. If it says `unauthorized`, accept the prompt on
the phone. If more than one device is listed, add `-s <serial>` to every adb command, for example
`adb -s ZD222NB59D install ...`.

### 6.3 Install and launch

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.triplethreats.masteria/.MainActivity
```

- `install -r` replaces an existing install and **keeps the app's data** (login, saved server address).
- To install fresh, uninstall first: `adb uninstall com.triplethreats.masteria`.

One-step alternative (builds and installs):

```powershell
.\gradlew.bat installDebug
```

---

## 7. Connect the app to the backend

The app needs a URL that reaches port 8080 on the PC running the backend. Pick the method that
matches your setup.

| Setup | URL the app uses | Extra step |
|---|---|---|
| **A. Phone over USB** (most reliable) | `http://127.0.0.1:8080` | `adb reverse tcp:8080 tcp:8080` |
| **B. Phone over Wi-Fi** (no cable) | `http://<PC IP>:8080`, e.g. `http://192.168.1.2:8080` | Same Wi-Fi + firewall allows Java |
| **C. Android emulator** | `http://10.0.2.2:8080` | None (default when `BACKEND_URL` is unset) |

There are **two ways to set the URL**. Both work with any method:

1. **At build time:** set `BACKEND_URL=...` in `android/local.properties` and rebuild. This becomes
   the default for fresh installs.
2. **At runtime (no rebuild):** open the app's **Settings → Server → Address**, type the URL, tap
   **Test**. If it shows **Connected**, the address is saved on the phone and used from then on.
   Settings is reachable from:
   - the **gear icon** (top right) on the Welcome screen,
   - **Profile → Settings**,
   - the **Server settings** button on the "can't connect" error card.

   A saved runtime address overrides the build-time default until the app's data is cleared.

### A. Phone over USB with `adb reverse` (recommended)

`adb reverse` forwards the phone's own `127.0.0.1:8080` through the USB cable to the PC's port
8080. No Wi-Fi, firewall or IP address needed.

```powershell
# 1. backend running on the PC (see §4)
# 2. phone plugged in, then:
adb reverse tcp:8080 tcp:8080
adb reverse --list                  # should print: UsbFfs tcp:8080 tcp:8080
```

App URL: `http://127.0.0.1:8080` (this is what the current `local.properties` builds in).

Check it from the phone's side:

```powershell
adb shell curl -s http://127.0.0.1:8080/health
```

> ⚠ **The reverse is lost whenever the phone is unplugged, the PC sleeps, or adb restarts.**
> If the app suddenly shows "Can't reach the server", run `adb reverse tcp:8080 tcp:8080` again.

### B. Phone over Wi-Fi

1. Put the phone and PC on the **same Wi-Fi network** (not a guest network; many college and
   hackathon networks block device-to-device traffic, so a phone hotspot is a good backup).
2. Find the PC's IP address:
   ```powershell
   ipconfig
   ```
   Look for **Wireless LAN adapter Wi-Fi → IPv4 Address**, e.g. `192.168.1.2`.
   (If the PC is connected to the phone's hotspot, the IP changes, so check again.)
3. Allow Java through Windows Firewall. The first time the server starts, Windows usually asks.
   Tick **Private networks** and allow. To check or add manually:
   **Windows Security → Firewall & network protection → Allow an app through firewall →
   "Java(TM) Platform SE binary" → Private ✔**.
   Or, from an **admin** PowerShell, open the port directly:
   ```powershell
   New-NetFirewallRule -DisplayName "Masteria 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
   ```
4. Test from the phone's browser: open `http://192.168.1.2:8080/health`. You should see the JSON
   from §5.
5. In the app: **Settings → Address** → `http://192.168.1.2:8080` → **Test** → **Connected**.
   Or put `BACKEND_URL=http://192.168.1.2:8080` in `local.properties` and rebuild.

### C. Android emulator

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds          # shows Pixel_9_Pro
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_9_Pro
```

`10.0.2.2` is the emulator's alias for the host PC's localhost. For the emulator, remove the
`BACKEND_URL` line from `local.properties` (or set it to `http://10.0.2.2:8080`), rebuild, and
install. No `adb reverse` needed.

### Why plain `http://` works

The app allows cleartext HTTP (`android/app/src/main/res/xml/network_security_config.xml`) because
the dev backend runs without TLS. For a public deployment, use an `https://` URL and remove
the cleartext permission.

---

## 8. Daily workflow (quick start)

Once setup is done, this is all you need each time.

**Terminal 1: backend**

```powershell
cd D:\Hackathons\AKUBHAAI\backend
java -jar build\libs\masteria-backend.jar
```

**Terminal 2: phone over USB**

```powershell
adb devices                          # phone listed as "device"
adb reverse tcp:8080 tcp:8080
adb shell am start -n com.triplethreats.masteria/.MainActivity
```

After changing **backend code or content**: stop the server, run `.\gradlew.bat buildFatJar`, start again.
After changing **app code**: `cd android; .\gradlew.bat installDebug`.

---

## 9. Testing

### 9.1 Backend unit and API tests

```powershell
cd D:\Hackathons\AKUBHAAI\backend
.\gradlew.bat test
```

50 tests: Elo and mastery maths, next-question selection, difficulty stepping, XP, levels, streaks,
numeric answer parsing, diagnostic scoring, question validation, and full API flows against an
in-memory server. These do **not** call NVIDIA. Reports: `backend\build\reports\tests\test\index.html`.

### 9.2 End-to-end smoke test (live server + live NIM)

With the server running:

```powershell
cd D:\Hackathons\AKUBHAAI\backend
$env:PYTHONIOENCODING="utf-8"; python scripts\smoke.py
# or against another address:
python scripts\smoke.py http://192.168.1.2:8080
```

It walks the whole learner journey: register → onboarding (incl. under-18 consent check) →
diagnostic (deliberately weak at algebra) → home recommends Algebra → 5-question quest →
complete (XP, badges) → map / progress / profile → locked topic rejected → mentor stream →
mistake analysis → Scan-to-Quest → report question → delete account → guest login. It ends with
`RESULT: PASS`, or exits non-zero at the first failure. It creates and then deletes its own test
accounts.

Last run (2026-09-19): all checks passed. `/answer` ≤ 33 ms, mentor first token ≈ 0.5 s,
mistake analysis ≈ 1.4 s, scan quest ≈ 20 s.

### 9.3 Watching the app

```powershell
adb logcat --pid=$(adb shell pidof com.triplethreats.masteria)     # Git Bash syntax
```

In PowerShell:

```powershell
$appPid = adb shell pidof com.triplethreats.masteria
adb logcat --pid=$appPid
adb logcat -b crash                        # only crashes
adb exec-out screencap -p > screen.png     # screenshot (Git Bash; see note)
```

> Screenshots come out **all black** if the phone's display is off. Wake it first
> (`adb shell input keyevent KEYCODE_POWER`). In PowerShell 5.1, `>` corrupts binary output, so
> take screenshots from Git Bash or use `adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png`.

The backend console logs every request with status and time (`200 POST /quests/.../answer 25ms`),
which is the quickest way to see what the app is doing.

---

## 10. Resetting data

| Goal | Command |
|---|---|
| Wipe all users and progress (server) | Stop the server, delete `backend\data\masteria-db.json`, start again |
| Log everyone out too | Also delete `backend\data\jwt-secret.txt` (a new secret is generated) |
| Reset the app on the phone (logout, saved server URL, cache) | `adb shell pm clear com.triplethreats.masteria` |
| Delete one account | In the app: **Settings → Delete account and data** |

If you wipe the server database but not the app, the app's saved login points at a user that no
longer exists. Sign out in the app or run `pm clear`.

---

## 11. Docker

Build from the **project root** (the image bundles `content/`):

```powershell
cd D:\Hackathons\AKUBHAAI
docker build -f backend/Dockerfile -t masteria-backend .
docker run -p 8080:8080 -e NVIDIA_API_KEY=nvapi-... -v masteria-data:/app/data masteria-backend
```

The `masteria-data` volume keeps the database between container restarts. Docker is not installed
on the dev PC, so this path has not been run yet. The jar route (§4B) is what has been tested.

---

## 12. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App: "Can't reach the server" | USB reverse lost / server not running / wrong URL | `curl.exe localhost:8080/health` on PC; `adb reverse tcp:8080 tcp:8080`; check Settings → Address |
| Works over USB, not over Wi-Fi | Firewall, different network, or client isolation on Wi-Fi | §7B steps 1–4; try a phone hotspot |
| Settings shows **NVIDIA NIM: Fallback** | Key missing/invalid or no internet on the PC | Check `NVIDIA_API_KEY` in `backend/.env`, restart server, check `/health` |
| Mentor replies are generic / "offline" style | NIM timed out, fallback text used | Look for `WARN ... NIM <model> -> HTTP ...` or `... timed out` in the server log; the client already retries the fallback models |
| Scan: "Couldn't build a reliable quest from that page" (422) | Too little text, or fewer than 3 AI questions passed validation | Use a clearer photo with more text, or the **Sample** page button |
| `Address already in use` / port 8080 busy | Another server instance is running | `netstat -ano \| findstr :8080`, then `taskkill /PID <pid> /F` |
| Gradle: "SDK location not found" | `android/local.properties` missing `sdk.dir` | Add `sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk` |
| Gradle: "filename, directory name, or volume label syntax is incorrect" | Backslashes in `sdk.dir` | Use forward slashes and escape the colon: `C\:/...` |
| `adb devices` shows `unauthorized` | USB debugging prompt not accepted | Unlock the phone, accept the prompt; replug |
| `adb` shows two devices, commands fail | Emulator + phone both connected | Add `-s ZD222NB59D` (or `-s emulator-5554`) |
| Old server behaviour after code change | Running a stale fat jar | Rebuild with `.\gradlew.bat buildFatJar` |
| Changed `BACKEND_URL` but app still uses old URL | Runtime address saved in the app overrides it | Change it in Settings, or `adb shell pm clear com.triplethreats.masteria` |
| Login fails after wiping the server | App holds a token for a deleted user | Sign out in the app or `pm clear` |

---

## 13. Command reference

### Backend (`cd backend`)

| Command | What it does |
|---|---|
| `.\gradlew.bat run` | Compile and run the server (dev) |
| `.\gradlew.bat test` | Run the 50 unit/API tests |
| `.\gradlew.bat buildFatJar` | Build `build\libs\masteria-backend.jar` |
| `java -jar build\libs\masteria-backend.jar` | Run the built server |
| `python scripts\smoke.py [url]` | End-to-end test against a running server |
| `curl.exe http://localhost:8080/health` | Health + AI status |
| `netstat -ano \| findstr :8080` | Find the process using port 8080 |
| `taskkill /PID <pid> /F` | Stop that process |

### Android (`cd android`)

| Command | What it does |
|---|---|
| `.\gradlew.bat assembleDebug` | Build the debug APK |
| `.\gradlew.bat assembleRelease` | Build the release APK (debug-signed) |
| `.\gradlew.bat installDebug` | Build and install on the connected device |
| `.\gradlew.bat clean` | Delete build output (use if a build behaves strangely) |

### adb

| Command | What it does |
|---|---|
| `adb devices` | List connected phones/emulators |
| `adb reverse tcp:8080 tcp:8080` | Phone's `127.0.0.1:8080` → PC's port 8080 over USB |
| `adb reverse --list` / `adb reverse --remove-all` | Show / remove USB forwards |
| `adb install -r <apk>` | Install or update, keeping app data |
| `adb uninstall com.triplethreats.masteria` | Remove the app |
| `adb shell am start -n com.triplethreats.masteria/.MainActivity` | Launch the app |
| `adb shell am force-stop com.triplethreats.masteria` | Kill the app |
| `adb shell pm clear com.triplethreats.masteria` | Wipe the app's data (logout, saved URL) |
| `adb logcat -b crash` | Show crash logs |
| `adb shell input keyevent KEYCODE_POWER` | Toggle the phone screen |
| `adb -s <serial> ...` | Target one device when several are connected |

### Backend API (quick manual checks)

The full contract is in [`docs/API.md`](docs/API.md). Examples (Git Bash):

```bash
# guest login → token
TOKEN=$(curl -s -X POST localhost:8080/auth/guest -H 'Content-Type: application/json' -d '{"name":"Test"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s localhost:8080/tracks
curl -s localhost:8080/home -H "Authorization: Bearer $TOKEN"
curl -N -X POST localhost:8080/ai/mentor -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"messages":[{"role":"user","content":"Explain linear equations"}]}'
curl -s -X DELETE localhost:8080/auth/me -H "Authorization: Bearer $TOKEN"   # clean up the test guest
```
