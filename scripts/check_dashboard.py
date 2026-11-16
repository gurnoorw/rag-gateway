#!/usr/bin/env python3
"""Checks the Grafana dashboard against its own rules and, optionally, a running gateway (AC-t04-6).

    python3 scripts/check_dashboard.py                      # static checks only
    python3 scripts/check_dashboard.py --scrape http://localhost:8080/actuator/prometheus
    python3 scripts/check_dashboard.py --scrape-file scrape.txt

Static checks (no app needed):
  - the JSON parses; every panel has at least one query; every query uses the provisioned datasource uid;
  - every query names at least one metric;
  - counters (``*_total``) appear only inside rate()/irate()/increase();
  - histogram buckets (``*_bucket``) appear only as
    ``histogram_quantile(q, sum by (le, ...) (rate(x_bucket[w])))``: percentiles come from server-side
    buckets, never from client-side summaries (no ``quantile`` label).
With a scrape: every metric a query names exists on the scrape page, and every label a query groups by
(except ``le``) is present on at least one series of a metric in that query.

The same rules run inside ``./gradlew test`` (DashboardMetricsIT). Standard library only. Exit code 0 when
every check passes, 1 otherwise.
"""
import argparse
import json
import pathlib
import re
import sys
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_DASHBOARD = REPO / "ops/grafana/dashboards/rag-gateway.json"
DATASOURCES = REPO / "ops/grafana/provisioning/datasources/prometheus.yml"

KEYWORDS = {"by", "without", "on", "ignoring", "group_left", "group_right", "bool", "and", "or", "unless",
            "offset", "inf", "nan"}
SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+\S+')
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')
GROUPING = re.compile(r'\b(?:by|without)\s*\(([^)]*)\)')
IDENT = re.compile(r'[a-zA-Z_:][a-zA-Z0-9_:]*')
COUNTER_IN_RATE = re.compile(r'\b(?:rate|irate|increase)\(\s*([a-zA-Z_:][a-zA-Z0-9_:]*_total)\b')
BUCKET_QUANTILE = re.compile(
    r'histogram_quantile\(\s*0?\.\d+\s*,\s*sum\s+by\s*\(\s*le\s*(?:,[^)]*)?\)\s*'
    r'\(\s*rate\(\s*([a-zA-Z_:][a-zA-Z0-9_:]*_bucket)\s*\[')


def metric_names(expr):
    """Metric names in a PromQL expression: identifiers that are not functions, keywords, labels or durations."""
    s = re.sub(r'"(?:[^"\\]|\\.)*"', '""', expr)          # string literals
    s = re.sub(r'\{[^}]*\}', '', s)                        # label matchers
    s = re.sub(r'\[[^\]]*\]', '', s)                       # range and subquery windows
    s = re.sub(r'\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)', '', s)  # label lists
    names = []
    for m in IDENT.finditer(s):
        word = m.group(0)
        if word.lower() in KEYWORDS:
            continue
        if re.match(r'\s*\(', s[m.end():]):                # a function call
            continue
        if m.start() > 0 and (s[m.start() - 1].isdigit() or s[m.start() - 1] == '.'):
            continue                                       # e.g. the "e" in 1e3
        if word not in names:
            names.append(word)
    return names


def grouping_labels(expr):
    labels = []
    for m in GROUPING.finditer(expr):
        labels += [l.strip() for l in m.group(1).split(",") if l.strip()]
    return labels


def provisioned_uid():
    m = re.search(r'^\s*uid:\s*(\S+)', DATASOURCES.read_text(), re.M)
    return m.group(1) if m else None


def panels_of(dashboard):
    for p in dashboard.get("panels", []):
        if p.get("type") == "row":
            yield from p.get("panels", [])
        else:
            yield p


def static_problems(dashboard, uid):
    problems = []
    seen_ids = set()
    for p in panels_of(dashboard):
        title = p.get("title", "?")
        if p.get("id") in seen_ids:
            problems.append(f"panel '{title}': duplicate id {p.get('id')}")
        seen_ids.add(p.get("id"))
        targets = p.get("targets") or []
        if not targets:
            problems.append(f"panel '{title}': no query")
        for t in targets:
            expr = t.get("expr", "")
            where = f"panel '{title}' query {t.get('refId', '?')}"
            ds = (t.get("datasource") or p.get("datasource") or {}).get("uid")
            if ds != uid:
                problems.append(f"{where}: datasource uid {ds!r}, provisioned uid is {uid!r}")
            names = metric_names(expr)
            if not names:
                problems.append(f"{where}: names no metric: {expr}")
            counters = [n for n in names if n.endswith("_total")]
            if len(COUNTER_IN_RATE.findall(expr)) != len(re.findall(r'_total\b', expr)) or \
                    set(COUNTER_IN_RATE.findall(expr)) != set(counters):
                problems.append(f"{where}: a counter outside rate()/increase(): {expr}")
            buckets = [n for n in names if n.endswith("_bucket")]
            if len(BUCKET_QUANTILE.findall(expr)) != len(re.findall(r'_bucket\b', expr)) or \
                    set(BUCKET_QUANTILE.findall(expr)) != set(buckets):
                problems.append(f"{where}: a histogram bucket outside "
                                f"histogram_quantile(q, sum by (le, ...) (rate(..._bucket[w]))): {expr}")
            if re.search(r'\bquantile\s*=', expr):
                problems.append(f"{where}: client-side quantile label: {expr}")
    return problems


def parse_scrape(text):
    """{series name: [label dict, ...]} from the Prometheus text format."""
    series = {}
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        m = SAMPLE.match(line.strip())
        if not m:
            raise ValueError(f"not a sample line: {line}")
        series.setdefault(m.group(1), []).append(dict(LABEL.findall(m.group(2) or "")))
    return series


def scrape_problems(dashboard, series):
    problems = []
    for p in panels_of(dashboard):
        for t in p.get("targets") or []:
            expr = t.get("expr", "")
            where = f"panel '{p.get('title', '?')}' query {t.get('refId', '?')}"
            names = metric_names(expr)
            missing = [n for n in names if n not in series]
            for n in missing:
                problems.append(f"{where}: metric {n} is not on the scrape page")
            present = [n for n in names if n in series]
            if not present:
                continue
            for label in grouping_labels(expr):
                if label == "le":
                    continue
                if not any(label in labels for n in present for labels in series[n]):
                    problems.append(f"{where}: groups by '{label}', which no series of {present} has")
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dashboard", default=str(DEFAULT_DASHBOARD))
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--scrape", help="URL of /actuator/prometheus on a running gateway")
    src.add_argument("--scrape-file", help="a saved scrape page")
    args = ap.parse_args()

    try:
        dashboard = json.loads(pathlib.Path(args.dashboard).read_text())
    except (OSError, ValueError) as e:
        print(f"FAIL  dashboard does not load: {e}")
        return 1
    queries = sum(len(p.get("targets") or []) for p in panels_of(dashboard))
    print(f"dashboard {args.dashboard}: {len(list(panels_of(dashboard)))} panels, {queries} queries")

    problems = static_problems(dashboard, provisioned_uid())
    if args.scrape or args.scrape_file:
        if args.scrape:
            with urllib.request.urlopen(args.scrape, timeout=10) as r:
                text = r.read().decode("utf-8")
        else:
            text = pathlib.Path(args.scrape_file).read_text()
        series = parse_scrape(text)
        print(f"scrape: {len(series)} series names")
        problems += scrape_problems(dashboard, series)
    else:
        print("no --scrape given: static checks only")

    for p in problems:
        print(f"FAIL  {p}")
    if problems:
        print(f"{len(problems)} problem(s)")
        return 1
    print("OK    every panel query passes" + (" and names metrics that exist" if args.scrape or args.scrape_file else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
