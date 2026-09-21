"""End-to-end smoke test against a running Masteria backend.

Usage:  python scripts/smoke.py [base_url]      (default http://localhost:8080)

Walks the whole learner journey (register -> onboarding -> diagnostic -> quest -> map/progress/profile
-> AI mentor/explain/scan -> report -> delete) and exits non-zero on the first failure.
Answer keys come from ../content so the diagnostic can deliberately miss linear equations.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "content", "questions")
KEYS = {}
for f in os.listdir(ROOT):
    for q in json.load(open(os.path.join(ROOT, f), encoding="utf-8")):
        KEYS[q["id"]] = q

token = None
failures = []


def call(method, path, body=None, expect=200, timeout=90):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    ms = (time.perf_counter() - t0) * 1000
    out = json.loads(raw) if raw else None
    ok = 200 <= status < 300 if expect == 200 else status == expect
    if not ok:
        raise SystemExit(f"FAIL {method} {path}: HTTP {status} (wanted {expect}) {raw[:300]!r}")
    return out, ms


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        failures.append(msg)


def answer_for(qid, correct):
    k = KEYS.get(qid)
    if k is None:  # AI-generated question, key unknown: just pick option 0
        return {"questionId": qid, "answerIndex": 0, "timeMs": 4000}
    if k["type"] == "mcq":
        idx = k["answerIndex"] if correct else (k["answerIndex"] + 1) % 4
        return {"questionId": qid, "answerIndex": idx, "timeMs": 4000}
    return {"questionId": qid, "answerText": k["answerText"] if correct else "99999", "timeMs": 4000}


print(f"Smoke test against {BASE}")
h, _ = call("GET", "/health")
check(h["status"] == "ok", f"health ok, ai online={h['ai']['online']}")

email = f"smoke{int(time.time())}@test.in"
a, _ = call("POST", "/auth/register", {"name": "Asha", "email": email, "password": "secret123"})
token = a["token"]
check(not a["user"]["onboarded"], "register -> not onboarded yet")
call("POST", "/auth/register", {"name": "Asha", "email": email, "password": "secret123"}, expect=409)
check(True, "duplicate email -> 409")
call("POST", "/auth/login", {"email": email, "password": "secret123"})
call("POST", "/onboarding", {"learnerType": "school", "goal": "grades", "selfLevel": "beginner",
                             "dailyMinutes": 15, "trackId": "school-math", "isMinor": True}, expect=400)
check(True, "minor without consent -> 400")
u, _ = call("POST", "/onboarding", {"learnerType": "school", "goal": "grades", "selfLevel": "beginner",
                                    "dailyMinutes": 15, "trackId": "school-math", "isMinor": True,
                                    "parentConsent": True})
check(u["onboarded"] and not u["diagnosed"], "onboarding -> onboarded, not diagnosed")

d, _ = call("GET", "/onboarding/diagnostic")
check(len(d["questions"]) == 9, f"diagnostic has {len(d['questions'])} questions")
leak = any("answerIndex" in q or "answerText" in q or "explanation" in q for q in d["questions"])
check(not leak, "diagnostic questions carry no answer key")
answers = [answer_for(q["id"], not (q["topicId"] == "math.linear_equations" and q["difficulty"] > 1))
           for q in d["questions"]]
r, _ = call("POST", "/onboarding/diagnostic", {"trackId": "school-math", "answers": answers})
check(r["weakestTopicId"] == "math.linear_equations", f"weakest = {r['weakestTopicId']}: {r['summary']}")

home, _ = call("GET", "/home")
rec = home["recommended"]
check(rec and rec["topicId"] == "math.linear_equations", f"home recommends {rec and rec['topicName']} ({rec and rec['questTitle']})")
print("       greeting:", home["greeting"], "| level", home["level"])

q, _ = call("POST", "/quests/start", {})
check(q["topicId"] == "math.linear_equations" and q["total"] == 5, f"quest '{q['title']}' started on {q['topicName']}")
qid, question, lat = q["id"], q["question"], []
while True:
    res, ms = call("POST", f"/quests/{qid}/answer", answer_for(question["id"], True))
    lat.append(ms)
    print(f"       q{res['answeredCount']} d{question['difficulty']} correct={res['correct']} "
          f"mastery {res['masteryBefore']}->{res['masteryAfter']} diff={res['difficultyChange']} {ms:.0f} ms")
    if res["done"]:
        break
    question = res["next"]
check(max(lat) < 200, f"/answer latency max {max(lat):.0f} ms (< 200)")
s, _ = call("POST", f"/quests/{qid}/complete")
s2, _ = call("POST", f"/quests/{qid}/complete")
check(s["xpEarned"] > 0 and s == s2, f"complete: +{s['xpEarned']} XP, idempotent; lines={[(l['label'], l['xp']) for l in s['breakdown']]}")
print("       next step:", s["nextStep"], "| badges:", [b["name"] for b in s["newBadges"]])

m, _ = call("GET", "/map")
st = {t["id"]: t["status"] for t in m["topics"]}
check(st["math.geometry"] in ("locked", "available"), f"map statuses: {st}")
p, _ = call("GET", "/progress")
check(p["totalAnswers"] >= 5 and len(p["xpLast7Days"]) == 7, f"progress: overall {p['overallMastery']}%, answers {p['totalAnswers']}, target-zone {p['targetZoneShare']}%")
pr, _ = call("GET", "/profile")
check(len(pr["badges"]) == 8, f"profile: {sum(b['earned'] for b in pr['badges'])}/8 badges earned")
call("POST", "/quests/start", {"topicId": "math.triangles"}, expect=403)
check(True, "locked topic -> 403")

# Mentor SSE: time to first delta
body = json.dumps({"messages": [{"role": "user", "content": "How do I solve 2x + 3 = 11?"}],
                   "topicId": "math.linear_equations"}).encode()
req = urllib.request.Request(BASE + "/ai/mentor", data=body, method="POST")
req.add_header("Content-Type", "application/json")
req.add_header("Authorization", "Bearer " + token)
t0 = time.perf_counter()
ttfb, text, done = None, "", False
with urllib.request.urlopen(req, timeout=60) as r:
    for line in r:
        line = line.decode("utf-8").strip()
        if not line.startswith("data:"):
            continue
        ev = json.loads(line[5:])
        if "delta" in ev:
            ttfb = ttfb or (time.perf_counter() - t0) * 1000
            text += ev["delta"]
        if ev.get("done"):
            done = True
            break
check(done and len(text) > 20, f"mentor streamed {len(text)} chars, first delta {ttfb:.0f} ms")
print("       mentor:", text[:160].replace("\n", " "), "...")

wrong = next(x for x in KEYS.values() if x["topicId"] == "math.linear_equations" and x["type"] == "mcq")
e, ms = call("POST", "/ai/explain", {"questionId": wrong["id"], "answerIndex": (wrong["answerIndex"] + 1) % 4})
check(len(e["analysis"]) > 10, f"explain ({e['source']}, {ms:.0f} ms): {e['analysis'][:120]}")

page = ("Newton's first law of motion states that an object at rest stays at rest and an object in motion "
        "stays in motion with the same speed and in the same direction unless acted upon by an unbalanced "
        "force. This tendency to resist changes in motion is called inertia. The more mass an object has, "
        "the greater its inertia. For example, passengers in a bus lurch forward when the bus stops suddenly "
        "because their bodies tend to keep moving. A book on a table stays still until someone pushes it.")
sc, ms = call("POST", "/ai/scan", {"text": page}, timeout=120)
check(sc["isScan"] and sc["total"] >= 3, f"scan quest '{sc['title']}' on {sc['topicName']} ({sc['total']} q, {ms/1000:.1f} s)")
print("       scan q1:", sc["question"]["question"][:120])
call("POST", "/ai/scan", {"text": "too short"}, expect=422)
check(True, "short scan text -> 422")

rep, _ = call("POST", f"/questions/{question['id']}/report", {"reason": "smoke test"})
check(rep["flags"] >= 1, f"report -> flags={rep['flags']}")
call("DELETE", "/auth/me")
call("GET", "/auth/me", expect=401)
check(True, "delete account -> token no longer valid")

token = None
g, _ = call("POST", "/auth/guest", {"name": "Guest"})
check(g["user"]["isGuest"], f"guest login ({g['user']['email']})")
token = g["token"]
call("DELETE", "/auth/me")

print("\nRESULT:", "PASS" if not failures else f"FAIL ({len(failures)})")
sys.exit(1 if failures else 0)
