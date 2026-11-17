#!/usr/bin/env python3
"""Holds the decision log's index, its sections and the references to it together (t14, D-55, AC-t14-5).

    python3 scripts/check_decisions.py
    python3 scripts/check_decisions.py --root path/to/tree     # another tree (used by scripts/tests)

Checks on ``docs/process/DECISIONS.md``:

  1. Conflicts: C1-C27 each have an index row and a ``### Cn.`` section, and there is no other C id.
  2. Decisions: every D id in the index has a ``### D-nn.`` section and every section has an index row.
     Index ids and section headings are each unique.
  3. References: every C or D id mentioned in ``README.md``, ``CLAUDE.md``, ``CHANGELOG.md`` or any Markdown file
     under ``docs/``
     (this file's own cross-references included) names an existing section. A range such as ``D-01–D-25`` is
     checked at both ends.
  4. LLD deviations: where the implementation departs from ``docs/LLD.md`` on the outbox claim SQL, ACL groups on
     keys, tenant columns and the 768-dimension vector, the place that records the departure names its entry
     (see ``DEVIATIONS``), and that entry exists.

Standard library only. Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DECISIONS = "docs/process/DECISIONS.md"
CONFLICTS = range(1, 28)  # C1-C27, fixed by the requirements review

ID = r"(?:C\d{1,2}|D-\d{2,3})"
ID_RE = re.compile(r"(?<![\w-])(" + ID + r")(?![\w])")
INDEX_ROW_RE = re.compile(r"^\|\s*(" + ID + r")\s*\|")
SECTION_RE = re.compile(r"^###\s+(" + ID + r")\.\s")

# Each LLD deviation: where it is recorded (file, a regex selecting the recording line or section) and the ids
# that place must name. A section selector starts at the heading and ends before the next heading of level 2-4.
DEVIATIONS = [
    ("outbox claim SQL", "docs/LLD.md", r"materialised CTE", ["D-47"]),
    ("outbox claim SQL", DECISIONS, r"^#### D-47 amendment", ["D-47"]),
    ("ACL groups on API keys", DECISIONS, r"^\| `Tenant\(id, tpm, budget, aclGroups\)`", ["C16"]),
    ("tenant columns on documents and chunks", DECISIONS, r"^\| `chunks\(", ["C17"]),
    ("tenant columns in retrieval", DECISIONS, r"^\| `Retriever\.retrieve\(", ["C17"]),
    ("768-dimension embeddings", DECISIONS, r"^\| `chunks\(.*vector\(768\)", ["C1"]),
]


def _id_key(identifier):
    return (identifier[0], int(identifier.lstrip("CD-")))


def _norm(identifier):
    """D-7 and D-07 are the same entry; C ids have no padding."""
    if identifier.startswith("D-"):
        return "D-%02d" % int(identifier[2:])
    return identifier


def parse_decisions(text):
    """Returns (index ids in order, section ids in order) of the decision log."""
    index, sections = [], []
    in_index = False
    for line in text.splitlines():
        if line.startswith("## "):
            in_index = line.strip() == "## Index"
            continue
        if in_index:
            m = INDEX_ROW_RE.match(line)
            if m:
                index.append(_norm(m.group(1)))
        m = SECTION_RE.match(line)
        if m:
            sections.append(_norm(m.group(1)))
    return index, sections


def references(path):
    """Yields (line number, id) for every C/D id in a Markdown file, both ends of a range included."""
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        for m in ID_RE.finditer(line):
            yield number, _norm(m.group(1))


def selected_text(text, selector):
    """The line matching ``selector``; for a heading, the heading and its section body."""
    lines = text.splitlines()
    pattern = re.compile(selector)
    for i, line in enumerate(lines):
        if pattern.search(line):
            if not line.startswith("#"):
                return line
            body = [line]
            for following in lines[i + 1:]:
                if re.match(r"^#{2,4}\s", following):
                    break
                body.append(following)
            return "\n".join(body)
    return None


def check(root):
    root = pathlib.Path(root)
    problems = []
    decisions_path = root / DECISIONS
    if not decisions_path.is_file():
        return ["%s not found" % DECISIONS], {}
    text = decisions_path.read_text(encoding="utf-8")
    index, sections = parse_decisions(text)
    index_set, section_set = set(index), set(sections)

    for name, ids in (("index row", index), ("section", sections)):
        for dup in sorted({i for i in ids if ids.count(i) > 1}, key=_id_key):
            problems.append("%s %s appears more than once" % (dup, name))

    expected_c = {"C%d" % n for n in CONFLICTS}
    for c in sorted(expected_c, key=_id_key):
        if c not in index_set:
            problems.append("%s has no index row" % c)
        if c not in section_set:
            problems.append("%s has no '### %s.' section" % (c, c))
    for c in sorted({i for i in index_set | section_set if i.startswith("C")} - expected_c, key=_id_key):
        problems.append("%s is outside C1-C27" % c)

    d_index = {i for i in index_set if i.startswith("D-")}
    d_sections = {i for i in section_set if i.startswith("D-")}
    for d in sorted(d_index - d_sections, key=_id_key):
        problems.append("%s is in the index but has no '### %s.' section" % (d, d))
    for d in sorted(d_sections - d_index, key=_id_key):
        problems.append("%s has a section but no index row" % d)

    docs = [root / "README.md", root / "CLAUDE.md", root / "CHANGELOG.md"] + sorted((root / "docs").rglob("*.md"))
    referenced = 0
    for doc in docs:
        if not doc.is_file():
            continue
        for number, identifier in references(doc):
            referenced += 1
            if identifier not in section_set:
                problems.append("%s:%d refers to %s, which has no section in %s"
                                % (doc.relative_to(root), number, identifier, DECISIONS))

    for topic, rel, selector, ids in DEVIATIONS:
        path = root / rel
        found = selected_text(path.read_text(encoding="utf-8"), selector) if path.is_file() else None
        if found is None:
            problems.append("LLD deviation '%s': nothing in %s matches /%s/" % (topic, rel, selector))
            continue
        named = {_norm(m.group(1)) for m in ID_RE.finditer(found)}
        for identifier in ids:
            if identifier not in named:
                problems.append("LLD deviation '%s': %s /%s/ does not name %s" % (topic, rel, selector, identifier))
            elif identifier not in section_set:
                problems.append("LLD deviation '%s': %s has no section" % (topic, identifier))

    stats = {"conflicts": len(expected_c & section_set), "decisions": len(d_sections),
             "references": referenced, "files": sum(1 for d in docs if d.is_file()), "deviations": len(DEVIATIONS)}
    return problems, stats


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=str(REPO), help="tree to check (default: this repository)")
    args = parser.parse_args(argv)
    problems, stats = check(args.root)
    if problems:
        for p in problems:
            print("FAIL  " + p)
        print("FAIL  decisions index: %d problem(s)" % len(problems))
        return 1
    print("OK    decisions index: C1-C27 and %d D entries indexed with sections; %d references in %d Markdown "
          "files resolve; %d LLD deviation records name their entry"
          % (stats["decisions"], stats["references"], stats["files"], stats["deviations"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
