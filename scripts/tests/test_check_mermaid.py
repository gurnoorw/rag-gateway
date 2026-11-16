"""Tests for scripts/check_mermaid.sh (t08, AC-t08-8). Needs Docker and the pinned mermaid-cli image;
skipped (not passed) when the script reports that the check could not run (exit 2).

Run: python3 -B -m unittest discover -s scripts/tests -v
"""
import pathlib
import subprocess
import unittest

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SCRIPT = REPO / "scripts/check_mermaid.sh"
FIXTURES = HERE / "fixtures"


def run(*args):
    return subprocess.run(["bash", str(SCRIPT), *args], cwd=REPO, capture_output=True, text=True, timeout=300)


class CheckMermaidTest(unittest.TestCase):

    def run_or_skip(self, *args):
        result = run(*args)
        if result.returncode == 2:
            self.skipTest("Mermaid check could not run here: " + result.stdout.strip())
        return result

    def test_readme_diagram_renders(self):
        result = self.run_or_skip()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK    README.md block-01", result.stdout)

    def test_broken_diagram_fails_with_the_parse_error(self):
        result = self.run_or_skip(str(FIXTURES / "mermaid-broken.md"))
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("does not render", result.stdout)
        self.assertIn("Parse error", result.stdout)

    def test_file_without_a_diagram_fails(self):
        result = self.run_or_skip(str(FIXTURES / "mermaid-none.md"))
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("has no ```mermaid block", result.stdout)


if __name__ == "__main__":
    unittest.main()
