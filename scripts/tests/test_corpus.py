"""Tests for scripts/corpus/ (t09, DECISIONS.md D-47): manifest parsing, path checks, front matter and shortcode
stripping, path -> document id. No network: the fetch itself (AC-t09-11) is run by hand against
raw.githubusercontent.com.

Run: python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "corpus"))

import fetch_corpus  # noqa: E402
import load_corpus  # noqa: E402

PINNED_COMMIT = "adaa04d017c75981660828f869b364a266b0ef53"
GROUPS = {"concepts": ["eng"], "tutorials": ["eng", "support"], "setup": ["eng", "finance"]}


class ManifestTest(unittest.TestCase):

    def test_committed_manifest_is_pinned_and_grouped_per_section(self):
        header, entries = fetch_corpus.read_manifest(fetch_corpus.DEFAULT_MANIFEST)
        self.assertEqual("kubernetes/website", header["repo"])
        self.assertEqual(PINNED_COMMIT, header["commit"])
        self.assertTrue(250 <= len(entries) <= 350, len(entries))
        self.assertEqual(len(entries), len({p for p, _ in entries}), "duplicate paths")
        for path, groups in entries:
            fetch_corpus.check_path(path)
            section = path[len(fetch_corpus.PATH_PREFIX):].split("/")[0]
            self.assertEqual(GROUPS[section], groups, path)

    def test_manifest_needs_repo_and_commit_and_tab_separated_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = pathlib.Path(tmp) / "m.tsv"
            manifest.write_text("# repo: a/b\ncontent/en/docs/concepts/x.md\teng\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                fetch_corpus.read_manifest(manifest)
            manifest.write_text("# repo: a/b\n# commit: c\ncontent/en/docs/concepts/x.md eng\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                fetch_corpus.read_manifest(manifest)

    def test_paths_outside_the_docs_tree_are_refused(self):
        for bad in ("content/en/docs/../../etc/passwd.md", "/content/en/docs/a.md", "README.md",
                    "content/en/docs/concepts/x.html"):
            with self.subTest(path=bad), self.assertRaises(ValueError):
                fetch_corpus.check_path(bad)

    def test_raw_url_uses_the_pinned_commit(self):
        url = fetch_corpus.raw_url({"repo": "kubernetes/website", "commit": PINNED_COMMIT},
                                   "content/en/docs/concepts/_index.md")
        self.assertEqual("https://raw.githubusercontent.com/kubernetes/website/" + PINNED_COMMIT
                         + "/content/en/docs/concepts/_index.md", url)


class DocIdTest(unittest.TestCase):

    def test_path_maps_to_dotted_id(self):
        self.assertEqual("concepts.workloads.controllers.job",
                         load_corpus.doc_id("content/en/docs/concepts/workloads/controllers/job.md"))
        self.assertEqual("concepts._index", load_corpus.doc_id("content/en/docs/concepts/_index.md"))

    def test_every_manifest_path_gives_a_valid_unique_id(self):
        _, entries = fetch_corpus.read_manifest(fetch_corpus.DEFAULT_MANIFEST)
        ids = [load_corpus.doc_id(p) for p, _ in entries]
        self.assertEqual(len(ids), len(set(ids)))
        for i in ids:
            self.assertRegex(i, r"\A[A-Za-z0-9][A-Za-z0-9._:-]{0,199}\Z")
            self.assertNotIn("/", i)


class CleaningTest(unittest.TestCase):

    def test_yaml_front_matter_gives_the_title_and_is_removed(self):
        raw = ('---\ntitle: "Jobs"\ncontent_type: concept\nweight: 50\nhide_summary: true\n---\n\n'
               "<!-- overview -->\nA Job creates one or more Pods.\n")
        title, body = load_corpus.split_front_matter(raw)
        self.assertEqual("Jobs", title)
        self.assertNotIn("content_type", body)
        self.assertEqual("A Job creates one or more Pods.\n", load_corpus.clean(body))

    def test_nested_title_keys_are_not_the_document_title(self):
        raw = "---\nreviewers:\n  title: nested\ntitle: Top level\n---\nBody\n"
        self.assertEqual("Top level", load_corpus.split_front_matter(raw)[0])

    def test_toml_front_matter_and_no_front_matter(self):
        self.assertEqual(("Toml", "Body\n"), load_corpus.split_front_matter('+++\ntitle = "Toml"\n+++\nBody\n'))
        self.assertEqual((None, "# Plain\n"), load_corpus.split_front_matter("# Plain\n"))

    def test_shortcodes_are_removed_keeping_visible_text(self):
        body = ('A {{< glossary_tooltip text="Pod" term_id="pod" >}} runs.\n\n'
                '{{< note >}}\nKeep this text.\n{{< /note >}}\n\n'
                '{{% capture overview %}}\nOld style.\n{{% /capture %}}\n\n'
                '## {{% heading "prerequisites" %}}\n\n'
                '{{< include "task-tutorial-prereqs.md" >}} {{< version-check >}}\n')
        cleaned = load_corpus.clean(body)
        self.assertNotIn("{{", cleaned)
        self.assertNotIn("}}", cleaned)
        self.assertIn("A Pod runs.", cleaned)
        self.assertIn("Keep this text.", cleaned)
        self.assertIn("Old style.", cleaned)
        self.assertIn("## Prerequisites", cleaned)

    def test_go_templates_in_commands_are_kept(self):
        body = "```shell\nkubectl get pods -o go-template='{{range .items}}{{.metadata.name}}{{end}}'\n```\n"
        self.assertEqual(body, load_corpus.clean(body))

    def test_document_without_title_falls_back_to_first_heading(self):
        doc = load_corpus.document("content/en/docs/setup/x.md", ["eng", "finance"], "# Setting up\n\nText.\n")
        self.assertEqual({"doc_id": "setup.x", "title": "Setting up", "content": "# Setting up\n\nText.\n",
                          "acl_groups": ["eng", "finance"]}, doc)


if __name__ == "__main__":
    unittest.main()
