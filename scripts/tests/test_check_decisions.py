"""Tests for scripts/check_decisions.py (t14, AC-t14-5).

The repository itself must pass; small generated trees show that each kind of drift fails.

Run: python3 -m unittest discover -s scripts/tests -v
"""
import contextlib
import io
import pathlib
import shutil
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

import check_decisions  # noqa: E402


def decisions_text(drop_section=(), drop_index=(), extra_sections=(), tenant_row="C16", chunks_row="C1, C17"):
    index = ["## Index", "", "| id | Topic | Decision |", "|---|---|---|"]
    index += ["| C%d | t | d |" % n for n in range(1, 28) if "C%d" % n not in drop_index]
    index += ["| %s | t | d |" % d for d in ("D-01", "D-14", "D-47") if d not in drop_index]
    body = ["## Part 1"]
    for n in range(1, 28):
        if "C%d" % n not in drop_section:
            body += ["### C%d. Conflict %d" % (n, n), "- **Decision:** see D-01."]
    body += ["## Part 2"]
    if "D-01" not in drop_section:
        body += ["### D-01. Build tool", "- text"]
    if "D-14" not in drop_section:
        body += ["### D-14. Changes to LLD contracts", "| LLD | Now | Why |", "|---|---|---|",
                 "| `Tenant(id, tpm, budget, aclGroups)` | groups on keys | %s |" % tenant_row,
                 "| `Retriever.retrieve(collection, question, groups, topK)` | `retrieve(RetrievalQuery)` | C17 |",
                 "| `chunks(chunk_id, vector(1024))` | `tenant_id`, `vector(768)` | %s |" % chunks_row]
    if "D-47" not in drop_section:
        body += ["### D-47. Ingestion", "- text", "#### D-47 amendment: the claim", "- overrides the LLD (D-47)"]
    body += list(extra_sections)
    return "\n".join(["# Architecture decisions", ""] + index + [""] + body) + "\n"


class Tree:
    def __init__(self, decisions=None, lld="The pick runs in a materialised CTE (the D-47 amendment).\n",
                 readme="See C3 and D-14; ranges C1–C27 and D-01–D-47.\n"):
        self.root = pathlib.Path(tempfile.mkdtemp(prefix="check-decisions-test-"))
        (self.root / "docs/process").mkdir(parents=True)
        (self.root / "docs/process/DECISIONS.md").write_text(decisions if decisions is not None else decisions_text())
        (self.root / "docs/LLD.md").write_text(lld)
        (self.root / "README.md").write_text(readme)

    def problems(self):
        return check_decisions.check(self.root)[0]

    def remove(self):
        shutil.rmtree(self.root, ignore_errors=True)


class CheckDecisionsTest(unittest.TestCase):

    def tree(self, **kwargs):
        tree = Tree(**kwargs)
        self.addCleanup(tree.remove)
        return tree

    def test_ac_t14_5_repository_passes(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = check_decisions.main([])
        self.assertEqual(0, code, out.getvalue())
        self.assertIn("OK    decisions index: C1-C27", out.getvalue())

    def test_generated_tree_passes(self):
        self.assertEqual([], self.tree().problems())

    def test_missing_conflict_section_fails(self):
        problems = self.tree(decisions=decisions_text(drop_section=("C12",))).problems()
        self.assertIn("C12 has no '### C12.' section", problems)

    def test_missing_conflict_index_row_fails(self):
        self.assertIn("C27 has no index row", self.tree(decisions=decisions_text(drop_index=("C27",))).problems())

    def test_indexed_decision_without_section_fails(self):
        problems = self.tree(decisions=decisions_text(drop_section=("D-01",))).problems()
        self.assertIn("D-01 is in the index but has no '### D-01.' section", problems)

    def test_section_without_index_row_fails(self):
        problems = self.tree(decisions=decisions_text(extra_sections=["### D-99. Unindexed", "- text"])).problems()
        self.assertIn("D-99 has a section but no index row", problems)

    def test_conflict_outside_the_fixed_range_fails(self):
        problems = self.tree(decisions=decisions_text(extra_sections=["### C28. Extra", "- text"])).problems()
        self.assertIn("C28 is outside C1-C27", problems)

    def test_duplicate_section_fails(self):
        problems = self.tree(decisions=decisions_text(extra_sections=["### D-14. Again", "- text"])).problems()
        self.assertIn("D-14 section appears more than once", problems)

    def test_reference_to_a_missing_entry_fails(self):
        problems = self.tree(readme="line\nSee D-56 for that.\n").problems()
        self.assertIn("README.md:2 refers to D-56, which has no section in docs/process/DECISIONS.md", problems)

    def test_range_end_is_checked(self):
        problems = self.tree(readme="D-01–D-60\n").problems()
        self.assertTrue(any("refers to D-60" in p for p in problems), problems)

    def test_ids_inside_other_tokens_are_not_references(self):
        self.assertEqual([], self.tree(readme="AC-t14-5, G11, RFC1, D-01x, xC3\n").problems())

    def test_lld_claim_sql_must_name_its_entry(self):
        problems = self.tree(lld="The pick runs in a materialised CTE.\n").problems()
        self.assertTrue(any("outbox claim SQL" in p and "does not name D-47" in p for p in problems), problems)

    def test_groups_on_keys_row_must_name_c16(self):
        problems = self.tree(decisions=decisions_text(tenant_row="t06")).problems()
        self.assertTrue(any("ACL groups on API keys" in p and "C16" in p for p in problems), problems)

    def test_chunks_row_must_name_tenant_and_dimension_entries(self):
        problems = self.tree(decisions=decisions_text(chunks_row="t09")).problems()
        self.assertTrue(any("tenant columns on documents and chunks" in p for p in problems), problems)
        self.assertTrue(any("768-dimension embeddings" in p for p in problems), problems)

    def test_main_prints_fail_lines_and_exits_1(self):
        tree = self.tree(readme="D-77\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = check_decisions.main(["--root", str(tree.root)])
        self.assertEqual(1, code)
        self.assertIn("FAIL  decisions index: 1 problem(s)", out.getvalue())


if __name__ == "__main__":
    unittest.main()
