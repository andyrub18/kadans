#!/usr/bin/env python3
"""End-to-end check of the calendar feed against a running API in Development (ARCHITECTURE -> "The calendar feed"):
Settings' side (on, the same link again, reset, off), and the feed's own address read the way a calendar app reads it,
with nothing but the link.

    python3 tools/smoke/calendar_feed_flows.py <api log> [base-url] [username] [password]   # default user: smoke

What the file holds (titles and times only, done ones marked, a skipped one gone, a moved one moved, a focus todo as
long as its cycle, the rule's times past the stored month), "unchanged" answered 304, a link that is invented, changed,
reset or turned off answered a bare 404, another account's feed holding none of this one's, a closed account's feed
gone at once, and no link anywhere in the API log. Registers one throwaway `feed<timestamp>` account and closes it;
leaves the user's feed off. Standard library only; takes a few seconds.

The feed is part of the phones' subscription. On a server that requires one (start the API with
`Billing__Required=true`; Development's fake store sells it), the script also checks that an account without one cannot
turn the feed on, that a subscription ending empties the calendar and keeps the link, and that its return fills it
again; it then leaves the user's fake subscription expired, as billing_flows.py does. Otherwise it says it skipped them.
"""
import datetime as dt, json, re, sys, time, urllib.request, urllib.error
LOG = sys.argv[1]
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:5199"
USER = sys.argv[3] if len(sys.argv) > 3 else "smoke"
PASSWORD = sys.argv[4] if len(sys.argv) > 4 else "Smoke123!"
fails = 0

def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("content-type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    def parse(b):
        if not b: return None
        try: return json.loads(b)
        except json.JSONDecodeError: return {"_raw": b[:200]}
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return r.status, parse(r.read().decode())
    except urllib.error.HTTPError as e: return e.code, parse(e.read().decode())

def read(url, etag=None, method="GET"):
    """The feed as a calendar app asks for it: no sign-in. -> (status, headers, text)."""
    req = urllib.request.Request(url, method=method)
    if etag: req.add_header("If-None-Match", etag)
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return r.status, r.headers, r.read().decode()
    except urllib.error.HTTPError as e: return e.code, e.headers, e.read().decode()

def C(label, ok, extra=""):
    global fails
    print(("  ok  " if ok else "  FAIL") + " " + label + (f"  ({extra})" if extra else ""))
    if not ok: fails += 1

def login(user=USER, password=PASSWORD):
    s, tok = call("POST", "/auth/login", {"username": user, "password": password})
    assert s == 200 and tok.get("accessToken"), f"login as {user} failed ({s}) - an account with MFA cannot run the smoke; pass [username] [password]"
    return tok["accessToken"]

def iso(d): return d.strftime("%Y-%m-%dT%H:%M:%SZ")
def stamp(d): return d.strftime("%Y%m%dT%H%M%SZ")

def events(ics):
    """The file's events, each a dict of its properties, lines unfolded and text unescaped as a calendar app does."""
    lines = ics.replace("\r\n ", "").split("\r\n")
    found, current = [], None
    for line in lines:
        if line == "BEGIN:VEVENT": current = {}
        elif line == "END:VEVENT": found.append(current); current = None
        elif current is not None:
            name, _, value = line.partition(":")
            current[name] = re.sub(r"\\([\\;,n])", lambda m: "\n" if m[1] == "n" else m[1], value)
    return found

def minutes(event):
    parse = lambda v: dt.datetime.strptime(v, "%Y%m%dT%H%M%SZ")
    return (parse(event["DTEND"]) - parse(event["DTSTART"])).total_seconds() / 60

def subscribe(token, state="Trial", days=14):
    """Development's fake store: a subscription in that state, ending in that many days."""
    return call("POST", "/billing/fake/purchases", {"state": state, "days": days}, token=token)

T = login()
s, billing = call("GET", "/billing/subscription", token=T)
REQUIRED = s == 200 and billing["required"]   # the server requires a subscription for phones, and so for the feed
if REQUIRED: subscribe(T)
now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
start = (now + dt.timedelta(days=1)).replace(hour=9, minute=0, second=0)
tag = f"feed {int(time.time())}"
links = []   # every link this run was given: none may be in the API log

# --- Settings' side: off, on, the same link again ---
call("DELETE", "/calendar-feed", token=T)
s, feed = call("GET", "/calendar-feed", token=T)
C("off: no link", s == 200 and feed == {"enabled": False, "url": None, "createdAt": None, "lastFetchedAt": None, "available": True}, f"{s} {feed}")
C("without a sign-in, Settings' side answers 401", [call(m, p)[0] for m, p in [("GET", "/calendar-feed"), ("PUT", "/calendar-feed"), ("POST", "/calendar-feed/reset"), ("DELETE", "/calendar-feed")]] == [401] * 4)
s, feed = call("PUT", "/calendar-feed", token=T)
url = feed["url"] if s == 200 else ""
links.append(url)
C("on: a link under /calendar/feed, ending as a calendar file", s == 200 and feed["enabled"] and re.fullmatch(re.escape(BASE) + r"/calendar/feed/[A-Za-z0-9_-]{64}/kadans\.ics", url) is not None, f"{s} {url[:60]}")
C("no calendar has read it yet", s == 200 and feed["lastFetchedAt"] is None)
C("turned on again: the same link", call("PUT", "/calendar-feed", token=T)[1]["url"] == url)
C("asked again: the same link", call("GET", "/calendar-feed", token=T)[1]["url"] == url)

# --- what the file holds ---
title = f"{tag}: rent; water, power \\ école 🗓️"
s, one = call("POST", "/todos/one-time", {"title": title, "description": "the door code is under the mat, do not share", "notificationEnabled": True, "dueDate": iso(start)}, token=T)
s, daily = call("POST", "/todos/recurring", {"title": f"{tag}: daily", "description": "", "notificationEnabled": False,
                "recurrenceRule": {"frequency": "Daily", "startDate": iso(start), "timeZone": "America/Port-au-Prince"}}, token=T)
s, tpl = call("POST", "/pomodoro/templates", {"name": f"{tag} cycle", "phases": [
    {"type": "Focus", "durationMinutes": 25}, {"type": "Break", "durationMinutes": 5}, {"type": "Focus", "durationMinutes": 25}]}, token=T)
s, focus = call("POST", "/todos/one-time", {"title": f"{tag}: focus", "description": "", "notificationEnabled": False,
                "dueDate": iso(start + dt.timedelta(hours=3)), "pomodoroTemplateId": tpl["id"]}, token=T)
s, tight = call("POST", "/todos/recurring", {"title": f"{tag}: every 10 minutes", "description": "", "notificationEnabled": False,
                "recurrenceRule": {"frequency": "Minutely", "interval": 10, "startDate": iso(start + dt.timedelta(days=2)), "timeZone": "UTC", "count": 4}}, token=T)
C("todos to read back", s == 200, f"{s} {tight}")

s, headers, ics = read(url)
mine = [e for e in events(ics) if tag in e.get("SUMMARY", "")]
by = lambda name: [e for e in mine if e["SUMMARY"].endswith(name)]
C("read with nothing but the link: a calendar file", s == 200 and headers["Content-Type"] == "text/calendar; charset=utf-8" and ics.startswith("BEGIN:VCALENDAR\r\n") and ics.endswith("END:VCALENDAR\r\n"), f"{s} {headers['Content-Type']}")
C("named Kadans, read again every 15 minutes by the apps that listen", "\r\nX-WR-CALNAME:Kadans\r\n" in ics and "\r\nREFRESH-INTERVAL;VALUE=DURATION:PT15M\r\n" in ics)
C("no line over 75 octets", all(len(line.encode()) <= 75 for line in ics.split("\r\n")))
once = [e for e in mine if e["SUMMARY"] == title]
C("the todo, its title as written (; , \\ accents, emoji)", len(once) == 1, f"{len(once)} of {len(mine)}")
C("at its time, for half an hour", len(once) == 1 and once[0]["DTSTART"] == stamp(start) and minutes(once[0]) == 30, f"{once[0] if once else None}")
C("titles and times only: no description, no alarm", "DESCRIPTION" not in ics and "under the mat" not in ics and "VALARM" not in ics)
C("a focus todo lasts its cycle (25 + 5 + 25)", len(by(": focus")) == 1 and minutes(by(": focus")[0]) == 55, f"{[minutes(e) for e in by(': focus')]}")
C("every 10 minutes: four events of 10, 10, 10 and 30 minutes", [minutes(e) for e in sorted(by(": every 10 minutes"), key=lambda e: e["DTSTART"])] == [10, 10, 10, 30],
  f"{[minutes(e) for e in by(': every 10 minutes')]}")
days = sorted(e["DTSTART"] for e in by(": daily"))
C("a daily todo: every day to 90 days ahead, past the stored month", 88 <= len(days) <= 91 and days[0] == stamp(start) and len(set(days)) == len(days), f"{len(days)} from {days[:1]}")
C("each event has its own name", len({e["UID"] for e in events(ics)}) == len(events(ics)))

# --- unchanged: 304 ---
etag = headers["ETag"]
s2, headers2, body2 = read(url, etag=etag)
C("asked again with its ETag: 304, nothing sent", etag and s2 == 304 and body2 == "" and headers2["ETag"] == etag, f"{s2} {etag}")
C("without it: the same file", read(url)[2] == ics)
s2, headers2, body2 = read(url, method="HEAD")
C("HEAD answers too", s2 == 200 and headers2["ETag"] == etag, f"{s2}")
feed = call("GET", "/calendar-feed", token=T)[1]
C("Settings says a calendar read it", feed["lastFetchedAt"] is not None and abs((dt.datetime.fromisoformat(feed["lastFetchedAt"]) - dt.datetime.now(dt.timezone.utc)).total_seconds()) < 60, f"{feed['lastFetchedAt']}")

# --- done, skipped, moved ---
occurrence = lambda todo: call("GET", f"/todos/{todo['id']}/occurrences?page=1&pageSize=100", token=T)[1]
call("PUT", f"/occurrences/{occurrence(one)[0]['id']}/complete", token=T)
rows = occurrence(daily)
call("PUT", f"/occurrences/{rows[1]['id']}/cancel", {"reason": "skip"}, token=T)
moved_to = start + dt.timedelta(days=2, hours=8)
call("PUT", f"/occurrences/{rows[2]['id']}/reschedule", {"newDate": iso(moved_to)}, token=T)
uid_before = next(e["UID"] for e in by(": daily") if e["DTSTART"] == days[2])
s, headers, ics = read(url, etag=etag)
mine = [e for e in events(ics) if tag in e.get("SUMMARY", "")]
C("after changes the file is sent again", s == 200 and headers["ETag"] != etag, f"{s}")
C("a done time says so", [e["SUMMARY"] for e in mine if title in e["SUMMARY"]] == ["✓ " + title])
after = sorted(e["DTSTART"] for e in by(": daily"))
C("a skipped time is gone", days[1] not in after and len(after) == len(days) - 1, f"{len(after)}")
moved = [e for e in by(": daily") if e["UID"] == uid_before]
C("a moved time moved, under the same name", len(moved) == 1 and moved[0]["DTSTART"] == stamp(moved_to) and days[2] not in after, f"{moved}")

# --- another account's feed; a closed account's ---
name = f"feed{int(time.time())}"
s, _ = call("POST", "/auth/register", {"username": name, "password": "Feed123!x", "email": f"{name}@example.com"})
other = login(name, "Feed123!x")
call("POST", "/todos/one-time", {"title": f"{tag}: theirs", "description": "", "notificationEnabled": False, "dueDate": iso(start)}, token=other)
if REQUIRED:
    # --- part of the phones' subscription: nothing without one ---
    s, feed = call("GET", "/calendar-feed", token=other)
    C("subscriptions required: an account without one is told the feed is not available", s == 200 and feed["available"] is False and feed["enabled"] is False, f"{s} {feed}")
    s, r = call("PUT", "/calendar-feed", token=other)
    C("it cannot turn the feed on: 402, and why", s == 402 and r["errorCode"] == "10059" and r["detail"] == "The calendar link is part of the phone subscription.", f"{s} {r}")
    C("nor get a link by resetting", call("POST", "/calendar-feed/reset", token=other)[0] == 402)
    C("still off", call("GET", "/calendar-feed", token=other)[1]["enabled"] is False)
    subscribe(other)
    C("with a subscription the feed is available", call("GET", "/calendar-feed", token=other)[1]["available"] is True)
else:
    C("subscriptions not required here: the feed is available to all", call("GET", "/calendar-feed", token=other)[1]["available"] is True)
their_url = call("PUT", "/calendar-feed", token=other)[1]["url"]
links.append(their_url)
theirs = [e["SUMMARY"] for e in events(read(their_url)[2])]
C("another account's feed holds its own todo and none of this one's", theirs == [f"{tag}: theirs"], f"{theirs[:3]}")
C("and this one's holds none of theirs", f"{tag}: theirs" not in read(url)[2])
if REQUIRED:
    # --- the subscription ends, and comes back ---
    full_etag = read(their_url)[1]["ETag"]
    subscribe(other, "Expired", -1)
    feed = call("GET", "/calendar-feed", token=other)[1]
    C("the subscription ended: the link is kept, and Settings says the feed is not available", feed["enabled"] and feed["url"] == their_url and feed["available"] is False, f"{feed}")
    s2, h2, empty = read(their_url, etag=full_etag)
    C("its calendars get a calendar with nothing in it", s2 == 200 and empty.startswith("BEGIN:VCALENDAR\r\n") and empty.endswith("END:VCALENDAR\r\n") and events(empty) == [], f"{s2} {len(events(empty))}")
    C("asked again, the empty calendar is unchanged (304)", read(their_url, etag=h2["ETag"])[0] == 304)
    C("replacing the link needs the subscription too", call("POST", "/calendar-feed/reset", token=other)[0] == 402)
    subscribe(other)
    back = [e["SUMMARY"] for e in events(read(their_url)[2])]
    C("the subscription back: the same link shows the todos again", back == [f"{tag}: theirs"], f"{back[:3]}")
    subscribe(other, "Expired", -1)
    C("without a subscription the feed can still be turned off", call("DELETE", "/calendar-feed", token=other)[0] == 204 and read(their_url)[0] == 404)
    subscribe(other)
    their_url = call("PUT", "/calendar-feed", token=other)[1]["url"]
    links.append(their_url)
s, r = call("POST", "/users/me/delete", {"currentPassword": "Feed123!x"}, token=other)
s2, _, body2 = read(their_url)
C("an account asked to be deleted: its feed is gone at once, a bare 404", s == 200 and s2 == 404 and body2 == "", f"{s} {s2}")

# --- links that are not the link ---
secret = url.split("/")[-2]
C("an invented link -> a bare 404", read(url.replace(secret, "A" * 64))[::2] == (404, ""))
changed = secret[:-1] + ("A" if secret[-1] != "A" else "B")
C("the link with one character changed -> 404", read(url.replace(secret, changed))[0] == 404)
C("the nonce without its signature -> 404", read(url.replace(secret, secret[:22] + "A" * 42))[0] == 404)
C("the link without its file name is no feed (401, as any address the API does not have)", read(url.rsplit("/", 1)[0])[::2] == (401, ""))

# --- reset, off ---
s, feed = call("POST", "/calendar-feed/reset", token=T)
new_url = feed["url"] if s == 200 else ""
links.append(new_url)
C("reset: another link, not read yet", s == 200 and new_url != url and feed["lastFetchedAt"] is None, f"{s}")
C("the old link stops at once", read(url)[0] == 404)
C("the new one answers", read(new_url)[0] == 200)
s, _ = call("DELETE", "/calendar-feed", token=T)
C("off -> 204", s == 204, f"{s}")
C("the link stops at once", read(new_url)[0] == 404)
C("Settings says off", call("GET", "/calendar-feed", token=T)[1]["enabled"] is False)
C("turning it off twice is fine", call("DELETE", "/calendar-feed", token=T)[0] == 204)
s, feed = call("PUT", "/calendar-feed", token=T)
links.append(feed["url"])
C("turned on again: a link never given before", s == 200 and feed["url"] not in (url, new_url))
s, feed = call("POST", "/calendar-feed/reset", token=T)
links.append(feed["url"])
call("DELETE", "/calendar-feed", token=T)
s, feed = call("POST", "/calendar-feed/reset", token=T)
links.append(feed["url"])
C("reset while off turns it on", s == 200 and feed["enabled"] and read(feed["url"])[0] == 200, f"{s}")
call("DELETE", "/calendar-feed", token=T)

# --- nothing of a link in the log ---
time.sleep(1)
log = open(LOG).read()
secrets = [link.split("/")[-2] for link in links if link]
C("the API log holds no link", len(secrets) == (7 if REQUIRED else 6) and not any(s in log for s in secrets), f"{len(secrets)} links")
C("but says the feed was turned on, reset and off", all(text in log for text in ["turned the calendar feed on", "reset the calendar feed's link", "turned the calendar feed off"]))
C("and logs a refused read with its path cut", "/calendar/feed/…" in log)

for todo in (one, daily, focus, tight):
    call("DELETE", f"/todos/{todo['id']}", token=T)
call("DELETE", f"/pomodoro/templates/{tpl['id']}", token=T)
if REQUIRED: subscribe(T, "Expired", -1)
else: print("  --   subscriptions are not required on this server: what the feed does without one was not checked (Billing__Required=true)")

print("\nALL OK" if fails == 0 else f"\n{fails} FAILED")
sys.exit(1 if fails else 0)
