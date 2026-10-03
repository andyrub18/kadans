#!/usr/bin/env python3
"""End-to-end check of the pomodoro timing model against a running API in Development.

    python3 tools/smoke/pomodoro_flows.py [base-url] [username] [password]   # default user: smoke

The auto-advance part uses a 1-minute phase, races a client against the deadline watcher and
measures how late the phase change and its notification are (must be under a second). A manual run
started alongside must get exactly one "time's up" at its phase end, and stay where it is. An app
that asks too early ("ran out" by its own clock) changes nothing. Whole script ≈ 2 min. Standard library only.
"""
import json, sys, time, urllib.request, urllib.error, datetime as dt
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5199"
USER = sys.argv[2] if len(sys.argv) > 2 else "smoke"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "Smoke123!"
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

s, tok = call("POST", "/auth/login", {"username": USER, "password": PASSWORD})
assert s == 200 and tok.get("accessToken"), f"login as {USER} failed ({s}) - an account with MFA cannot run the smoke; pass [username] [password]"
T = tok["accessToken"]

s, base = call("GET", "/pomodoro/stats", token=T)  # the dev DB accumulates; assert deltas
s, tpl = call("POST", "/pomodoro/templates", {"name": "smoke classic", "phases": [
    {"type": "Focus", "durationMinutes": 25}, {"type": "Break", "durationMinutes": 5}, {"type": "Focus", "durationMinutes": 25}]}, token=T)
# template lifecycle: update and delete
s, tmp = call("POST", "/pomodoro/templates", {"name": "smoke throwaway", "phases": [{"type": "Focus", "durationMinutes": 10}]}, token=T)
s, tmp = call("PUT", f"/pomodoro/templates/{tmp['id']}", {"name": "smoke renamed", "phases": [
    {"type": "Focus", "durationMinutes": 50}, {"type": "Break", "durationMinutes": 10}]}, token=T)
C("template update replaces name and phases", s == 200 and tmp["name"] == "smoke renamed" and [p["durationMinutes"] for p in tmp["phases"]] == [50, 10], f"{s}")
s, _ = call("DELETE", f"/pomodoro/templates/{tmp['id']}", token=T)
C("template delete", s == 200)
s, r = call("DELETE", f"/pomodoro/templates/{tmp['id']}", token=T)
C("template delete again -> 404", s == 404)
s, r = call("POST", "/pomodoro/templates", {"name": "smoke too long", "phases": [{"type": "Focus", "durationMinutes": 5}] * 25}, token=T)
C("a cycle of 25 phases is refused (at most 24)", s == 400, f"{s}")
s, r = call("POST", "/pomodoro/templates", {"name": "smoke too slow", "phases": [{"type": "Focus", "durationMinutes": 241}]}, token=T)
C("a 241-minute phase is refused (at most 240)", s == 400, f"{s}")

s, todo = call("POST", "/todos/one-time", {"title": "smoke: deep work", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl["id"]}, token=T)

# manual run
s, run = call("POST", f"/todos/{todo['id']}/pomodoro/start", token=T)
C("start run", s == 200 and run["status"] == "Active" and not run["autoAdvance"], f"{s}")
span = (parse_ts(run["finishAt"]) - parse_ts(run["startedAt"])).total_seconds()
C("a session ends by itself 12 hours after its start unless told otherwise", abs(span - 12 * 3600) < 1, f"{span:.0f}s")
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
deltas = {k: stats[k] - base[k] for k in ("completedRuns", "focusMinutes", "breakMinutes")}
# The phases were skipped seconds after they began: stats count the time really spent, not the plan (50 + 5).
C("stats gained one completed run and the minutes really spent (none)", deltas == {"completedRuns": 1, "focusMinutes": 0, "breakMinutes": 0}, json.dumps(deltas))
C("stats per-day in user's tz has today", any(d["completedRuns"] >= 1 for d in stats["perDay"]), stats["timeZoneId"])

# auto-advance run: 1-minute focus then 5-minute break
s, tpl2 = call("POST", "/pomodoro/templates", {"name": "smoke tiny", "phases": [
    {"type": "Focus", "durationMinutes": 1}, {"type": "Break", "durationMinutes": 5}]}, token=T)
s, todo2 = call("POST", "/todos/one-time", {"title": "smoke: sprint", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl2["id"]}, token=T)
call("PUT", "/notifications/read-all", token=T)
s, run2 = call("POST", f"/todos/{todo2['id']}/pomodoro/start?autoAdvance=true", token=T)
C("auto-advance run started", s == 200 and run2["autoAdvance"], f"{s}")
deadline = parse_ts(run2["phaseEndsAt"])
# An app whose clock runs fast says the phase ran out: the server's clock decides, nothing changes.
s, early = call("PUT", f"/pomodoro/runs/{run2['id']}/advance", {"expectedPhaseIndex": 0, "onlyIfEnded": True}, token=T)
# (the database keeps microseconds: compare instants, not strings)
C("an early 'ran out' leaves the run as it is", s == 200 and early["currentPhaseIndex"] == 0
  and abs((parse_ts(early["phaseEndsAt"]) - deadline).total_seconds()) < 0.001, f"{s}")
# A manual run on the same 1-minute template, ending at about the same moment: one time's up, no advance.
s, todo4 = call("POST", "/todos/one-time", {"title": "smoke: manual sprint", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl2["id"]}, token=T)
s, run4 = call("POST", f"/todos/{todo4['id']}/pomodoro/start", token=T)
manual_deadline = parse_ts(run4["phaseEndsAt"])
# A workday that ends by itself: picked 10 minutes ahead, moved to ~65 s ahead, finished there with nobody pressing Finish.
s, todo5 = call("POST", "/todos/one-time", {"title": "smoke: short day", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl["id"]}, token=T)
too_far = iso(dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=25))
s, _ = call("POST", f"/todos/{todo5['id']}/pomodoro/start?loop=true&finishAt={too_far}", token=T)
C("an end more than a day ahead is refused", s == 400, f"{s}")
s, run5 = call("POST", f"/todos/{todo5['id']}/pomodoro/start?loop=true&finishAt={iso(dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=10))}", token=T)
C("a looping session starts with the end it was given", s == 200 and abs((parse_ts(run5["finishAt"]) - dt.datetime.now(dt.timezone.utc)).total_seconds() - 600) < 5, f"{s}")
s, _ = call("PUT", f"/pomodoro/runs/{run5['id']}/finish-at", {"finishAt": iso(dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=20))}, token=T)
C("an end less than a minute ahead is refused", s == 400, f"{s}")
day_end = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=66)).replace(microsecond=0)
s, run5 = call("PUT", f"/pomodoro/runs/{run5['id']}/finish-at", {"finishAt": iso(day_end)}, token=T)
C("the end can move while it runs", s == 200 and parse_ts(run5["finishAt"]) == day_end, f"{s}")
print("  ...  waiting for the focus minute to elapse; the deadline watcher must advance on the second")
time.sleep(max(0, (deadline - dt.datetime.now(dt.timezone.utc)).total_seconds() - 0.3))
# Play the watching client too: advance at the very instant the phase ends, racing the server.
# Whoever loses must lose cleanly - one phase change, one notification.
import threading
race = {}
def client_advance():
    time.sleep(max(0, (deadline - dt.datetime.now(dt.timezone.utc)).total_seconds()))
    race["status"], race["body"] = call("PUT", f"/pomodoro/runs/{run2['id']}/advance", {"expectedPhaseIndex": 0, "onlyIfEnded": True}, token=T)
racer = threading.Thread(target=client_advance); racer.start()
advanced = None
for _ in range(200):
    s, cur = call("GET", f"/todos/{todo2['id']}/pomodoro/active-run", token=T)
    if s == 200 and cur["currentPhaseIndex"] == 1:
        advanced = cur; seen_at = dt.datetime.now(dt.timezone.utc); break
    time.sleep(0.05)
racer.join()
C("the run advanced to the break", advanced is not None, f"{cur if advanced is None else 'phase 1'}")
if advanced:
    late = (seen_at - deadline).total_seconds()
    C("phase change visible within 1 s of the deadline (the old job: up to 5 s)", late < 1.0, f"{late*1000:.0f} ms after the deadline")
    left = (parse_ts(advanced["phaseEndsAt"]) - dt.datetime.now(dt.timezone.utc)).total_seconds()
    C("break deadline anchored on the schedule, not on wake-up time", 3*60 < left <= 5*60, f"{left:.0f}s left of 5 min")
    C("the racing client either won or was told to refresh (never a 5xx)", race.get("status") in (200, 400, 409), f"{race.get('status')}")
time.sleep(0.5)
s, items = call("GET", "/notifications?unreadOnly=true", token=T)
notes = [n for n in items if n["kind"] == "pomodoro.phase.completed" and (n.get("data") or {}).get("runId") == run2["id"]]
phase_note = notes[0] if notes else None
C("phase-completed notification stored", phase_note is not None and "Break" in phase_note["body"], phase_note["body"] if phase_note else items)
C("exactly one notification although client and server both advanced", len(notes) == 1, f"{len(notes)} notification(s)")
if phase_note:
    note_late = (parse_ts(phase_note["createdAt"]) - deadline).total_seconds()
    C("notification created within 1 s of the deadline", note_late < 1.0, f"{note_late*1000:.0f} ms")
s, runs2 = call("GET", f"/todos/{todo2['id']}/pomodoro/runs", token=T)
C("no duplicated phases after the race", s == 200 and len(runs2[0]["phases"]) == 2, f"{len(runs2[0]['phases']) if s == 200 else s} phases")
C("no notification for the early ask", len(notes) <= 1)

# the manual run: time's up once, and it waits for the person
time.sleep(max(0, (manual_deadline - dt.datetime.now(dt.timezone.utc)).total_seconds() + 1.0))
def times_up():
    s, items = call("GET", "/notifications?unreadOnly=true", token=T)
    return [n for n in items if n["kind"] == "pomodoro.phase.ended" and (n.get("data") or {}).get("runId") == run4["id"]]
ups = times_up()
C("the manual run said time's up, naming the break", len(ups) == 1 and "5" in ups[0]["body"], ups[0]["body"] if ups else "none")
if ups:
    up_late = (parse_ts(ups[0]["createdAt"]) - manual_deadline).total_seconds()
    C("time's up within 1 s of the phase end", up_late < 1.0, f"{up_late*1000:.0f} ms")
s, cur4 = call("GET", f"/todos/{todo4['id']}/pomodoro/active-run", token=T)
C("the manual run waits at the end of the focus", s == 200 and cur4["currentPhaseIndex"] == 0 and cur4["status"] == "Active", f"{s}")
s, _ = call("PUT", f"/pomodoro/runs/{run4['id']}/advance", {"expectedPhaseIndex": 0, "onlyIfEnded": True}, token=T)
s, cur4 = call("GET", f"/todos/{todo4['id']}/pomodoro/active-run", token=T)
C("a 'ran out' from the app does not advance a manual run", cur4["currentPhaseIndex"] == 0, str(cur4["currentPhaseIndex"]))
call("PUT", f"/pomodoro/runs/{run4['id']}/pause", token=T); call("PUT", f"/pomodoro/runs/{run4['id']}/resume", token=T)
time.sleep(1.5)
C("pausing and resuming at 0:00 does not say it again", len(times_up()) == 1, f"{len(times_up())}")
s, run4 = call("PUT", f"/pomodoro/runs/{run4['id']}/advance", {"expectedPhaseIndex": 0}, token=T)
C("'Next phase' moves on", s == 200 and run4["currentPhaseIndex"] == 1, f"{s}")
call("PUT", f"/pomodoro/runs/{run4['id']}/cancel", token=T)

# the short day ends by itself at its end time
time.sleep(max(0, (day_end - dt.datetime.now(dt.timezone.utc)).total_seconds() + 1.0))
s, runs5 = call("GET", f"/todos/{todo5['id']}/pomodoro/runs", token=T)
ended = runs5[0] if s == 200 and runs5 else {}
C("the session finished by itself at its end time", ended.get("status") == "Completed" and ended.get("completedAt") and abs((parse_ts(ended["completedAt"]) - day_end).total_seconds()) < 0.5,
  f"{ended.get('status')} {ended.get('completedAt')}")
C("the focus under way counted up to the end", ended.get("phases") and ended["phases"][0]["completedAt"] is not None)
s, items = call("GET", "/notifications?unreadOnly=true", token=T)
finished = [n for n in items if n["kind"] == "pomodoro.run.finished" and (n.get("data") or {}).get("runId") == run5["id"]]
C("one 'session ended' notification", len(finished) == 1, finished[0]["body"] if finished else "none")

s, run2 = call("PUT", f"/pomodoro/runs/{run2['id']}/cancel", token=T)
C("cancel auto run", s == 200 and run2["status"] == "Cancelled")

# --- loop: the cycle repeats until finished ---
s, todo3 = call("POST", "/todos/one-time", {"title": "smoke: workday", "description": "", "notificationEnabled": False,
               "dueDate": iso(now + dt.timedelta(days=1)), "pomodoroTemplateId": tpl["id"]}, token=T)
s, run3 = call("POST", f"/todos/{todo3['id']}/pomodoro/start?loop=true", token=T)
C("loop run started", s == 200 and run3["loop"] and run3["cycleLength"] == 3, f"{s}")
for _ in range(3):
    s, run3 = call("PUT", f"/pomodoro/runs/{run3['id']}/advance", {}, token=T)
C("advancing past the last phase wraps into lap 2", run3["status"] == "Active" and run3["currentPhaseIndex"] == 3 and len(run3["phases"]) == 6, f"{run3['status']} idx={run3['currentPhaseIndex']} phases={len(run3['phases'])}")
C("lap 2 deadline is live", run3["phaseEndsAt"] is not None)
s, run3 = call("PUT", f"/pomodoro/runs/{run3['id']}/finish", token=T)
C("finish ends the loop as Completed", s == 200 and run3["status"] == "Completed", f"{s}")
s, stats = call("GET", "/pomodoro/stats", token=T)
C("finished loop counts as a completed run", stats["completedRuns"] - base["completedRuns"] >= 2, str(stats["completedRuns"]))
call("PUT", f"/todos/{todo3['id']}/cancel", {"reason": "cleanup"}, token=T)
for t in (todo, todo2, todo4, todo5):
    call("PUT", f"/todos/{t['id']}/cancel", {"reason": "cleanup"}, token=T)

print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
