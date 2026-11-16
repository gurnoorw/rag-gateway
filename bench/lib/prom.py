#!/usr/bin/env python3
"""Reads the app's /actuator/prometheus (t08). Two uses:

  prom.py snapshot <url> <out.json>   histogram buckets of the timers the summary needs, written to a file;
                                      run before and after a scenario, the difference is that scenario's data.

As a module: parse(), histogram(), delta(), quantile() for bench/lib/summary.py, heap_used_bytes() for the sampler.

Percentiles from buckets use the same rule as Prometheus's histogram_quantile: find the bucket holding the rank
and interpolate linearly between its edges. They are estimates within a bucket (D-38); the summary notes list
the edges. A quantile that falls in the +Inf bucket is reported as the highest finite edge and flagged.
"""
import json
import math
import re
import sys
import urllib.request

# Timers whose buckets the summary reads (Prometheus names of gateway.overhead and gateway.requestlog.write).
HISTOGRAMS = ["gateway_overhead_seconds", "gateway_requestlog_write_seconds"]

LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)')
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')


def scrape(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read().decode()


def parse(text):
    """[(name, {label: value}, float)] for every sample line."""
    out = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        m = LINE.match(line)
        if not m:
            continue
        labels = dict(LABEL.findall(m.group(2) or ""))
        out.append((m.group(1), labels, float(m.group(3))))
    return out


def histogram(samples, base):
    """Cumulative bucket counts {le: count} for one histogram, summed over every label set (provider, model)."""
    buckets = {}
    for name, labels, value in samples:
        if name == base + "_bucket":
            le = labels["le"]
            key = math.inf if le == "+Inf" else float(le)
            buckets[key] = buckets.get(key, 0.0) + value
    return buckets


def delta(after, before):
    return {le: after[le] - before.get(le, 0.0) for le in after}


def quantile(q, buckets):
    """(value_in_seconds, in_inf_bucket) by Prometheus's histogram_quantile rule; None when there is no data."""
    edges = sorted(buckets)
    total = buckets[math.inf]
    if total <= 0:
        return None
    rank = q * total
    prev_edge, prev_count = 0.0, 0.0
    for edge in edges:
        count = buckets[edge]
        if count >= rank:
            if edge == math.inf:
                return prev_edge, True
            if count == prev_count:
                return edge, False
            return prev_edge + (edge - prev_edge) * (rank - prev_count) / (count - prev_count), False
        prev_edge, prev_count = edge, count
    return prev_edge, True


def heap_used_bytes(samples):
    return sum(v for n, labels, v in samples if n == "jvm_memory_used_bytes" and labels.get("area") == "heap")


# Counters summed over labels, recorded next to the histograms (e.g. how often the limiter failed open).
COUNTERS = ["gateway_ratelimit_failopen_total"]


def counter(samples, name):
    return sum(v for n, _, v in samples if n == name)


def snapshot(url):
    samples = parse(scrape(url))
    snap = {base: {("+Inf" if le == math.inf else repr(le)): c for le, c in histogram(samples, base).items()}
            for base in HISTOGRAMS}
    snap["counters"] = {name: counter(samples, name) for name in COUNTERS}
    return snap


def load_snapshot(path):
    with open(path) as f:
        raw = json.load(f)
    snap = {base: {(math.inf if le == "+Inf" else float(le)): c for le, c in raw[base].items()} for base in HISTOGRAMS}
    snap["counters"] = raw.get("counters", {})
    return snap


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1] != "snapshot":
        sys.exit("usage: prom.py snapshot <prometheus-url> <out.json>")
    snap = snapshot(sys.argv[2])
    for base in HISTOGRAMS:
        if not snap[base]:
            sys.exit(f"metric {base} not found at {sys.argv[2]}")
    with open(sys.argv[3], "w") as f:
        json.dump(snap, f, indent=1)
