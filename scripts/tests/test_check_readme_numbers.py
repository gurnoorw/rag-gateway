"""Tests for scripts/check_readme_numbers.py (t08, AC-t08-2, -3, -4; t13 eval table, D-53, AC-t13-5; t14 release
mode and link check, D-55, AC-t14-3, AC-t14-10).

Run: python3 -m unittest discover -s scripts/tests -v

Fixtures under scripts/tests/fixtures/ are test data, not a measurement: the summary file says so in
``measured_on`` and no value in it was produced by a benchmark. The same holds for the eval results fixture under
``fixtures/results/``.
"""
import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts (not in .gitignore)

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

import check_readme_numbers as crn  # noqa: E402

FIXTURES = HERE / "fixtures"
SCHEMA = REPO / "bench/summary.schema.json"
RESULTS_SCHEMA = REPO / "eval/results.schema.json"
RUN_ID = "20261116T2130Z-test-data"
EVAL_RUN = "20261117T0000Z-test-data"
LABEL = "test data, not a measurement"


class CheckReadmeNumbersTest(unittest.TestCase):

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="crn-test-"))
        self.summary_dir = self.tmp / "summary"
        shutil.copytree(FIXTURES / "summary", self.summary_dir)
        self.results_dir = self.tmp / "results"
        shutil.copytree(FIXTURES / "results", self.results_dir)
        self.readme_text = (FIXTURES / "README.fixture.md").read_text(encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_check(self, readme_text=None):
        readme = self.tmp / "README.md"
        readme.write_text(self.readme_text if readme_text is None else readme_text, encoding="utf-8")
        out = io.StringIO()
        code = crn.check(readme, self.summary_dir, SCHEMA, out=out, results_dir=self.results_dir,
                         results_schema_path=RESULTS_SCHEMA)
        return code, out.getvalue()

    def replaced(self, old, new):
        self.assertIn(old, self.readme_text, "fixture text to replace is missing")
        return self.readme_text.replace(old, new, 1)

    def assertFailsWith(self, text, *fragments):
        code, out = self.run_check(text)
        self.assertEqual(1, code, out)
        for fragment in fragments:
            self.assertIn(fragment, out)
        return out

    # ------------------------------------------------------------ passing cases

    def test_fixture_readme_passes_with_every_unit_and_rounding(self):
        code, out = self.run_check()
        self.assertEqual(0, code, out)
        self.assertIn("compared to a run file: 13, pending: 2", out)

    def test_repository_readme_passes_against_committed_runs(self):
        out = io.StringIO()
        code = crn.check(REPO / "README.md", REPO / "bench/summary", SCHEMA, out=out)
        self.assertEqual(0, code, out.getvalue())

    def test_fixture_summary_is_valid_against_the_schema(self):
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema not installed; the fixture is checked by the run-all schema check instead")
        schema = json.loads(SCHEMA.read_text())
        data = json.loads((FIXTURES / "summary" / f"{RUN_ID}.json").read_text())
        jsonschema.validate(data, schema)
        self.assertEqual(LABEL, data["measured_on"])

    def test_rounding_is_half_up_per_unit(self):
        self.assertEqual("12.3", crn.expected_text(12.25, "ms"))
        self.assertEqual("98.8", crn.expected_text(0.98765, "%"))
        self.assertEqual("100.0", crn.expected_text(1.0, "%"))
        self.assertEqual("0.1235", crn.expected_text(0.123456, "USD"))
        self.assertEqual("0.0051", crn.expected_text(0.00505, "USD"))
        self.assertEqual("1000", crn.expected_text(999.5, "tokens/min"))
        self.assertEqual("10234", crn.expected_text(10234, "requests"))

    def test_value_with_thousands_separator_is_accepted(self):
        code, out = self.run_check()
        self.assertEqual(0, code, out)  # the fixture writes 10,234

    def test_lists_other_numbers_for_the_reviewer_but_not_designed_for_or_checked_values(self):
        code, out = self.run_check()
        listing = out.split("for the reviewer (AC-t08-4):", 1)[1].split("OK:", 1)[0]
        self.assertIn("5, 6  ::  Some prose", listing)
        self.assertNotIn("900", listing)          # designed-for table
        self.assertNotIn("10,234", listing)       # a checked Value cell
        self.assertNotIn("12.3", listing)

    def test_macbook_row_backed_by_a_macbook_run_file_passes(self):
        data = json.loads((self.summary_dir / f"{RUN_ID}.json").read_text())
        data["run_id"] = "20261116T2200Z-test-data-macbook"
        data["measured_on"] = "test data, not a measurement (MacBook label case)"
        (self.summary_dir / "20261116T2200Z-test-data-macbook.json").write_text(json.dumps(data))
        row = ("| Overhead requests, laptop | 10,234 | requests | test data, not a measurement (MacBook label case) "
               "| 20261116T2200Z-test-data-macbook | `scenarios.overhead.requests` |\n<!-- measured:end -->")
        code, out = self.run_check(self.replaced("<!-- measured:end -->", row))
        self.assertEqual(0, code, out)

    # ------------------------------------------------------------ required failures

    def test_fails_on_mismatched_value(self):
        text = self.replaced("| 12.3 | ms |", "| 12.2 | ms |")
        self.assertFailsWith(text, "Value 12.2 ms != 12.3 ms")

    def test_fails_on_value_not_rounded_as_the_unit_says(self):
        text = self.replaced("| 12.3 | ms |", "| 12.25 | ms |")
        self.assertFailsWith(text, "Value 12.25 ms != 12.3 ms")

    def test_fails_on_missing_run_file(self):
        (self.summary_dir / f"{RUN_ID}.json").unlink()
        self.assertFailsWith(None, "run file missing")

    def test_fails_on_wrong_measured_on_label(self):
        text = self.replaced("| 12.3 | ms | test data, not a measurement |",
                             "| 12.3 | ms | cloud container, 4 vCPU / 15 GB |")
        self.assertFailsWith(text, "Measured on 'cloud container, 4 vCPU / 15 GB' differs")

    def test_fails_on_unknown_source_key(self):
        text = self.replaced("`scenarios.overhead.requests`", "`scenarios.overhead.request_count`")
        self.assertFailsWith(text, "unknown source key 'scenarios.overhead.request_count'")

    def test_fails_on_unknown_scenario(self):
        text = self.replaced("`scenarios.overhead.requests`", "`scenarios.throughput.requests`")
        self.assertFailsWith(text, "unknown source key")

    # ------------------------------------------------------------ further failures

    def test_fails_on_key_known_to_schema_but_absent_from_run_file(self):
        row = (f"| Redis refused p50 (k6 client-side) | 1.0 | ms | {LABEL} | {RUN_ID} "
               "| `scenarios.redis_down_from_start.refused.added_ms.p50` |\n<!-- measured:end -->")
        self.assertFailsWith(self.replaced("<!-- measured:end -->", row), "is not in " + RUN_ID)

    def test_fails_on_key_that_is_not_a_number(self):
        text = self.replaced("`scenarios.overhead.requests`", "`scenarios.cost_per_1k.label`")
        self.assertFailsWith(text, "does not name a number")

    def test_fails_on_run_id_inside_file_differing_from_name(self):
        path = self.summary_dir / f"{RUN_ID}.json"
        data = json.loads(path.read_text())
        data["run_id"] = "20261116T2131Z-test-data"
        path.write_text(json.dumps(data))
        self.assertFailsWith(None, "has run_id '20261116T2131Z-test-data'")

    def test_fails_on_empty_measured_on(self):
        text = self.replaced("| pending | ms | test data, not a measurement |", "| pending | ms |  |")
        self.assertFailsWith(text, "empty Measured on")

    def test_fails_on_macbook_in_a_row_whose_run_file_does_not_say_so(self):
        text = self.replaced("| 12.3 | ms | test data, not a measurement |", "| 12.3 | ms | 8 GB MacBook Air |")
        self.assertFailsWith(text, "'MacBook' outside a measured row")

    def test_fails_on_macbook_in_a_pending_row(self):
        text = self.replaced("| pending laptop run | ms | test data, not a measurement |",
                             "| pending laptop run | ms | 8 GB MacBook Air |")
        self.assertFailsWith(text, "'MacBook' outside a measured row")

    def test_fails_on_macbook_in_prose(self):
        text = self.readme_text + "\nNumbers from the 8 GB MacBook Air are faster.\n"
        self.assertFailsWith(text, "'MacBook' outside a measured row")

    def test_fails_on_pending_row_with_a_run(self):
        text = self.replaced("| pending | ms | test data, not a measurement | |",
                             f"| pending | ms | test data, not a measurement | {RUN_ID} |")
        self.assertFailsWith(text, "must have an empty Run")

    def test_fails_on_number_without_run(self):
        text = self.replaced("| pending | ms | test data, not a measurement | |",
                             "| 1.0 | ms | test data, not a measurement | |")
        self.assertFailsWith(text, "a measured value needs a Run")

    def test_fails_on_percentile_row_without_its_source(self):
        text = self.replaced("TTFB added p99 (k6 client-side)", "TTFB added p99")
        self.assertFailsWith(text, "percentile row must say")

    def test_fails_on_unit_that_does_not_fit_the_key(self):
        text = self.replaced("| 98.8 | % |", "| 98.8 | ms |")
        self.assertFailsWith(text, "unit 'ms' does not fit")

    def test_fails_on_unknown_unit(self):
        text = self.replaced("| 12.3 | ms |", "| 12.3 | msec |")
        self.assertFailsWith(text, "unknown unit 'msec'")

    def test_fails_on_wrong_table_header(self):
        text = self.replaced("| Metric | Value | Unit | Measured on | Run | Source key |",
                             "| Metric | Value | Unit | Run | Source key |")
        self.assertFailsWith(text, "measured table header must be")

    def test_fails_without_markers(self):
        text = self.readme_text.replace("<!-- measured:begin -->", "")
        self.assertFailsWith(text, "measured:begin")

    def test_fails_on_array_selector_that_matches_no_run(self):
        text = self.replaced("runs[n=1000]", "runs[n=4000]")
        self.assertFailsWith(text, "is not in " + RUN_ID)


    # ------------------------------------------------------------ eval table (t13)

    def eval_file(self, run=EVAL_RUN):
        return self.results_dir / f"{run}.json"

    def write_eval_variant(self, run, **changes):
        data = json.loads(self.eval_file().read_text())
        data["run_id"] = run
        for key, value in changes.items():
            data[key] = value
        self.eval_file(run).write_text(json.dumps(data))
        return data

    def test_eval_fixture_passes_with_every_eval_unit(self):
        code, out = self.run_check()
        self.assertEqual(0, code, out)
        self.assertIn("eval rows: 6, compared to a results file: 4, pending: 2", out)

    def test_eval_fixture_results_file_is_valid_against_the_schema(self):
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema not installed; the writer schema-checks real results files")
        jsonschema.validate(json.loads(self.eval_file().read_text()), json.loads(RESULTS_SCHEMA.read_text()))

    def test_eval_fails_on_mismatched_value(self):
        text = self.replaced("| 0.429 | fraction |", "| 0.428 | fraction |")
        self.assertFailsWith(text, "Value 0.428 fraction != 0.429 fraction")

    def test_eval_fails_without_its_markers_or_with_the_wrong_header(self):
        self.assertFailsWith(self.readme_text.replace("<!-- eval:begin -->", ""), "eval:begin")
        text = self.replaced("| Metric | Value | Unit | Measured on | Embedder | Run | Source key |",
                             "| Metric | Value | Unit | Measured on | Run | Source key |")
        self.assertFailsWith(text, "eval table header must be")

    def test_ac_t13_5_a_hybrid_value_from_a_keyword_only_file_fails(self):
        row = ("| Recall@5, hybrid, 400 tokens | 0.500 | fraction | test data, not a measurement | nomic-embed-text "
               f"| {EVAL_RUN} | `retrieval[mode=hybrid,chunk_target_tokens=400].recall_at_5` |")
        text = self.replaced("| Recall@5, hybrid, 400 tokens | pending laptop run | fraction | test data, not a "
                             "measurement | nomic-embed-text | | `retrieval[mode=hybrid,chunk_target_tokens=400]"
                             ".recall_at_5` |", row)
        self.assertFailsWith(text, "needs a nomic-embed-text results file", "Embedder 'nomic-embed-text' differs")

    def test_ac_t13_5_a_hybrid_row_must_name_the_real_embedder_even_while_pending(self):
        text = self.replaced("nomic-embed-text | | `retrieval[mode=hybrid", "none | | `retrieval[mode=hybrid")
        self.assertFailsWith(text, "needs Embedder nomic-embed-text, got 'none'")

    def test_ac_t13_5_the_fake_embedder_is_never_an_eval_embedder(self):
        text = self.replaced("| 0.429 | fraction | test data, not a measurement | none |",
                             "| 0.429 | fraction | test data, not a measurement | fake |")
        self.assertFailsWith(text, "Embedder must be one of nomic-embed-text, none, got 'fake'")

    def test_ac_t13_5_a_hybrid_value_from_a_real_embedder_file_passes(self):
        data = json.loads(self.eval_file().read_text())
        rows = data["retrieval"] + [dict(data["retrieval"][1], mode="hybrid", recall_at_5=0.75)]
        self.write_eval_variant("20261117T0001Z-test-data-model", embedder={"kind": "nomic-embed-text"},
                                retrieval=rows)
        row = ("| Recall@5, hybrid, 400 tokens | 0.750 | fraction | test data, not a measurement | nomic-embed-text "
               "| 20261117T0001Z-test-data-model | `retrieval[mode=hybrid,chunk_target_tokens=400].recall_at_5` |")
        text = self.replaced("| Recall@5, hybrid, 400 tokens | pending laptop run | fraction | test data, not a "
                             "measurement | nomic-embed-text | | `retrieval[mode=hybrid,chunk_target_tokens=400]"
                             ".recall_at_5` |", row)
        code, out = self.run_check(text)
        self.assertEqual(0, code, out)

    def test_ac_t13_5_an_answer_value_needs_a_judge_section(self):
        self.write_eval_variant("20261117T0002Z-test-data-model", embedder={"kind": "nomic-embed-text"})
        text = self.replaced("| Answers correct | pending laptop run | fraction | test data, not a measurement "
                             "| nomic-embed-text | |",
                             "| Answers correct | 0.500 | fraction | test data, not a measurement "
                             "| nomic-embed-text | 20261117T0002Z-test-data-model |")
        self.assertFailsWith(text, "needs an answers.judge section")

    def test_ac_t13_5_an_acl_value_from_a_hybrid_acl_run_needs_the_real_embedder(self):
        data = json.loads(self.eval_file().read_text())
        self.write_eval_variant("20261117T0003Z-test-data", acl=dict(data["acl"], mode="hybrid"))
        text = self.replaced(f"| 0 | chunks | test data, not a measurement | none | {EVAL_RUN} |",
                             "| 0 | chunks | test data, not a measurement | none | 20261117T0003Z-test-data |")
        self.assertFailsWith(text, "needs a nomic-embed-text results file")

    def test_eval_fails_when_the_embedder_column_differs_from_the_file(self):
        text = self.replaced("| 0.429 | fraction | test data, not a measurement | none |",
                             "| 0.429 | fraction | test data, not a measurement | nomic-embed-text |")
        self.assertFailsWith(text, "Embedder 'nomic-embed-text' differs")

    def test_eval_fails_on_unknown_key_unit_and_missing_file(self):
        self.assertFailsWith(self.replaced(".recall_at_5` |", ".recall_at_7` |"), "unknown source key")
        self.assertFailsWith(self.replaced("| 0.429 | fraction |", "| 0.429 | ms |"), "unknown unit 'ms'")
        self.assertFailsWith(self.replaced("| 6 | questions |", "| 6 | fraction |"), "unit 'fraction' does not fit")
        self.eval_file().unlink()
        self.assertFailsWith(None, "results file missing")

    def test_selector_with_two_fields_picks_one_row(self):
        parts = crn.parse_key("retrieval[mode=keyword,chunk_target_tokens=200].mrr")
        data = json.loads(self.eval_file().read_text())
        self.assertEqual(0.2135, crn.value_at(data, parts))
        self.assertIsNone(crn.parse_key("retrieval[mode=keyword,,x=1].mrr"))
        self.assertEqual("0.429", crn.expected_text(0.4285, "fraction", crn.EVAL_UNITS))
        self.assertEqual("21.4", crn.expected_text(0.2135, "%", crn.EVAL_UNITS))

    # ------------------------------------------------------------ release mode (t14, D-55, AC-t14-3)

    # The fixture's pending rows: two in the measured table, two in the eval table.
    PENDING_METRICS = [
        "Cache-hit p99 (k6 client-side)",
        "Log insert p99 (bucket edges 0.5, 1, 2, 5 ms)",
        "Recall@5, hybrid, 400 tokens",
        "Answers correct",
    ]

    def run_release(self, allowlist_lines, readme_text=None):
        allowlist = self.tmp / "allowlist.txt"
        allowlist.write_text("".join(line + "\n" for line in allowlist_lines), encoding="utf-8")
        readme = self.tmp / "README.md"
        readme.write_text(self.readme_text if readme_text is None else readme_text, encoding="utf-8")
        out = io.StringIO()
        code = crn.check(readme, self.summary_dir, SCHEMA, out=out, results_dir=self.results_dir,
                         results_schema_path=RESULTS_SCHEMA, release=True, allowlist_path=allowlist)
        return code, out.getvalue()

    def test_ac_t14_3_release_fails_on_every_pending_cell_with_an_empty_allowlist(self):
        code, out = self.run_release(["# names nothing"])
        self.assertEqual(1, code, out)
        for metric in self.PENDING_METRICS:
            self.assertIn(f"blocks the release: '{metric}'", out)
        self.assertIn("pending measured cell", out)
        self.assertIn("pending eval cell", out)
        self.assertIn("release mode: 4 pending cell(s), 0 on the allowlist, 4 blocking", out)

    def test_ac_t14_3_release_fails_on_the_one_pending_cell_left_off_the_allowlist(self):
        code, out = self.run_release(self.PENDING_METRICS[:3])
        self.assertEqual(1, code, out)
        self.assertIn("blocks the release: 'Answers correct'", out)
        self.assertEqual(1, out.count("blocks the release"), out)

    def test_ac_t14_3_release_passes_when_every_pending_cell_is_listed(self):
        code, out = self.run_release(["# owner decision, D-55", ""] + self.PENDING_METRICS)
        self.assertEqual(0, code, out)
        self.assertIn("release mode: 4 pending cell(s), 4 on the allowlist, 0 blocking", out)

    def test_normal_mode_lets_pending_cells_pass_without_an_allowlist(self):
        code, out = self.run_check()
        self.assertEqual(0, code, out)
        self.assertNotIn("release mode", out)

    def test_release_allowlist_matches_the_metric_exactly(self):
        listed = ["cache-hit p99 (k6 client-side)"] + self.PENDING_METRICS[1:]
        code, out = self.run_release(listed)
        self.assertEqual(1, code, out)
        self.assertIn("blocks the release: 'Cache-hit p99 (k6 client-side)'", out)
        self.assertIn("notice: allowlist entry matches no pending row: 'cache-hit p99 (k6 client-side)'", out)

    def test_release_fails_on_two_pending_rows_with_the_same_metric(self):
        row = ("| Answers correct | pending laptop run | fraction | test data, not a measurement | nomic-embed-text | "
               "| `answers.grounded_fraction` |\n<!-- eval:end -->")
        code, out = self.run_release(self.PENDING_METRICS, self.replaced("<!-- eval:end -->", row))
        self.assertEqual(1, code, out)
        self.assertIn("pending rows share the Metric 'Answers correct'", out)

    def test_release_still_runs_every_number_check(self):
        code, out = self.run_release(self.PENDING_METRICS, self.replaced("| 12.3 | ms |", "| 12.2 | ms |"))
        self.assertEqual(1, code, out)
        self.assertIn("Value 12.2 ms != 12.3 ms", out)

    def test_main_release_without_the_allowlist_file_is_a_usage_error(self):
        code = crn.main(["--readme", str(FIXTURES / "README.fixture.md"), "--summary-dir", str(self.summary_dir),
                         "--results-dir", str(self.results_dir), "--release",
                         "--allowlist", str(self.tmp / "no-such-allowlist.txt")])
        self.assertEqual(2, code)

    def test_repository_allowlist_exists_and_its_comments_name_no_row(self):
        # Entries are the owner's decision (D-55), so only the file's presence and format are asserted here.
        self.assertTrue(crn.ALLOWLIST.is_file())
        self.assertFalse(any(e.startswith("#") for e in crn.read_allowlist(crn.ALLOWLIST)))


class LinkCheckTest(unittest.TestCase):
    """AC-t14-10: every relative link in the README and the runbook resolves to a committed file."""

    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp(prefix="crn-links-"))
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.write("docs/HLD.md", "# HLD\n")
        self.write("docs/process/DECISIONS.md", "# Decisions\n")
        self.write("eval/README.md", "# Eval\n")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, rel, text, track=True):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        if track:
            subprocess.run(["git", "-C", str(self.root), "add", rel], check=True)
        return path

    def check(self, readme, runbook="# Runbook\n"):
        files = [self.write("README.md", readme), self.write("docs/RUNBOOK.md", runbook)]
        failures = crn.Failures()
        checked = crn.check_links(files, self.root, failures)
        return checked, failures.items

    GOOD = (
        "# Title\n\n"
        "[HLD](docs/HLD.md), [deep dive](docs/HLD.md#deep-dives), [eval dir](eval/), [status](#status),\n"
        "[root-relative](/docs/process/DECISIONS.md), [site](https://example.com/x.md), <mailto:a@example.com>\n"
        "<a href=\"docs/HLD.md\">html</a>\n\n"
        "[ref]: docs/process/DECISIONS.md\n\n"
        "```bash\ncat [x](missing-in-code.md)\n```\n\n"
        "Inline `[x](missing-in-inline-code.md)` and <!-- [x](missing-in-comment.md) --> are not links.\n"
    )

    def test_ac_t14_10_resolving_links_pass_and_code_comments_and_urls_are_skipped(self):
        checked, failures = self.check(self.GOOD, "See [decisions](process/DECISIONS.md) and [eval](../eval/README.md).\n")
        self.assertEqual([], failures)
        self.assertEqual(8, checked)  # 6 in the README, 2 in the runbook

    def test_ac_t14_10_fails_on_a_broken_relative_link_in_the_readme(self):
        _, failures = self.check(self.GOOD + "\n[gone](docs/GONE.md)\n")
        self.assertEqual(1, len(failures), failures)
        self.assertIn("README.md line 15: link 'docs/GONE.md' does not resolve to a committed file", failures[0])

    def test_ac_t14_10_fails_on_a_broken_relative_link_in_the_runbook(self):
        # Relative to the runbook's own directory: docs/docs/HLD.md does not exist.
        _, failures = self.check(self.GOOD, "Read [the HLD](HLD.md), not [this](docs/HLD.md).\n")
        self.assertEqual(1, len(failures), failures)
        self.assertIn("docs/RUNBOOK.md line 1: link 'docs/HLD.md' does not resolve", failures[0])

    def test_ac_t14_10_a_file_that_exists_but_is_not_committed_fails(self):
        self.write("docs/notes.md", "draft\n", track=False)
        _, failures = self.check("[notes](docs/notes.md)\n")
        self.assertEqual(1, len(failures), failures)
        self.assertIn("does not resolve to a committed file (docs/notes.md)", failures[0])

    def test_ac_t14_10_a_link_outside_the_repository_fails(self):
        _, failures = self.check("[up](../outside.md)\n")
        self.assertIn("points outside the repository", failures[0])

    def test_ac_t14_10_reference_definitions_and_html_links_are_checked(self):
        _, failures = self.check("[x][r]\n\n[r]: docs/NOPE.md\n\n<a href=\"docs/ALSO-NOPE.md\">y</a>\n")
        self.assertEqual(2, len(failures), failures)

    def test_ac_t14_10_a_missing_image_fails_and_passes_once_committed(self):
        readme = "![Grafana dashboard](docs/img/grafana-dashboard.png)\n<img src=\"docs/img/other.png\" alt=\"x\">\n"
        _, failures = self.check(readme)
        self.assertEqual(2, len(failures), failures)
        self.assertIn("image 'docs/img/grafana-dashboard.png' is missing", failures[0])
        self.assertIn("image 'docs/img/other.png' is missing", failures[1])
        self.write("docs/img/grafana-dashboard.png", "png")
        self.write("docs/img/other.png", "png")
        _, failures = self.check(readme)
        self.assertEqual([], failures)

    def test_release_mode_fails_on_a_readme_image_whose_file_is_missing(self):
        fixture = (FIXTURES / "README.fixture.md").read_text(encoding="utf-8")
        readme = self.write("README.md", fixture + "\n![Grafana dashboard](docs/img/grafana-dashboard.png)\n")
        runbook = self.write("docs/RUNBOOK.md", "# Runbook\n")
        allowlist = self.write("allowlist.txt", "".join(m + "\n" for m in CheckReadmeNumbersTest.PENDING_METRICS))
        args = dict(results_dir=FIXTURES / "results", results_schema_path=RESULTS_SCHEMA, release=True,
                    allowlist_path=allowlist, link_files=[readme, runbook], repo_root=self.root)
        out = io.StringIO()
        code = crn.check(readme, FIXTURES / "summary", SCHEMA, out=out, **args)
        self.assertEqual(1, code, out.getvalue())
        self.assertIn("image 'docs/img/grafana-dashboard.png' is missing", out.getvalue())
        self.write("docs/img/grafana-dashboard.png", "png")
        out = io.StringIO()
        code = crn.check(readme, FIXTURES / "summary", SCHEMA, out=out, **args)
        self.assertEqual(0, code, out.getvalue())

    def test_without_a_git_repository_the_link_check_fails_instead_of_passing(self):
        plain = pathlib.Path(tempfile.mkdtemp(prefix="crn-nogit-"))
        try:
            (plain / "README.md").write_text("[x](y.md)\n", encoding="utf-8")
            failures = crn.Failures()
            env_dir = os.environ.get("GIT_CEILING_DIRECTORIES")
            os.environ["GIT_CEILING_DIRECTORIES"] = str(plain.parent)
            try:
                crn.check_links([plain / "README.md"], plain, failures)
            finally:
                if env_dir is None:
                    os.environ.pop("GIT_CEILING_DIRECTORIES")
                else:
                    os.environ["GIT_CEILING_DIRECTORIES"] = env_dir
            self.assertIn("the link check did not run", failures.items[0])
        finally:
            shutil.rmtree(plain, ignore_errors=True)

    def test_ac_t14_10_repository_readme_and_runbook_links_resolve(self):
        failures = crn.Failures()
        checked = crn.check_links([REPO / "README.md", REPO / "docs/RUNBOOK.md"], REPO, failures)
        self.assertEqual([], failures.items)
        self.assertGreater(checked, 0)


if __name__ == "__main__":
    unittest.main()
