#!/usr/bin/env python3
"""End-to-end check of the Tasks module (occurrence materialization, overrides, rule changes,
previews) against a running API in Development. Logs in as the seeded admin.

    python3 tools/smoke/task_flows.py [base-url]

Creates todos with titles prefixed `smoke:`; cancels them at the end. Standard library only.
"""
import json, sys, urllib.request, urllib.error, datetime as dt
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

def iso(d): return d.strftime("%Y-%m-%dT%H:%M:%SZ")   # Z, never "+00:00": a plus sign in a query string is a space
now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
start = (now + dt.timedelta(days=1)).replace(hour=9, minute=0, second=0)

s, tok = call("POST", "/auth/login", {"username": "admin", "password": "Admin123!"})
T = tok["accessToken"]
def occ(todo_id): return call("GET", f"/todos/{todo_id}/occurrences?pageSize=5000", token=T)[1]

# --- bounded rule: fully materialized, todo completes when its last occurrence is done ---
s, t3 = call("POST", "/todos/recurring", {"title": "smoke: 3 days", "description": "", "notificationEnabled": False,
             "recurrenceRule": {"frequency": "Daily", "startDate": iso(start), "timeZone": "America/Port-au-Prince", "count": 3}}, token=T)
C("create bounded recurring returns todo", s == 200 and t3["recurrenceRule"]["count"] == 3, f"{s}")
o3 = occ(t3["id"])
C("3 occurrences materialized", len(o3) == 3 and all(o["originalScheduledAt"] == o["scheduledAt"] for o in o3), f"{len(o3)}")
s, r = call("PUT", f"/occurrences/{o3[0]['id']}/complete", token=T)
C("complete first", s == 200)
s, r = call("PUT", f"/occurrences/{o3[0]['id']}/complete", token=T)
C("complete twice -> 400 already completed", s == 400 and r["errorCode"] == "10005", f"{s}")
s, r = call("PUT", f"/occurrences/{o3[1]['id']}/reschedule", {"newDate": iso(start + dt.timedelta(days=10)), "reason": "travel"}, token=T)
C("reschedule second", s == 200 and r["isRescheduled"] and r["scheduledAt"].startswith(iso(start + dt.timedelta(days=10))[:16]) and r["originalScheduledAt"] == o3[1]["originalScheduledAt"], f"{s} {r}")
s, r = call("PUT", f"/occurrences/{o3[1]['id']}/reschedule", {"newDate": iso(now - dt.timedelta(days=1))}, token=T)
C("reschedule into the past -> 400", s == 400)
s, r = call("PUT", f"/occurrences/{o3[2]['id']}/cancel", {"reason": "skip"}, token=T)
C("cancel third", s == 200)
s, todo = call("GET", f"/todos/{t3['id']}", token=T)
C("todo still active with a pending occurrence", todo["status"] == "Scheduled", todo["status"])
s, r = call("PUT", f"/occurrences/{o3[1]['id']}/complete", token=T)
s, todo = call("GET", f"/todos/{t3['id']}", token=T)
C("bounded todo auto-completes once nothing is pending", todo["status"] == "Completed", todo["status"])
s, hist = call("GET", f"/todos/{t3['id']}/history", token=T)
C("history shows all 3 with statuses", sorted(h["status"] for h in hist) == ["Cancelled", "Completed", "Completed"], f"{[h['status'] for h in hist]}")

# --- indefinite hourly rule: horizon, batch cap, previews ---
s, th = call("POST", "/todos/recurring", {"title": "smoke: hourly", "description": "", "notificationEnabled": False,
             "recurrenceRule": {"frequency": "Hourly", "startDate": iso(start), "timeZone": "UTC"}}, token=T)
oh = occ(th["id"])
C("hourly: materialized up to the horizon (~30d*24 ≈ 700)", 650 <= len(oh) <= 720, f"{len(oh)}")
s, tm = call("POST", "/todos/recurring", {"title": "smoke: minutely", "description": "", "notificationEnabled": False,
             "recurrenceRule": {"frequency": "Minutely", "startDate": iso(start), "timeZone": "UTC"}}, token=T)
om = occ(tm["id"])
C("minutely: capped at 1000 per pass", len(om) == 1000, f"{len(om)}")
far_from, far_to = start + dt.timedelta(days=40), start + dt.timedelta(days=40, hours=3)
s, rng = call("GET", f"/occurrences?from={iso(far_from)}&to={iso(far_to)}", token=T)
previews = [o for o in rng if o["isPreview"] and o["todoId"] == th["id"]]
C("range beyond horizon returns previews for the hourly todo", len(previews) == 4 and all(o["id"] is None for o in previews), f"{len(previews)} of {len(rng)}")
s, rng2 = call("GET", f"/occurrences?from={iso(start)}&to={iso(start + dt.timedelta(hours=2))}", token=T)
mat = [o for o in rng2 if o["todoId"] == th["id"]]
C("range inside horizon returns materialized rows, no previews", len(mat) == 3 and not any(o["isPreview"] for o in mat), f"{len(mat)}")

# --- rule change keeps touched rows, drops untouched ones the new rule does not produce ---
s, tw = call("POST", "/todos/recurring", {"title": "smoke: weekly", "description": "", "notificationEnabled": False,
             "recurrenceRule": {"frequency": "Daily", "startDate": iso(start), "timeZone": "UTC", "count": 10}}, token=T)
ow = occ(tw["id"])
call("PUT", f"/occurrences/{ow[1]['id']}/remark", {"remark": "keep me"}, token=T)
call("PUT", f"/occurrences/{ow[2]['id']}/reschedule", {"newDate": iso(start + dt.timedelta(days=20))}, token=T)
s, upd = call("PUT", f"/todos/{tw['id']}", {"title": "smoke: weekly (edited)", "description": "", "notificationEnabled": True, "notifyBeforeInMinutes": 5}, token=T)
C("update without rule keeps occurrences", s == 200 and upd["notifyBeforeInMinutes"] == 5 and len(occ(tw["id"])) == 10, f"{s}")
s, upd = call("PUT", f"/todos/{tw['id']}", {"title": "smoke: weekly", "description": "", "notificationEnabled": True,
             "recurrenceRule": {"frequency": "Weekly", "startDate": iso(start), "timeZone": "UTC", "count": 4}}, token=T)
C("update with new rule", s == 200 and upd["recurrenceRule"]["rrule"].startswith("FREQ=WEEKLY"), f"{s} {upd}")
ow2 = occ(tw["id"])
kept_remark = [o for o in ow2 if o["remarks"] == "keep me"]
kept_resched = [o for o in ow2 if o["isRescheduled"]]
weekly_new = [o for o in ow2 if not o["isRescheduled"] and o["remarks"] is None]
C("touched rows survived the rule change", len(kept_remark) == 1 and len(kept_resched) == 1, f"{len(kept_remark)}/{len(kept_resched)}")
C("untouched rows replaced by the weekly instances (start + 3 more within horizon)", 3 <= len(weekly_new) <= 4, f"{len(weekly_new)}: {[o['scheduledAt'][:10] for o in weekly_new]}")
C("first weekly instance reuses the existing row (same id)", any(o["id"] == ow[0]["id"] for o in ow2))

# --- todo-level reschedule and cancel ---
s, r = call("PUT", f"/todos/{th['id']}/reschedule", {"newDate": iso(start + dt.timedelta(days=2)), "reason": "later"}, token=T)
C("todo-level reschedule moves the next pending occurrence", s == 200 and r["isRescheduled"], f"{s}")
s, r = call("PUT", f"/todos/{th['id']}/cancel", {"reason": "done with smoke"}, token=T)
C("cancel todo", s == 200)
s, hist = call("GET", f"/todos/{th['id']}/history?pageSize=5", token=T)
C("cancelled todo: occurrences cancelled", all(h["status"] == "Cancelled" for h in hist) and len(hist) == 5)
s, r = call("GET", f"/todos/{th['id']}", token=T)
C("cancelled todo still readable by id", s == 200 and r["status"] == "Cancelled", f"{s}")
for t in (tm, tw):
    call("PUT", f"/todos/{t['id']}/cancel", {"reason": "cleanup"}, token=T)

# --- one-time ---
s, one = call("POST", "/todos/one-time", {"title": "smoke: dentist", "description": "", "notificationEnabled": True, "dueDate": iso(start + dt.timedelta(days=3))}, token=T)
oo = occ(one["id"])
C("one-time: single occurrence", s == 200 and len(oo) == 1 and one["recurrenceRule"]["isOneTime"])
s, r = call("PUT", f"/todos/{one['id']}/reschedule", {"newDate": iso(start + dt.timedelta(days=4))}, token=T)
C("one-time reschedule keeps the same occurrence", s == 200 and r["id"] == oo[0]["id"])
s, r = call("PUT", f"/occurrences/{oo[0]['id']}/complete", token=T)
s, r = call("GET", f"/todos/{one['id']}", token=T)
C("one-time completes the todo", r["status"] == "Completed", r["status"])

print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
