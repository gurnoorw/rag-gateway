"""Tests for scripts/eval/check_evidence.py (t13, D-53, AC-t13-1).

Run: python3 -m unittest discover -s scripts/tests -v

The label checks run on synthetic documents (no network). One test runs the committed eval set against the fetched
corpus when data/corpus/ holds it (scripts/corpus/fetch_corpus.py), and is skipped otherwise.
"""
import json
import pathlib
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))

import check_evidence as ce  # noqa: E402

PASSAGE = "Init containers always run to completion."


def question(qid, kind, gold=(), groups=None, expected=None):
    q = {"id": qid, "kind": kind, "question": "A paraphrased question for %s?" % qid,
         "expected": expected or ("refusal" if kind == "unanswerable" else "answer"),
         "gold": [{"doc_id": d, "evidence": e} for d, e in gold]}
    if groups is not None:
        q["required_groups"] = groups
    return q


def full_set():
    """A set with exactly the minimum composition, every label valid against DOCS."""
    qs = []
    for i in range(15):
        qs.append(question("q%02d" % (len(qs) + 1), "conceptual", [("concepts.pods", PASSAGE)]))
    for i in range(8):
        qs.append(question("q%02d" % (len(qs) + 1), "exact_term", [("concepts.pods", PASSAGE)]))
    for i in range(5):
        qs.append(question("q%02d" % (len(qs) + 1), "unanswerable"))
    for i in range(6):
        qs.append(question("q%02d" % (len(qs) + 1), "acl", [("setup.kubeadm", "kubeadm generates the certificates")],
                           groups=["finance"]))
    return [(n + 1, q) for n, q in enumerate(qs)]


DOCS = {
    "concepts.pods": (["eng"], "# Init\n\n" + PASSAGE + "\nEach one starts after the previous.\n"),
    "setup.kubeadm": (["eng", "finance"], "With kubeadm, kubeadm generates the certificates for you.\n"),
    "tutorials.basics": (["eng", "support"], "A tutorial page about basics.\n"),
    "concepts.unfetched": (["eng"], None),
}


class CheckEvidenceTest(unittest.TestCase):

    def failures(self, questions):
        return ce.check(questions, DOCS)[0]

    def test_a_valid_set_with_the_minimum_composition_passes(self):
        failures, counts = ce.check(full_set(), DOCS)
        self.assertEqual([], failures)
        self.assertEqual(15, counts["conceptual"])

    def test_evidence_must_be_verbatim_in_the_cleaned_text(self):
        qs = full_set()
        qs[0][1]["gold"][0]["evidence"] = "Init containers always run until completion."
        self.assertTrue(any("not verbatim" in f for f in self.failures(qs)))

    def test_evidence_length_is_20_to_400_characters(self):
        qs = full_set()
        qs[0][1]["gold"][0]["evidence"] = "Init containers"
        self.assertTrue(any("20..400 characters" in f for f in self.failures(qs)))

    def test_gold_document_must_be_in_the_manifest_and_fetched(self):
        qs = full_set()
        qs[0][1]["gold"][0]["doc_id"] = "concepts.missing"
        qs[1][1]["gold"][0]["doc_id"] = "concepts.unfetched"
        failures = self.failures(qs)
        self.assertTrue(any("not a manifest document" in f for f in failures))
        self.assertTrue(any("was not fetched" in f for f in failures))

    def test_acl_gold_must_be_hidden_from_support_and_visible_to_required_groups(self):
        qs = full_set()
        qs[-1][1]["gold"][0] = {"doc_id": "tutorials.basics", "evidence": "A tutorial page about basics."}
        qs[-2][1]["required_groups"] = ["support"]
        failures = self.failures(qs)
        self.assertTrue(any("visible to the unauthorised 'support' group" in f for f in failures))
        self.assertTrue(any("cannot see setup.kubeadm" in f for f in failures))

    def test_gold_rules_by_kind(self):
        qs = full_set()
        qs[0][1]["gold"] = []
        qs[23][1]["gold"] = [{"doc_id": "concepts.pods", "evidence": PASSAGE}]   # an unanswerable one
        qs[24][1]["expected"] = "answer"
        del qs[-1][1]["required_groups"]
        failures = self.failures(qs)
        self.assertTrue(any("needs at least one gold label" in f for f in failures))
        self.assertTrue(any("an unanswerable question has no gold" in f for f in failures))
        self.assertTrue(any("expects 'refusal'" in f for f in failures))
        self.assertTrue(any("needs required_groups" in f for f in failures))

    def test_composition_minimums(self):
        qs = [q for q in full_set() if q[1]["kind"] != "acl"]
        failures = self.failures(qs)
        self.assertTrue(any("needs at least 30" in f for f in failures))
        self.assertTrue(any("0 acl questions, needs at least 6" in f for f in failures))

    def test_cleaning_is_the_loaders(self):
        """A passage spanning a removed shortcode matches the cleaned text, never the raw file."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp = pathlib.Path(tmp)
            path = "content/en/docs/concepts/x.md"
            (tmp / "corpus" / "content/en/docs/concepts").mkdir(parents=True)
            (tmp / "corpus" / path).write_text('---\ntitle: "X"\n---\n\nPods are {{< glossary_tooltip text="small" '
                                               'term_id="pod" >}} units of compute.\n', encoding="utf-8")
            manifest = tmp / "m.tsv"
            manifest.write_text("# repo: kubernetes/website\n# commit: c\n%s\teng\n" % path, encoding="utf-8")
            docs = ce.cleaned_documents(manifest, tmp / "corpus")
            self.assertIn("Pods are small units of compute.", docs["concepts.x"][1])
            self.assertNotIn("glossary_tooltip", docs["concepts.x"][1])

    def test_main_reports_and_exits_non_zero_on_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            file = pathlib.Path(tmp) / "questions.v9.jsonl"
            file.write_text(json.dumps(question("q01", "conceptual", [("concepts.pods", PASSAGE)])) + "\n")
            self.assertEqual(1, ce.main(["--questions", str(file), "--corpus-dir", str(pathlib.Path(tmp) / "none")]))

    def test_committed_set_against_the_fetched_corpus(self):
        _, entries = ce.fetch_corpus.read_manifest(ce.fetch_corpus.DEFAULT_MANIFEST)
        if not all((ce.fetch_corpus.DEFAULT_OUT / p).is_file() for p, _ in entries):
            self.skipTest("corpus not fetched (python3 scripts/corpus/fetch_corpus.py)")
        self.assertEqual(0, ce.main([]))


if __name__ == "__main__":
    unittest.main()
