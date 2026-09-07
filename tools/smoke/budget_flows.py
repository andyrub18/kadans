#!/usr/bin/env python3
"""End-to-end check of the Budget module (accounts, HTG/USD, transfers with exchange,
category budgets, monthly summary, recurring rules) against a running API in Development.

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

# --- recurring: 3 daily occurrences all in the past; the job materializes them ---
s, rule = call("POST", "/budget/recurring/", {
    "accountId": bank["id"], "kind": "Expense", "amount": 250, "note": "smoke recurring",
    "recurrence": {"frequency": "Daily", "startDate": iso(now - dt.timedelta(days=2, hours=1)), "count": 3},
}, T)
C("create recurring rule", s == 200, f"{s} {rule}")
deadline = time.time() + 90
count = 0
while time.time() < deadline:
    s, txs = call("GET", f"/budget/transactions/?accountId={bank['id']}&kind=Expense&pageSize=50", token=T)
    count = sum(1 for t in txs if t.get("recurringTransactionId") == rule["id"])
    if count == 3: break
    time.sleep(5)
C("job materialized all 3 past occurrences", count == 3, f"got {count}")
s, rules = call("GET", "/budget/recurring/", token=T)
mine = next((x for x in rules if x["id"] == rule["id"]), None)
C("exhausted rule deactivated", mine is not None and mine["isActive"] is False, f"{mine and mine['isActive']}")

# --- exchange rate: parameter the user updates daily; estimates only ---
s, r = call("GET", "/budget/exchange-rate/", token=T)
C("rate starts unset (or from a prior run)", s == 200)
s, r = call("PUT", "/budget/exchange-rate/", {"htgPerUsd": 132.50}, T)
C("set the day's rate", s == 200 and r["htgPerUsd"] == 132.50)
s, r = call("PUT", "/budget/exchange-rate/", {"htgPerUsd": -1}, T)
C("negative rate rejected (10048)", s == 400 and code(r) == "10048")
summary = month_summary()
combined = summary.get("combined")
C("summary now carries the at-your-rate estimate", combined is not None and combined["htgPerUsd"] == 132.50, f"{combined}")
# no USD income exists, so the combined income must equal the plain HTG income
income_now, _ = htg_totals(summary)
C("combined income converts correctly", combined is not None and combined["income"] == income_now)

# --- cleanup: archive smoke accounts (transactions stay; archived accounts refuse new money) ---
for account in (cash, bank, usd):
    call("PUT", f"/budget/accounts/{account['id']}", {"name": account["name"], "type": account["type"], "isArchived": True}, T)
s, r = call("POST", "/budget/transactions/", {"accountId": cash["id"], "kind": "Income", "amount": 1, "occurredAt": iso(now)}, T)
C("archived account refuses new money (10051)", s == 400 and code(r) == "10051")

print()
print("ALL PASSED" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
