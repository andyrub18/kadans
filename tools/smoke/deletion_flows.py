#!/usr/bin/env python3
"""End-to-end check of account and todo deletion against a running API in Development (Email:Provider=Log, so the
emailed links are read back from the API log).

    python3 tools/smoke/deletion_flows.py <api log> [base-url]

Registers a fresh `del<timestamp>` account and deletes it, in the app's way (with the password) and the web page's
way (an address, then the emailed link and its button). The account closes at once and a sign-in only offers to keep
it; it is kept the first time. The erasure itself, 7 days later, is the AccountErasureJob's (see its tests). Standard
library only; takes a few seconds.
"""
import json, re, sys, time, urllib.parse, urllib.request, urllib.error, datetime as dt
LOG = sys.argv[1]; BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:5199"
fails = 0

def call(method, path, body=None, token=None, form=None, raw=False):
    data = urllib.parse.urlencode(form).encode() if form is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("content-type", "application/x-www-form-urlencoded" if form is not None else "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    def parse(b):
        if raw or not b: return b or None
        try: return json.loads(b)
        except json.JSONDecodeError: return {"_raw": b[:300]}
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return r.status, parse(r.read().decode())
    except urllib.error.HTTPError as e: return e.code, parse(e.read().decode())

def C(label, ok, extra=""):
    global fails
    print(("  ok  " if ok else "  FAIL") + " " + label + (f"  ({extra})" if extra else ""))
    if not ok: fails += 1

def last_link(pattern):
    time.sleep(0.5)
    found = re.findall(pattern, open(LOG).read())
    return found[-1] if found else None

def iso(d): return d.strftime("%Y-%m-%dT%H:%M:%SZ")
NAME = f"del{int(time.time())}"; EMAIL = f"{NAME}@example.com"; PASSWORD = "Delete123!"

s, _ = call("POST", "/auth/register", {"username": NAME, "password": PASSWORD, "email": EMAIL})
C("register", s == 200, f"{s}")
s, tok = call("POST", "/auth/login", {"username": NAME, "password": PASSWORD})
T = tok["accessToken"]
now = dt.datetime.now(dt.timezone.utc)
s, todo = call("POST", "/todos/recurring", {"title": "del: daily", "description": "", "notificationEnabled": False,
               "recurrenceRule": {"frequency": "Daily", "startDate": iso(now + dt.timedelta(hours=1)), "timeZone": "UTC"}}, token=T)
C("a todo to keep", s == 200, f"{s}")

# --- one todo, for good ---
s, gone = call("POST", "/todos/one-time", {"title": "del: one-off", "description": "", "notificationEnabled": False, "dueDate": iso(now + dt.timedelta(days=1))}, token=T)
s, _ = call("DELETE", f"/todos/{gone['id']}", token=T)
C("delete a todo", s == 200, f"{s}")
C("it is gone, not cancelled", call("GET", f"/todos/{gone['id']}", token=T)[0] == 404)
C("deleting it again -> 404", call("DELETE", f"/todos/{gone['id']}", token=T)[0] == 404)

# --- the app's way: the password, then closed at once ---
s, r = call("POST", "/users/me/delete", {}, token=T)
C("without the password -> 400", s == 400, f"{s}")
s, r = call("POST", "/users/me/delete", {"currentPassword": "not-it"}, token=T)
C("with a wrong password -> 401", s == 401, f"{s}")
s, r = call("POST", "/users/me/delete", {"currentPassword": PASSWORD}, token=T)
erase = dt.datetime.fromisoformat(r["eraseAfter"]) if s == 200 else None
C("deleting closes the account; erased in 7 days", erase is not None and abs((erase - dt.datetime.now(dt.timezone.utc)).total_seconds() - 7 * 86400) < 60, f"{s} {r}")
C("the session is over at once", call("GET", "/users/me", token=T)[0] == 401)
C("and its refresh too", call("POST", "/auth/refresh", {"refreshToken": tok["refreshToken"]})[0] == 401)
C("the owner is told (and alarmed if it was not them)", "Your Kadans account will be erased" in open(LOG).read())
s, offer = call("POST", "/auth/login", {"username": NAME, "password": PASSWORD})
C("signing in opens nothing: only the offer to keep it", s == 200 and offer["deletionScheduled"] and offer["accessToken"] is None and offer["restoreToken"], f"{s}")
C("a session token cannot keep it", call("POST", "/auth/restore-account", {"restoreToken": T})[0] == 400)
s, kept = call("POST", "/auth/restore-account", {"restoreToken": offer["restoreToken"]})
C("'Keep my account' reopens it with a session", s == 200 and kept["accessToken"], f"{s}")
T = kept["accessToken"]
C("everything is still there", call("GET", f"/todos/{todo['id']}", token=T)[0] == 200)

# --- the web page's way (for people without the app) ---
s, page = call("GET", "/account/delete", raw=True)
C("the web page asks for an address", s == 200 and 'name="email"' in (page or ""), f"{s}")
s, page = call("POST", "/account/delete", form={"email": "nobody-at-all@example.com"}, raw=True)
C("an unknown address gets the same answer", s == 200 and "If an account uses this address" in (page or ""), f"{s}")
s, page = call("POST", "/account/delete", form={"email": EMAIL}, raw=True)
C("the address gets a link", s == 200, f"{s}")
link = last_link(r"/account/delete/confirm\?userId=([^&\s]+)&token=(\S+)")
C("link logged", link is not None)
if link:
    s, page = call("GET", f"/account/delete/confirm?userId={link[0]}&token={link[1]}", raw=True)
    C("opening the link deletes nothing; it shows what will happen and a button", s == 200 and NAME in (page or "") and "<form" in (page or "")
      and call("GET", "/users/me", token=T)[0] == 200, f"{s}")
    s, page = call("POST", "/account/delete/confirm", form={"userId": urllib.parse.unquote(link[0]), "token": "broken" + link[1]}, raw=True)
    C("a tampered link is refused", s == 400, f"{s}")
    s, page = call("POST", "/account/delete/confirm", form={"userId": urllib.parse.unquote(link[0]), "token": link[1]}, raw=True)
    C("the button closes the account", s == 200 and "will be erased on" in (page or ""), f"{s}")
    C("its session is over", call("GET", "/users/me", token=T)[0] == 401)
    s, offer = call("POST", "/auth/login", {"username": NAME, "password": PASSWORD})
    C("a sign-in offers to keep it again", s == 200 and offer["deletionScheduled"], f"{s}")

print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}  (account {NAME} left awaiting erasure)")
sys.exit(1 if fails else 0)
