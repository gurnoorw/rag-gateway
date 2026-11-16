#!/usr/bin/env python3
"""Holds the README's measured table to the committed benchmark run files (t08, D-45; AC-t08-2, -3, -4).

    python3 scripts/check_readme_numbers.py
    python3 scripts/check_readme_numbers.py --readme README.md --summary-dir bench/summary \\
        --schema bench/summary.schema.json

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

"MacBook" may appear in the README only inside measured rows whose run file's ``measured_on`` says MacBook
(AC-t08-3): a laptop label never appears without the laptop run behind it.

It then lists every other number in the README outside the designed-for table (between
``<!-- designed-for:begin -->`` and ``<!-- designed-for:end -->``) and outside the checked Value cells, with
line numbers, for the reviewer's AC-t08-4 check. The list is informational and never fails the run.

Standard library only. Exit code 0 when every check passes, 1 otherwise, 2 on a usage error.
"""
import argparse
import json
import pathlib
import re
import sys
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP

REPO = pathlib.Path(__file__).resolve().parent.parent

MEASURED_BEGIN, MEASURED_END = "<!-- measured:begin -->", "<!-- measured:end -->"
DESIGNED_BEGIN, DESIGNED_END = "<!-- designed-for:begin -->", "<!-- designed-for:end -->"
COLUMNS = ["Metric", "Value", "Unit", "Measured on", "Run", "Source key"]
PENDING = {"pending", "pending laptop run"}

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

PERCENTILE = re.compile(r"\bp(50|95|99)\b")
BUCKET_EDGES = re.compile(r"bucket edges\s+\d[\d.]*(\s*,\s*\d[\d.]*)+\s*(ms|s)\b")
KEY_SEGMENT = re.compile(r"^([a-z0-9_]+)(?:\[(\d+|[a-z0-9_]+=[^\]]+)\])?$")
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
                field = index.split("=", 1)[0]
                item = resolve_ref(schema, node)
                if field not in item.get("properties", {}):
                    return None, None, f"items of '{name}' have no field '{field}'"
    if "$ref" in node:
        refs.append(node["$ref"])
    leaf = resolve_ref(schema, node)
    if leaf.get("type") not in ("number", "integer"):
        return None, None, "the key does not name a number in the schema"
    return leaf, refs, None


def unit_fits(unit, parts, leaf, refs):
    rule = UNITS[unit][2]
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
            field, want = index.split("=", 1)
            match = [x for x in node if isinstance(x, dict) and str(x.get(field)) == want]
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


def expected_text(value, unit):
    decimals, scale, _ = UNITS[unit]
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


def check(readme_path, summary_dir, schema_path, out=sys.stdout):
    failures = Failures()
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

    print(f"README: {readme_path}", file=out)
    print(f"measured rows: {rows}, compared to a run file: {len(checked_value_lines)}, "
          f"pending: {rows - len(checked_value_lines)}", file=out)
    print("", file=out)
    print("Other numbers in the README (outside the designed-for table and the checked values), "
          "for the reviewer (AC-t08-4):", file=out)
    for line_no, kind, found, text in others:
        shown = text if len(text) <= 110 else text[:107] + "..."
        print(f"  {line_no:4d} [{kind}] {', '.join(found)}  ::  {shown}", file=out)
    print("", file=out)
    if failures.items:
        print(f"FAIL ({len(failures.items)}):", file=out)
        for f in failures.items:
            print(f"  {f}", file=out)
        return 1
    print(f"OK: {len(checked_value_lines)} measured value(s) match their run files; "
          f"{rows - len(checked_value_lines)} row(s) pending, with valid keys and labels", file=out)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--readme", default=str(REPO / "README.md"))
    parser.add_argument("--summary-dir", default=str(REPO / "bench/summary"))
    parser.add_argument("--schema", default=str(REPO / "bench/summary.schema.json"))
    args = parser.parse_args(argv)
    for p in (args.readme, args.schema):
        if not pathlib.Path(p).is_file():
            print(f"missing file: {p}", file=sys.stderr)
            return 2
    return check(args.readme, args.summary_dir, args.schema)


if __name__ == "__main__":
    sys.exit(main())
