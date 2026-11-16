"""Tests for scripts/check_readme_numbers.py (t08, AC-t08-2, -3, -4).

Run: python3 -m unittest discover -s scripts/tests -v

Fixtures under scripts/tests/fixtures/ are test data, not a measurement: the summary file says so in
``measured_on`` and no value in it was produced by a benchmark.
"""
import io
import json
import pathlib
import shutil
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
RUN_ID = "20261116T2130Z-test-data"
LABEL = "test data, not a measurement"


class CheckReadmeNumbersTest(unittest.TestCase):

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="crn-test-"))
        self.summary_dir = self.tmp / "summary"
        shutil.copytree(FIXTURES / "summary", self.summary_dir)
        self.readme_text = (FIXTURES / "README.fixture.md").read_text(encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_check(self, readme_text=None):
        readme = self.tmp / "README.md"
        readme.write_text(self.readme_text if readme_text is None else readme_text, encoding="utf-8")
        out = io.StringIO()
        code = crn.check(readme, self.summary_dir, SCHEMA, out=out)
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


if __name__ == "__main__":
    unittest.main()
