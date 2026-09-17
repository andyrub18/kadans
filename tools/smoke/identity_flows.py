#!/usr/bin/env python3
"""End-to-end check of the Identity module against a running API in Development
(Email:Provider=Log, so confirmation/reset links are read back from the API log).

    ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://localhost:5199 \
        dotnet run --project src/Kadans.Api --no-launch-profile > /tmp/kadans-api.log 2>&1 &
    python3 tools/smoke/identity_flows.py /tmp/kadans-api.log

Registers a fresh `alice<timestamp>` user on every run, so it is re-runnable; needs the seeded admin
(admin@kadans.local) only as an address that is already taken.
Uses only the standard library.
"""
import html, json, re, sys, time, base64, hmac, hashlib, struct, urllib.request, urllib.error, urllib.parse
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:5199"; LOG = sys.argv[1]
def call(method, path, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("content-type", "application/json")
    if token: req.add_header("Authorization", "Bearer " + token)
    def parse(b):
        if raw or not b: return b or None
        try: return json.loads(b)
        except json.JSONDecodeError: return {"_raw": b[:200]}
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, parse(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, parse(e.read().decode())
def check(label, ok, extra=""): print(("  ok  " if ok else "  FAIL") + " " + label + (f"  ({extra})" if extra else "")); return ok
def link(pattern):
    time.sleep(0.5); txt = open(LOG).read(); m = re.findall(pattern, txt); return m[-1] if m else None
def totp(key):
    key = key.replace(" ", "").upper(); k = base64.b32decode(key + "=" * ((8 - len(key) % 8) % 8))
    c = int(time.time()) // 30; h = hmac.new(k, struct.pack(">Q", c), hashlib.sha1).digest(); o = h[-1] & 15
    return str((struct.unpack(">I", h[o:o+4])[0] & 0x7fffffff) % 1000000).zfill(6)
fails = 0
# A fresh user per run: the script is re-runnable against a database that keeps its history.
USERNAME = f"alice{int(time.time())}"; EMAIL = f"{USERNAME}@example.com"; EMAIL2 = f"{USERNAME}.new@example.com"
def C(label, ok, extra=""):
    global fails
    if not check(label, ok, extra): fails += 1

# register + confirm
s, r = call("POST", "/auth/register", {"username": USERNAME, "password": "Alice123!", "email": EMAIL, "displayName": "Alice", "timeZone": "America/Port-au-Prince", "language": "ht"})
C("register alice", s == 200 and r["emailConfirmed"] is False, f"{s}")
m = link(r"/auth/confirm-email\?userId=([^&\s]+)&token=([^\s]+)")
C("confirmation link logged", m is not None)
C("confirmation email is in Kreyòl", "Konfime imèl Kadans ou" in open(LOG).read())
s, r = call("GET", f"/auth/confirm-email?userId={m[0]}&token={m[1]}", raw=True)
# alice is ht, so the (now localized, HTML) landing page says it in Kreyòl
C("GET confirm link", s == 200 and ("confirmed" in r or "konfime" in r), f"{s}")
s, tok = call("POST", "/auth/login", {"username": EMAIL, "password": "Alice123!"})
C("login with email as username", s == 200 and tok["accessToken"], f"{s}")
s, me = call("GET", "/users/me", token=tok["accessToken"])
C("GET /users/me emailConfirmed", s == 200 and me["emailConfirmed"] is True and me["twoFactorEnabled"] is False)

# refresh rotation + reuse detection
s, pair2 = call("POST", "/auth/refresh", {"refreshToken": tok["refreshToken"]})
C("refresh rotates", s == 200 and pair2["refreshToken"] != tok["refreshToken"])
s, _ = call("POST", "/auth/refresh", {"refreshToken": tok["refreshToken"]})
C("reuse of rotated token rejected", s == 401)
s, _ = call("POST", "/auth/refresh", {"refreshToken": pair2["refreshToken"]})
C("whole family revoked after reuse", s == 401)

# forgot / reset
s, _ = call("POST", "/auth/forgot-password", {"email": EMAIL})
C("forgot-password 200", s == 200)
s, _ = call("POST", "/auth/forgot-password", {"email": "nobody@example.com"})
C("forgot-password unknown email still 200", s == 200)
m = link(r"/auth/reset-password\?email=([^&\s]+)&token=([^\s]+)")
C("reset link logged", m is not None)
s, r = call("POST", "/auth/reset-password", {"email": urllib.parse.unquote(m[0]), "token": m[1], "newPassword": "Alice456!"})
C("reset-password", s == 200, f"{s} {r}")
s, r = call("POST", "/auth/reset-password", {"email": EMAIL, "token": m[1], "newPassword": "Alice789!"})
C("reset token single-use", s == 400 and r["errorCode"] == "10033")
s, tok = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice456!"})
C("login with new password", s == 200)

# change password
s, r = call("PUT", "/users/me/password", {"currentPassword": "wrong", "newPassword": "Alice999!"}, token=tok["accessToken"])
C("change password wrong current -> 401", s == 401, f"{s}")
s, r = call("PUT", "/users/me/password", {"currentPassword": "Alice456!", "newPassword": "Alice999!"}, token=tok["accessToken"])
C("change password", s == 200)
s, _ = call("POST", "/auth/refresh", {"refreshToken": tok["refreshToken"]})
C("sessions revoked after password change", s == 401)
s, tok = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
at = tok["accessToken"]

# email change
s, r = call("POST", "/users/me/email", {"newEmail": "admin@kadans.local"}, token=at)
C("email change to taken address -> 400", s == 400 and r["errorCode"] == "10038")
s, r = call("POST", "/users/me/email", {"newEmail": EMAIL2}, token=at)
C("email change request", s == 200)
m = link(r"/auth/confirm-email-change\?userId=([^&\s]+)&newEmail=([^&\s]+)&token=([^\s]+)")
C("email-change link logged", m is not None)
# Open the link the way a mail client does: a plain GET, no session.
s, r = call("GET", f"/auth/confirm-email-change?userId={m[0]}&newEmail={m[1]}&token={m[2]}", raw=True)
C("the emailed link answers with a page in the account's language", s == 200 and "Adrès imèl ou chanje" in html.unescape(r or ""), f"{s}")
s, r = call("GET", f"/auth/confirm-email-change?userId={m[0]}&newEmail={m[1]}&token={m[2]}", raw=True)
C("opening it twice is harmless", s == 200, f"{s}")
s, r = call("GET", f"/auth/confirm-email-change?userId={m[0]}&newEmail=evil%40example.com&token={m[2]}", raw=True)
C("the token only works for the address it was sent to", s == 400, f"{s}")
C("the previous address is told about the change", "Adrès imèl kont Kadans ou chanje" in open(LOG).read())
s, me = call("GET", "/users/me", token=at)
C("new email visible + confirmed", me["email"] == EMAIL2 and me["emailConfirmed"] is True)

# MFA
s, enroll = call("POST", "/users/me/mfa/enroll", token=at)
C("mfa enroll", s == 200 and enroll["authenticatorUri"].startswith("otpauth://totp/Kadans:"), f"{s}")
s, r = call("POST", "/users/me/mfa/enable", {"code": "000000"}, token=at)
C("mfa enable wrong code -> 401", s == 401)
s, rc = call("POST", "/users/me/mfa/enable", {"code": totp(enroll["sharedKey"])}, token=at)
C("mfa enable", s == 200 and len(rc["codes"]) == 8, f"{s} {rc}")
s, chal = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
C("login returns mfa challenge", s == 200 and chal["mfaRequired"] is True and chal["accessToken"] is None and chal["mfaToken"])
s, _ = call("GET", "/users/me", token=chal["mfaToken"])
C("mfa token is not a bearer token", s == 401)
s, r = call("POST", "/auth/mfa/verify", {"mfaToken": chal["mfaToken"], "code": "123456"})
C("mfa verify wrong code -> 401", s == 401)
s, tok = call("POST", "/auth/mfa/verify", {"mfaToken": chal["mfaToken"], "code": totp(enroll["sharedKey"])})
C("mfa verify with TOTP", s == 200 and tok["accessToken"], f"{s}")
s, chal = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
s, tok2 = call("POST", "/auth/mfa/verify", {"mfaToken": chal["mfaToken"], "code": rc["codes"][0]})
C("mfa verify with recovery code", s == 200 and tok2["accessToken"], f"{s}")
s, chal = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
s, r = call("POST", "/auth/mfa/verify", {"mfaToken": chal["mfaToken"], "code": rc["codes"][0]})
C("recovery code single-use", s == 401)
s, r = call("POST", "/users/me/mfa/disable", {"code": totp(enroll["sharedKey"])}, token=tok["accessToken"])
C("mfa disable", s == 200, f"{s} {r}")
s, tok = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
C("login without mfa after disable", s == 200 and tok["mfaRequired"] is False and tok["accessToken"])

# devices
inst = "0193f6e4-8f7b-7c1d-9b2e-1a2b3c4d5e6f"
s, d = call("PUT", f"/users/me/devices/{inst}", {"platform": "Android", "name": "Pixel", "pushToken": "fcm-abc", "appVersion": "0.1"}, token=tok["accessToken"])
C("device register", s == 200 and d["hasPushToken"] is True, f"{s} {d}")
s, d = call("PUT", f"/users/me/devices/{inst}", {"platform": "Android", "name": "Pixel 9", "pushToken": "fcm-def"}, token=tok["accessToken"])
C("device upsert", s == 200 and d["name"] == "Pixel 9")
s, lst = call("GET", "/users/me/devices", token=tok["accessToken"])
C("device list", s == 200 and len(lst) == 1)
s, _ = call("DELETE", f"/users/me/devices/{inst}", token=tok["accessToken"])
C("device delete", s == 200)
s, r = call("DELETE", f"/users/me/devices/{inst}", token=tok["accessToken"])
C("device delete again -> 404", s == 404)

# external wiring
s, r = call("POST", "/auth/external", {"provider": "google", "idToken": "junk"})
# unconfigured env -> 400/10036; env with a registered client id -> 401/10035 (token invalid)
C("external google rejects junk", (s, r.get("errorCode")) in ((400, "10036"), (401, "10035")), f"{s} {r.get('errorCode') if r else r}")
s, r = call("POST", "/auth/external", {"provider": "facebook", "idToken": "junk"})
C("external unknown provider -> 400", s == 400)
s, r = call("GET", "/auth/providers")
C("providers is public and never leaks a secret", s == 200 and "google" in r and "secret" not in json.dumps(r).lower(), f"{s} {r}")
s, r = call("POST", "/auth/external/google/code", {"code": "junk", "codeVerifier": "v", "redirectUri": "http://127.0.0.1:49152"})
# no Desktop client configured -> 400/10036; configured -> Google refuses the junk code -> 401/10035
C("google code exchange rejects junk", (s, r.get("errorCode")) in ((400, "10036"), (401, "10035")), f"{s} {r.get('errorCode') if r else r}")
s, r = call("POST", "/auth/external/google/code", {"code": "junk", "codeVerifier": "v", "redirectUri": "https://evil.example/cb"})
C("google code exchange refuses a non-loopback redirect", s in (400, 401))

# revoke-all + logout
s, tok = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
s, _ = call("POST", "/users/me/sessions/revoke-all", token=tok["accessToken"])
s, _ = call("POST", "/auth/refresh", {"refreshToken": tok["refreshToken"]})
C("revoke-all kills refresh", s == 401)
s, tok = call("POST", "/auth/login", {"username": USERNAME, "password": "Alice999!"})
s, _ = call("POST", "/auth/revoke", {"refreshToken": tok["refreshToken"]})
s, _ = call("POST", "/auth/refresh", {"refreshToken": tok["refreshToken"]})
C("logout kills refresh", s == 401)
print(f"\n{'ALL PASSED' if fails == 0 else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
