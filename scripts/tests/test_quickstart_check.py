"""Tests for scripts/quickstart_check.sh's README parsing (t08, AC-t08-9) and the shell scripts' syntax.

The full quickstart run needs the mock compose profile, port 8080 and a JDK; it is not run here.

Run: python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import subprocess
import unittest

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent


class QuickstartCheckTest(unittest.TestCase):

    def test_readme_quickstart_has_at_most_four_commands_ending_in_curl(self):
        result = subprocess.run(["bash", "scripts/quickstart_check.sh", "--print"], cwd=REPO,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        commands = result.stdout.strip().splitlines()
        self.assertLessEqual(len(commands), 4, commands)
        self.assertIn("./gradlew bootRun", "\n".join(commands))
        self.assertTrue(commands[-1].split(": ", 1)[1].startswith("curl "), commands[-1])
        self.assertIn("--profile mock", commands[0])

    def test_shell_scripts_parse(self):
        for name in ("demo.sh", "quickstart_check.sh", "check_mermaid.sh"):
            with self.subTest(script=name):
                result = subprocess.run(["bash", "-n", f"scripts/{name}"], cwd=REPO, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
