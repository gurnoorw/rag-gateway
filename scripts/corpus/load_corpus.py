#!/usr/bin/env python3
"""Load the fetched corpus into the gateway's document API (t09, DECISIONS.md D-47).

For every manifest entry found under data/corpus/ (run fetch_corpus.py first):
  - takes the title from the Hugo front matter and removes the front matter;
  - removes Hugo shortcode tags ({{< ... >}}, {{% ... %}}), keeping the visible text of the two that carry it
    (glossary_tooltip's text="..." and heading "..."), and HTML comments;
  - maps the path to a document id with dots: content/en/docs/concepts/workloads/controllers/job.md ->
    concepts.workloads.controllers.job (no slash: ids are URL path segments);
  - PUTs {"title", "content", "acl_groups"} to /v1/rag/collections/<collection>/documents/<doc id> with the
    manifest's groups (concepts eng; tutorials eng,support; setup eng,finance).

    RAG_ADMIN_KEY=<an admin key> python3 scripts/corpus/load_corpus.py [--base-url http://localhost:8080]
        [--collection k8s-docs] [--limit N] [--wait 300]
    python3 scripts/corpus/load_corpus.py --dry-run     # parse only, print a summary; no key needed

The key is read from the environment and never printed. A 503 (write cap) is retried after its Retry-After.
--wait polls GET until every loaded document is indexed and prints the elapsed time; with the fake embedder that
time is "fake embedder, not a model". The corpus is CC BY 4.0, modified as above: data/CORPUS_ATTRIBUTION.md.
Standard library only.
"""
import argparse
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import fetch_corpus  # noqa: E402  (same directory)

DOC_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}\Z")
FRONT_MATTER = re.compile(r"\A(?:---[ \t]*\n(.*?)\n---[ \t]*(?:\n|\Z)|\+\+\+[ \t]*\n(.*?)\n\+\+\+[ \t]*(?:\n|\Z))", re.S)
SHORTCODE = re.compile(r"\{\{[<%](.*?)[>%]\}\}", re.S)
TEXT_ATTR = re.compile(r'\btext\s*=\s*"([^"]*)"')
HEADING_ARG = re.compile(r'\A\s*heading\s+"([^"]*)"')
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
BLANK_RUNS = re.compile(r"\n[ \t]*\n(?:[ \t]*\n)+")
MAX_RETRIES = 5


def doc_id(path):
    """content/en/docs/a/b/c.md -> a.b.c"""
    rel = path[len(fetch_corpus.PATH_PREFIX):] if path.startswith(fetch_corpus.PATH_PREFIX) else path
    if rel.endswith(".md"):
        rel = rel[:-3]
    result = rel.replace("/", ".")
    if not DOC_ID.match(result):
        raise ValueError("document id %r from %s does not match the id pattern" % (result, path))
    return result


def split_front_matter(text):
    """Returns (title or None, body). YAML (---) or TOML (+++) front matter at the very start."""
    text = text.replace("\r\n", "\n").lstrip("﻿")
    m = FRONT_MATTER.match(text)
    if not m:
        return None, text
    block = m.group(1) if m.group(1) is not None else m.group(2)
    title = None
    for line in block.split("\n"):
        tm = re.match(r"title\s*[:=]\s*(.*)\Z", line)   # top level only: nested keys are indented
        if tm:
            title = unquote(tm.group(1).strip())
            break
    return title or None, text[m.end():]


def unquote(value):
    value = re.sub(r"\s+#.*\Z", "", value) if not value.startswith(("'", '"')) else value
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return value.strip()


def strip_shortcodes(text):
    def replace(m):
        inner = m.group(1)
        if inner.lstrip().startswith("/"):
            return ""
        t = TEXT_ATTR.search(inner)
        if t:
            return t.group(1)
        h = HEADING_ARG.match(inner)
        if h:
            return h.group(1).replace("_", " ").replace("-", " ").capitalize()
        return ""
    return SHORTCODE.sub(replace, text)


def clean(body):
    body = HTML_COMMENT.sub("", body)
    body = strip_shortcodes(body)
    body = BLANK_RUNS.sub("\n\n", body)
    return body.strip() + "\n"


def document(path, groups, raw_text):
    """The PUT body and id for one manifest entry."""
    title, body = split_front_matter(raw_text)
    content = clean(body)
    if not title:
        heading = re.search(r"^#\s+(.+)$", content, re.M)
        title = heading.group(1).strip() if heading else doc_id(path).rsplit(".", 1)[-1]
    return {"doc_id": doc_id(path), "title": title[:500], "content": content, "acl_groups": list(groups)}


def put(base_url, collection, doc, key):
    url = "%s/v1/rag/collections/%s/documents/%s" % (base_url.rstrip("/"), urllib.parse.quote(collection, safe=""),
                                                     urllib.parse.quote(doc["doc_id"], safe=""))
    body = json.dumps({k: doc[k] for k in ("title", "content", "acl_groups")}).encode("utf-8")
    for attempt in range(MAX_RETRIES + 1):
        request = urllib.request.Request(url, data=body, method="PUT", headers={
            "Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 503 and attempt < MAX_RETRIES:
                time.sleep(float(e.headers.get("Retry-After", "1") or 1))
                continue
            return e.code, e.read().decode("utf-8", "replace")


def get_status(base_url, collection, doc_id_value, key):
    url = "%s/v1/rag/collections/%s/documents/%s" % (base_url.rstrip("/"), urllib.parse.quote(collection, safe=""),
                                                     urllib.parse.quote(doc_id_value, safe=""))
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read()).get("status")
    except urllib.error.HTTPError as e:
        return "HTTP %d" % e.code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", type=pathlib.Path, default=fetch_corpus.DEFAULT_MANIFEST)
    parser.add_argument("--corpus-dir", type=pathlib.Path, default=fetch_corpus.DEFAULT_OUT)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--collection", default="k8s-docs")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--wait", type=int, default=0, help="seconds to wait for every document to be indexed")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    header, entries = fetch_corpus.read_manifest(args.manifest)
    if args.limit:
        entries = entries[: args.limit]
    docs, missing, empty = [], [], []
    for path, groups in entries:
        file = args.corpus_dir / fetch_corpus.check_path(path)
        if not file.is_file():
            missing.append(path)
            continue
        doc = document(path, groups, file.read_text(encoding="utf-8"))
        (empty if not doc["content"].strip() else docs).append(doc)
    print("%d documents from %s at %s (%d missing: run fetch_corpus.py; %d empty after cleaning, skipped)"
          % (len(docs), header["repo"], header["commit"][:12], len(missing), len(empty)))
    if args.dry_run:
        sizes = sorted(len(d["content"].encode("utf-8")) for d in docs)
        if sizes:
            print("content bytes: total %d, largest %d" % (sum(sizes), sizes[-1]))
        return 1 if missing else 0

    key = os.environ.get("RAG_ADMIN_KEY", "")
    if not key:
        print("set RAG_ADMIN_KEY to an admin API key of the target tenant", file=sys.stderr)
        return 2
    started = time.monotonic()
    failed = []
    for doc in docs:
        status, body = put(args.base_url, args.collection, doc, key)
        if status != 202:
            failed.append((doc["doc_id"], status, body[:200]))
    print("PUT %d documents: %d accepted, %d failed" % (len(docs), len(docs) - len(failed), len(failed)))
    for doc_id_value, status, body in failed:
        print("  HTTP %s  %s  %s" % (status, doc_id_value, body))

    if args.wait and not failed:
        pending = {d["doc_id"] for d in docs}
        deadline = time.monotonic() + args.wait
        while pending and time.monotonic() < deadline:
            pending = {d for d in pending if get_status(args.base_url, args.collection, d, key) != "indexed"}
            if pending:
                time.sleep(2)
        elapsed = time.monotonic() - started
        if pending:
            print("%d documents not indexed after %d s" % (len(pending), args.wait))
            return 1
        print("all %d documents indexed %.1f s after the first PUT" % (len(docs), elapsed))
    return 1 if failed or missing else 0


if __name__ == "__main__":
    sys.exit(main())
