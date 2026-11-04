#!/usr/bin/env python3
"""Fills an empty account with believable demo data, for the store screenshots and Google's reviewers:

    python3 tools/demo/seed_demo.py googleTest                    # English, Port-au-Prince time
    python3 tools/demo/seed_demo.py kadans.demo.fr --language fr  # the same in French (ht: Kreyòl)

Asks for the account's password (and a two-factor code if it has one), never stored. Talks to production
(https://api.kadansplanning.com; --api for another server). It refuses an account that already has todos or budget
accounts: it is for demo accounts, never someone's real one.

What it makes, in the chosen language: a classic Pomodoro cycle; seven todos, three of them later today so Home is
full when you take the screenshots (take them within a few hours); a budget with four accounts in gourdes and dollars,
categories with limits, a month of movements, a transfer into dollars, and the salary as a monthly rule. Movements
are dated in the month that has the most days behind it: the current one from the 10th on, else the previous one
(swipe back a month in Budget). Standard library only.
"""
import argparse
import datetime as dt
import getpass
import json
import sys
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

TEXTS = {
    "en": {
        "template": "Classic",
        "todos": {
            "deep": ("Deep work: project report", "Two cycles, phone face down."),
            "language": ("Spanish practice", "20 new words, then the podcast."),
            "plants": ("Water the plants", ""),
            "meeting": ("Team meeting", "Bring the weekly numbers."),
            "bill": ("Pay the internet bill", ""),
            "reading": ("Evening reading", "30 pages."),
            "dentist": ("Dentist appointment", "Bring the insurance card."),
        },
        "accounts": {"cash": "Cash", "bank": "Bank account", "mobile": "Mobile money", "savings": "Savings (USD)"},
        "categories": {"salary": "Salary", "food": "Food", "transport": "Transport", "bills": "Bills",
                       "phone": "Phone & internet", "health": "Health", "leisure": "Leisure"},
        "notes": {"market": "Market", "tap": "Tap-tap", "power": "Electricity", "internet": "Internet",
                  "pharmacy": "Pharmacy", "cinema": "Cinema", "topup": "Phone top-up", "saving": "To savings"},
    },
    "fr": {
        "template": "Classique",
        "todos": {
            "deep": ("Travail de fond : rapport", "Deux cycles, téléphone retourné."),
            "language": ("Pratique de l'anglais", "20 mots nouveaux, puis le podcast."),
            "plants": ("Arroser les plantes", ""),
            "meeting": ("Réunion d'équipe", "Apporter les chiffres de la semaine."),
            "bill": ("Payer la facture internet", ""),
            "reading": ("Lecture du soir", "30 pages."),
            "dentist": ("Rendez-vous chez le dentiste", "Prendre la carte d'assurance."),
        },
        "accounts": {"cash": "Espèces", "bank": "Compte bancaire", "mobile": "Mobile money", "savings": "Épargne (USD)"},
        "categories": {"salary": "Salaire", "food": "Alimentation", "transport": "Transport", "bills": "Factures",
                       "phone": "Téléphone et internet", "health": "Santé", "leisure": "Loisirs"},
        "notes": {"market": "Marché", "tap": "Tap-tap", "power": "Électricité", "internet": "Internet",
                  "pharmacy": "Pharmacie", "cinema": "Cinéma", "topup": "Recharge téléphone", "saving": "Vers l'épargne"},
    },
    "ht": {
        "template": "Klasik",
        "todos": {
            "deep": ("Travay serye: rapò a", "De sik, telefòn nan vire."),
            "language": ("Pratike angle", "20 mo nouvo, apre sa podcast la."),
            "plants": ("Wouze plant yo", ""),
            "meeting": ("Reyinyon ekip la", "Pote chif semèn nan."),
            "bill": ("Peye bòdwo entènèt la", ""),
            "reading": ("Lekti aswè", "30 paj."),
            "dentist": ("Randevou ak dantis", "Pote kat asirans lan."),
        },
        "accounts": {"cash": "Lajan kach", "bank": "Kont labank", "mobile": "Mobile money", "savings": "Ekonomi (USD)"},
        "categories": {"salary": "Salè", "food": "Manje", "transport": "Transpò", "bills": "Bòdwo",
                       "phone": "Telefòn ak entènèt", "health": "Sante", "leisure": "Lwazi"},
        "notes": {"market": "Mache", "tap": "Taptap", "power": "Kouran", "internet": "Entènèt",
                  "pharmacy": "Famasi", "cinema": "Sinema", "topup": "Kat telefòn", "saving": "Nan ekonomi"},
    },
}

WEEK = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]

# Four 25-minute focuses, short breaks, a long break: the app's own "classic cycle".
CLASSIC = [("Focus", 25), ("Break", 5)] * 3 + [("Focus", 25), ("Break", 30)]


class Api:
    def __init__(self, base, language):
        self.base, self.language, self.token = base.rstrip("/"), language, None

    def call(self, method, path, body=None):
        request = urllib.request.Request(self.base + path, method=method,
                                         data=None if body is None else json.dumps(body).encode())
        request.add_header("Accept-Language", self.language)
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{method} {path}: HTTP {e.code} {e.read().decode(errors='replace')[:300]}")


def iso(moment):
    return moment.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def next_quarter(moment):
    return (moment + dt.timedelta(minutes=15 - moment.minute % 15)).replace(second=0, microsecond=0)


def ahead(now, hour, minute=0):
    """Today at that time if still more than 10 minutes ahead, else tomorrow."""
    at = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    return at if at > now + dt.timedelta(minutes=10) else at + dt.timedelta(days=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("username")
    parser.add_argument("--language", choices=sorted(TEXTS), default="en")
    parser.add_argument("--time-zone", default="America/Port-au-Prince")
    parser.add_argument("--api", default="https://api.kadansplanning.com")
    args = parser.parse_args()
    t = TEXTS[args.language]
    zone = ZoneInfo(args.time_zone)
    api = Api(args.api, args.language)

    session = api.call("POST", "/auth/login", {"username": args.username, "password": getpass.getpass(f"Password for {args.username}: ")})
    if session.get("mfaRequired"):
        session = api.call("POST", "/auth/mfa/verify", {"mfaToken": session["mfaToken"], "code": input("Two-factor code: ").strip()})
    api.token = session["accessToken"]

    if api.call("GET", "/todos?page=1&pageSize=1") or api.call("GET", "/budget/accounts/"):
        raise SystemExit(f"{args.username} already has todos or budget accounts: this is for an empty demo account.")

    api.call("PUT", "/users/me", {"language": args.language, "timeZone": args.time_zone})

    # ---- Tasks: three later today, so Home is full; the rest on their own rhythm.
    now = dt.datetime.now(zone)
    first = next_quarter(now + dt.timedelta(minutes=45))
    template = api.call("POST", "/pomodoro/templates", {
        "name": t["template"], "phases": [{"type": kind, "durationMinutes": minutes} for kind, minutes in CLASSIC]})

    def recurring(key, start, rule, focus=False, lead=10):
        title, description = t["todos"][key]
        todo = api.call("POST", "/todos/recurring", {
            "title": title, "description": description, "notificationEnabled": True, "notifyBeforeInMinutes": lead,
            "recurrenceRule": {"startDate": iso(start), "timeZone": args.time_zone, **rule},
            **({"pomodoroTemplateId": template["id"]} if focus else {})})
        print(f"  todo   {title}  ({start:%a %H:%M})")
        return todo

    # Weekdays, and today whatever it is: it must be on Home now.
    weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]
    today_name = first.strftime("%A") if first.date() == now.date() else now.strftime("%A")
    recurring("deep", first, {"frequency": "Weekly", "byDayOfWeek": sorted(set(weekdays + [today_name]),
                                                                           key=WEEK.index)}, focus=True)
    recurring("language", first + dt.timedelta(hours=3), {"frequency": "Daily"}, focus=True)
    recurring("plants", first + dt.timedelta(hours=5), {"frequency": "Daily", "interval": 3}, lead=0)
    recurring("reading", ahead(now, 21), {"frequency": "Daily"}, lead=5)
    monday = ahead(now, 9) + dt.timedelta(days=(7 - ahead(now, 9).weekday()) % 7)
    recurring("meeting", monday, {"frequency": "Weekly", "byDayOfWeek": ["Monday"]}, lead=15)
    recurring("bill", ahead(now, 10) + dt.timedelta(days=2), {"frequency": "Monthly"}, lead=60)
    title, description = t["todos"]["dentist"]
    dentist = ahead(now, 10, 30) + dt.timedelta(days=6)
    api.call("POST", "/todos/one-time", {"title": title, "description": description, "notificationEnabled": True,
                                         "notifyBeforeInMinutes": 60, "dueDate": iso(dentist)})
    print(f"  todo   {title}  ({dentist:%a %d %b %H:%M})")

    # ---- Budget.
    api.call("PUT", "/budget/settings/rates/Usd", {"rateInBase": 132})
    a = t["accounts"]
    cash = api.call("POST", "/budget/accounts/", {"name": a["cash"], "currency": "Htg", "type": "Cash", "initialBalance": 6500})
    bank = api.call("POST", "/budget/accounts/", {"name": a["bank"], "currency": "Htg", "type": "Bank", "initialBalance": 42000})
    mobile = api.call("POST", "/budget/accounts/", {"name": a["mobile"], "currency": "Htg", "type": "MobileMoney", "initialBalance": 2800})
    savings = api.call("POST", "/budget/accounts/", {"name": a["savings"], "currency": "Usd", "type": "Savings", "initialBalance": 850})
    c = t["categories"]
    category = {key: api.call("POST", "/budget/categories/", {"name": c[key], "kind": kind, "icon": icon})["id"]
                for key, kind, icon in [("salary", "Income", "💼"), ("food", "Expense", "🍚"), ("transport", "Expense", "🚌"),
                                        ("bills", "Expense", "💡"), ("phone", "Expense", "📱"), ("health", "Expense", "💊"),
                                        ("leisure", "Expense", "🎬")]}
    api.call("PUT", f"/budget/categories/{category['food']}/budget", {"amount": 15000, "currency": "Htg", "period": "Month"})
    api.call("PUT", f"/budget/categories/{category['leisure']}/budget", {"amount": 3000, "currency": "Htg", "period": "Month"})
    api.call("PUT", f"/budget/categories/{category['transport']}/budget", {"amount": 4000, "currency": "Htg", "period": "Month"})

    today = now.date()
    month = today.replace(day=1) if today.day >= 10 else (today.replace(day=1) - dt.timedelta(days=1)).replace(day=1)
    last = today if month.month == today.month else today.replace(day=1) - dt.timedelta(days=1)

    def day(n, hour=12):
        """The n-th day of the demo month, never after now."""
        return iso(min(dt.datetime.combine(min(month.replace(day=min(n, last.day)), last), dt.time(hour), zone), now))

    n = t["notes"]
    movements = [
        (bank, "Income", 85000, 1, "salary", None),
        (cash, "Expense", 2350, 2, "food", n["market"]), (cash, "Expense", 250, 2, "transport", n["tap"]),
        (mobile, "Expense", 500, 3, "phone", n["topup"]), (bank, "Expense", 1850, 4, "bills", n["power"]),
        (cash, "Expense", 2800, 6, "food", n["market"]), (cash, "Expense", 300, 6, "transport", n["tap"]),
        (bank, "Expense", 2500, 7, "phone", n["internet"]), (cash, "Expense", 650, 8, "health", n["pharmacy"]),
        (cash, "Expense", 3100, 9, "food", n["market"]), (cash, "Expense", 250, 9, "transport", n["tap"]),
        (mobile, "Expense", 1200, 9, "leisure", n["cinema"]),
    ]
    for account, kind, amount, d, key, note in movements:
        api.call("POST", "/budget/transactions/", {"accountId": account["id"], "kind": kind, "amount": amount,
                                                  "occurredAt": day(d), "categoryId": category[key],
                                                  **({"note": note} if note else {})})
    api.call("POST", "/budget/transactions/", {"accountId": bank["id"], "kind": "Transfer", "amount": 13200, "occurredAt": day(5),
                                              "transferAccountId": savings["id"], "transferAmount": 100, "note": n["saving"]})
    api.call("POST", "/budget/transactions/", {"accountId": bank["id"], "kind": "Transfer", "amount": 5000, "occurredAt": day(5),
                                              "transferAccountId": cash["id"]})
    salary_day = dt.datetime.combine(month.replace(day=1), dt.time(8), zone)
    while salary_day <= now:
        salary_day = (salary_day.replace(day=1) + dt.timedelta(days=32)).replace(day=1)
    api.call("POST", "/budget/recurring/", {"accountId": bank["id"], "kind": "Income", "amount": 85000, "categoryId": category["salary"],
                                           "recurrence": {"frequency": "Monthly", "startDate": iso(salary_day)}})
    print(f"  budget 4 accounts, 7 categories (3 with a limit), {len(movements) + 2} movements in {month:%B %Y}, the salary monthly")
    print(f"\nDone. Home shows today's todos from {first:%H:%M}; take the screenshots within a few hours.")


if __name__ == "__main__":
    main()
