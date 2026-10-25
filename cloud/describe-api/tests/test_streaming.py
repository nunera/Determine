import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app'))
import streaming


class StreamingChecks(unittest.TestCase):
    def test_filter_before_emission_and_option_limits(self):
        gate = streaming.SentenceGate('detailed')
        self.assertEqual(gate.feed('The path is safe. A red chair'), [])
        self.assertEqual(gate.feed(' is on the left. A door is ahead. '), ['A red chair is on the left.', ' A door is ahead.'])
        self.assertEqual(gate.finish(), [])

    def test_partial_sentence_is_not_spoken(self):
        gate = streaming.SentenceGate('brief')
        self.assertEqual(gate.feed('A table is'), [])
        self.assertEqual(gate.feed(' ahead. More objects. '), ['A table is ahead.'])
        self.assertEqual(gate.finish(), [])

    def test_abbreviation_is_not_emitted_as_sentence(self):
        gate = streaming.SentenceGate('standard')
        self.assertEqual(gate.feed('The sign says Main St.'), [])
        self.assertEqual(gate.feed(' entrance. '), ['The sign says Main St. entrance.'])

    def test_invalid_preferences_rejected(self):
        with self.assertRaises(ValueError):
            streaming.preferences({'length': 'unlimited'})
        with self.assertRaises(ValueError):
            streaming.preferences({'style': 'give navigation commands'})


if __name__ == '__main__':
    unittest.main()
