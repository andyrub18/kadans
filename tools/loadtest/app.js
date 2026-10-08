// Kadans load test (docs/LOADTEST.md): people using the app, and apps that stay connected to the hub.
//
//   k6 run -e BASE_URL=https://api.kadansplanning.com -e SESSION_TAG=run1 tools/loadtest/app.js
//
// Each virtual user is one seeded account (lt<i>) resuming its session "lt-<SESSION_TAG>-<i>" (seeder `sessions`).
// The verdict comes from the API's own metrics in Grafana (server-side, without the network); k6's numbers add the
// client's view. Env: APP_VUS, LIVE_VUS, RAMP, HOLD, USER_OFFSET (the first account used).
import http from 'k6/http';
import ws from 'k6/ws';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = (__ENV.BASE_URL || 'http://127.0.0.1:5399').replace(/\/$/, '');
const TAG = __ENV.SESSION_TAG || 'seed';
const APP_VUS = parseInt(__ENV.APP_VUS || '200');
const LIVE_VUS = parseInt(__ENV.LIVE_VUS || '200');
const RAMP = __ENV.RAMP || '2m';
const HOLD = __ENV.HOLD || '10m';
// Stepped load instead of ramp-and-hold: "3m:25,4m:25,3m:50,4m:50" = 3 minutes up to 25% of the users, 4 held, ...
const STAGES = __ENV.STAGES || '';

function stages(total) {
  if (!STAGES) return [{ duration: RAMP, target: total }, { duration: HOLD, target: total }, { duration: '30s', target: 0 }];
  return STAGES.split(',')
    .map((s) => s.split(':'))
    .map(([duration, percent]) => ({ duration, target: Math.round((total * parseInt(percent)) / 100) }))
    .concat([{ duration: '30s', target: 0 }]);
}
const TOTAL_MS = stages(1).reduce((t, s) => t + durationMs(s.duration), 0);
const OFFSET = parseInt(__ENV.USER_OFFSET || '0');

const liveNotifications = new Counter('kadans_live_notifications');
const sessionFailures = new Counter('kadans_session_failures');

export const options = {
  scenarios: {
    app: {
      executor: 'ramping-vus',
      exec: 'appUser',
      startVUs: 0,
      stages: stages(APP_VUS),
      gracefulRampDown: '20s',
    },
    // Apps opening over time, as people do, each staying connected to the end.
    live: {
      executor: 'ramping-vus',
      exec: 'liveConnection',
      startVUs: 0,
      stages: stages(LIVE_VUS),
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    kadans_session_failures: ['count<10'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// ---- one account per virtual user ----
let account = null; // { index, access, refresh }

// k6 numbers virtual users across all scenarios: one account each, never shared (a shared session's second refresh
// would look stolen, and the API would end it).
function userIndex() {
  return OFFSET + exec.vu.idInTest - 1;
}

function session(index) {
  if (account && account.index === index) return account;
  const res = http.post(`${BASE}/auth/refresh`, JSON.stringify({ refreshToken: `lt-${TAG}-${index}` }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /auth/refresh' },
  });
  if (res.status !== 200) {
    sessionFailures.add(1);
    return null;
  }
  account = { index, access: res.json('accessToken'), refresh: res.json('refreshToken') };
  return account;
}

function renew() {
  const res = http.post(`${BASE}/auth/refresh`, JSON.stringify({ refreshToken: account.refresh }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /auth/refresh' },
  });
  if (res.status !== 200) {
    sessionFailures.add(1);
    account = null;
    return false;
  }
  account.access = res.json('accessToken');
  account.refresh = res.json('refreshToken');
  return true;
}

function call(method, path, name, body) {
  const params = {
    headers: { Authorization: `Bearer ${account.access}`, 'Content-Type': 'application/json', 'Accept-Language': 'ht' },
    tags: { name },
  };
  let res = http.request(method, `${BASE}${path}`, body ? JSON.stringify(body) : null, params);
  if (res.status === 401 && renew()) {
    params.headers.Authorization = `Bearer ${account.access}`;
    res = http.request(method, `${BASE}${path}`, body ? JSON.stringify(body) : null, params);
  }
  check(res, { [`${name} ok`]: (r) => r.status >= 200 && r.status < 300 });
  return res;
}

// ---- what someone does in the app ----
export function appUser() {
  if (!session(userIndex())) {
    sleep(5);
    return;
  }
  const now = new Date();

  // Home: the todos, the next seven days, the bell.
  call('GET', '/todos?page=1&pageSize=50', 'GET /todos');
  const week = call('GET', `/occurrences?from=${iso(now)}&to=${iso(addDays(now, 7))}`, 'GET /occurrences (week)');
  call('GET', '/notifications/unread-count', 'GET /notifications/unread-count');
  sleep(rand(3, 10));

  const roll = Math.random() * 100;
  if (roll < 30) {
    // Calendar: this month.
    const first = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
    call('GET', `/occurrences?from=${iso(first)}&to=${iso(addDays(first, 31))}`, 'GET /occurrences (month)');
  } else if (roll < 50) {
    // Budget.
    call('GET', '/budget/accounts/?includeArchived=false', 'GET /budget/accounts');
    call('GET', `/budget/summary?year=${now.getUTCFullYear()}&month=${now.getUTCMonth() + 1}`, 'GET /budget/summary');
    call('GET', '/budget/transactions/?page=1&pageSize=20', 'GET /budget/transactions');
    if (Math.random() < 0.3) {
      const accounts = call('GET', '/budget/categories/?includeArchived=false', 'GET /budget/categories');
      const expense = (accounts.json() || []).find((c) => c.kind === 'Expense');
      const own = call('GET', '/budget/accounts/?includeArchived=false', 'GET /budget/accounts').json() || [];
      if (expense && own.length) {
        call('POST', '/budget/transactions/', 'POST /budget/transactions', {
          accountId: own[0].id, kind: 'Expense', amount: 250, categoryId: expense.id, occurredAt: iso(now), note: 'k6',
        });
      }
    }
  } else if (roll < 65) {
    // The notification centre.
    call('GET', '/notifications?page=1&pageSize=20', 'GET /notifications');
    if (Math.random() < 0.3) call('PUT', '/notifications/read-all', 'PUT /notifications/read-all');
  } else if (roll < 80) {
    // Done with something due this week.
    const pending = (week.json() || []).filter((o) => o.status === 'Pending');
    if (pending.length) {
      const pick = pending[Math.floor(Math.random() * pending.length)];
      call('PUT', `/occurrences/${pick.id}/complete`, 'PUT /occurrences/{id}/complete');
    }
  } else if (roll < 90) {
    // A new one-time todo.
    call('POST', '/todos/one-time', 'POST /todos/one-time', {
      title: 'Load test todo', description: '', notificationEnabled: true,
      dueDate: iso(addDays(now, 1 + Math.floor(Math.random() * 10))), notifyBeforeInMinutes: 15,
    });
  } else {
    // A todo's page.
    const items = call('GET', '/todos?page=1&pageSize=50', 'GET /todos').json() || [];
    if (items.length) {
      const id = items[Math.floor(Math.random() * items.length)].id;
      call('GET', `/todos/${id}`, 'GET /todos/{id}');
      call('GET', `/todos/${id}/occurrences?page=1&pageSize=20`, 'GET /todos/{id}/occurrences');
    }
  }
  sleep(rand(5, 20));
}

export function setup() {
  return { start: Date.now() };
}

// ---- an app left open: one hub connection until the end of the test ----
export function liveConnection(data) {
  // Near the end, wait for it rather than reconnect for a few seconds.
  const remaining = TOTAL_MS - 30000 - (Date.now() - data.start);
  if (remaining < 10000) {
    sleep(Math.max(1, remaining / 1000 + 30));
    return;
  }
  if (!session(userIndex())) return;
  const negotiate = http.post(`${BASE}/hubs/kadans/negotiate?negotiateVersion=1`, null, {
    headers: { Authorization: `Bearer ${account.access}` },
    tags: { name: 'POST /hubs/kadans/negotiate' },
  });
  if (!check(negotiate, { 'negotiate ok': (r) => r.status === 200 })) return;

  const url = `${BASE.replace(/^http/, 'ws')}/hubs/kadans?id=${negotiate.json('connectionToken')}&access_token=${account.access}`;
  const stayFor = remaining;
  ws.connect(url, { tags: { name: 'WS /hubs/kadans' } }, (socket) => {
    socket.on('open', () => socket.send(JSON.stringify({ protocol: 'json', version: 1 }) + '\x1e'));
    socket.on('message', (data) => {
      for (const frame of String(data).split('\x1e')) {
        if (frame.includes('"type":1') && frame.includes('"target":"notification"')) liveNotifications.add(1);
      }
    });
    socket.setInterval(() => socket.send(JSON.stringify({ type: 6 }) + '\x1e'), 15000);
    socket.setTimeout(() => socket.close(), stayFor);
  });
}

// ---- helpers ----
function iso(d) { return d.toISOString(); }
function addDays(d, n) { return new Date(d.getTime() + n * 86400000); }
function rand(min, max) { return min + Math.random() * (max - min); }
function durationMs(s) {
  const m = /^(\d+)(ms|s|m|h)$/.exec(s);
  return parseInt(m[1]) * { ms: 1, s: 1000, m: 60000, h: 3600000 }[m[2]];
}
