#!/usr/bin/env python3
"""The accounts whose phones are free (testers, Google's reviewers, family), kept by an admin.

    python3 tools/admin/free_accounts.py list
    python3 tools/admin/free_accounts.py add marie               # a username, or a confirmed email address
    python3 tools/admin/free_accounts.py remove marie@example.com

Signs in as an admin (password and two-factor code asked here, never stored), does the one thing, signs out.
A change applies at once, no restart: the person opens the app again (or taps "Restore my purchase") and is in.
Server: $KADANS_API, else https://api.kadansplanning.com; --api and --admin override. docs/DEPLOYMENT.md →
Subscriptions → Free accounts.
"""
import argparse, getpass, json, os, sys, urllib.error, urllib.parse, urllib.request


class ApiError(Exception):
    pass


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept-Language", "en")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")
            try:
                problem = json.loads(text)
                detail = problem.get("detail") or problem.get("title")
            except ValueError:
                detail = None
            if e.code == 403:
                detail = "this account is not an admin"
            raise ApiError(f"{method} {path}: HTTP {e.code}" + (f" – {detail}" if detail else ""))
        except urllib.error.URLError as e:
            raise ApiError(f"cannot reach {self.base}: {e.reason}")


def sign_in(api, admin):
    password = getpass.getpass(f"Password for {admin}: ")
    session = api.call("POST", "/auth/login", {"username": admin, "password": password})
    if session.get("mfaRequired"):
        code = input("Two-factor code: ").strip()
        session = api.call("POST", "/auth/mfa/verify", {"mfaToken": session["mfaToken"], "code": code})
    if not session.get("accessToken"):
        raise ApiError("signed in, but no session came back (is the account closed for deletion?)")
    api.token = session["accessToken"]
    return session["refreshToken"]


def show(accounts):
    if not accounts:
        print("No free accounts.")
        return
    for a in accounts:
        email = a.get("email") or "–"
        if a.get("email") and not a.get("emailConfirmed"):
            email += " (not confirmed)"
        print(f"{a.get('username') or '(account gone)':<24} {email:<36} since {a['addedAt'][:10]}  {a['userId']}")


def main():
    parser = argparse.ArgumentParser(description="The accounts whose phones are free.")
    parser.add_argument("--api", default=os.environ.get("KADANS_API", "https://api.kadansplanning.com"))
    parser.add_argument("--admin", default="admin", help="the admin's username (default: admin)")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="the free accounts")
    commands.add_parser("add", help="make an account's phones free").add_argument("account", help="username or confirmed email")
    commands.add_parser("remove", help="an account needs a subscription again").add_argument("account", help="username, email or user id")
    args = parser.parse_args()

    api = Api(args.api)
    refresh_token = None
    try:
        refresh_token = sign_in(api, args.admin)
        if args.command == "list":
            show(api.call("GET", "/billing/free-accounts"))
        elif args.command == "add":
            added = api.call("POST", "/billing/free-accounts", {"account": args.account})
            print("Free now:")
            show([added])
        else:
            wanted = args.account.strip().lower()
            matches = [a for a in api.call("GET", "/billing/free-accounts")
                       if wanted in (a["userId"].lower(), (a.get("username") or "").lower(), (a.get("email") or "").lower())]
            if not matches:
                print(f"{args.account} is not a free account.")
                return 1
            for a in matches:
                api.call("DELETE", f"/billing/free-accounts/{urllib.parse.quote(a['userId'])}")
            print("Needs a subscription again:")
            show(matches)
        return 0
    except ApiError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    finally:
        if refresh_token:
            try:
                api.call("POST", "/auth/revoke", {"refreshToken": refresh_token})
            except ApiError:
                pass


if __name__ == "__main__":
    sys.exit(main())
