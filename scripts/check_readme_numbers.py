#!/usr/bin/env python3
"""Holds the README's measured tables to the committed run files (t08, D-45; t13, D-53; t14, D-55).

    python3 scripts/check_readme_numbers.py
    python3 scripts/check_readme_numbers.py --release
    python3 scripts/check_readme_numbers.py --readme README.md --summary-dir bench/summary \\
        --schema bench/summary.schema.json --results-dir eval/results --results-schema eval/results.schema.json \\
        --runbook docs/RUNBOOK.md --allowlist docs/process/release-pending-allowlist.txt

The measured table sits between ``<!-- measured:begin -->`` and ``<!-- measured:end -->`` and has the columns
Metric | Value | Unit | Measured on | Run | Source key. For every row:

  - Measured on is not empty (AC-t08-3).
  - Source key is a dotted path to a numeric leaf of ``bench/summary.schema.json`` (``scenarios.overhead.
    requests``; array items as ``runs[0]`` or ``runs[n=1000]``). A key the schema does not have fails.
  - Unit is one of a fixed set and fits the key: ``ms`` needs a ``*_ms`` segment, ``USD`` a ``*_usd``, ``s`` a
    ``*_s``, ``MB`` a ``*_mb``, ``tokens/min`` a ``*_per_min``, ``%`` a fraction, and the count units
    (requests, count, errors, streams, threads) an integer.
  - A Value of ``pending`` or ``pending laptop run`` carries no number and needs an empty Run.
  - Otherwise ``bench/summary/<Run>.json`` must exist, its ``run_id`` must be Run, its ``measured_on`` must
    equal Measured on, the key must be present in it, and Value must equal the file's value after rounding:
    ms, s and MB to 1 decimal; % to 1 decimal of the fraction x 100; USD to 4 decimals; tokens/min to a whole
    number; count units exactly. Rounding is half-up on the decimal text of the number. Thousands separators
    (``10,000``) are allowed in the README.
  - A row whose Metric names a percentile (p50/p95/p99) says where it came from: "k6 client-side" or
    "bucket edges" followed by the edges (D-38).

The eval table (t13, D-53) sits between ``<!-- eval:begin -->`` and ``<!-- eval:end -->`` and has the columns
Metric | Value | Unit | Measured on | Embedder | Run | Source key. It is read from ``eval/results/<Run>.json`` and
its keys are checked against ``eval/results.schema.json``; array items are selected by field values, e.g.
``retrieval[mode=keyword,chunk_target_tokens=400].recall_at_5``. The rules above apply, plus:

  - Units: ``fraction`` (a schema fraction shown to 3 decimals), ``%`` (a fraction x 100 to 1 decimal), and the
    counts ``chunks`` and ``questions`` (integers, exactly).
  - Embedder is ``none`` or ``nomic-embed-text`` (never the fake embedder) and equals the file's ``embedder.kind``.
  - A vector, hybrid or answer row (``mode=vector``, ``mode=hybrid`` or ``generator_mode`` hybrid/vector in the key,
    an ``answers.`` key, or an ``acl.`` key whose file says vector or hybrid) needs Embedder ``nomic-embed-text`` and a
    file whose ``embedder.kind`` is ``nomic-embed-text``; an answer row also needs an ``answers.judge`` section. Until
    such a file is committed those rows read ``pending laptop run`` with an empty Run (AC-t13-5).

"MacBook" may appear in the README only inside measured or eval rows whose run file's ``measured_on`` says MacBook
(AC-t08-3): a laptop label never appears without the laptop run behind it.

Links (t14, AC-t14-10): every relative link in the README and in ``docs/RUNBOOK.md`` (Markdown links and images,
reference definitions, HTML ``href``/``src``) must resolve to a file tracked by git (``git ls-files``) inside the
repository; a link to a directory needs a tracked file under it. Links with a scheme (``https:``, ``mailto:``) and
in-page anchors (``#status``) are not checked; a ``path#anchor`` is checked for its path. Text in fenced code blocks,
inline code and HTML comments is not a link. A broken image link is reported as a missing image. This check runs in
both modes: a broken link is wrong whether or not a release is being cut.

``--release`` (t14, D-55) adds the release rule: every ``pending`` / ``pending laptop run`` Value cell, in the
measured or the eval table, fails unless its Metric text is a line of ``docs/process/release-pending-allowlist.txt``
(exact text; blank lines and ``#`` comments ignored). The file names nothing by default, so shipping without a
number is the owner's explicit choice. Two pending rows with the same Metric text fail in release mode, because one
allowlist line could not tell them apart. Allowlist lines that match no pending row are printed as a notice.

It then lists every other number in the README outside the designed-for table (between
``<!-- designed-for:begin -->`` and ``<!-- designed-for:end -->``) and outside the checked Value cells, with
line numbers, for the reviewer's AC-t08-4 check. The list is informational and never fails the run.

Standard library only. Exit code 0 when every check passes, 1 otherwise, 2 on a usage error.
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from urllib.parse import unquote

REPO = pathlib.Path(__file__).resolve().parent.parent

MEASURED_BEGIN, MEASURED_END = "<!-- measured:begin -->", "<!-- measured:end -->"
DESIGNED_BEGIN, DESIGNED_END = "<!-- designed-for:begin -->", "<!-- designed-for:end -->"
COLUMNS = ["Metric", "Value", "Unit", "Measured on", "Run", "Source key"]
EVAL_BEGIN, EVAL_END = "<!-- eval:begin -->", "<!-- eval:end -->"
EVAL_COLUMNS = ["Metric", "Value", "Unit", "Measured on", "Embedder", "Run", "Source key"]
EVAL_EMBEDDERS = {"none", "nomic-embed-text"}
MODEL_EMBEDDER = "nomic-embed-text"
PENDING = {"pending", "pending laptop run"}
ALLOWLIST = REPO / "docs/process/release-pending-allowlist.txt"
RUNBOOK = REPO / "docs/RUNBOOK.md"

# unit -> (decimals, scale, rule). rule checks the key segments / schema node the unit may be used with.
COUNT_UNITS = {"requests", "count", "errors", "streams", "threads"}
UNITS = {
    "ms": (1, 1, "suffix:_ms"),
    "s": (1, 1, "suffix:_s"),
    "MB": (1, 1, "suffix:_mb"),
    "USD": (4, 1, "suffix:_usd"),
    "tokens/min": (0, 1, "suffix:_per_min"),
    "%": (1, 100, "fraction"),
    **{u: (0, 1, "integer") for u in COUNT_UNITS},
}
# The eval table's units: unit -> (decimals, scale, rule).
EVAL_UNITS = {
    "fraction": (3, 1, "fraction"),
    "%": (1, 100, "fraction"),
    "chunks": (0, 1, "integer"),
    "questions": (0, 1, "integer"),
}

PERCENTILE = re.compile(r"\bp(50|95|99)\b")
BUCKET_EDGES = re.compile(r"bucket edges\s+\d[\d.]*(\s*,\s*\d[\d.]*)+\s*(ms|s)\b")
KEY_SEGMENT = re.compile(r"^([a-z0-9_]+)(?:\[(\d+|[a-z0-9_]+=[^\],=]+(?:,[a-z0-9_]+=[^\],=]+)*)\])?$")
# A number standing alone: not part of an identifier such as D-05, t08, v1, llama3.2:1b or a run id.
NUMBER = re.compile(r"(?<![\w.\-/:])(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?(?:[KMB](?!\w))?(?![\w\-:])")


class Failures:
    def __init__(self):
        self.items = []

    def add(self, where, message):
        self.items.append(f"{where}: {message}")


# ---------------------------------------------------------------- schema

def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def resolve_ref(schema, node):
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        if not ref.startswith("#/"):
            raise ValueError(f"unsupported $ref {ref}")
        target = schema
        for part in ref[2:].split("/"):
            target = target[part]
        node = target
    return node


def parse_key(key):
    """'a.b[0].c' -> [('a', None), ('b', '0'), ('c', None)]; None if malformed."""
    parts = []
    for raw in key.split("."):
        m = KEY_SEGMENT.match(raw)
        if not m:
            return None
        parts.append((m.group(1), m.group(2)))
    return parts


def schema_leaf(schema, parts):
    """The schema node at the key, its $ref names on the way, or (None, reason)."""
    node, refs = schema, []
    for name, index in parts:
        if "$ref" in node:
            refs.append(node["$ref"])
        node = resolve_ref(schema, node)
        props = node.get("properties", {}) if isinstance(node, dict) else {}
        if name not in props:
            return None, None, f"the schema has no '{name}' here"
        node = props[name]
        if index is not None:
            if "$ref" in node:
                refs.append(node["$ref"])
            node = resolve_ref(schema, node)
            if node.get("type") != "array":
                return None, None, f"'{name}' is not an array in the schema"
            node = node["items"]
            if index is not None and "=" in index:
                item = resolve_ref(schema, node)
                for field, _ in selector(index):
                    if field not in item.get("properties", {}):
                        return None, None, f"items of '{name}' have no field '{field}'"
    if "$ref" in node:
        refs.append(node["$ref"])
    leaf = resolve_ref(schema, node)
    if leaf.get("type") not in ("number", "integer"):
        return None, None, "the key does not name a number in the schema"
    return leaf, refs, None


def selector(index):
    """'mode=keyword,chunk_target_tokens=400' -> [('mode', 'keyword'), ('chunk_target_tokens', '400')]"""
    return [tuple(pair.split("=", 1)) for pair in index.split(",")]


def unit_fits(unit, parts, leaf, refs, units=UNITS):
    rule = units[unit][2]
    names = [n for n, _ in parts]
    if rule.startswith("suffix:"):
        suffix = rule.split(":", 1)[1]
        return any(n.endswith(suffix) for n in names)
    if rule == "fraction":
        return bool(refs) and refs[-1] == "#/$defs/fraction"
    if rule == "integer":
        return leaf.get("type") == "integer"
    return False


# ---------------------------------------------------------------- run files

def value_at(data, parts):
    node = data
    for name, index in parts:
        if not isinstance(node, dict) or name not in node:
            return None
        node = node[name]
        if index is None:
            continue
        if not isinstance(node, list):
            return None
        if "=" in index:
            pairs = selector(index)
            match = [x for x in node if isinstance(x, dict) and all(str(x.get(f)) == w for f, w in pairs)]
            if len(match) != 1:
                return None
            node = match[0]
        else:
            i = int(index)
            if i >= len(node):
                return None
            node = node[i]
    if isinstance(node, bool) or not isinstance(node, (int, float)):
        return None
    return node


def expected_text(value, unit, units=UNITS):
    decimals, scale, _ = units[unit]
    d = Decimal(repr(value)) * scale
    q = Decimal(1).scaleb(-decimals)
    return str(d.quantize(q, rounding=ROUND_HALF_UP))


def readme_value(cell):
    """The Value cell as plain decimal text, or None if it is not a number."""
    text = cell.replace(",", "").strip()
    try:
        Decimal(text)
    except InvalidOperation:
        return None
    if not re.fullmatch(r"-?\d+(\.\d+)?", text):
        return None
    return text


# ---------------------------------------------------------------- links (AC-t14-10)

INLINE_LINK = re.compile(r"(!?)\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*(<[^>]*>|[^\s)]+)(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
REF_DEFINITION = re.compile(r"^ {0,3}\[[^\]]+\]:\s*(<[^>]*>|\S+)")
HTML_LINK = re.compile(r"<(img|a|source)\b[^>]*?\b(src|href|srcset)\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)
SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")
CODE_SPAN = re.compile(r"(`+)(?:(?!\1).)+?\1")
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


def tracked_files(root):
    """Paths git tracks under root (relative, '/'-separated), or None if git cannot list them."""
    try:
        res = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return {p for p in res.stdout.decode("utf-8").split("\0") if p}


def links_in(text):
    """(line number, target, is_image) for every link outside fenced code, inline code and HTML comments."""
    text = COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), text)
    found = []
    in_code = False
    for n, line in enumerate(text.splitlines(), start=1):
        if line.lstrip().startswith(("```", "~~~")):
            in_code = not in_code
            continue
        if in_code:
            continue
        line = CODE_SPAN.sub("", line)
        for m in INLINE_LINK.finditer(line):
            found.append((n, m.group(2).strip("<>"), m.group(1) == "!"))
        m = REF_DEFINITION.match(line)
        if m:
            found.append((n, m.group(1).strip("<>"), False))
        for m in HTML_LINK.finditer(line):
            target = m.group(3).split()[0] if m.group(2).lower() == "srcset" else m.group(3)
            found.append((n, target, m.group(1).lower() in ("img", "source")))
    return found


def check_links(paths, root, failures):
    """Every relative link in the given files resolves to a tracked file under root. Returns the links checked."""
    root = pathlib.Path(root).resolve()
    tracked = tracked_files(root)
    if tracked is None:
        failures.add(str(root), "cannot list tracked files (git ls-files failed): the link check did not run")
        return 0
    checked = 0
    for path in paths:
        path = pathlib.Path(path).resolve()
        try:
            shown = path.relative_to(root).as_posix()
        except ValueError:
            shown = str(path)
        for line_no, target, image in links_in(path.read_text(encoding="utf-8")):
            if not target or target.startswith(("#", "//")) or SCHEME.match(target):
                continue
            checked += 1
            bare = unquote(target.split("#", 1)[0].split("?", 1)[0])
            base = root if bare.startswith("/") else path.parent
            resolved = pathlib.Path(os.path.normpath(base / bare.lstrip("/")))
            what = "image" if image else "link"
            where = f"{shown} line {line_no}"
            try:
                rel = resolved.relative_to(root).as_posix()
            except ValueError:
                failures.add(where, f"{what} '{target}' points outside the repository")
                continue
            if rel in tracked or rel == "." or any(t.startswith(rel + "/") for t in tracked):
                continue  # a tracked file, or a directory holding one
            if image:
                failures.add(where, f"image '{target}' is missing: {rel} is not a committed file")
            else:
                failures.add(where, f"link '{target}' does not resolve to a committed file ({rel})")
    return checked


# ---------------------------------------------------------------- release mode (D-55)

def read_allowlist(path):
    """The Metric texts the owner allows to stay pending at release: one per line, '#' starts a comment line."""
    lines = pathlib.Path(path).read_text(encoding="utf-8").splitlines()
    return [s.strip() for s in lines if s.strip() and not s.strip().startswith("#")]


def check_release(pending, allowlist_path, failures, out):
    """pending: (line index, table, metric). Each pending cell not on the allowlist fails. Returns the allowed count."""
    allowed = set(read_allowlist(allowlist_path))
    by_metric = {}
    for i, _, metric in pending:
        by_metric.setdefault(metric, []).append(i)
    for metric, idx in by_metric.items():
        if len(idx) > 1:
            failures.add("lines " + ", ".join(str(i + 1) for i in idx),
                         f"pending rows share the Metric '{metric}', so one allowlist line cannot name one of them")
    ok = 0
    for i, table, metric in pending:
        if metric in allowed:
            ok += 1
        else:
            failures.add(f"line {i + 1}", f"pending {table} cell blocks the release: '{metric}' "
                                          f"(not in {pathlib.Path(allowlist_path).name})")
    for entry in sorted(allowed - set(by_metric)):
        print(f"notice: allowlist entry matches no pending row: '{entry}'", file=out)
    return ok


# ---------------------------------------------------------------- README

def section(lines, begin, end):
    """(first, last) line indexes strictly between the markers, or None."""
    starts = [i for i, l in enumerate(lines) if l.strip() == begin]
    ends = [i for i, l in enumerate(lines) if l.strip() == end]
    if len(starts) != 1 or len(ends) != 1 or ends[0] < starts[0]:
        return None
    return starts[0] + 1, ends[0] - 1


def split_row(line):
    s = line.strip()
    if not (s.startswith("|") and s.endswith("|")):
        return None
    return [c.strip() for c in s[1:-1].split("|")]


def strip_code(cell):
    return cell[1:-1] if len(cell) >= 2 and cell.startswith("`") and cell.endswith("`") else cell


def needs_model(key, data=None):
    """True for a vector, hybrid or answer row: only a real-embedder results file may fill it (D-53)."""
    if re.search(r"(?:^|[\[,])(?:mode|generator_mode)=(?:vector|hybrid)\b", key) or key.startswith("answers."):
        return True
    if key.startswith("acl.") and data is not None:
        return data.get("acl", {}).get("mode") in ("vector", "hybrid")
    return False


def check_eval(lines, results_dir, schema, failures, checked_value_lines, macbook_ok_lines, row_lines, pending=None):
    """The eval table (t13). Returns (rows, rows compared to a results file)."""
    span = section(lines, EVAL_BEGIN, EVAL_END)
    if span is None:
        failures.add("README", f"needs exactly one {EVAL_BEGIN} ... {EVAL_END} section")
        return 0, 0
    first, last = span
    table = [(i, split_row(lines[i])) for i in range(first, last + 1) if lines[i].strip()]
    if len(table) < 2 or table[0][1] != EVAL_COLUMNS:
        failures.add(f"line {first + 1}", f"eval table header must be | {' | '.join(EVAL_COLUMNS)} |")
        return 0, 0
    rows = compared = 0
    cache = {}
    for i, cells in table[2:]:
        where = f"line {i + 1}"
        if cells is None or len(cells) != len(EVAL_COLUMNS):
            failures.add(where, f"not a {len(EVAL_COLUMNS)}-column table row")
            continue
        rows += 1
        row_lines.add(i)
        metric, value, unit, measured_on, embedder, run, key = cells
        key, run, embedder = strip_code(key), strip_code(run), strip_code(embedder)
        if not measured_on:
            failures.add(where, "empty Measured on")
        if embedder not in EVAL_EMBEDDERS:
            failures.add(where, f"Embedder must be one of {', '.join(sorted(EVAL_EMBEDDERS))}, got '{embedder}'")
        if unit not in EVAL_UNITS:
            failures.add(where, f"unknown unit '{unit}' (allowed: {', '.join(sorted(EVAL_UNITS))})")
            continue
        parts = parse_key(key)
        if parts is None:
            failures.add(where, f"malformed source key '{key}'")
            continue
        leaf, refs, why = schema_leaf(schema, parts)
        if leaf is None:
            failures.add(where, f"unknown source key '{key}': {why}")
            continue
        if not unit_fits(unit, parts, leaf, refs, EVAL_UNITS):
            failures.add(where, f"unit '{unit}' does not fit source key '{key}'")
        if needs_model(key) and embedder != MODEL_EMBEDDER:
            failures.add(where, f"a vector, hybrid or answer row needs Embedder {MODEL_EMBEDDER}, got '{embedder}'")

        if value in PENDING:
            if run:
                failures.add(where, f"'{value}' row must have an empty Run, got '{run}'")
            if pending is not None:
                pending.append((i, "eval", metric))
            continue
        if not run:
            failures.add(where, "a measured value needs a Run")
            continue
        run_file = results_dir / f"{run}.json"
        if run not in cache:
            cache[run] = load_json(run_file) if run_file.is_file() else None
        data = cache[run]
        if data is None:
            failures.add(where, f"results file missing: {run_file}")
            continue
        if data.get("run_id") != run:
            failures.add(where, f"{run_file.name} has run_id '{data.get('run_id')}', not '{run}'")
        label = data.get("measured_on", "")
        if measured_on != label:
            failures.add(where, f"Measured on '{measured_on}' differs from {run_file.name}: '{label}'")
        if "MacBook" in label:
            macbook_ok_lines.add(i)
        kind = data.get("embedder", {}).get("kind")
        if kind != embedder:
            failures.add(where, f"Embedder '{embedder}' differs from {run_file.name}: '{kind}'")
        if needs_model(key, data) and kind != MODEL_EMBEDDER:
            failures.add(where, f"a vector, hybrid or answer value needs a {MODEL_EMBEDDER} results file; "
                                f"{run_file.name} has embedder '{kind}'")
        if key.startswith("answers.") and "judge" not in data.get("answers", {}):
            failures.add(where, f"an answer value needs an answers.judge section in {run_file.name}")
        actual = value_at(data, parts)
        if actual is None:
            failures.add(where, f"source key '{key}' is not in {run_file.name}")
            continue
        got = readme_value(value)
        want = expected_text(actual, unit, EVAL_UNITS)
        checked_value_lines.add(i)
        compared += 1
        if got is None:
            failures.add(where, f"Value '{value}' is not a number")
        elif got != want:
            failures.add(where, f"Value {value} {unit} != {want} {unit} ({key} = {actual} in {run_file.name})")
    return rows, compared


def check(readme_path, summary_dir, schema_path, out=sys.stdout, results_dir=None, results_schema_path=None,
          release=False, allowlist_path=None, link_files=None, repo_root=None):
    """Runs every check; returns 0 or 1. Links are checked only when link_files is given (against repo_root)."""
    failures = Failures()
    pending = []  # (line index, table, metric) of every pending Value cell
    results_dir = pathlib.Path(results_dir) if results_dir else REPO / "eval/results"
    results_schema_path = results_schema_path or REPO / "eval/results.schema.json"
    readme_path = pathlib.Path(readme_path)
    summary_dir = pathlib.Path(summary_dir)
    lines = readme_path.read_text(encoding="utf-8").splitlines()
    schema = load_json(schema_path)

    measured = section(lines, MEASURED_BEGIN, MEASURED_END)
    designed = section(lines, DESIGNED_BEGIN, DESIGNED_END)
    if measured is None:
        failures.add(readme_path.name, f"needs exactly one {MEASURED_BEGIN} ... {MEASURED_END} section")
    if designed is None:
        failures.add(readme_path.name, f"needs exactly one {DESIGNED_BEGIN} ... {DESIGNED_END} section")

    checked_value_lines = set()   # line indexes whose Value cell was compared to a run file
    macbook_ok_lines = set()      # line indexes where "MacBook" is backed by a run file
    measured_rows = set()         # line indexes of well-formed measured rows
    run_cache = {}
    rows = 0

    if measured is not None:
        first, last = measured
        table = [(i, split_row(lines[i])) for i in range(first, last + 1) if lines[i].strip()]
        if len(table) < 2 or table[0][1] != COLUMNS:
            failures.add(f"line {first + 1}", f"measured table header must be | {' | '.join(COLUMNS)} |")
            table = None
        else:
            table = table[2:]  # header and separator
            if not table:
                failures.add(f"line {first + 1}", "measured table has no rows")
        for i, cells in table or []:
            where = f"line {i + 1}"
            if cells is None or len(cells) != len(COLUMNS):
                failures.add(where, f"not a {len(COLUMNS)}-column table row")
                continue
            rows += 1
            measured_rows.add(i)
            metric, value, unit, measured_on, run, key = cells
            key = strip_code(key)
            run = strip_code(run)

            if not measured_on:
                failures.add(where, "empty Measured on")
            if unit not in UNITS:
                failures.add(where, f"unknown unit '{unit}' (allowed: {', '.join(sorted(UNITS))})")
                continue

            parts = parse_key(key)
            if parts is None:
                failures.add(where, f"malformed source key '{key}'")
                continue
            leaf, refs, why = schema_leaf(schema, parts)
            if leaf is None:
                failures.add(where, f"unknown source key '{key}': {why}")
                continue
            if not unit_fits(unit, parts, leaf, refs):
                failures.add(where, f"unit '{unit}' does not fit source key '{key}'")

            if PERCENTILE.search(metric) and "k6 client-side" not in metric and not BUCKET_EDGES.search(metric):
                failures.add(where, "a percentile row must say 'k6 client-side' or list its 'bucket edges'")

            if value in PENDING:
                if run:
                    failures.add(where, f"'{value}' row must have an empty Run, got '{run}'")
                pending.append((i, "measured", metric))
                continue

            if not run:
                failures.add(where, "a measured value needs a Run")
                continue
            run_file = summary_dir / f"{run}.json"
            if run not in run_cache:
                run_cache[run] = load_json(run_file) if run_file.is_file() else None
            data = run_cache[run]
            if data is None:
                failures.add(where, f"run file missing: {run_file}")
                continue
            if data.get("run_id") != run:
                failures.add(where, f"{run_file.name} has run_id '{data.get('run_id')}', not '{run}'")
            file_label = data.get("measured_on", "")
            if measured_on != file_label:
                failures.add(where, f"Measured on '{measured_on}' differs from {run_file.name}: '{file_label}'")
            if "MacBook" in file_label:
                macbook_ok_lines.add(i)

            actual = value_at(data, parts)
            if actual is None:
                failures.add(where, f"source key '{key}' is not in {run_file.name}")
                continue
            got = readme_value(value)
            want = expected_text(actual, unit)
            checked_value_lines.add(i)
            if got is None:
                failures.add(where, f"Value '{value}' is not a number")
            elif got != want:
                failures.add(where, f"Value {value} {unit} != {want} {unit} "
                                    f"({key} = {actual} in {run_file.name})")

    eval_rows, eval_checked = check_eval(lines, results_dir, load_json(results_schema_path), failures,
                                         checked_value_lines, macbook_ok_lines, measured_rows, pending)

    for i, line in enumerate(lines):
        if "MacBook" in line and i not in macbook_ok_lines:
            failures.add(f"line {i + 1}", "'MacBook' outside a measured row whose run file says MacBook")

    # ---- every other number, for the reviewer (AC-t08-4)
    skip = set()
    if designed is not None:
        skip.update(range(designed[0], designed[1] + 1))
    others = []
    in_code = False
    for i, line in enumerate(lines):
        if line.strip().startswith("```"):
            in_code = not in_code
            continue
        if i in skip:
            continue
        text = line
        if i in measured_rows:
            # Value, Measured on, Run and Source key are checked above; only the Metric text is listed.
            text = split_row(line)[0]
        found = [m.group(0) for m in NUMBER.finditer(text)]
        if found:
            others.append((i + 1, "code" if in_code else "text", found, line.strip()))

    links_checked = None
    if link_files:
        links_checked = check_links(link_files, repo_root or REPO, failures)
    allowed = 0
    if release:
        allowed = check_release(pending, allowlist_path or ALLOWLIST, failures, out)

    print(f"README: {readme_path}", file=out)
    measured_checked = len(checked_value_lines) - eval_checked
    print(f"measured rows: {rows}, compared to a run file: {measured_checked}, "
          f"pending: {rows - measured_checked}", file=out)
    print(f"eval rows: {eval_rows}, compared to a results file: {eval_checked}, "
          f"pending: {eval_rows - eval_checked}", file=out)
    print("", file=out)
    print("Other numbers in the README (outside the designed-for table and the checked values), "
          "for the reviewer (AC-t08-4):", file=out)
    for line_no, kind, found, text in others:
        shown = text if len(text) <= 110 else text[:107] + "..."
        print(f"  {line_no:4d} [{kind}] {', '.join(found)}  ::  {shown}", file=out)
    print("", file=out)
    if links_checked is not None:
        names = ", ".join(pathlib.Path(p).name for p in link_files)
        print(f"relative links checked in {names}: {links_checked}", file=out)
    if release:
        print(f"release mode: {len(pending)} pending cell(s), {allowed} on the allowlist, "
              f"{len(pending) - allowed} blocking", file=out)
    if failures.items:
        print(f"FAIL ({len(failures.items)}):", file=out)
        for f in failures.items:
            print(f"  {f}", file=out)
        return 1
    print(f"OK: {measured_checked} measured value(s) match their run files; "
          f"{rows - measured_checked} row(s) pending, with valid keys and labels; "
          f"{eval_checked} eval value(s) match their results files, {eval_rows - eval_checked} pending"
          + ("; release mode: every pending cell is on the allowlist" if release else ""), file=out)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--readme", default=str(REPO / "README.md"))
    parser.add_argument("--summary-dir", default=str(REPO / "bench/summary"))
    parser.add_argument("--schema", default=str(REPO / "bench/summary.schema.json"))
    parser.add_argument("--results-dir", default=str(REPO / "eval/results"))
    parser.add_argument("--results-schema", default=str(REPO / "eval/results.schema.json"))
    parser.add_argument("--runbook", default=str(RUNBOOK), help="link-checked with the README (AC-t14-10)")
    parser.add_argument("--repo-root", default=str(REPO), help="links must resolve to files git tracks here")
    parser.add_argument("--release", action="store_true",
                        help="fail on every pending cell whose Metric the allowlist does not name (D-55)")
    parser.add_argument("--allowlist", default=str(ALLOWLIST))
    args = parser.parse_args(argv)
    required = [args.readme, args.schema, args.results_schema, args.runbook]
    if args.release:
        required.append(args.allowlist)
    for p in required:
        if not pathlib.Path(p).is_file():
            print(f"missing file: {p}", file=sys.stderr)
            return 2
    return check(args.readme, args.summary_dir, args.schema, results_dir=args.results_dir,
                 results_schema_path=args.results_schema, release=args.release, allowlist_path=args.allowlist,
                 link_files=[args.readme, args.runbook], repo_root=args.repo_root)


if __name__ == "__main__":
    sys.exit(main())
