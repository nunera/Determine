import unittest

import support  # noqa: F401  (sets sys.path)
import safety


class SplitSentencesTest(unittest.TestCase):
    def test_abbreviations_do_not_split(self):
        self.assertEqual(
            safety.split_sentences("Dr. Lee's office door is on the left. A step is ahead."),
            ["Dr. Lee's office door is on the left.", "A step is ahead."],
        )
        self.assertEqual(
            safety.split_sentences("A sign says approx. 20 m to Main St. Station. A bench is right."),
            ["A sign says approx. 20 m to Main St. Station.", "A bench is right."],
        )
        self.assertEqual(
            safety.split_sentences("A U.S. Route 1 sign is ahead. Cars are parked on the right."),
            ["A U.S. Route 1 sign is ahead.", "Cars are parked on the right."],
        )
        self.assertEqual(
            safety.split_sentences("Doors, chairs, etc. are on the left. A wall is ahead."),
            ["Doors, chairs, etc. are on the left.", "A wall is ahead."],
        )

    def test_decimals_do_not_split(self):
        self.assertEqual(
            safety.split_sentences("A step is 1.5 m ahead. A pole is 0.5 m to the right."),
            ["A step is 1.5 m ahead.", "A pole is 0.5 m to the right."],
        )

    def test_unit_letter_before_period_still_splits(self):
        self.assertEqual(
            safety.split_sentences("A pole stands about 2 m. A door is on the right."),
            ["A pole stands about 2 m.", "A door is on the right."],
        )

    def test_question_exclamation_quotes_and_lines(self):
        self.assertEqual(
            safety.split_sentences('A sign reads "Exit." Stairs go down on the left! Is that a door?'),
            ['A sign reads "Exit."', "Stairs go down on the left!", "Is that a door?"],
        )
        self.assertEqual(
            safety.split_sentences("- A door on the left\n- Steps going up ahead\n\n1. A bin on the right"),
            ["A door on the left", "Steps going up ahead", "A bin on the right"],
        )

    def test_chat_role_prefix_removed(self):
        self.assertEqual(safety.split_sentences("Assistant: A door is ahead."), ["A door is ahead."])

    def test_empty(self):
        self.assertEqual(safety.split_sentences("   \n "), [])


class KeepAndTrimTest(unittest.TestCase):
    def test_keeps_first_two_sentences(self):
        result = safety.apply_filter("A door is left. A bench is right. A bin is ahead.")
        self.assertEqual(result.sentences, ["A door is left.", "A bench is right."])
        self.assertTrue(result.truncated)
        self.assertEqual(result.candidates, 3)

    def test_trims_to_240_at_word_boundary(self):
        first = "A long corridor runs ahead with " + "wooden doors on both sides " * 6 + "and a window at the end."
        second = "A staircase goes up on the right beside " + "a row of tall potted plants " * 6 + "near it."
        raw = f"{first} {second}"
        result = safety.apply_filter(raw)
        self.assertLessEqual(len(result.text), safety.MAX_CHARS)
        self.assertTrue(result.truncated)
        # Every kept piece is a word-aligned prefix of the original text.
        self.assertTrue(raw.startswith(result.text))
        self.assertIn(raw[len(result.text)], " ")
        self.assertFalse(result.text.endswith(" "))

    def test_exactly_240_chars_is_not_trimmed(self):
        sentence = ("x" * 9 + " ") * 23 + "y" * 9 + "."  # 230 + 10 = 240 chars
        self.assertEqual(len(sentence), 240)
        result = safety.apply_filter(sentence)
        self.assertEqual(result.text, sentence)
        self.assertFalse(result.truncated)

    def test_single_overlong_token_is_hard_cut(self):
        result = safety.apply_filter("A" + "b" * 400)
        self.assertEqual(len(result.text), safety.MAX_CHARS)

    def test_second_sentence_dropped_when_budget_spent(self):
        first = "A " + "very " * 47 + "long hallway."  # 250 chars
        result = safety.apply_filter(first + " A door is on the left.")
        self.assertEqual(len(result.sentences), 1)
        self.assertLessEqual(len(result.text), 240)


class ReassuranceTest(unittest.TestCase):
    def test_contract_terms_dropped(self):
        for sentence in [
            "The path ahead is clear.",
            "It is safe to walk forward.",
            "There is nothing ahead.",
            "There are no obstacles in front of you.",
            "You are on an empty path.",
            "All clear.",
            "The sidewalk is free of obstacles.",
        ]:
            with self.subTest(sentence=sentence):
                result = safety.apply_filter(f"A wall is on the left. {sentence}")
                self.assertEqual(result.sentences, ["A wall is on the left."])
                self.assertEqual(result.dropped_reassurance, 1)

    def test_paraphrased_absence_dropped(self):
        for sentence in [
            "The walkway is unobstructed.",
            "Nobody is around.",
            "There's no step here.",
            "No people are nearby.",
            "You can walk straight ahead.",
            "The way ahead looks open.",
            "You can safely continue.",
        ]:
            with self.subTest(sentence=sentence):
                self.assertTrue(safety.asserts_safety_or_absence(sentence))

    def test_hazard_words_that_contain_deny_terms_are_kept(self):
        for sentence in [
            "A low clearance sign hangs above the door.",
            "An unsafe ledge is on the right.",
            "A doorway is open on your right.",
            "A door is open ahead.",
        ]:
            with self.subTest(sentence=sentence):
                self.assertFalse(safety.asserts_safety_or_absence(sentence))

    def test_filter_order_drops_after_keeping_two(self):
        # Step 1 keeps the first two sentences before step 2 drops; sentence 3 never comes back.
        result = safety.apply_filter("The path is clear. A door is on the left. A bin is ahead.")
        self.assertEqual(result.sentences, ["A door is on the left."])


class PersonAttributeTest(unittest.TestCase):
    def test_attribute_guesses_dropped(self):
        for sentence in [
            "A man stands on the left.",
            "A young person waits ahead.",
            "An elderly person sits on the bench.",
            "A 30-year-old person is ahead.",
            "An Asian person walks on the right.",
            "A black person is ahead.",
            "A person looks angry.",
            "A person is smiling near the door.",
            "She is near the wall.",
            "A police officer stands ahead.",
            "A person is trying to cross.",
        ]:
            with self.subTest(sentence=sentence):
                result = safety.apply_filter(f"A wall is on the left. {sentence}")
                self.assertEqual(result.sentences, ["A wall is on the left."])
                self.assertEqual(result.dropped_person, 1)

    def test_neutral_mentions_kept(self):
        for sentence in [
            "A person is farther ahead near the wall.",
            "People are standing on the right.",
            "A white car is parked on the right.",
            "A manhole cover is ahead.",
            "There is a black pole on the left.",
            "A guard rail runs along the left side.",
        ]:
            with self.subTest(sentence=sentence):
                self.assertFalse(safety.guesses_person_attribute(sentence))


class ContractExampleTest(unittest.TestCase):
    def test_contract_example_passes_unchanged(self):
        text = "A doorway is open on your right. A person is farther ahead near the wall."
        result = safety.apply_filter(text)
        self.assertEqual(result.text, text)
        self.assertEqual(
            result.sentences, ["A doorway is open on your right.", "A person is farther ahead near the wall."]
        )

    def test_everything_dropped_leaves_nothing(self):
        result = safety.apply_filter("The path is clear. A woman is smiling.")
        self.assertEqual(result.sentences, [])
        self.assertEqual(result.text, "")

    def test_prompt_is_contract_text(self):
        self.assertTrue(safety.PROMPT.startswith("You are describing a photo taken from a blind pedestrian's"))
        self.assertTrue(safety.PROMPT.endswith("Never say the path is clear, safe or empty."))
        self.assertIn('Mention people only as "a person" or "people"', safety.PROMPT)


if __name__ == "__main__":
    unittest.main()
