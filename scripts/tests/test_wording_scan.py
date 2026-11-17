"""Tests for scripts/wording_scan.sh (t14, AC-t14-12).

The pattern file is a temporary file made here (WORDING_PATTERNS_FILE), never the private one, and its term is
random, so no term list is committed. Planted terms go into a temporary repository outside this one.

Run: python3 -m unittest discover -s scripts/tests -v
"""
import os
import pathlib
import secrets
import shutil
import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SCRIPT = REPO / "scripts/wording_scan.sh"
GIT_ENV = dict(os.environ, GIT_AUTHOR_NAME="test", GIT_AUTHOR_EMAIL="test@example.invalid",
               GIT_COMMITTER_NAME="test", GIT_COMMITTER_EMAIL="test@example.invalid",
               GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")


class WordingScanTest(unittest.TestCase):

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="wording-scan-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.term = "planted" + secrets.token_hex(6)
        self.patterns = self.tmp / "patterns.txt"
        self.patterns.write_text("# comment line\n\n%s\n" % self.term)

    def scan(self, repo=None, patterns=None):
        env = dict(os.environ, WORDING_PATTERNS_FILE=str(patterns or self.patterns))
        args = ["bash", str(SCRIPT)] + ([str(repo)] if repo else [])
        return subprocess.run(args, capture_output=True, text=True, timeout=60, env=env)

    def make_repo(self, files):
        repo = self.tmp / "repo"
        repo.mkdir()
        subprocess.run(["git", "-C", str(repo), "init", "-q"], check=True, env=GIT_ENV)
        for name, content in files.items():
            (repo / name).parent.mkdir(parents=True, exist_ok=True)
            (repo / name).write_text(content)
        subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True, env=GIT_ENV)
        subprocess.run(["git", "-C", str(repo), "commit", "-q", "-m", "files"], check=True, env=GIT_ENV)
        return repo

    def test_ac_t14_12_planted_term_fails_without_printing_it(self):
        repo = self.make_repo({"README.md": "fine\n", "docs/a.md": "one\ntwo %s three\n" % self.term.upper()})
        result = self.scan(repo)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("FAIL  wording scan: 1 line(s)", result.stdout)
        self.assertIn("docs/a.md:2  (pattern line 3)", result.stdout)
        self.assertNotIn(self.term, result.stdout.lower())

    def test_ac_t14_12_clean_tree_passes(self):
        result = self.scan(self.make_repo({"README.md": "engineering content only\n"}))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK    wording scan: 1 private terms, none in 1 tracked files", result.stdout)

    def test_untracked_files_are_not_scanned(self):
        repo = self.make_repo({"README.md": "fine\n"})
        (repo / "notes.txt").write_text(self.term + "\n")
        self.assertEqual(0, self.scan(repo).returncode)

    def test_this_repository_passes_a_random_term(self):
        result = self.scan()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_ac_t14_12_without_the_pattern_file_it_skips(self):
        result = self.scan(patterns=self.tmp / "missing.txt")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("skipped: no private pattern file", result.stdout)

    def test_pattern_file_without_terms_is_an_error(self):
        empty = self.tmp / "empty.txt"
        empty.write_text("# only a comment\n\n   \n")
        self.assertEqual(2, self.scan(patterns=empty).returncode)

    def test_default_pattern_file_is_the_gitignored_private_one(self):
        self.assertIn("$HERE/private/wording-patterns.txt", SCRIPT.read_text())
        ignored = subprocess.run(["git", "check-ignore", "-q", "private/wording-patterns.txt"], cwd=REPO)
        self.assertEqual(0, ignored.returncode, "private/wording-patterns.txt must be gitignored")
        tracked = subprocess.run(["git", "ls-files", "private"], cwd=REPO, capture_output=True, text=True)
        self.assertEqual("", tracked.stdout)


if __name__ == "__main__":
    unittest.main()
