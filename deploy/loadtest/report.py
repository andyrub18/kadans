#!/usr/bin/env python3
"""The load test's verdict, from Prometheus (docs/LOADTEST.md): ./loadtest.sh report [minutes, default 20].

Reads the load-test API (instance="loadtest") and the server over the last N minutes, and prints each target with its
measured value. Runs queries through the prometheus container, so it needs nothing but docker on the server.
"""
import json, subprocess, sys, urllib.parse

window = f"{int(sys.argv[1]) if len(sys.argv) > 1 else 20}m"
api = 'job="kadans-api", instance="loadtest"'
http = f'{api}, http_route!~"/hubs/.*"'


def query(expr):
    url = "http://localhost:9090/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    out = subprocess.run(["docker", "exec", "kadans-prometheus-1", "wget", "-qO-", url], capture_output=True, text=True).stdout
    try:
        return json.loads(out)["data"]["result"]
    except (ValueError, KeyError):
        return []


def one(expr):
    result = query(expr)
    return float(result[0]["value"][1]) if result else None


def fmt(v, unit=""):
    if v is None or v != v:
        return "–"
    if unit == "s":
        return f"{v * 1000:.0f} ms" if v < 1 else f"{v:.1f} s"
    if unit == "%":
        return f"{v * 100:.1f}%"
    return f"{v:,.0f}" if abs(v) >= 100 else f"{v:.2f}"


def p(q, metric, labels="", by="le"):
    return f"histogram_quantile({q}, sum by ({by}) (rate({metric}_bucket{{{labels}}}[{window}])))"


def line(label, value, target=""):
    print(f"  {label:<44} {value:>12}   {target}")


print(f"Load test, last {window} (instance=loadtest)\n")
print("Requests")
line("requests / s", fmt(one(f"sum(rate(http_server_request_duration_seconds_count{{{http}}}[{window}]))")))
line("p50", fmt(one(p(0.5, "http_server_request_duration_seconds", http)), "s"))
line("p95", fmt(one(p(0.95, "http_server_request_duration_seconds", http)), "s"), "target: under 200 ms")
line("p99", fmt(one(p(0.99, "http_server_request_duration_seconds", http)), "s"))
line("server errors (5xx)", fmt(one(f'sum(increase(http_server_request_duration_seconds_count{{{http}, http_response_status_code=~"5.."}}[{window}]))')), "target: 0")
line("live connections (max)", fmt(one(f"max_over_time(sum(signalr_server_active_connections{{{api}}})[{window}:15s])")))
print("  slowest routes (p95):")
for r in sorted(query(p(0.95, "http_server_request_duration_seconds", http, "le, http_request_method, http_route")),
                key=lambda r: -float(r["value"][1]) if r["value"][1] not in ("NaN", "+Inf") else 0)[:8]:
    line(f"  {r['metric'].get('http_request_method', '')} {r['metric'].get('http_route', '')}"[:44], fmt(float(r["value"][1]), "s"))

print("\nReminders and push")
line("reminders sent", fmt(one(f"sum(increase(kadans_reminders_sent_total{{{api}}}[{window}]))")))
line("reminders skipped as too late", fmt(one(f"sum(increase(kadans_reminders_stale_total{{{api}}}[{window}]))")), "target: 0")
line("reminder lateness p50", fmt(one(p(0.5, "kadans_reminder_lateness_seconds", api)), "s"))
line("reminder lateness p99", fmt(one(p(0.99, "kadans_reminder_lateness_seconds", api)), "s"), "target: under 60 s, all of them")
line("pushes dropped", fmt(one(f"sum(increase(kadans_push_dropped_total{{{api}}}[{window}]))")), "target: 0")
line("push queue (max)", fmt(one(f"max_over_time(sum(kadans_push_queue_length{{{api}}})[{window}:15s])")))
line("push delay p95 (queued → answered)", fmt(one(p(0.95, "kadans_push_delay_seconds", api)), "s"))
for r in query(f"sum by (result) (increase(kadans_push_messages_total{{{api}}}[{window}]))"):
    line(f"push messages {r['metric'].get('result')}", fmt(float(r["value"][1])))
line("Pomodoro deadline lateness p99", fmt(one(p(0.99, "kadans_pomodoro_deadline_lateness_seconds", api)), "s"))

print("\nScheduled jobs (p95 of a pass, errors)")
for r in query(p(0.95, "kadans_job_duration_seconds", api, "le, job_name")):
    name = r["metric"].get("job_name")
    errors = one(f'sum(increase(kadans_job_duration_seconds_count{{{api}, job_name="{name}", outcome="error"}}[{window}]))')
    line(f"  {name}", fmt(float(r["value"][1]), "s"), f"errors: {fmt(errors)}")

print("\nServer")
line("API CPU (of all cores, max 1 min avg)", fmt(one(f"max_over_time((sum(rate(dotnet_process_cpu_time_seconds_total{{{api}}}[1m])) / max(dotnet_process_cpu_count{{{api}}}))[{window}:15s])"), "%"))
line("server CPU busy (max 1 min avg)", fmt(one(f'max_over_time((1 - avg(rate(node_cpu_seconds_total{{mode="idle"}}[1m])))[{window}:15s])'), "%"))
line("memory available (min)", fmt(one(f"min_over_time((min(node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes))[{window}:15s])"), "%"))
line("API working set (max)", fmt((one(f"max_over_time(sum(dotnet_process_memory_working_set_bytes{{{api}}})[{window}:15s])") or 0) / 1048576) + " MB")
line("database connections in use (max)", fmt(one(f'max_over_time(sum(db_client_connection_count{{{api}, db_client_connection_state="used"}})[{window}:15s])')))
line("database command p95", fmt(one(p(0.95, "db_client_operation_duration_seconds", api)), "s"))
line("thread pool queue (max)", fmt(one(f"max_over_time(sum(dotnet_thread_pool_queue_length_total{{{api}}})[{window}:15s])")))
