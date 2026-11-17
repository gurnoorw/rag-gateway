#!/usr/bin/env python3
"""Checks the eval set's gold labels against the pinned corpus (t13, DECISIONS.md D-53, AC-t13-1).

    python3 scripts/corpus/fetch_corpus.py          # once: the pinned files into the gitignored data/corpus/
    python3 scripts/eval/check_evidence.py          # eval/questions.v1.jsonl against data/corpus/
    python3 scripts/eval/check_evidence.py --questions eval/questions.v1.jsonl --corpus-dir data/corpus

For every question:
  - the fields the schema requires by kind (eval/questions.schema.json is the contract; this repeats the
    rules a label can break without a schema library: id pattern, kind, expected, gold by kind, groups);
  - every gold document is in the manifest and its file was fetched;
  - every evidence passage (20-400 characters) occurs verbatim in that document's cleaned text, made by
    ``load_corpus.document()``: the same cleaning the loader sends to the index, so there is one implementation;
  - an ``acl`` question's gold documents are not visible to the unauthorised asker (the ``support`` group) and are
    visible to its ``required_groups``.
Then the set's composition: at least 30 questions, 15 conceptual, 8 exact_term, 5 unanswerable and 6 acl.

Exit status 0 when every check passes, 1 otherwise (each failure is listed), 2 on a usage error.
Standard library only.
"""
import argparse
import collections
import json
import pathlib
import re
import sys

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts
REPO = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "corpus"))
import fetch_corpus  # noqa: E402
import load_corpus  # noqa: E402

DEFAULT_QUESTIONS = REPO / "eval" / "questions.v1.jsonl"
ID = re.compile(r"q[0-9]{2,3}\Z")
GROUP = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}\Z")
KINDS = ("conceptual", "exact_term", "unanswerable", "acl")
MINIMUM = {"total": 30, "conceptual": 15, "exact_term": 8, "unanswerable": 5, "acl": 6}
UNAUTHORISED_GROUP = "support"
EVIDENCE_CHARS = (20, 400)


def load_questions(path):
    questions = []
    for n, line in enumerate(pathlib.Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            questions.append((n, json.loads(line)))
    return questions


def cleaned_documents(manifest, corpus_dir):
    """doc id -> (groups, cleaned content or None when the file was not fetched)."""
    _, entries = fetch_corpus.read_manifest(manifest)
    docs = {}
    for path, groups in entries:
        file = pathlib.Path(corpus_dir) / fetch_corpus.check_path(path)
        content = None
        if file.is_file():
            content = load_corpus.document(path, groups, file.read_text(encoding="utf-8"))["content"]
        docs[load_corpus.doc_id(path)] = (groups, content)
    return docs


def check(questions, docs):
    """Returns (failures, counts by kind)."""
    failures, counts, seen = [], collections.Counter(), set()
    for line, q in questions:
        where = "line %d (%s)" % (line, q.get("id", "?"))
        kind = q.get("kind")
        counts[kind] += 1
        if not ID.match(str(q.get("id", ""))):
            failures.append("%s: id must match q00..q999" % where)
        if q.get("id") in seen:
            failures.append("%s: duplicate id" % where)
        seen.add(q.get("id"))
        if kind not in KINDS:
            failures.append("%s: unknown kind %r" % (where, kind))
            continue
        if not isinstance(q.get("question"), str) or not 10 <= len(q["question"]) <= 2000:
            failures.append("%s: question must be 10..2000 characters" % where)
        gold = q.get("gold")
        if not isinstance(gold, list):
            failures.append("%s: gold must be a list" % where)
            continue
        expected = "refusal" if kind == "unanswerable" else "answer"
        if q.get("expected") != expected:
            failures.append("%s: a %s question expects %r" % (where, kind, expected))
        if kind == "unanswerable":
            if gold:
                failures.append("%s: an unanswerable question has no gold" % where)
            continue
        if not gold:
            failures.append("%s: a %s question needs at least one gold label" % (where, kind))
        groups = q.get("required_groups")
        if kind == "acl":
            if not groups or not all(isinstance(g, str) and GROUP.match(g) for g in groups):
                failures.append("%s: an acl question needs required_groups" % where)
                groups = []
        for label in gold:
            doc, evidence = label.get("doc_id"), label.get("evidence", "")
            if doc not in docs:
                failures.append("%s: %s is not a manifest document" % (where, doc))
                continue
            doc_groups, content = docs[doc]
            if content is None:
                failures.append("%s: %s was not fetched (run scripts/corpus/fetch_corpus.py)" % (where, doc))
                continue
            if not EVIDENCE_CHARS[0] <= len(evidence) <= EVIDENCE_CHARS[1]:
                failures.append("%s: evidence must be %d..%d characters, got %d"
                                % (where, EVIDENCE_CHARS[0], EVIDENCE_CHARS[1], len(evidence)))
            if evidence not in content:
                failures.append("%s: evidence is not verbatim in the cleaned text of %s: %r"
                                % (where, doc, evidence[:80]))
            if kind == "acl":
                if UNAUTHORISED_GROUP in doc_groups:
                    failures.append("%s: %s is visible to the unauthorised %r group"
                                    % (where, doc, UNAUTHORISED_GROUP))
                if groups and not set(groups) & set(doc_groups):
                    failures.append("%s: required_groups %s cannot see %s (groups %s)"
                                    % (where, groups, doc, doc_groups))
    total = sum(counts.values())
    if total < MINIMUM["total"]:
        failures.append("the set has %d questions, needs at least %d" % (total, MINIMUM["total"]))
    for kind in KINDS:
        if counts[kind] < MINIMUM[kind]:
            failures.append("the set has %d %s questions, needs at least %d" % (counts[kind], kind, MINIMUM[kind]))
    return failures, counts


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--questions", type=pathlib.Path, default=DEFAULT_QUESTIONS)
    parser.add_argument("--manifest", type=pathlib.Path, default=fetch_corpus.DEFAULT_MANIFEST)
    parser.add_argument("--corpus-dir", type=pathlib.Path, default=fetch_corpus.DEFAULT_OUT)
    args = parser.parse_args(argv)
    for p in (args.questions, args.manifest):
        if not p.is_file():
            print("missing file: %s" % p, file=sys.stderr)
            return 2
    header, _ = fetch_corpus.read_manifest(args.manifest)
    questions = load_questions(args.questions)
    failures, counts = check(questions, cleaned_documents(args.manifest, args.corpus_dir))
    labels = sum(len(q.get("gold") or []) for _, q in questions)
    print("%s: %d questions (%s); %d gold passages against %s at %s"
          % (args.questions.name, len(questions), ", ".join("%s %d" % (k, counts[k]) for k in KINDS), labels,
             header["repo"], header["commit"][:12]))
    if failures:
        print("FAIL (%d):" % len(failures))
        for f in failures:
            print("  " + f)
        return 1
    print("OK: every evidence passage is verbatim in its document's cleaned text")
    return 0


if __name__ == "__main__":
    sys.exit(main())
