"""Offline audit regressions; no model or network required."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import eval_describe as evaluation


class AuditTests(unittest.TestCase):
    def test_negated_objects_not_counted(self):
        self.assertEqual(evaluation.mentions("No people or cars. A chair is ahead."), {"chair"})
        self.assertEqual(evaluation.mentions("There is not a person, but a dog is near."), {"dog"})
        self.assertEqual(evaluation.mentions("Without a person or bicycle; a wall."), {"wall"})
        self.assertEqual(evaluation.mentions("Not only a person but a chair."), {"person", "chair"})

    def test_no_person_truth_and_unknown_words(self):
        score = evaluation.audit("A person beside a sculpture.", {"objects": [], "salient": []})
        self.assertEqual(score["hallucinatedKnownObjects"], ["person"])
        self.assertEqual(score["knownObjectMentionCount"], 1)
        absent = evaluation.audit("No person is visible.", {"objects": [], "salient": []})
        self.assertEqual(absent["knownObjectHallucinationCount"], 0)

    def test_known_salient_denominator_and_aliases(self):
        score = evaluation.audit("People near a door.", {
            "objects": ["people", "doorway", "sculpture"],
            "salient": ["person", "people", "chair", "sculpture"]})
        self.assertEqual(score["salientKnownCount"], 2)
        self.assertEqual(score["salientKnownRecalledCount"], 1)
        self.assertEqual(score["knownObjectHallucinationCount"], 0)
        self.assertEqual(score["unscoredTruthLabels"], ["sculpture"])

    def test_empty_denominators_and_failures(self):
        summary = evaluation.summarize([], 0)
        self.assertIsNone(summary["knownObjectHallucination"]["rate"])
        self.assertIsNone(summary["salientRecall"]["rate"])
        self.assertIsNone(summary["roundTripMs"]["p95"])
        summary = evaluation.summarize([{"ok": False, "error": "http_502", "roundTripMs": 12}], 1)
        self.assertEqual(summary["failureCounts"], {"http_502": 1})
        self.assertEqual(summary["failedRowsExcludedFromAudit"], 1)
        self.assertEqual(summary["salientRecall"]["denominator"], 0)

    def test_failed_row_does_not_dilute_scores(self):
        score = evaluation.audit("A person.", {"objects": [], "salient": ["door"]})
        summary = evaluation.summarize([
            {"ok": True, "audit": score}, {"ok": False, "error": "timeout"}], 1)
        self.assertEqual(summary["knownObjectHallucination"], {"numerator": 1, "denominator": 1, "rate": 1.0})
        self.assertEqual(summary["salientRecall"], {"numerator": 0, "denominator": 1, "rate": 0.0})

    def test_supplied_raw_and_filtered_kept_separate(self):
        result = {}
        evaluation.score_response(result, {"text": "A wall.", "raw_text": "A person. The path is clear."},
                                  {"objects": ["wall"], "salient": ["wall"]})
        self.assertEqual(result["audit"]["knownObjectHallucinationCount"], 0)
        self.assertEqual(result["rawAudit"]["knownObjectHallucinationCount"], 1)
        self.assertTrue(result["rawAudit"]["falseReassuranceFlag"])


if __name__ == "__main__":
    unittest.main()
