#!/usr/bin/env python3
"""End-to-end check of the reminders phones ring themselves (ARCHITECTURE → "Reminders ring on the phone") against a
running API in Development that logs its pushes instead of sending them. The reminder job runs every 5 s.

    Push__Provider=Log dotnet run --project src/Kadans.Api > api.log      # overrides a Push:Provider user-secret
    python3 tools/smoke/reminder_flows.py api.log [base-url] [username] [password]   # default user: smoke

Push:Provider=Log is needed: through Firebase, the fake tokens die at their first push, and a phone skipped could not
be told from a phone whose token was retired.

Registers two phones: "ringer" fetches its reminder window, "other" does not. A todo whose reminder falls a minute
later then shows: the window and its words, the check of one reminder, the silent "reminders changed" signal to the
ringer only, and the reminder's push skipping the ringer (it has it) but reaching the other phone. A second todo,
made after the ringer's last sync, is pushed to the ringer too: its window no longer holds the account's latest
version. A cancelled reminder is no longer due; stopping clears the window. Standard library only; takes ~2.5 min.
"""
import json, re, sys, time, urllib.request, urllib.error, datetime as dt, uuid
LOG = sys.argv[1]; BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:5199"
USER = sys.argv[3] if len(sys.argv) > 3 else "smoke"
PASSWORD = sys.argv[4] if len(sys.argv) > 4 else "Smoke123!"
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
def parse_iso(s): return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
def utcnow(): return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)

def log_lines_since(when, needle):
    """The API log's lines after `when` (its local timestamps) that contain `needle`."""
    found = []
    with open(LOG, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.match(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+ [+-]\d\d:\d\d)", line)
            if m and needle in line and dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f %z") >= when:
                found.append(line.strip())
    return found

def push_count(line):
    m = re.search(r"PUSH \(not sent\) to (\d+) device", line)
    return int(m.group(1)) if m else -1

assert "FCM: " not in open(LOG, encoding="utf-8", errors="replace").read(), \
    "this API pushes through Firebase: start it with Push__Provider=Log (see the top of this script)"

s, tok = call("POST", "/auth/login", {"username": USER, "password": PASSWORD})
assert s == 200 and tok.get("accessToken"), f"login as {USER} failed ({s}) - an account with MFA cannot run the smoke; pass [username] [password]"
T = tok["accessToken"]
stamp = int(time.time())

print("== two phones")
ringer, other = str(uuid.uuid4()), str(uuid.uuid4())
for inst, name in ((ringer, "smoke ringer"), (other, "smoke other")):
    s, _ = call("PUT", f"/users/me/devices/{inst}", {"platform": "Android", "name": name, "pushToken": f"{name.replace(' ', '-')}-{stamp}"}, T)
    C(f"{name} registered", s == 200, s)
s, devices = call("GET", "/users/me/devices", token=T)
with_token = sum(1 for d in devices if d.get("hasPushToken"))
print(f"     (the account has {with_token} phone(s) with a push token, these two included)")

print("== the window")
s, body = call("POST", "/reminders/sync", {"installationId": str(uuid.uuid4())}, T)
C("an unknown device gets 404", s == 404, s)
s, window = call("POST", "/reminders/sync", {"installationId": ringer, "days": 30}, T)
C("the ringer's window answers", s == 200, s)
through = parse_iso(window["through"]) if s == 200 else utcnow()
C("it reaches 7 days at most", through <= utcnow() + dt.timedelta(days=7, minutes=1), window.get("through") if s == 200 else "")

print("== a todo whose reminder rings in a minute")
before_change = dt.datetime.now().astimezone()
due = utcnow() + dt.timedelta(minutes=16)
s, todo = call("POST", "/todos/one-time", {"title": f"Smoke ring {stamp}", "description": "", "notificationEnabled": True,
                                           "dueDate": iso(due), "notifyBeforeInMinutes": 15}, T)
C("todo created", s == 200, s)
todo_id = todo["id"]
time.sleep(4)
signals = log_lines_since(before_change, "reminders.changed")
C("the change was signalled to the phones that ring (silent push)", len(signals) >= 1 and push_count(signals[-1]) >= 1, signals[-1] if signals else "no log line")

s, window = call("POST", "/reminders/sync", {"installationId": ringer}, T)
mine = [r for r in window.get("reminders", [])] if s == 200 else []
mine = [r for r in mine if r["todoId"] == todo_id]
C("the ringer's new window holds it", len(mine) == 1, len(mine))
reminder = mine[0] if mine else {}
C("with its words", reminder.get("title") == f"Smoke ring {stamp}" and "15 min" in reminder.get("body", ""), reminder.get("body"))
notify_at = parse_iso(reminder["notifyAt"]) if reminder else utcnow()
C("ringing 15 minutes before the start", reminder and parse_iso(reminder["startsAt"]) - notify_at == dt.timedelta(minutes=15), reminder.get("startsAt"))

s, check = call("GET", f"/reminders/{reminder.get('occurrenceId', uuid.uuid4())}", token=T)
C("its check says it is due, at that time", s == 200 and check.get("due") is True and parse_iso(check["notifyAt"]) == notify_at, check)

print("== the reminder's push skips the ringer")
time.sleep(max(0, (notify_at - utcnow()).total_seconds()) + 12)
pushes = [l for l in log_lines_since(before_change, "occurrence.due") if "PUSH (not sent)" in l]
C("pushed to every phone but the ringer", any(push_count(l) == with_token - 1 for l in pushes), pushes[-1] if pushes else "no push line")

print("== a change the ringer has not synced: the push reaches it again")
before_second = dt.datetime.now().astimezone()
due2 = utcnow() + dt.timedelta(minutes=16)
s, todo2 = call("POST", "/todos/one-time", {"title": f"Smoke ring 2 {stamp}", "description": "", "notificationEnabled": True,
                                            "dueDate": iso(due2), "notifyBeforeInMinutes": 15}, T)
C("second todo created (the ringer does not sync)", s == 200, s)
time.sleep((due2 - dt.timedelta(minutes=15) - utcnow()).total_seconds() + 12)
pushes = [l for l in log_lines_since(before_second, "occurrence.due") if "PUSH (not sent)" in l]
C("pushed to every phone, the ringer included", any(push_count(l) == with_token for l in pushes), pushes[-1] if pushes else "no push line")

print("== cancelled, stopped, cleaned up")
s, _ = call("PUT", f"/todos/{todo_id}/cancel", {"reason": "smoke"}, T)
C("first todo cancelled", s == 200, s)
s, check = call("GET", f"/reminders/{reminder.get('occurrenceId', uuid.uuid4())}", token=T)
C("its reminder is no longer due", s == 200 and check.get("due") is False, check)
s, _ = call("DELETE", f"/reminders/sync/{ringer}", token=T)
C("stopping answers 204", s == 204, s)
for tid in (todo_id, todo2.get("id")):
    call("DELETE", f"/todos/{tid}", token=T)
for inst in (ringer, other):
    call("DELETE", f"/users/me/devices/{inst}", token=T)

print("\nALL PASSED" if fails == 0 else f"\n{fails} FAILED")
sys.exit(1 if fails else 0)
