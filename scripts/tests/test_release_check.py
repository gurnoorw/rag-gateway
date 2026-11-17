"""Tests for scripts/release_check.sh (t14, AC-t14-11).

The fresh-clone build is skipped here (--skip-build, reported as SKIP and never as a pass): it is a full
`./gradlew clean build`, run by hand and by the reviewer. The other checks run for real; their results depend on
the tree (Docker for Mermaid, Dev A's --release mode, the owner's LICENSE), so these tests assert the order, the
one-line-per-check format, the exit code and the checks whose result is fixed by this repository's state.

Run: python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import re
import subprocess
import unittest

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SCRIPT = REPO / "scripts/release_check.sh"
LINE = re.compile(r"^(OK|FAIL|SKIP) +(\S+) ")


def run(*args, env=None):
    return subprocess.run(["bash", str(SCRIPT), *args], cwd=REPO, capture_output=True, text=True, timeout=600,
                          env=env)


def checks(stdout):
    return [(m.group(2), m.group(1)) for m in map(LINE.match, stdout.splitlines()) if m]


class ReleaseCheckTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.release = run("--skip-build", "--version", "1.0.0")
        cls.snapshot = run("--skip-build", "--version", "0.1.0-SNAPSHOT")

    def test_ac_t14_11_one_line_per_check_in_the_planned_order(self):
        names = [name for name, _ in checks(self.release.stdout)]
        self.assertEqual(["build", "readme", "secrets", "decisions", "mermaid", "license", "version", "wording"],
                         names, self.release.stdout)

    def test_skipped_build_is_not_a_pass(self):
        self.assertIn(("build", "SKIP"), checks(self.release.stdout))
        self.assertEqual(1, self.release.returncode, self.release.stdout)
        self.assertRegex(self.release.stdout, r"== \d+ check\(s\) not OK")

    def test_ac_t14_11_missing_licence_is_the_owners_decision(self):
        if (REPO / "LICENSE").exists():
            self.skipTest("a LICENSE file exists")
        self.assertIn("FAIL  license   no LICENSE file: owner decision, D-55", self.release.stdout)

    def test_static_checks_this_repository_passes(self):
        results = dict(checks(self.release.stdout))
        for name in ("secrets", "decisions"):
            with self.subTest(check=name):
                self.assertEqual("OK", results[name], self.release.stdout)

    def test_readme_check_runs_in_release_mode_or_says_it_is_missing(self):
        readme = [line for line in self.release.stdout.splitlines() if re.match(r"^(OK|FAIL)  readme ", line)]
        self.assertEqual(1, len(readme), self.release.stdout)
        has_flag = "--release" in (REPO / "scripts/check_readme_numbers.py").read_text()
        self.assertEqual(not has_flag, "has no --release mode in this tree" in readme[0], readme[0])

    def test_version_checks_changelog_and_build_file(self):
        self.assertIn("## [1.0.0]", (REPO / "CHANGELOG.md").read_text())
        # 1.0.0: the changelog entry exists, the build file still has the snapshot (the owner's release commit).
        self.assertIn("FAIL  version", self.release.stdout)
        self.assertIn('build.gradle.kts has version = "0.1.0-SNAPSHOT", not version = "1.0.0"', self.release.stdout)
        self.assertNotIn("CHANGELOG.md has no '## [1.0.0]' entry", self.release.stdout)
        # The snapshot: the build file matches, the changelog has no such entry.
        self.assertIn("CHANGELOG.md has no '## [0.1.0-SNAPSHOT]' entry", self.snapshot.stdout)
        self.assertNotIn("build.gradle.kts has version", self.snapshot.stdout)

    def test_without_version_there_is_no_version_line(self):
        names = [name for name, _ in checks(run("--skip-build").stdout)]
        self.assertNotIn("version", names)
        self.assertEqual("wording", names[-1])

    def test_usage_error(self):
        self.assertEqual(2, run("--version").returncode)
        self.assertEqual(2, run("--bogus").returncode)


class ShellSyntaxTest(unittest.TestCase):

    def test_release_scripts_parse(self):
        for name in ("release_check.sh", "secrets_scan.sh", "wording_scan.sh"):
            with self.subTest(script=name):
                result = subprocess.run(["bash", "-n", f"scripts/{name}"], cwd=REPO, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

    def test_a_wording_scan_without_its_pattern_file_is_a_skip_not_a_pass(self):
        # D-56: the scan exits 0 when it has no terms to look for, but a release check that checked nothing fails.
        import os
        env = dict(os.environ, WORDING_PATTERNS_FILE=str(REPO / "build" / "no-such-pattern-file.txt"))
        result = run("--skip-build", env=env)
        self.assertIn(("wording", "SKIP"), checks(result.stdout), result.stdout)
        self.assertEqual(1, result.returncode, result.stdout)

