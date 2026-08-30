#!/usr/bin/env python3
"""End-to-end check of reminders and the notification centre against a running API in
Development (Push:Provider=Log, Tasks:ReminderIntervalSeconds=10).

    python3 tools/smoke/notification_flows.py <api log> [base-url]

Registers a device for the admin, creates a one-time todo due in ~70 s with a 1-minute lead,
waits for the reminder job, then checks GET /notifications, the push log line and mark-read.
Standard library only; takes ~30 s.
"""
import json, sys, time, urllib.request, urllib.error, datetime as dt, uuid, re
LOG = sys.argv[1]; BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:5199"
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

def iso(d): return d.strftime("%Y-%m-%dT%H:%M:%SZ")
now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)

s, tok = call("POST", "/auth/login", {"username": "admin", "password": "Admin123!"})
T = tok["accessToken"]
call("PUT", "/notifications/read-all", token=T)
inst = str(uuid.uuid4())
s, d = call("PUT", f"/users/me/devices/{inst}", {"platform": "Android", "name": "smoke phone", "pushToken": "fcm-smoke-token"}, token=T)
C("device with push token registered", s == 200 and d["hasPushToken"])

due = now + dt.timedelta(seconds=75)
s, todo = call("POST", "/todos/one-time", {"title": "smoke: reminder", "description": "", "notificationEnabled": True, "notifyBeforeInMinutes": 1, "dueDate": iso(due)}, token=T)
C("todo due in 75 s with 1 min lead", s == 200, f"{s}")
s, quiet = call("POST", "/todos/one-time", {"title": "smoke: silent", "description": "", "notificationEnabled": False, "dueDate": iso(due)}, token=T)

s, before = call("GET", "/notifications/unread-count", token=T)
C("unread count is 0 before the reminder", before["unread"] == 0, str(before))

print("  ...  waiting for the reminder job (notify_at = due - 1 min ≈ +15 s; job every 10 s)")
found = None
for _ in range(9):
    time.sleep(5)
    s, items = call("GET", "/notifications?unreadOnly=true", token=T)
    found = next((n for n in items if n["kind"] == "occurrence.due" and n["data"]["todoId"] == todo["id"]), None)
    if found: break
C("reminder notification stored", found is not None, f"{items if not found else found['body']}")
if found:
    C("title is the todo title", found["title"] == "smoke: reminder")
    C("data carries occurrenceId and scheduledAt", "occurrenceId" in found["data"] and found["data"]["scheduledAt"].startswith(iso(due)[:16]))
    C("body mentions the start time", re.search(r"\d\d:\d\d", found["body"]) is not None, found["body"])
s, items = call("GET", "/notifications?unreadOnly=true", token=T)
C("silent todo produced no reminder", not any(n["data"]["todoId"] == quiet["id"] for n in items))
log = open(LOG).read()
C("push logged for the registered device", "PUSH (not sent) to 1 device(s) [Android]" in log)
C("reminder job logged", "Reminder run: 1 reminder(s) sent" in log)
s, hist = call("GET", f"/todos/{todo['id']}/occurrences", token=T)
s, r = call("PUT", f"/notifications/{found['id']}/read", token=T) if found else (0, None)
C("mark read", s == 200)
s, after = call("GET", "/notifications/unread-count", token=T)
C("unread count back to 0", after["unread"] == 0, str(after))
s, r = call("PUT", f"/notifications/{uuid.uuid4()}/read", token=T)
C("mark read unknown -> 404", s == 404)

# reschedule re-arms the reminder
occ = hist[0]
s, r = call("PUT", f"/occurrences/{occ['id']}/reschedule", {"newDate": iso(now + dt.timedelta(seconds=200))}, token=T)
C("reschedule accepted", s == 200)
time.sleep(12)
s, items = call("GET", "/notifications?unreadOnly=true", token=T)
C("no second reminder yet (new notify_at is in the future)", not any(n["data"].get("occurrenceId") == occ["id"] for n in items))

for t in (todo, quiet):
    call("PUT", f"/todos/{t['id']}/cancel", {"reason": "cleanup"}, token=T)
call("DELETE", f"/users/me/devices/{inst}", token=T)
print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
