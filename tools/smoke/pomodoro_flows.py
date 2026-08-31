#!/usr/bin/env python3
"""End-to-end check of the pomodoro timing model against a running API in Development.

    python3 tools/smoke/pomodoro_flows.py [base-url]

The auto-advance part uses a 1-minute phase and waits ~70 s for the job
(Tasks:PomodoroAutoAdvanceSeconds). Whole script ≈ 2 min. Standard library only.
"""
import json, sys, time, urllib.request, urllib.error, datetime as dt
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5199"
fails = 0

def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("content-type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    def parse(b):
        if not b: return None
        try: return json.loads(b)
        except json.JSONDecodeError: return {"_raw": b[:200]}
    try:
        with urllib.request.urlopen(req) as r: return r.status, parse(r.read().decode())
    except urllib.error.HTTPError as e: return e.code, parse(e.read().decode())

def C(label, ok, extra=""):
    global fails
    print(("  ok  " if ok else "  FAIL") + " " + label + (f"  ({extra})" if extra else ""))
    if not ok: fails += 1

def parse_ts(s): return dt.datetime.fromisoformat(s)
def iso(d): return d.strftime("%Y-%m-%dT%H:%M:%SZ")
now = dt.datetime.now(dt.timezone.utc)

s, tok = call("POST", "/auth/login", {"username": "admin", "password": "Admin123!"})
T = tok["accessToken"]

s, tpl = call("POST", "/pomodoro/templates", {"name": "smoke classic", "phases": [
    {"type": "Focus", "durationMinutes": 25}, {"type": "Break", "durationMinutes": 5}, {"type": "Focus", "durationMinutes": 25}]}, token=T)
s, todo = call("POST", "/todos/one-time", {"title": "smoke: deep work", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl["id"]}, token=T)

# manual run
s, run = call("POST", f"/todos/{todo['id']}/pomodoro/start", token=T)
C("start run", s == 200 and run["status"] == "Active" and not run["autoAdvance"], f"{s}")
ends = parse_ts(run["phaseEndsAt"]); delta = (ends - dt.datetime.now(dt.timezone.utc)).total_seconds()
C("phaseEndsAt ≈ now + 25 min", 24*60 < delta <= 25*60, f"{delta:.0f}s")
s, r = call("POST", f"/todos/{todo['id']}/pomodoro/start", token=T)
C("second run refused while one is active", s == 400)

s, run = call("PUT", f"/pomodoro/runs/{run['id']}/pause", token=T)
C("pause stores the remainder", s == 200 and run["status"] == "Paused" and run["phaseEndsAt"] is None and 24*60 < run["pausedRemainingSeconds"] <= 25*60, f"{run.get('pausedRemainingSeconds')}")
remaining = run["pausedRemainingSeconds"]
time.sleep(2)
s, run = call("PUT", f"/pomodoro/runs/{run['id']}/resume", token=T)
new_delta = (parse_ts(run["phaseEndsAt"]) - dt.datetime.now(dt.timezone.utc)).total_seconds()
C("resume re-anchors the deadline to the frozen remainder", s == 200 and abs(new_delta - remaining) < 3, f"{new_delta:.0f}s vs {remaining}s")

s, r = call("PUT", f"/pomodoro/runs/{run['id']}/advance", {"expectedPhaseIndex": 1}, token=T)
C("advance with stale index -> 400", s == 400)
s, run = call("PUT", f"/pomodoro/runs/{run['id']}/advance", {"expectedPhaseIndex": 0}, token=T)
C("advance to break with 5 min deadline", s == 200 and run["currentPhaseIndex"] == 1 and 4*60 < (parse_ts(run["phaseEndsAt"]) - dt.datetime.now(dt.timezone.utc)).total_seconds() <= 5*60)
s, run = call("PUT", f"/pomodoro/runs/{run['id']}/advance", {}, token=T)
s, run = call("PUT", f"/pomodoro/runs/{run['id']}/advance", {}, token=T)
C("advancing the last phase completes the run", run["status"] == "Completed" and run["phaseEndsAt"] is None and all(p["completedAt"] for p in run["phases"]))

s, hist = call("GET", f"/todos/{todo['id']}/pomodoro/runs", token=T)
C("run history lists the completed run", s == 200 and len(hist) == 1 and hist[0]["status"] == "Completed")
s, stats = call("GET", "/pomodoro/stats", token=T)
C("stats count the completed run and 50 focus minutes", stats["completedRuns"] == 1 and stats["focusMinutes"] == 50 and stats["breakMinutes"] == 5, json.dumps({k: stats[k] for k in ("completedRuns","focusMinutes","breakMinutes")}))
C("stats per-day in user's tz", len(stats["perDay"]) == 1 and stats["perDay"][0]["completedRuns"] == 1, stats["timeZoneId"])

# auto-advance run: 1-minute focus then 5-minute break
s, tpl2 = call("POST", "/pomodoro/templates", {"name": "smoke tiny", "phases": [
    {"type": "Focus", "durationMinutes": 1}, {"type": "Break", "durationMinutes": 5}]}, token=T)
s, todo2 = call("POST", "/todos/one-time", {"title": "smoke: sprint", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl2["id"]}, token=T)
call("PUT", "/notifications/read-all", token=T)
s, run2 = call("POST", f"/todos/{todo2['id']}/pomodoro/start?autoAdvance=true", token=T)
C("auto-advance run started", s == 200 and run2["autoAdvance"], f"{s}")
print("  ...  waiting ~75 s for the focus minute to elapse and the job to advance")
advanced = None
for _ in range(19):
    time.sleep(5)
    s, cur = call("GET", f"/todos/{todo2['id']}/pomodoro/active-run", token=T)
    if s == 200 and cur["currentPhaseIndex"] == 1:
        advanced = cur; break
C("server advanced the run to the break", advanced is not None, f"{cur if advanced is None else 'phase 1'}")
if advanced:
    left = (parse_ts(advanced["phaseEndsAt"]) - dt.datetime.now(dt.timezone.utc)).total_seconds()
    C("break deadline anchored on the schedule, not on job time", 3*60 < left <= 5*60, f"{left:.0f}s left of 5 min")
s, items = call("GET", "/notifications?unreadOnly=true", token=T)
phase_note = next((n for n in items if n["kind"] == "pomodoro.phase.completed"), None)
C("phase-completed notification stored", phase_note is not None and "Break" in phase_note["body"], phase_note["body"] if phase_note else items)

s, run2 = call("PUT", f"/pomodoro/runs/{run2['id']}/cancel", token=T)
C("cancel auto run", s == 200 and run2["status"] == "Cancelled")
for t in (todo, todo2):
    call("PUT", f"/todos/{t['id']}/cancel", {"reason": "cleanup"}, token=T)

print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
