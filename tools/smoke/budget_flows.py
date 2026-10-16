#!/usr/bin/env python3
"""End-to-end check of the Budget module (accounts, HTG/USD, transfers with exchange,
category budgets, monthly summary, editing a movement, recurring rules and transfers) against a running API in
Development.

    python3 tools/smoke/budget_flows.py [base-url] [username] [password]

Logs in as `smoke` by default (admin has MFA enabled). Restart the API just before running so
the recurring-transactions job's first pass (~15 s after boot) lands inside the polling window.
Standard library only.
"""
import json, sys, time, urllib.request, urllib.error, datetime as dt

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
s, r = call("PUT", f"/budget/categories/{food['id']}/budget", {"monthlyLimit": 20000, "currency": "Htg"}, T)
C("set monthly limit on expense category", s == 200)
s, r = call("PUT", f"/budget/categories/{salary['id']}/budget", {"monthlyLimit": 1, "currency": "Htg"}, T)
C("limit on income category rejected (10050)", s == 400 and code(r) == "10050")
summary = month_summary()
income1, expense1 = htg_totals(summary)
C("summary totals per currency (delta)", income1 - income0 == 85000 and expense1 - expense0 == 2500.50, f"+{income1-income0}/+{expense1-expense0}")
spend = next((c for c in summary["categories"] if c["categoryId"] == food["id"]), None)
C("category spend vs limit", spend is not None and spend["amount"] == 2500.50 and spend["monthlyLimit"] == 20000, f"{spend}")

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

deadline = time.time() + 90
count = saved = exchanged = 0
while time.time() < deadline:
    s, txs = call("GET", f"/budget/transactions/?accountId={bank['id']}&pageSize=100", token=T)
    count = sum(1 for t in txs if t.get("recurringTransactionId") == rule["id"])
    saved = [t for t in txs if t.get("recurringTransactionId") == saving["id"]]
    s, txs = call("GET", f"/budget/transactions/?accountId={usd['id']}&pageSize=100", token=T)
    exchanged = [t for t in txs if t.get("recurringTransactionId") == dollars["id"]]
    if count == 3 and len(saved) == 2 and len(exchanged) == 1: break
    time.sleep(5)
C("job materialized all 3 past occurrences", count == 3, f"got {count}")
C("job booked both saving transfers, into the bank", len(saved) == 2 and all(
    t["kind"] == "Transfer" and t["accountId"] == cash["id"] and t["transferAccountId"] == bank["id"] and t["transferAmount"] == 100
    for t in saved), f"{saved}")
C("job booked the exchange with its received amount", len(exchanged) == 1 and exchanged[0]["transferAmount"] == 10, f"{exchanged}")
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

# --- cleanup: archive smoke accounts (transactions stay; archived accounts refuse new money) ---
for account in (cash, bank, usd, eur_acc):
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
