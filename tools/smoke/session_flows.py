#!/usr/bin/env python3
"""End-to-end check of sign-in sessions against a running API in Development: an access token stops working
the moment its session ends (not when it expires), the device that session registered stops getting pushes,
its live hub connection closes, and a push token belongs to one account at a time.

    python3 tools/smoke/session_flows.py [base-url] [username] [password]   # default user: smoke

Signs the user in several times and ends those sessions (sign out everywhere included: other sessions of the
user end too). Registers one throwaway `push<timestamp>` account. Standard library only; takes a few seconds.
"""
import base64, json, os, socket, sys, time, urllib.request, urllib.error, uuid
from urllib.parse import urlparse
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5199"
USER = sys.argv[2] if len(sys.argv) > 2 else "smoke"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "Smoke123!"
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

def C(label, ok, extra=""):
    global fails
    print(("  ok  " if ok else "  FAIL") + " " + label + (f"  ({extra})" if extra else ""))
    if not ok: fails += 1

def login(user=USER, password=PASSWORD):
    s, tok = call("POST", "/auth/login", {"username": user, "password": password})
    assert s == 200 and tok.get("accessToken"), f"login as {user} failed ({s}) - an account with MFA cannot run the smoke; pass [username] [password]"
    return tok

def device(token, installation, push_token):
    return call("PUT", f"/users/me/devices/{installation}", {"platform": "Android", "name": "smoke phone", "pushToken": push_token}, token=token)

def devices(token):
    s, items = call("GET", "/users/me/devices", token=token)
    return {d["installationId"]: d for d in items} if s == 200 else {}

class Hub:
    """The app's way in: a WebSocket straight to the hub (no negotiate), JSON protocol."""
    def __init__(self, token):
        url = urlparse(BASE)
        self.sock = socket.create_connection((url.hostname, url.port or 80), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET /hubs/kadans?access_token={token} HTTP/1.1\r\nHost: {url.netloc}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(1024)
            if not chunk: break
            head += chunk
        self.status = int(head.split(b" ", 2)[1]) if head else 0
        if self.status == 101:
            self.send('{"protocol":"json","version":1}\x1e')

    def send(self, text):
        payload, mask = text.encode(), os.urandom(4)
        self.sock.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def closed_within(self, seconds):
        """True when the server closes the connection (close frame or EOF) within the time; pings don't count."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            self.sock.settimeout(max(0.1, deadline - time.time()))
            try:
                data = self.sock.recv(4096)
            except socket.timeout:
                return False
            except OSError:
                return True
            if not data or data[0] & 0x0F == 0x8:  # EOF, or a close frame
                return True
        return False

# 1. Two sessions of the same account, each with its device.
phone, laptop = login(), login()
phone_install, laptop_install = str(uuid.uuid4()), str(uuid.uuid4())
C("phone registers for push", device(phone["accessToken"], phone_install, f"smoke-phone-{phone_install}")[0] == 200)
C("laptop registers for push", device(laptop["accessToken"], laptop_install, f"smoke-laptop-{laptop_install}")[0] == 200)
C("both access tokens work", call("GET", "/users/me", token=phone["accessToken"])[0] == 200 and call("GET", "/users/me", token=laptop["accessToken"])[0] == 200)

# 2. Signing out on the phone: its access token is refused at once (it has ~60 min left), its device is gone.
s, _ = call("POST", "/auth/revoke", {"refreshToken": phone["refreshToken"]})
C("sign out", s == 200, str(s))
C("the signed-out access token is refused at once", call("GET", "/users/me", token=phone["accessToken"])[0] == 401)
C("its refresh token is refused", call("POST", "/auth/refresh", {"refreshToken": phone["refreshToken"]})[0] == 401)
listed = devices(laptop["accessToken"])
C("the phone left the device list (no more pushes there)", phone_install not in listed, str(list(listed)))
C("the laptop's session and device are untouched", call("GET", "/users/me", token=laptop["accessToken"])[0] == 200 and laptop_install in listed)

# 3. A refresh keeps the session working with the new token.
s, rotated = call("POST", "/auth/refresh", {"refreshToken": laptop["refreshToken"]})
C("refresh", s == 200, str(s))
C("the refreshed token works", call("GET", "/users/me", token=rotated["accessToken"])[0] == 200)

# 4. Sign out everywhere from another session: the laptop's token and live connection end with it.
hub = Hub(rotated["accessToken"])
C("the laptop's live connection opens", hub.status == 101, str(hub.status))
other = login()
started = time.time()
s, _ = call("POST", "/users/me/sessions/revoke-all", token=other["accessToken"])
C("sign out everywhere", s == 200, str(s))
C("the laptop's live connection is closed", hub.closed_within(5), f"{time.time() - started:.2f} s")
C("the laptop's access token is refused at once", call("GET", "/users/me", token=rotated["accessToken"])[0] == 401)
C("the caller's own token too", call("GET", "/users/me", token=other["accessToken"])[0] == 401)
C("a refused token cannot open a new connection", Hub(rotated["accessToken"]).status == 401)

# 5. One push token, one account: a phone signed into another account takes its token along.
again = login()
shared = f"smoke-shared-{uuid.uuid4()}"
install = str(uuid.uuid4())
C("smoke registers the phone", device(again["accessToken"], install, shared)[0] == 200)
name = f"push{int(time.time())}"
s, _ = call("POST", "/auth/register", {"username": name, "password": "Push123!x", "email": f"{name}@example.com"})
C("a second account", s == 200, str(s))
second = login(name, "Push123!x")
C("it signs in on the same phone", device(second["accessToken"], install, shared)[0] == 200)
C("the first account no longer has that phone", install not in devices(again["accessToken"]))
C("the second account has it, push on", devices(second["accessToken"]).get(install, {}).get("hasPushToken") is True)
call("POST", "/auth/revoke", {"refreshToken": again["refreshToken"]})
call("POST", "/auth/revoke", {"refreshToken": second["refreshToken"]})

print("\nALL PASSED" if fails == 0 else f"\n{fails} FAILED")
sys.exit(1 if fails else 0)
