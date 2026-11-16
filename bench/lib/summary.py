#!/usr/bin/env python3
"""Builds bench/summary/<run_id>.json from one run's raw files (t08, D-45):

  summary.py <results-dir> <run_id> <out.json>

Inputs, all written by bench/run-all.sh into <results-dir> (gitignored) during the run:
  <run_id>-run.json            manifest: label, date, commit, which scenarios ran, their parameters, tenants
  <run_id>-<n>.json            k6 --summary-export of each scenario run
  <run_id>-prom-1-*.json       histogram snapshots before/after the scenario 1 gateway leg (bench/lib/prom.py)
  <run_id>-5-kill.json         when mock-claude was killed (scenario 5)
  <run_id>-7-n<N>-*.json       sampler output and app-log slice per open-streams step (scenario 7, 7b)
and request_log rows of this run's own tenants, read with psql (env PSQL, default: psql in the compose
postgres service).

Every number written comes from those inputs; parameters of the run (arrival rate, configured repeat share, the
tenant's limit, heap setting, stream length) come from the manifest. A scenario that did not run, or whose inputs
are missing, is left out of "scenarios" (never zero) and the reason goes into setup.notes. The output is validated
against bench/summary.schema.json; on any error this exits non-zero and writes nothing.
"""
import collections
import json
import os
import re
import shlex
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prom  # noqa: E402
import schema_check  # noqa: E402

PCTS = (("p50", "p(50)", 0.50), ("p95", "p(95)", 0.95), ("p99", "p(99)", 0.99))
COST_LABEL = "modelled from list prices on a synthetic workload"
DEFAULT_PSQL = "docker compose exec -T postgres psql -U {user} -d {db}"


class Inputs:
    def __init__(self, results, run_id):
        self.results, self.run_id = results, run_id

    def path(self, name):
        return os.path.join(self.results, f"{self.run_id}-{name}.json")

    def has(self, name):
        return os.path.exists(self.path(name))

    def json(self, name):
        with open(self.path(name)) as f:
            return json.load(f)

    def k6(self, name):
        return self.json(name)["metrics"]


def r(x, nd=3):
    return round(float(x), nd)


def trend(metrics, name):
    m = metrics.get(name)
    if m is None or m.get("count", 0) == 0:
        raise ValueError(f"k6 metric {name} has no samples")
    return m


def count(metrics, name):
    """A k6 Counter that was never incremented is absent from the export: that is a real zero."""
    return int(metrics.get(name, {}).get("count", 0))


def rate_trues(metrics, name):
    """Number of true values added to a k6 Rate (the legacy export calls them "passes")."""
    return int(metrics[name]["passes"])


def diff_pcts(a, b):
    return {key: r(a[stat] - b[stat]) for key, stat, _ in PCTS}


def psql_json(sql):
    cmd = os.environ.get("PSQL") or DEFAULT_PSQL.format(user=os.environ.get("POSTGRES_USER", "raggateway"),
                                                        db=os.environ.get("POSTGRES_DB", "raggateway"))
    out = subprocess.run(shlex.split(cmd) + ["-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
                         check=True, capture_output=True, text=True)
    return json.loads(out.stdout.strip())


def sql_literal(s):
    if not re.fullmatch(r"[a-z0-9-]+", s):
        raise ValueError(f"unexpected tenant id {s!r}")
    return f"'{s}'"


def hist_pcts_ms(buckets, notes, what):
    out = {}
    for key, _, q in PCTS:
        v = prom.quantile(q, buckets)
        if v is None:
            raise ValueError(f"{what}: no observations between the snapshots")
        value, in_inf = v
        if in_inf:
            notes.append(f"{what} {key} lies above the highest bucket edge; reported as that edge ({value * 1000} ms)")
        out[key] = r(value * 1000)
    return out


# ---------------------------------------------------------------- scenarios

def overhead(inp, man, notes, out):
    gw, direct = inp.k6("1-gateway"), inp.k6("1-direct")
    requests = count(gw, "http_reqs")
    for leg, m in (("gateway", gw), ("direct", direct)):
        dropped = count(m, "dropped_iterations")
        if dropped:
            notes.append(f"scenario 1 {leg} leg: k6 dropped {dropped} iterations (arrival rate not fully held)")
    before = prom.load_snapshot(inp.path("prom-1-before"))
    after = prom.load_snapshot(inp.path("prom-1-after"))
    out["overhead"] = {
        "requests": requests,
        "arrival_rate_per_s": man["params"]["overhead_rate"],
        "added_latency_seen_by_client_ms": {
            "ttfb": diff_pcts(trend(gw, "http_req_waiting"), trend(direct, "http_req_waiting")),
            "total": diff_pcts(trend(gw, "http_req_duration"), trend(direct, "http_req_duration")),
        },
        "gateway_processing_overhead_ms": hist_pcts_ms(
            prom.delta(after["gateway_overhead_seconds"], before["gateway_overhead_seconds"]), notes,
            "gateway processing overhead"),
    }
    notes.append(f"scenario 1: {requests} requests through the gateway and {count(direct, 'http_reqs')} straight "
                 f"to the mock, each at {man['params']['overhead_rate']}/s for {man['params']['overhead_duration']} "
                 "(constant-arrival-rate); added latency = gateway minus direct, per percentile, k6 client-side "
                 "(ttfb = http_req_waiting, total = http_req_duration); gateway processing overhead = "
                 "gateway_overhead_seconds histogram over the gateway leg, interpolated within buckets "
                 "(edges 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000 ms)")
    failopen = (after["counters"].get("gateway_ratelimit_failopen_total", 0)
                - before["counters"].get("gateway_ratelimit_failopen_total", 0))
    notes.append(f"scenario 1: the rate limiter or budget check failed open {int(failopen)} times during the "
                 "gateway leg (Redis command timeout 50 ms, D-17; Redis shares the CPUs)")
    if "8" in man["scenarios"]:
        writes = prom.delta(after["gateway_requestlog_write_seconds"], before["gateway_requestlog_write_seconds"])
        pcts = hist_pcts_ms(writes, notes, "log insert")
        out["log_insert"] = {"p50_ms": pcts["p50"], "p99_ms": pcts["p99"], "count": int(writes[float("inf")])}
        notes.append("scenario 8: gateway_requestlog_write_seconds over the scenario 1 gateway leg, interpolated "
                     "within buckets (edges 0.5, 1, 2, 5, 10, 25, 50, 100 ms)")


def cache_hit_latency(inp, man, notes, out):
    m = inp.k6("2")
    t = trend(m, "cache_hit_duration")
    out["cache_hit_latency"] = {"p50_ms": r(t["p(50)"]), "p95_ms": r(t["p(95)"]), "p99_ms": r(t["p(99)"]),
                                "requests": int(t["count"])}
    not_hit = count(m, "cache_not_hit")
    notes.append(f"scenario 2: total time of responses marked X-Gateway-Cache: HIT, k6 client-side, open load "
                 f"{man['params']['cache_rate']}/s over a warmed pool of {man['params']['cache_pool']} temperature-0 "
                 f"prompts; {not_hit} responses in the load phase were not hits")


def cache_hit_rate(inp, man, notes, out):
    tenant = man["tenants"]["hitrate"]
    row = psql_json(f"SELECT json_build_object('n', count(*), 'hits', count(*) FILTER (WHERE cache_status = 'hit')) "
                    f"FROM request_log WHERE tenant_id = {sql_literal(tenant)}")
    k6_hits = count(inp.k6("3"), "cache_hits")
    if row["n"] == 0:
        raise ValueError("scenario 3: no request_log rows for its tenant")
    out["cache_hit_rate"] = {
        "configured_repeat_share": man["params"]["hitrate_repeat_share"],
        "measured_hit_rate": r(row["hits"] / row["n"], 4),
        "requests": row["n"],
        "workload": man["params"]["hitrate_workload"],
    }
    notes.append(f"scenario 3: hit rate from request_log.cache_status of the run's own tenant; response headers "
                 f"counted {k6_hits} hits, request_log {row['hits']}")


def cost_per_1k(inp, man, notes, out):
    rows = {}
    for variant in ("baseline", "cache", "routing", "both"):
        tenant = man["tenants"][f"cost-{variant}"]
        rows[variant] = psql_json(
            "SELECT json_build_object('n', count(*), 'usd', COALESCE(sum(cost_usd), 0), "
            "'hits', count(*) FILTER (WHERE cache_status = 'hit'), "
            "'cheap', count(*) FILTER (WHERE model_used = 'claude-haiku-5-5')) "
            f"FROM request_log WHERE tenant_id = {sql_literal(tenant)}")
    counts = {v: x["n"] for v, x in rows.items()}
    if len(set(counts.values())) != 1 or not counts["baseline"]:
        raise ValueError(f"scenario 4: the four runs logged different request counts {counts}")
    n = counts["baseline"]
    per1k = {v: r(float(x["usd"]) / n * 1000, 6) for v, x in rows.items()}
    out["cost_per_1k"] = {
        "label": COST_LABEL,
        "baseline_usd": per1k["baseline"], "cache_usd": per1k["cache"],
        "routing_usd": per1k["routing"], "cache_and_routing_usd": per1k["both"],
        "requests_per_run": n,
        "workload": man["params"]["cost_workload"],
    }
    notes.append("scenario 4: SUM(request_log.cost_usd) / requests x 1,000 per run, one fresh tenant per run; "
                 + ", ".join(f"{v}: {x['hits']} cache hits, {x['cheap']} on claude-haiku-5-5" for v, x in rows.items())
                 + f"; list prices of {man['price_date']}")


def fallback(inp, man, notes, out):
    m = inp.k6("5")
    kill = inp.json("5-kill")
    start = m["run_start_epoch_ms"]["value"]
    after_total = count(m, "after_total")
    if after_total == 0:
        raise ValueError("scenario 5: no request started after the kill")
    before_failed = count(m, "before_total") - count(m, "before_ok")
    out["fallback"] = {
        "primary_killed_at_s": r((kill["kill_epoch_ms"] - start) / 1000, 2),
        "requests_after_kill": after_total,
        "success_rate_after_kill": r(count(m, "after_ok") / after_total, 4),
        "added_latency_ms": diff_pcts(trend(m, "after_duration"), trend(m, "before_duration")),
    }
    notes.append(f"scenario 5: open load {man['params']['fallback_rate']}/s for {man['params']['fallback_duration']}; "
                 f"mock-claude killed (docker kill, SIGKILL) at the stated second, the command took "
                 f"{kill['kill_done_epoch_ms'] - kill['kill_epoch_ms']} ms; requests are classed by their start time; "
                 f"added latency = successful after-kill minus successful before-kill, per percentile, k6 client-side; "
                 f"{count(m, 'after_served_by_ollama')} of {count(m, 'after_ok')} successful after-kill requests "
                 f"were served by ollama; {before_failed} requests started before the kill failed (in flight when "
                 "it happened)")


def rate_limit(inp, man, notes, out):
    tenant = man["tenants"]["ratelimit"]
    p = man["params"]
    t = sql_literal(tenant)
    row = psql_json(
        "WITH s AS (SELECT min(created_at) AS t0 FROM request_log WHERE tenant_id = " + t + ") "
        "SELECT json_build_object("
        "'over', (SELECT count(*) FROM request_log WHERE tenant_id = " + t + " AND http_status = 429), "
        "'ok', (SELECT count(*) FROM request_log WHERE tenant_id = " + t + " AND http_status = 200), "
        "'other', (SELECT count(*) FROM request_log WHERE tenant_id = " + t + " AND http_status NOT IN (200, 429)), "
        "'window_tokens', (SELECT COALESCE(sum(input_tokens + output_tokens), 0) FROM request_log, s "
        "  WHERE tenant_id = " + t + " AND http_status = 200 "
        f"  AND created_at >= s.t0 + interval '{int(p['ratelimit_warm_s'])} seconds' "
        f"  AND created_at < s.t0 + interval '{int(p['ratelimit_duration_s'])} seconds'))")
    m = inp.k6("6")
    with_header, k6_429 = count(m, "rejected_429_with_retry_after"), count(m, "rejected_429")
    if row["over"] == 0:
        raise ValueError("scenario 6: no request was over the limit; the load is too small to test it")
    if with_header > row["over"]:
        raise ValueError(f"scenario 6: k6 saw {with_header} 429s with Retry-After, request_log has {row['over']}")
    minutes = (p["ratelimit_duration_s"] - p["ratelimit_warm_s"]) / 60
    out["rate_limit"] = {
        "over_limit_requests": row["over"],
        "with_429_and_retry_after": with_header,
        "share_correct": r(with_header / row["over"], 4),
        "admitted_tokens_per_min": r(row["window_tokens"] / minutes, 1),
        "limit_tokens_per_min": p["ratelimit_tpm"],
    }
    notes.append(f"scenario 6: open load {p['ratelimit_rate']}/s for {p['ratelimit_duration_s']} s on a tenant with "
                 f"{p['ratelimit_tpm']} tokens/min; request_log: {row['over']} rows with 429, {row['ok']} with 200, "
                 f"{row['other']} other; k6 saw {k6_429} 429s; admitted tokens/min = billed input+output tokens of "
                 f"200 rows logged from second {p['ratelimit_warm_s']} to {p['ratelimit_duration_s']} after the "
                 "first row (the bucket starts full, so the first minute also admits the initial capacity)")


def redis_down(inp, man, notes, out):
    base = trend(inp.k6("1-gateway"), "http_req_duration")
    block = {}
    for mode in ("refused", "blackholed"):
        m = inp.k6(f"6b-{mode}")
        started, done = count(m, "requests_started"), count(m, "requests_completed")
        block[mode] = {"added_ms": diff_pcts(trend(m, "http_req_duration"), base),
                       "requests": started,
                       "errors": started - done}
        notes.append(f"scenario 6b {mode}: {started} requests started, {done} completed, {count(m, 'http_reqs')} "
                     f"got a response before the end (the added latency is over those), "
                     f"{rate_trues(m, 'stream_errors')} responses were not a completed stream, "
                     f"{started - count(m, 'http_reqs')} were still waiting when k6's 60 s graceful stop ended; "
                     f"k6 dropped {count(m, 'dropped_iterations')} scheduled arrivals (no free VU)")
    out["redis_down_from_start"] = block
    probe = inp.json("6b-blackhole-probe")
    notes.append(f"scenario 6b: the scenario 1 gateway load at {man['params']['overhead_rate']}/s for "
                 f"{man['params']['redis_down_duration']}, app started with Redis stopped (refused) and with "
                 f"REDIS_HOST={probe['host']} (a connect attempt timed out after {probe['timeout_s']} s with no "
                 "answer: black-holed); added = total time (http_req_duration) minus scenario 1's gateway leg, per "
                 "percentile, k6 client-side; errors = responses that were not a completed stream")


def log_slice(facts):
    with open(facts["log"], "rb") as f:
        f.seek(facts["start"])
        return f.read(facts["end"] - facts["start"]).decode(errors="replace")


def pinned(text):
    """Pinning traces of -Djdk.tracePinnedThreads=short in an app-log slice: one header line per event
    ("VirtualThread[#563,claude-call]/runnable@ForkJoinPool-1-worker-1 reason:MONITOR" on JDK 21.0.12; older
    builds print "Thread[#..."), then the frames; a frame holding a monitor ends with "<== monitors:N"."""
    reports = 0
    frames = collections.Counter()
    for line in text.splitlines():
        if re.match(r"^(Virtual)?Thread\[#\d+", line):
            reports += 1
        elif "<==" in line:
            frames[line.strip()] += 1
    return reports, [f"{frame} (x{n})" for frame, n in frames.most_common(5)]


def stream_step(inp, name):
    """(sample, pinned reports, top frames, problem). problem is None when the samples can stand as the step's
    peaks; otherwise it says why not, and the step goes into the notes only (the schema has no way to say "the
    peak could not be sampled", and a partial sample written as a peak would understate it)."""
    sample = inp.json(f"{name}-sample")
    facts = inp.json(f"{name}-log")
    text = log_slice(facts)
    reports, frames = pinned(text)
    problems = []
    if "OutOfMemoryError" in text:
        problems.append("the app threw OutOfMemoryError (heap -Xmx" + facts.get("xmx", "?") + ")")
    if facts.get("app_restarted"):
        problems.append("the app stopped answering and was restarted after the step")
    if sample["sample_error_count"] or not sample["heap_samples"] or not sample["thread_dumps"]:
        problems.append(f"{sample['sample_error_count']} heap/thread samples failed ({sample['heap_samples']} heap "
                        f"samples and {sample['thread_dumps']} thread dumps succeeded)")
    return sample, reports, frames, "; ".join(problems) or None


def open_streams(inp, man, notes, out):
    runs = []
    max_zero = 0
    for n in man["params"]["stream_ns"]:
        name = f"7-n{n}"
        if not inp.has(name):
            notes.append(f"scenario 7 N={n}: no k6 result; not reported")
            continue
        m = inp.k6(name)
        started, done = count(m, "streams_started"), count(m, "streams_completed")
        errors = started - done
        if errors == 0 and started > 0:
            max_zero = max(max_zero, n)
        sample, reports, frames, problem = stream_step(inp, name)
        if problem:
            notes.append(f"scenario 7 N={n}: {started} streams started, {errors} not completed; left out of runs "
                         f"because its peak heap and virtual threads could not be sampled: {problem}; "
                         f"{reports} pinning traces in the step")
            continue
        run = {"n": n, "errors": errors, "peak_heap_used_mb": sample["peak_heap_used_mb"],
               "peak_virtual_threads": sample["peak_virtual_threads"], "pinned_reports": reports}
        if frames:
            run["pinned_top_frames"] = frames
        runs.append(run)
        notes.append(f"scenario 7 N={n}: {started} streams started, {errors} not completed; "
                     f"{sample['heap_samples']} heap samples, {sample['thread_dumps']} thread dumps")
    if not runs:
        raise ValueError("scenario 7: no step produced a reportable result")
    block = {"heap": man["params"]["xmx"], "stream_seconds": man["params"]["stream_seconds"],
             "max_n_zero_errors": max_zero, "runs": runs}
    notes.append(f"scenario 7: closed model, constant-vus N for {man['params']['stream_hold']} per step, each VU "
                 f"holding one ~{man['params']['stream_seconds']} s stream at a time; app at -Xmx{man['params']['xmx']} "
                 "with -Djdk.tracePinnedThreads=short; errors = streams started minus streams completed with [DONE]; "
                 "max_n_zero_errors is over every step run, including one left out of runs; peak heap = largest "
                 "sum of jvm_memory_used_bytes{area=heap} sampled every 0.5 s; peak virtual threads = largest count "
                 "in a jcmd thread dump taken every 2 s (jvm_threads_live counts platform threads only); "
                 "pinned_reports = pinning trace headers printed during the step")
    if "7b" in man["scenarios"] and inp.has("7b"):
        m = inp.k6("7b")
        sample, reports, frames, problem = stream_step(inp, "7b")
        total, done = count(m, "requests"), count(m, "completed")
        if total == 0:
            raise ValueError("scenario 7b: no request started")
        detail = (f"N={man['params']['delayed_n']} for {man['params']['delayed_hold']}, the Claude mock holds its "
                  f"headers for 20 s (first-token timeout 5 s); {total} requests started, {done} completed, "
                  f"{count(m, 'served_by_ollama')} served by ollama; {reports} pinning traces"
                  + (f", top frames: {', '.join(frames)}" if frames else ""))
        if problem:
            notes.append(f"scenario 7b: {detail}; not reported because its peaks could not be sampled: {problem}")
        else:
            block["headers_delayed"] = {"n": man["params"]["delayed_n"], "errors": total - done,
                                        "fallback_success_rate": r(done / total, 4),
                                        "peak_heap_used_mb": sample["peak_heap_used_mb"],
                                        "peak_virtual_threads": sample["peak_virtual_threads"]}
            notes.append(f"scenario 7b: {detail}")
    out["open_streams"] = block


SCENARIOS = [("1", overhead), ("2", cache_hit_latency), ("3", cache_hit_rate), ("4", cost_per_1k),
             ("5", fallback), ("6", rate_limit), ("6b", redis_down), ("7", open_streams)]


def build(results, run_id):
    inp = Inputs(results, run_id)
    man = inp.json("run")
    notes = list(man.get("notes", []))
    scenarios = {}
    failures = []
    for sid, fn in SCENARIOS:
        if sid not in man["scenarios"]:
            continue
        try:
            fn(inp, man, notes, scenarios)
        except (OSError, KeyError, ValueError) as e:
            failures.append(f"scenario {sid}: {type(e).__name__}: {e}")
    if failures:
        raise SystemExit("summary not written:\n  " + "\n  ".join(failures))
    for sid in man.get("not_run", {}):
        notes.append(f"scenario {sid} not run: {man['not_run'][sid]}")
    return {
        "run_id": run_id,
        "measured_on": man["measured_on"],
        "date": man["date"],
        "git_commit": man["git_commit"],
        "setup": {
            "jvm_xmx": man["params"]["xmx"],
            "virtual_threads": man["virtual_threads"],
            "mock_latency": man["mock_latency"],
            "load_generator_shares_host": True,
            "k6_image": man["k6_image"],
            "price_date": man["price_date"],
            "notes": " | ".join(notes),
        },
        "scenarios": scenarios,
    }


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: summary.py <results-dir> <run_id> <out.json>")
    results, run_id, out = sys.argv[1:]
    summary = build(results, run_id)
    schema_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "summary.schema.json")
    with open(schema_path) as f:
        errors = schema_check.validate(summary, json.load(f))
    if errors:
        raise SystemExit("summary does not match bench/summary.schema.json:\n  " + "\n  ".join(errors))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
