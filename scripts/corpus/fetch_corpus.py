#!/usr/bin/env python3
"""Fetch the RAG demo corpus at its pinned commit (t09, DECISIONS.md D-47, AC-t09-11).

Reads data/corpus-manifest.tsv (repo and commit in its header, one path per line), downloads every file from
raw.githubusercontent.com at that commit into the gitignored data/corpus/, and checks that the repository's
LICENSE at the same commit is CC BY 4.0. Nothing fetched is committed.

    python3 scripts/corpus/fetch_corpus.py              # all files, then a summary
    python3 scripts/corpus/fetch_corpus.py --limit 5    # a quick check

Exit status: 0 when the licence matches and every file returned HTTP 200; 1 otherwise (each failure is listed).
Standard library only; HTTPS_PROXY is honoured.
"""
import argparse
import concurrent.futures
import pathlib
import sys
import urllib.error
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO_ROOT / "data" / "corpus-manifest.tsv"
DEFAULT_OUT = REPO_ROOT / "data" / "corpus"
RAW = "https://raw.githubusercontent.com/{repo}/{commit}/{path}"
LICENCE_MARKER = "Attribution 4.0 International"
PATH_PREFIX = "content/en/docs/"
EXPECTED_COUNT = (250, 350)


def read_manifest(path):
    """Returns (header dict, [(path, [groups])]). Header lines look like '# key: value'."""
    header, entries = {}, []
    for raw in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            key, sep, value = line[1:].partition(":")
            if sep and key.strip() in ("repo", "commit", "licence"):
                header[key.strip()] = value.strip()
            continue
        parts = raw.split("\t")
        if len(parts) != 2:
            raise ValueError("manifest line is not <path>TAB<groups>: " + raw)
        entries.append((parts[0], [g for g in parts[1].split(",") if g]))
    for key in ("repo", "commit"):
        if key not in header:
            raise ValueError("manifest header has no '# %s:' line" % key)
    return header, entries


def check_path(path):
    """A manifest path must be a Markdown file under content/en/docs/ with no '..' (it becomes a local path)."""
    parts = pathlib.PurePosixPath(path).parts
    if not path.startswith(PATH_PREFIX) or not path.endswith(".md") or ".." in parts or path.startswith("/"):
        raise ValueError("unexpected manifest path: " + path)
    return path


def raw_url(header, path):
    return RAW.format(repo=header["repo"], commit=header["commit"], path=path)


def fetch(url, timeout=30):
    """Returns (status, body bytes); status 0 for a network error."""
    request = urllib.request.Request(url, headers={"User-Agent": "corpus-fetch"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as e:
        return e.code, b""
    except (urllib.error.URLError, OSError) as e:
        print("  network error for %s: %s" % (url, e), file=sys.stderr)
        return 0, b""


def write(out_dir, path, body):
    target = (out_dir / path).resolve()
    if out_dir.resolve() not in target.parents:
        raise ValueError("refusing to write outside %s: %s" % (out_dir, path))
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    tmp.write_bytes(body)
    tmp.replace(target)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", type=pathlib.Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0, help="fetch only the first N files (0 = all)")
    args = parser.parse_args(argv)

    header, entries = read_manifest(args.manifest)
    paths = [check_path(p) for p, _ in entries]
    if args.limit:
        paths = paths[: args.limit]
    args.out.mkdir(parents=True, exist_ok=True)
    print("%s at %s: %d files in the manifest" % (header["repo"], header["commit"], len(entries)))

    status, licence = fetch(raw_url(header, "LICENSE"))
    licence_ok = status == 200 and LICENCE_MARKER in licence.decode("utf-8", "replace")
    if status == 200:
        write(args.out, "LICENSE", licence)
    print("LICENSE: HTTP %d, %s" % (status, "CC BY 4.0" if licence_ok else "NOT the expected CC BY 4.0 text"))

    failures, total_bytes, largest = [], 0, 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = pool.map(lambda p: (p, *fetch(raw_url(header, p))), paths)
        for path, code, body in results:
            if code != 200:
                failures.append((path, code))
                continue
            write(args.out, path, body)
            total_bytes += len(body)
            largest = max(largest, len(body))

    ok = len(paths) - len(failures)
    print("fetched %d/%d files with HTTP 200, %.2f MB, largest %d KB, into %s"
          % (ok, len(paths), total_bytes / 1e6, round(largest / 1000), args.out))
    for path, code in failures:
        print("  FAILED HTTP %d  %s" % (code, path))
    if not args.limit and not (EXPECTED_COUNT[0] <= len(entries) <= EXPECTED_COUNT[1]):
        print("manifest count %d is outside the expected %d-%d" % ((len(entries),) + EXPECTED_COUNT))
        return 1
    return 0 if licence_ok and not failures else 1


if __name__ == "__main__":
    sys.exit(main())
