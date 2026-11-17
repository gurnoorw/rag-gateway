"""Tests for scripts/secrets_scan.sh (t14, AC-t14-7).

Every planted key is made up at run time inside a temporary repository outside this one, so no key-shaped string
is ever committed here. The provider prefix is assembled from parts, as in the script, so this file cannot match
the scan either.

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
SCRIPT = REPO / "scripts/secrets_scan.sh"

PROVIDER_PREFIX = "sk" + "-" + "ant" + "-"
GIT_ENV = dict(os.environ, GIT_AUTHOR_NAME="test", GIT_AUTHOR_EMAIL="test@example.invalid",
               GIT_COMMITTER_NAME="test", GIT_COMMITTER_EMAIL="test@example.invalid",
               GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
ENV_EXAMPLE = "# names only\nPOSTGRES_PASSWORD=\nGATEWAY_CLAUDE_API_KEY=\nEVAL_JUDGE_API_KEY=\n"


def scan(path):
    return subprocess.run(["bash", str(SCRIPT), str(path)], capture_output=True, text=True, timeout=120)


class TempRepo:
    def __init__(self):
        self.path = pathlib.Path(tempfile.mkdtemp(prefix="secrets-scan-test-"))
        self.git("init", "-q", "-b", "main")
        self.commit({".env.example": ENV_EXAMPLE, "README.md": "clean\n"}, "initial")

    def git(self, *args):
        subprocess.run(["git", "-C", str(self.path), *args], check=True, env=GIT_ENV, capture_output=True)

    def commit(self, files, message):
        for name, content in files.items():
            target = self.path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if content is None:
                target.unlink()
            else:
                target.write_text(content)
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)

    def remove(self):
        shutil.rmtree(self.path, ignore_errors=True)


class SecretsScanTest(unittest.TestCase):

    def setUp(self):
        self.repo = TempRepo()
        self.addCleanup(self.repo.remove)

    def assertFound(self, result, kind, where):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("FAIL  secrets scan", result.stdout)
        self.assertRegex(result.stdout, r"%s\s+[0-9a-f]{7}\s+%s" % (kind, where))

    def test_clean_repository_passes_and_env_example_names_without_values_are_ignored(self):
        self.repo.commit({"app.yml": "api-key: ${GATEWAY_CLAUDE_API_KEY:}\n",
                          "run.sh": 'export EVAL_JUDGE_API_KEY="$(cat key.txt)"\nEVAL_JUDGE_API_KEY=<your key>\n'},
                         "references only")
        result = scan(self.repo.path)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK    secrets scan: 2 commits", result.stdout)
        self.assertIn("2 key variables from .env.example", result.stdout)

    def test_ac_t14_7_planted_provider_key_fails_even_after_it_is_deleted(self):
        key = PROVIDER_PREFIX + "api03-" + secrets.token_urlsafe(40)
        self.repo.commit({"src/Config.java": 'String key = "%s";\n' % key}, "add config")
        self.repo.commit({"src/Config.java": None}, "remove config")
        result = scan(self.repo.path)
        self.assertFound(result, "provider-key", r"src/Config\.java:1")
        self.assertNotIn(key, result.stdout + result.stderr, "the report must not repeat the key")

    def test_gateway_key_literal_fails(self):
        key = "rgw_" + secrets.token_urlsafe(32)
        self.assertEqual(47, len(key))
        self.repo.commit({"docs/notes.md": "line one\nuse %s here\n" % key}, "notes")
        result = scan(self.repo.path)
        self.assertFound(result, "gateway-key", r"docs/notes\.md:2")
        self.assertNotIn(key, result.stdout)

    def test_key_variable_with_a_value_fails(self):
        self.repo.commit({".env.sample": "GATEWAY_CLAUDE_API_KEY=%s\n" % secrets.token_hex(16)}, "sample env")
        self.assertFound(scan(self.repo.path), "key-variable", r"\.env\.sample:1")

    def test_key_variable_as_json_or_default_value_fails(self):
        self.repo.commit({"conf.json": '{"EVAL_JUDGE_API_KEY": "%s"}\n' % secrets.token_hex(8),
                          "compose.yml": "KEY: ${GATEWAY_CLAUDE_API_KEY:-%s}\n" % secrets.token_hex(8)}, "conf")
        result = scan(self.repo.path)
        self.assertFound(result, "key-variable", r"conf\.json:1")
        self.assertFound(result, "key-variable", r"compose\.yml:1")

    def test_password_defaults_are_not_key_variables(self):
        self.repo.commit({"compose.yml": "POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-localdev}\n"}, "compose")
        self.assertEqual(0, scan(self.repo.path).returncode)

    def test_key_in_a_commit_message_fails(self):
        self.repo.commit({}, "oops " + PROVIDER_PREFIX + secrets.token_hex(12))
        self.assertFound(scan(self.repo.path), "provider-key", "commit message")

    def test_key_on_another_branch_is_found(self):
        self.repo.git("checkout", "-q", "-b", "side")
        self.repo.commit({"x.txt": PROVIDER_PREFIX + secrets.token_hex(12) + "\n"}, "side")
        self.repo.git("checkout", "-q", "main")
        self.assertFound(scan(self.repo.path), "provider-key", r"x\.txt:1")

    def test_not_a_repository_exits_2(self):
        empty = pathlib.Path(tempfile.mkdtemp(prefix="secrets-scan-norepo-"))
        self.addCleanup(shutil.rmtree, empty, True)
        self.assertEqual(2, scan(empty).returncode)


class SecretsScanSelfTest(unittest.TestCase):

    def test_script_never_contains_the_provider_prefix(self):
        for path in (SCRIPT, pathlib.Path(__file__)):
            with self.subTest(file=path.name):
                self.assertNotIn(PROVIDER_PREFIX, path.read_text())

    def test_ac_t14_7_this_repository_history_is_clean(self):
        result = scan(REPO)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK    secrets scan", result.stdout)

    def test_scan_does_not_match_its_own_source_in_a_copy(self):
        repo = TempRepo()
        self.addCleanup(repo.remove)
        repo.commit({"scripts/secrets_scan.sh": SCRIPT.read_text(),
                     "scripts/tests/test_secrets_scan.py": pathlib.Path(__file__).read_text()}, "copy")
        result = scan(repo.path)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
