#!/usr/bin/env python3
"""End-to-end check of the Budget module (accounts, HTG/USD, transfers with exchange,
category limits with their periods, conversion and alerts, monthly summary, editing a movement, recurring rules and
transfers, a bank statement's lines imported once however often they are sent, and "export my data") against a running
API in Development.

    python3 tools/smoke/budget_flows.py [base-url] [username] [password]

Logs in as `smoke` by default (admin has MFA enabled). Restart the API just before running so
the recurring-transactions job's first pass (~15 s after boot) lands inside the polling window.
Standard library only.
"""
import json, sys, time, urllib.request, urllib.error, datetime as dt
from zoneinfo import ZoneInfo

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5199"
USER = sys.argv[2] if len(sys.argv) > 2 else "smoke"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "Smoke123!"
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

def code(r): return (r or {}).get("errorCode")

s, tok = call("POST", "/auth/login", {"username": USER, "password": PASSWORD})
assert s == 200 and tok.get("accessToken"), f"login failed: {s} {tok}"
T = tok["accessToken"]
now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
def iso(d): return d.strftime("%Y-%m-%dT%H:%M:%SZ")

# summary totals accumulate per user per month across runs: snapshot first, assert deltas
def month_summary():
    return call("GET", f"/budget/summary?year={now.year}&month={now.month}", token=T)[1]
def htg_totals(summary):
    row = next((x for x in summary["totals"] if x["currency"] == "Htg"), None)
    return (row["income"], row["expense"]) if row else (0, 0)
income0, expense0 = htg_totals(month_summary())

# --- accounts, one per currency ---
s, cash = call("POST", "/budget/accounts/", {"name": "smoke: Cash", "currency": "Htg", "type": "Cash", "initialBalance": 1000}, T)
C("create HTG cash account", s == 200 and cash["balance"] == 1000, f"{s}")
s, bank = call("POST", "/budget/accounts/", {"name": "smoke: Bank", "currency": "Htg", "type": "Bank"}, T)
C("create HTG bank account", s == 200)
s, usd = call("POST", "/budget/accounts/", {"name": "smoke: USD", "currency": "Usd", "type": "Savings"}, T)
C("create USD account", s == 200)

# --- categories ---
s, salary = call("POST", "/budget/categories/", {"name": "smoke: Salè", "kind": "Income", "icon": "💼"}, T)
C("create income category", s == 200)
s, food = call("POST", "/budget/categories/", {"name": "smoke: Manje", "kind": "Expense", "icon": "🍚"}, T)
C("create expense category", s == 200)

# --- income + expense with category rules ---
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Income", "amount": 85000, "occurredAt": iso(now), "categoryId": salary["id"], "note": "smoke pay"}, T)
C("income lands", s == 200 and r["currency"] == "Htg")
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Expense", "amount": 2500.50, "occurredAt": iso(now), "categoryId": food["id"], "note": "smoke market"}, T)
C("expense lands", s == 200)
expense_id = r["id"] if s == 200 else None
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Expense", "amount": 100, "occurredAt": iso(now), "categoryId": salary["id"]}, T)
C("expense with income category rejected (10050)", s == 400 and code(r) == "10050", f"{s} {code(r)}")
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Expense", "amount": 10.999, "occurredAt": iso(now)}, T)
C("three decimals rejected (10048)", s == 400 and code(r) == "10048")

# --- transfers ---
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Transfer", "amount": 5000, "occurredAt": iso(now), "transferAccountId": bank["id"]}, T)
C("same-currency transfer", s == 200 and r["transferAmount"] == 5000)
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Transfer", "amount": 100, "occurredAt": iso(now), "transferAccountId": cash["id"]}, T)
C("self transfer rejected (10049)", s == 400 and code(r) == "10049")
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Transfer", "amount": 13200, "occurredAt": iso(now), "transferAccountId": usd["id"]}, T)
C("cross-currency without received amount rejected (10047)", s == 400 and code(r) == "10047")
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Transfer", "amount": 13200, "occurredAt": iso(now), "transferAccountId": usd["id"], "transferAmount": 100}, T)
C("cross-currency transfer with exchange", s == 200 and r["transferAmount"] == 100)

# --- balances: 1000 + 85000 - 2500.50 - 5000 - 13200 = 65299.50; bank 5000; usd 100 ---
s, accounts = call("GET", "/budget/accounts/", token=T)
by_id = {a["id"]: a for a in accounts}
C("cash balance computed", by_id[cash["id"]]["balance"] == 65299.50, str(by_id[cash["id"]]["balance"]))
C("bank received the transfer", by_id[bank["id"]]["balance"] == 5000)
C("usd received the exchanged amount", by_id[usd["id"]]["balance"] == 100)

# --- category budget + summary ---
s, r = call("PUT", f"/budget/categories/{food['id']}/budget", {"amount": 20000, "currency": "Htg", "period": "Month"}, T)
C("set a monthly limit on an expense category", s == 200 and r["period"] == "Month", f"{s} {r}")
s, r = call("PUT", f"/budget/categories/{salary['id']}/budget", {"amount": 1, "currency": "Htg"}, T)
C("limit on income category rejected (10050)", s == 400 and code(r) == "10050")
summary = month_summary()
income1, expense1 = htg_totals(summary)
C("summary totals per currency (delta)", income1 - income0 == 85000 and expense1 - expense0 == 2500.50, f"+{income1-income0}/+{expense1-expense0}")
spend = next((c for c in summary["categories"] if c["categoryId"] == food["id"]), None)
C("category spend in the month", spend is not None and spend["amount"] == 2500.50, f"{spend}")
limit = next((l for l in summary.get("limits") or [] if l["categoryId"] == food["id"]), None)
C("category spend vs limit", limit is not None and limit["spent"] == 2500.50 and limit["amount"] == 20000 and limit["percent"] == 12, f"{limit}")

# --- edit + delete restore the math ---
s, r = call("PUT", f"/budget/transactions/{expense_id}", {"amount": 3000, "occurredAt": iso(now), "categoryId": food["id"], "note": "smoke market (edited)"}, T)
C("edit expense", s == 200 and r["amount"] == 3000)
s, r = call("DELETE", f"/budget/transactions/{expense_id}", token=T)
C("delete expense", s == 200)
s, accounts = call("GET", "/budget/accounts/", token=T)
C("balance reflects the delete", {a["id"]: a for a in accounts}[cash["id"]]["balance"] == 67800.00)

# --- an edit moves a movement: the wrong account, a transfer's destination; never its kind ---
def balances():
    return {a["id"]: a["balance"] for a in call("GET", "/budget/accounts/", token=T)[1]}
s, lunch = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Expense", "amount": 50, "occurredAt": iso(now), "categoryId": food["id"]}, T)
s, r = call("GET", f"/budget/transactions/{lunch['id']}", token=T)
C("one movement, to open it", s == 200 and r["amount"] == 50 and r["categoryId"] == food["id"], f"{s}")
s, r = call("GET", "/budget/transactions/00000000-0000-0000-0000-000000000001", token=T)
C("an unknown movement is 404", s == 404, f"{s}")
s, r = call("PUT", f"/budget/transactions/{lunch['id']}", {"amount": 12.5, "occurredAt": iso(now), "accountId": usd["id"], "note": "smoke lunch"}, T)
C("moved to the USD account, in its currency", s == 200 and r["accountId"] == usd["id"] and r["currency"] == "Usd" and r["categoryId"] is None, f"{s} {r}")
b = balances()
C("the balances follow the move", b[cash["id"]] == 67800.00 and b[usd["id"]] == 87.5, f"{b[cash['id']]} {b[usd['id']]}")
s, r = call("PUT", f"/budget/transactions/{lunch['id']}", {"amount": 12.5, "occurredAt": iso(now), "transferAccountId": bank["id"]}, T)
C("an expense takes no destination (10049)", s == 400 and code(r) == "10049", f"{s} {code(r)}")
s, r = call("PUT", f"/budget/transactions/{lunch['id']}", {"amount": 12.5, "occurredAt": iso(now), "note": "x" * 1001}, T)
C("a note over 1,000 characters is refused (10015)", s == 400 and code(r) == "10015", f"{s} {code(r)}")
call("DELETE", f"/budget/transactions/{lunch['id']}", token=T)
s, move = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Transfer", "amount": 1000, "occurredAt": iso(now), "transferAccountId": bank["id"]}, T)
s, r = call("PUT", f"/budget/transactions/{move['id']}", {"amount": 1320, "occurredAt": iso(now), "transferAccountId": usd["id"]}, T)
C("a destination in dollars needs the received amount (10047)", s == 400 and code(r) == "10047", f"{s} {code(r)}")
s, r = call("PUT", f"/budget/transactions/{move['id']}", {"amount": 1320, "occurredAt": iso(now), "transferAccountId": usd["id"], "transferAmount": 10}, T)
C("the transfer goes to dollars instead", s == 200 and r["transferAccountId"] == usd["id"] and r["transferAmount"] == 10, f"{s} {r}")
b = balances()
C("the bank no longer gets it, the dollars do", b[bank["id"]] == 5000 and b[usd["id"]] == 110, f"{b[bank['id']]} {b[usd['id']]}")
call("DELETE", f"/budget/transactions/{move['id']}", token=T)

# --- recurring: 3 daily occurrences all in the past; the job materializes them ---
s, rule = call("POST", "/budget/recurring/", {
    "accountId": bank["id"], "kind": "Expense", "amount": 250, "note": "smoke recurring",
    "recurrence": {"frequency": "Daily", "startDate": iso(now - dt.timedelta(days=2, hours=1)), "count": 3},
}, T)
C("create recurring rule", s == 200, f"{s} {rule}")

# --- recurring transfers: "100 to savings every month", here daily and in the past so the job books them now ---
s, saving = call("POST", "/budget/recurring/", {
    "accountId": cash["id"], "kind": "Transfer", "amount": 100, "transferAccountId": bank["id"], "note": "smoke saving",
    "recurrence": {"frequency": "Daily", "startDate": iso(now - dt.timedelta(days=1, hours=1)), "count": 2},
}, T)
C("a recurring transfer between gourde accounts", s == 200 and saving["transferAccountId"] == bank["id"] and saving["transferAmount"] == 100, f"{s} {saving}")
s, r = call("POST", "/budget/recurring/", {
    "accountId": cash["id"], "kind": "Transfer", "amount": 1320, "transferAccountId": usd["id"],
    "recurrence": {"frequency": "Monthly", "startDate": iso(now)},
}, T)
C("one into dollars needs the received amount (10047)", s == 400 and code(r) == "10047", f"{s} {code(r)}")
s, r = call("POST", "/budget/recurring/", {
    "accountId": cash["id"], "kind": "Transfer", "amount": 100, "transferAccountId": bank["id"], "categoryId": food["id"],
    "recurrence": {"frequency": "Monthly", "startDate": iso(now)},
}, T)
C("a recurring transfer has no category (10050)", s == 400 and code(r) == "10050", f"{s} {code(r)}")
s, dollars = call("POST", "/budget/recurring/", {
    "accountId": cash["id"], "kind": "Transfer", "amount": 1320, "transferAccountId": usd["id"], "transferAmount": 10,
    "recurrence": {"frequency": "Daily", "startDate": iso(now - dt.timedelta(hours=1)), "count": 1},
}, T)
C("a recurring exchange into dollars", s == 200 and dollars["transferAmount"] == 10, f"{s} {dollars}")

# --- the todo's rule builder for money: a salary on the 15th and the 30th, rent on the last Friday of some months ---
def payroll_days(start, count, until):
    """The 15th and the 30th from start, a month without a 30th paying on its last day (as the app sends it)."""
    days, year, month = [], start.year, start.month
    while len(days) < count:
        last = ((dt.date(year + (month == 12), month % 12 + 1, 1)) - dt.timedelta(days=1)).day
        for day in (15, min(30, last)):
            d = start.replace(year=year, month=month, day=day)
            if d >= start and len(days) < count: days.append(d)
        month, year = (1, year + 1) if month == 12 else (month + 1, year)
    return [d.date().isoformat() for d in days if d <= until]
s, payroll = call("POST", "/budget/accounts/", {"name": "smoke: Payroll", "currency": "Htg", "type": "Bank"}, T)
first_15 = (now.replace(day=1) - dt.timedelta(days=40)).replace(day=15, hour=12, minute=0, second=0)
s, twice = call("POST", "/budget/recurring/", {
    "accountId": payroll["id"], "kind": "Income", "amount": 10, "note": "smoke 15/30",
    "recurrence": {"frequency": "Monthly", "startDate": iso(first_15), "byMonthDay": [15, 28, 29, 30], "bySetPos": [1, -1], "count": 4},
}, T)
C("a salary on the 15th and the 30th (or a shorter month's last day)", s == 200 and "BYMONTHDAY=15,28,29,30" in twice["rrule"]
  and "BYSETPOS=1,-1" in twice["rrule"] and twice["frequency"] == "Monthly"
  and twice["interval"] == 1 and twice["count"] == 4, f"{s} {twice}")
def last_friday(year, month):
    d = (dt.datetime(year + (month == 12), month % 12 + 1, 1, 12, tzinfo=dt.timezone.utc) - dt.timedelta(days=1))
    return d - dt.timedelta(days=(d.weekday() - 4) % 7)
# The start is the first occurrence, as the app sends it: the next last Friday of March or June.
rent_start = min(d for d in (last_friday(y, m) for y in (now.year, now.year + 1) for m in (3, 6)) if d > now)
s, rent = call("POST", "/budget/recurring/", {
    "accountId": payroll["id"], "kind": "Expense", "amount": 5, "note": "smoke last friday",
    "recurrence": {"frequency": "Monthly", "startDate": iso(rent_start), "byDayOfWeek": ["Friday"], "bySetPos": [-1], "byMonth": [3, 6]},
}, T)
C("rent on the last Friday of March and June", s == 200 and "BYSETPOS=-1" in rent["rrule"] and "BYMONTH=3,6" in rent["rrule"]
  and dt.datetime.fromisoformat(rent["nextAt"]) == rent_start, f"{s} {rent}")

deadline = time.time() + 90
count = saved = exchanged = 0
while time.time() < deadline:
    s, txs = call("GET", f"/budget/transactions/?accountId={bank['id']}&pageSize=100", token=T)
    count = sum(1 for t in txs if t.get("recurringTransactionId") == rule["id"])
    saved = [t for t in txs if t.get("recurringTransactionId") == saving["id"]]
    s, txs = call("GET", f"/budget/transactions/?accountId={usd['id']}&pageSize=100", token=T)
    exchanged = [t for t in txs if t.get("recurringTransactionId") == dollars["id"]]
    s, txs = call("GET", f"/budget/transactions/?accountId={payroll['id']}&pageSize=100", token=T)
    paid = sorted(t["occurredAt"][:10] for t in txs if t.get("recurringTransactionId") == twice["id"])
    if count == 3 and len(saved) == 2 and len(exchanged) == 1 and paid == payroll_days(first_15, 4, now): break
    time.sleep(5)
C("job materialized all 3 past occurrences", count == 3, f"got {count}")
C("job booked both saving transfers, into the bank", len(saved) == 2 and all(
    t["kind"] == "Transfer" and t["accountId"] == cash["id"] and t["transferAccountId"] == bank["id"] and t["transferAmount"] == 100
    for t in saved), f"{saved}")
C("job booked the exchange with its received amount", len(exchanged) == 1 and exchanged[0]["transferAmount"] == 10, f"{exchanged}")
C("job booked the salary on each 15th and 30th so far", paid == payroll_days(first_15, 4, now), f"{paid} vs {payroll_days(first_15, 4, now)}")
s, rules = call("GET", "/budget/recurring/", token=T)
mine = next((x for x in rules if x["id"] == rule["id"]), None)
C("exhausted rule deactivated", mine is not None and mine["isActive"] is False, f"{mine and mine['isActive']}")
b = balances()
C("the transfers moved the money", b[bank["id"]] == 5000 - 750 + 200 and b[usd["id"]] == 100 + 10, f"{b[bank['id']]} {b[usd['id']]}")

s, r = call("PUT", f"/budget/recurring/{dollars['id']}", {"amount": 1400, "isActive": False}, T)
C("a recurring exchange changed keeps needing the received amount (10047)", s == 400 and code(r) == "10047", f"{s} {code(r)}")
s, r = call("PUT", f"/budget/recurring/{dollars['id']}", {"amount": 1400, "transferAmount": 10.5, "isActive": False}, T)
C("its next ones carry the new pair", s == 200 and r["amount"] == 1400 and r["transferAmount"] == 10.5, f"{s} {r}")
s, r = call("PUT", f"/budget/recurring/{saving['id']}", {"amount": 150, "transferAmount": 140, "isActive": False}, T)
C("between gourdes one amount only (10047)", s == 400 and code(r) == "10047", f"{s} {code(r)}")
s, r = call("PUT", f"/budget/recurring/{saving['id']}", {"amount": 150, "isActive": False}, T)
C("the saving raised to 150", s == 200 and r["amount"] == 150 and r["transferAmount"] == 150, f"{s} {r}")

# --- recurring limits: money moves by the day, and a rule backdates a year at most ---
s, r = call("POST", "/budget/recurring/", {
    "accountId": bank["id"], "kind": "Expense", "amount": 5, "note": "smoke hourly",
    "recurrence": {"frequency": "Hourly", "startDate": iso(now)},
}, T)
C("an hourly money rule is refused (10011)", s == 400 and code(r) == "10011", f"{s} {r}")
s, r = call("POST", "/budget/recurring/", {
    "accountId": bank["id"], "kind": "Expense", "amount": 5, "note": "smoke backdated",
    "recurrence": {"frequency": "Monthly", "startDate": iso(now - dt.timedelta(days=400))},
}, T)
C("a money rule backdated over a year is refused (10012)", s == 400 and code(r) == "10012", f"{s} {r}")

# --- settings: base currency + indicative per-currency rates (estimates only) ---
s, r = call("GET", "/budget/settings/", token=T)
C("settings default to HTG base", s == 200 and r["baseCurrency"] == "Htg", f"{r}")
s, r = call("PUT", "/budget/settings/rates/Usd", {"rateInBase": 132.50}, T)
C("set today's USD rate", s == 200 and any(x["currency"] == "Usd" and x["rateInBase"] == 132.50 for x in r["rates"]))
s, r = call("PUT", "/budget/settings/rates/Dop", {"rateInBase": 2.20}, T)
C("a second currency (DOP) gets its own rate", s == 200 and len(r["rates"]) >= 2)
s, r = call("PUT", "/budget/settings/rates/Htg", {"rateInBase": 1}, T)
C("rate on the base itself rejected (10047)", s == 400 and code(r) == "10047")
s, r = call("PUT", "/budget/settings/rates/Usd", {"rateInBase": -1}, T)
C("negative rate rejected (10048)", s == 400 and code(r) == "10048")
s, eur_acc = call("POST", "/budget/accounts/", {"name": "smoke: EUR", "currency": "Eur", "type": "Savings", "initialBalance": 300}, T)
C("EUR account (diaspora) accepted", s == 200)
summary = month_summary()
combined = summary.get("combined")
C("estimate is in the base currency", combined is not None and combined["baseCurrency"] == "Htg", f"{combined}")
C("EUR reported missing, never guessed", combined is not None and "Eur" in combined.get("missingRates", []))
income_now, _ = htg_totals(summary)
C("combined income converts correctly (no USD income yet)", combined is not None and combined["income"] == income_now)
s, r = call("DELETE", "/budget/settings/rates/Dop", token=T)
C("forget a rate", s == 200)
s, r = call("PUT", "/budget/settings/base-currency", {"baseCurrency": "Usd"}, T)
C("base change clears old-base rates", s == 200 and r["baseCurrency"] == "Usd" and r["rates"] == [], f"{s} {r}")
call("PUT", "/budget/settings/base-currency", {"baseCurrency": "Htg"}, T)  # restore

# --- a limit in its own currency: other currencies at the person's rate, alerts at 80% and 100%, once each ---
def limit_alerts(category_id):
    s, items = call("GET", "/notifications?pageSize=100", token=T)
    return [n for n in items if n["kind"] == "budget.limit" and (n.get("data") or {}).get("categoryId") == category_id]
call("PUT", "/budget/settings/rates/Usd", {"rateInBase": 132.50}, T)
s, fuel = call("POST", "/budget/categories/", {"name": "smoke: Gaz", "kind": "Expense", "icon": "⛽"}, T)
s, wallet = call("POST", "/budget/accounts/", {"name": "smoke: Wallet", "currency": "Htg", "initialBalance": 50000}, T)
s, dollars_acc = call("POST", "/budget/accounts/", {"name": "smoke: Dollars", "currency": "Usd", "initialBalance": 500}, T)
s, euros_acc = call("POST", "/budget/accounts/", {"name": "smoke: Euros", "currency": "Eur", "initialBalance": 500}, T)
s, r = call("PUT", f"/budget/categories/{fuel['id']}/budget", {"amount": 1000, "currency": "Htg", "period": "Week"}, T)
C("a weekly limit in gourdes", s == 200 and r["currency"] == "Htg" and r["period"] == "Week", f"{s} {r}")
def spend_on_fuel(account, amount):
    return call("POST", "/budget/transactions/", {"accountId": account["id"], "kind": "Expense", "amount": amount, "occurredAt": iso(now), "categoryId": fuel["id"]}, T)
spend_on_fuel(wallet, 700)
C("70%: no alert yet", limit_alerts(fuel["id"]) == [], f"{limit_alerts(fuel['id'])}")
spend_on_fuel(dollars_acc, 2)  # 2 USD at 132.50 = 265 HTG: 965 of 1 000
alerts = limit_alerts(fuel["id"])
C("96% with dollars at the rate: the 80% alert", len(alerts) == 1 and (alerts[0]["data"] or {}).get("level") == "80"
  and "96%" in alerts[0]["body"] and alerts[0]["title"] == "⛽ smoke: Gaz" and alerts[0]["data"].get("link") == "kadans://budget", f"{alerts}")
spend_on_fuel(wallet, 10)
C("no second 80% alert in the same week", len(limit_alerts(fuel["id"])) == 1)
spend_on_fuel(wallet, 100)
alerts = limit_alerts(fuel["id"])
C("over the limit: the 100% alert", len(alerts) == 2 and any((a["data"] or {}).get("level") == "100" and "107%" in a["body"] for a in alerts), f"{alerts}")
spend_on_fuel(euros_acc, 5)  # no EUR rate: left out, said
s, summary = call("GET", f"/budget/summary?year={now.year}&month={now.month}", token=T)
status = next((l for l in summary.get("limits") or [] if l["categoryId"] == fuel["id"]), None)
week_start = dt.datetime.fromisoformat(status["periodStart"]) if status else None
C("the week's status: spent in gourdes, dollars priced, euros left out", status is not None and status["spent"] == 1075 and status["percent"] == 107
  and status["converted"] == ["Usd"] and status["missingRates"] == ["Eur"] and status["period"] == "Week", f"{status}")
local_start = week_start.astimezone(ZoneInfo(summary["timeZoneId"])) if week_start else None
C("the week starts at midnight on Monday, there, at most a week ago", local_start is not None and local_start.weekday() == 0
  and (local_start.hour, local_start.minute) == (0, 0) and dt.timedelta(0) <= now - week_start < dt.timedelta(days=7), f"{local_start}")
s, r = call("PUT", f"/budget/categories/{fuel['id']}/budget", {"amount": 1000, "currency": "Usd", "period": "Week"}, T)
C("a limit keeps its currency (10047)", s == 400 and code(r) == "10047", f"{s} {code(r)}")
s, r = call("PUT", f"/budget/categories/{fuel['id']}/budget", {"amount": 2000, "currency": "Htg", "period": "Month"}, T)
C("its amount and period change", s == 200 and r["amount"] == 2000 and r["period"] == "Month", f"{s} {r}")
spend_on_fuel(wallet, 600)  # 1 675 of 2 000 this month: 83%, news for the new limit
alerts = limit_alerts(fuel["id"])
C("a changed limit alerts afresh", len(alerts) == 3 and any("83%" in a["body"] for a in alerts), f"{[a['body'] for a in alerts]}")
s, r = call("DELETE", f"/budget/categories/{fuel['id']}/budget", token=T)
C("remove the limit", s == 200)
s, r = call("PUT", f"/budget/categories/{fuel['id']}/budget", {"amount": 10, "currency": "Usd", "period": "Year"}, T)
C("then set one in dollars, for the year", s == 200 and r["currency"] == "Usd" and r["period"] == "Year", f"{s} {r}")

# --- a bank statement's lines: the app reads the file, only the lines the person kept come here ---
s, stmt = call("POST", "/budget/accounts/", {"name": "smoke: Statement", "currency": "Htg", "type": "Bank"}, T)
s, other = call("POST", "/budget/accounts/", {"name": "smoke: Other bank", "currency": "Htg", "type": "Bank"}, T)
run = str(int(time.time()))
def key(n): return f"l1-smoke{run}x{n}"
def line(n, kind, amount, note, days_ago=5, category=None):
    return {"kind": kind, "amount": amount, "occurredAt": iso(now - dt.timedelta(days=days_ago)), "importKey": key(n), "note": note, "categoryId": category}
market = "smoke MARCHE EN FER " + run
statement = [line(1, "Expense", 1250, market), line(2, "Income", 85000, "smoke SALAIRE " + run), line(3, "Expense", 980.5, market, days_ago=2)]
def import_lines(account, movements): return call("POST", "/budget/transactions/import/", {"accountId": account["id"], "movements": movements}, T)
def balance_of(account):
    return next(a["balance"] for a in call("GET", "/budget/accounts/?includeArchived=true", token=T)[1] if a["id"] == account["id"])

s, r = import_lines(stmt, statement)
C("a statement's lines are booked on the account", s == 200 and r == {"imported": 3, "skipped": 0}, f"{s} {r}")
C("and its balance counts them", balance_of(stmt) == 85000 - 1250 - 980.5, f"{balance_of(stmt)}")
s, booked = call("GET", f"/budget/transactions/?accountId={stmt['id']}", token=T)
C("each as a movement of its kind, on its day, with the bank's text",
  s == 200 and sorted((m["kind"], m["amount"], m["note"]) for m in booked) == sorted((m["kind"], m["amount"], m["note"]) for m in statement), f"{booked}")
s, r = import_lines(stmt, statement)
C("the same statement again adds nothing", s == 200 and r == {"imported": 0, "skipped": 3}, f"{s} {r}")
s, r = import_lines(stmt, [statement[2], line(4, "Expense", 300, "smoke TAXI " + run, days_ago=1)])
C("the next statement, which overlaps it, adds only what is new", s == 200 and r == {"imported": 1, "skipped": 1}, f"{s} {r}")
s, r = import_lines(stmt, [line(5, "Expense", 50, "smoke twice " + run), line(5, "Expense", 50, "smoke twice " + run)])
C("a line sent twice in one request is booked once", s == 200 and r == {"imported": 1, "skipped": 1}, f"{s} {r}")
C("the balance after all that", balance_of(stmt) == 85000 - 1250 - 980.5 - 300 - 50, f"{balance_of(stmt)}")

def known(account): return call("GET", f"/budget/transactions/import/known?accountId={account['id']}", token=T)
s, r = known(stmt)
C("before importing, the app learns which lines the account has", s == 200 and sorted(r["importKeys"]) == sorted(key(n) for n in (1, 2, 3, 4, 5)), f"{s} {r}")
s, r = known(other)
C("another account has none of them", s == 200 and r["importKeys"] == [], f"{s} {r}")
s, r = import_lines(other, [statement[0]])
C("and can book the same line itself (a key belongs to its account)", s == 200 and r == {"imported": 1, "skipped": 0}, f"{s} {r}")

# the person files the market under Food once; the next statement's market lines come with that category
first_market = next(m for m in booked if m["note"] == market)
s, r = call("PUT", f"/budget/transactions/{first_market['id']}", {"amount": first_market["amount"], "occurredAt": first_market["occurredAt"], "categoryId": food["id"], "note": market}, T)
C("an imported movement is corrected like any other", s == 200 and r["categoryId"] == food["id"], f"{s} {r}")
s, r = known(stmt)
mine = [c for c in r["categories"] if run in c["note"]] if s == 200 else None
C("a text filed once comes back with its category, for its kind",
  mine == [{"kind": "Expense", "note": market, "categoryId": food["id"]}], f"{s} {mine}")
s, r = import_lines(stmt, [line(6, "Expense", 410, market, days_ago=1, category=food["id"])])
C("and is booked with it", s == 200 and r == {"imported": 1, "skipped": 0}, f"{s} {r}")

s, r = import_lines(stmt, [line(7, "Transfer", 10, "smoke")])
C("a statement's line is never a transfer (400)", s == 400, f"{s} {r}")
s, r = import_lines(stmt, [])
C("an empty import is refused (400)", s == 400, f"{s}")
s, r = import_lines(stmt, [line(1000 + n, "Expense", 1, "smoke many") for n in range(501)])
C("more than 500 lines at once are refused (400)", s == 400, f"{s}")
s, r = import_lines(stmt, [dict(line(8, "Expense", 10, "smoke"), importKey="not a key!")])
C("a line without a proper key is refused (400)", s == 400, f"{s}")
s, r = import_lines(stmt, [line(9, "Expense", 10, "smoke good " + run), line(10, "Expense", 0, "smoke bad " + run)])
C("one line that cannot be booked stops them all (10048)", s == 400 and code(r) == "10048", f"{s} {code(r)}")
s, r = known(stmt)
C("…none of them is in the account", s == 200 and key(9) not in r["importKeys"] and key(10) not in r["importKeys"], f"{s}")
s, r = import_lines(stmt, [line(11, "Expense", 10, "smoke", category=salary["id"])])
C("a category of the other kind is refused (10050)", s == 400 and code(r) == "10050", f"{s} {code(r)}")
s, r = import_lines(stmt, [line(12, "Expense", 10, "smoke", category="00000000-0000-0000-0000-00000000dead")])
C("an unknown category is refused (404)", s == 404, f"{s}")
s, r = import_lines({"id": "00000000-0000-0000-0000-00000000dead"}, [line(13, "Expense", 10, "smoke")])
C("an unknown account is refused (404)", s == 404, f"{s}")
s, r = known({"id": "00000000-0000-0000-0000-00000000dead"})
C("nothing is known of an account that is not the person's (404)", s == 404, f"{s}")
s, r = call("GET", f"/budget/transactions/import/known?accountId={stmt['id']}")
C("nor without a sign-in (401)", s == 401, f"{s}")
s, r = call("POST", "/budget/transactions/import/", {"accountId": stmt["id"], "movements": [line(14, "Expense", 10, "smoke")]})
C("nothing is imported without a sign-in (401)", s == 401, f"{s}")

# --- export my data: one zip, a spreadsheet's files and a calendar's ---
import io, zipfile, csv
def raw(path, token=None):
    req = urllib.request.Request(BASE + path)
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r: return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e: return e.code, dict(e.headers), e.read()
s, r = call("POST", "/budget/transactions/", {"accountId": stmt["id"], "kind": "Expense", "amount": 1, "occurredAt": iso(now), "note": "=HYPERLINK(\"http://example.invalid\") " + run}, T)
# a todo with a time done and a note on it, and a focus cycle: the export carries what the person wrote, wherever
start = (now + dt.timedelta(days=1)).replace(hour=9, minute=0, second=0)
s, todo = call("POST", "/todos/recurring", {"title": "smoke export " + run, "description": "to carry off", "notificationEnabled": False,
                                             "recurrenceRule": {"frequency": "Daily", "startDate": iso(start), "timeZone": "UTC", "count": 3}}, T)
times = call("GET", f"/todos/{todo['id']}/occurrences?page=1&pageSize=10", token=T)[1] if s == 200 else []
if times:
    call("PUT", f"/occurrences/{times[0]['id']}/remark", {"remark": "smoke note " + run}, token=T)
    call("PUT", f"/occurrences/{times[0]['id']}/complete", token=T)
s, cycle = call("POST", "/pomodoro/templates", {"name": "smoke cycle " + run, "phases": [{"type": "Focus", "durationMinutes": 25}, {"type": "Break", "durationMinutes": 5}]}, T)
started = time.time()
s, headers, body = raw("/users/me/export", T)
took = time.time() - started
C("export answers with a zip", s == 200 and headers.get("Content-Type") == "application/zip" and body[:2] == b"PK", f"{s} {headers.get('Content-Type')}")
C("named for the day it was made", f"kadans-export-{now:%Y-%m-%d}.zip" in headers.get("Content-Disposition", ""), headers.get("Content-Disposition", ""))
C("in a few seconds at most", took < 10, f"{took:.1f} s")
archive = zipfile.ZipFile(io.BytesIO(body)) if s == 200 else None
names = sorted(archive.namelist()) if archive else []
C("with the account, the budget, the todos and the focus time in it", names == [
    "account.csv", "budget/accounts.csv", "budget/categories.csv", "budget/currencies.csv", "budget/movements.csv", "budget/recurring.csv",
    "focus/cycles.csv", "focus/sessions.csv", "todos/history.csv", "todos/todos.csv", "todos/todos.ics"], f"{names}")
if archive:
    def table(name):
        text = archive.read(name).decode("utf-8")
        return text[:1] == "\ufeff", list(csv.reader(io.StringIO(text.lstrip("\ufeff"))))
    marked, rows = table("budget/movements.csv")
    C("movements.csv opens in Excel with its accents (UTF-8 mark) and a header", marked and rows[0][:5] == ["Date", "Account", "Currency", "Kind", "Amount"], f"{rows[0]}")
    column = {name: index for index, name in enumerate(rows[0])}
    mine = [row for row in rows[1:] if row[column["Account"]] == "smoke: Statement" and run in row[column["Note"]]]
    C("every movement of the account is there", len(mine) == 7, f"{len(mine)}")
    taxi = next((row for row in mine if "TAXI" in row[column["Note"]]), None)
    C("a line as a person reads it: its day, its account, its amount with two decimals",
      taxi is not None and taxi[column["Amount"]] == "300.00" and taxi[column["Currency"]] == "HTG" and taxi[column["Kind"]] == "Expense" and len(taxi[column["Date"]]) == 10, f"{taxi}")
    C("the ones a statement brought say so", taxi is not None and taxi[column["Imported"]] == "yes" and sum(1 for row in mine if row[column["Imported"]] == "yes") == 6)
    filed = next((row for row in mine if row[column["Category"]]), None)
    C("a category by its name, not its id", filed is not None and filed[column["Category"]] == "smoke: Manje", f"{filed}")
    formula = next((row for row in mine if "HYPERLINK" in row[column["Note"]]), None)
    C("a note that looks like a formula cannot run in a spreadsheet", formula is not None and formula[column["Note"]].startswith("'="), f"{formula}")
    marked, rows = table("budget/accounts.csv")
    account_row = next((row for row in rows[1:] if row[0] == "smoke: Statement"), None)
    C("accounts.csv has each account with its balance", account_row is not None and float(account_row[rows[0].index("Balance")]) == balance_of(stmt), f"{account_row}")
    marked, rows = table("budget/categories.csv")
    fuel_row = next((row for row in rows[1:] if row[0] == fuel["name"] and row[rows[0].index("Limit")]), None)
    C("categories.csv has each category with its limit", fuel_row is not None and fuel_row[rows[0].index("Limit"):] == ["10.00", "USD", "Year"], f"{fuel_row}")
    marked, rows = table("budget/recurring.csv")
    C("recurring.csv has the recurring movements, as their rule and in words", len(rows) > 1 and "Repeats (RRULE)" in rows[0] and any("FREQ=" in row[rows[0].index("Repeats (RRULE)")] and row[1].startswith("smoke: ") for row in rows[1:]), f"{rows[:2]}")
    marked, rows = table("budget/currencies.csv")
    C("currencies.csv starts with the currency the person counts in", len(rows) > 1 and rows[1][0] == rows[1][2] and rows[1][1] == "1", f"{rows[:3]}")
    marked, rows = table("account.csv")
    C("account.csv names the account, and holds no secret", len(rows) == 2 and rows[1][0] == USER and rows[0] == ["Username", "Email", "Name", "Time zone", "Language", "Clock"], f"{rows[0]}")
    marked, rows = table("todos/history.csv")
    noted = next((row for row in rows[1:] if row[0] == "smoke export " + run), None)
    C("history.csv has a time done, with the person's note", noted is not None and noted[rows[0].index("Status")] == "Completed" and noted[rows[0].index("Note")] == "smoke note " + run and noted[rows[0].index("Done at")], f"{noted}")
    marked, rows = table("focus/cycles.csv")
    C("cycles.csv has the person's focus cycles", any(row == ["smoke cycle " + run, "Focus 25, Break 5"] for row in rows[1:]), f"{rows[-1] if rows else None}")
    marked, rows = table("focus/sessions.csv")
    C("sessions.csv has a header", marked and rows[0] == ["Todo", "Started", "Ended", "Status", "Focus minutes"], f"{rows[0] if rows else None}")
    ics = archive.read("todos/todos.ics").decode("utf-8")
    C("todos.ics is a calendar file", ics.startswith("BEGIN:VCALENDAR") and ics.rstrip().endswith("END:VCALENDAR"))
    marked, rows = table("todos/todos.csv")
    C("todos.csv has a header", marked and len(rows) >= 1 and "Title" in rows[0], f"{rows[0] if rows else None}")
s, headers, body = raw("/users/me/export")
C("no export without a sign-in (401)", s == 401, f"{s}")
if todo and todo.get("id"): call("DELETE", f"/todos/{todo['id']}", token=T)
if cycle and cycle.get("id"): call("DELETE", f"/pomodoro/templates/{cycle['id']}", token=T)

# --- cleanup: archive smoke accounts (transactions stay; archived accounts refuse new money) ---
s, r = call("PUT", f"/budget/accounts/{other['id']}", {"name": other["name"], "type": other["type"], "isArchived": True}, T)
s, r = import_lines(other, [line(15, "Expense", 10, "smoke")])
C("an archived account takes no statement (10051)", s == 400 and code(r) == "10051", f"{s} {code(r)}")
for account in (cash, bank, usd, eur_acc, payroll, wallet, dollars_acc, euros_acc, stmt):
    call("PUT", f"/budget/accounts/{account['id']}", {"name": account["name"], "type": account["type"], "isArchived": True}, T)
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Income", "amount": 1, "occurredAt": iso(now)}, T)
C("archived account refuses new money (10051)", s == 400 and code(r) == "10051")
s, txs = call("GET", f"/budget/transactions/?accountId={cash['id']}&kind=Income", token=T)
pay = txs[0]
s, r = call("PUT", f"/budget/transactions/{pay['id']}", {"amount": pay["amount"], "occurredAt": pay["occurredAt"], "categoryId": salary["id"], "note": "smoke pay (fixed)"}, T)
C("an old movement of an archived account can be fixed", s == 200 and r["note"] == "smoke pay (fixed)", f"{s} {r}")
s, open_acc = call("POST", "/budget/accounts/", {"name": "smoke: Open", "currency": "Htg"}, T)
s, kept = call("POST", "/budget/transactions/", {"accountId": open_acc["id"], "kind": "Income", "amount": 1, "occurredAt": iso(now)}, T)
s, r = call("PUT", f"/budget/transactions/{kept['id']}", {"amount": 1, "occurredAt": iso(now), "accountId": cash["id"]}, T)
C("but none moves onto an archived account (10051)", s == 400 and code(r) == "10051", f"{s} {code(r)}")
call("PUT", f"/budget/accounts/{open_acc['id']}", {"name": open_acc["name"], "type": open_acc["type"], "isArchived": True}, T)

print()
print("ALL PASSED" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
