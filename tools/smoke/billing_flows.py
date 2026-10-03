#!/usr/bin/env python3
"""End-to-end check of subscriptions against a running API in Development (Billing:FakeStore:Enabled, no Google key).

    python3 tools/smoke/billing_flows.py [base-url] [username] [password]   # default user: smoke

What the app reads before a paywall, purchases through the development fake store, a Google purchase refused while
no store is configured, and Google's notification endpoint refusing what Google did not sign. Leaves the user's fake
subscription expired. Standard library only; takes a second.
"""
import json, re, sys, urllib.request, urllib.error, datetime as dt
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5199"
USER = sys.argv[2] if len(sys.argv) > 2 else "smoke"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "Smoke123!"
fails = 0

def call(method, path, body=None, token=None, headers=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("content-type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items(): req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            b = r.read().decode(); return r.status, json.loads(b) if b else None
    except urllib.error.HTTPError as e:
        b = e.read().decode()
        try: return e.code, json.loads(b) if b else None
        except json.JSONDecodeError: return e.code, None

def C(label, ok, extra=""):
    global fails
    print(("  ok  " if ok else "  FAIL") + " " + label + (f"  ({extra})" if extra else ""))
    if not ok: fails += 1

s, tok = call("POST", "/auth/login", {"username": USER, "password": PASSWORD})
assert s == 200 and tok.get("accessToken"), f"login as {USER} failed ({s})"
T = tok["accessToken"]

s, st = call("GET", "/billing/subscription", token=T)
C("the app can read the subscription", s == 200, f"{s}")
C("not required yet: no paywall, phones allowed", st["required"] is False and st["hasAccess"] is True, json.dumps(st))
C("a purchase must carry the account's hash (64 hex)", re.fullmatch(r"[0-9a-f]{64}", st["accountHash"] or "") is not None)
C("the product to sell is named", st["googleProductId"] == "kadans_mobile")
C("Development offers the fake store", st["fakeStore"] is True)

s, r = call("POST", "/billing/google/purchases", {"purchaseToken": "anything"}, token=T)
C("no Google key: a Google purchase cannot be checked -> 503", s == 503 and r["errorCode"] == "10055", f"{s}")

s, st = call("POST", "/billing/fake/purchases", {"state": "Trial", "days": 14}, token=T)
ends = dt.datetime.fromisoformat(st["expiresAt"]) if s == 200 else None
C("a fake trial for 14 days", s == 200 and st["state"] == "Trial" and st["store"] == "Fake"
  and abs((ends - dt.datetime.now(dt.timezone.utc)).total_seconds() - 14 * 86400) < 60, f"{s}")
s, st = call("POST", "/billing/fake/purchases", {"state": "Expired", "days": -1}, token=T)
C("replaced by an expired one", s == 200 and st["state"] == "Expired", f"{s}")

s, _ = call("POST", "/billing/google/notifications", {"message": {"data": "e30="}})
C("Google's notification endpoint refuses what Google did not sign", s == 401, f"{s}")
s, _ = call("POST", "/billing/google/notifications", {"message": {"data": "e30="}}, headers={"Authorization": "Bearer forged.jwt.token"})
C("and a forged token", s == 401, f"{s}")

print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
