import json
import pathlib
import unittest

import determine_wire as w

VECTORS = json.loads((pathlib.Path(__file__).parents[1] / "vectors" / "vectors.json").read_text())


class VectorTest(unittest.TestCase):
    def test_vectors_round_trip(self):
        for v in VECTORS:
            with self.subTest(v["name"]):
                pkt = bytes.fromhex(v["hex"])
                seq, msg = w.decode(pkt)
                self.assertEqual(seq, v["seq"])
                self.assertEqual(w.as_dict(msg), v["fields"])
                self.assertEqual(w.encode(msg, seq), pkt)


class MalformedTest(unittest.TestCase):
    def setUp(self):
        self.ping = w.encode(w.Ping(123), 1)

    def test_rejects_bad_magic_version_type_and_length(self):
        bad = [
            b"\x00\x00" + self.ping[2:],                # magic
            self.ping[:2] + b"\x01" + self.ping[3:],     # version (v1 against a v2 packet)
            self.ping[:3] + b"\x7f" + self.ping[4:],     # type
            self.ping[:-1],                               # short body
            self.ping + b"\x00",                          # long body
            self.ping[:5],                                # short header
        ]
        for pkt in bad:
            with self.subTest(pkt=pkt.hex()), self.assertRaises(w.WireError):
                w.decode(pkt)

    def test_range_is_scalar_v2(self):
        pkt = w.encode(w.Range(123, 987, w.RANGE_VALID), 2)
        self.assertEqual(len(pkt), 19)
        seq, msg = w.decode(pkt)
        self.assertEqual((msg.t_us, msg.mm, msg.status), (123, 987, w.RANGE_VALID))
        self.assertEqual(w.encode(msg, seq), pkt)

    def test_cue_and_ack_have_no_gain_fields(self):
        cue = w.Cue(1, 2, 3, w.SIDE_BOTH, 4, 200, 200, 0, 150)
        self.assertEqual(len(w.encode(cue, 3)), 32)
        ack = w.Ack(3, w.ACK_APPLIED, 9)
        self.assertEqual(len(w.encode(ack, 4)), 22)
        self.assertEqual(len(w.encode(w.Profile(w.MODE_SSD, 180), 5)), 10)

    def test_profile_haptic_max_zero_decodes_but_is_invalid(self):
        # The board must still decode it so it can ACK BAD_CUE; semantics are rejected, not the bytes.
        pkt = w.encode(w.Profile(w.MODE_STEREO, 0), 6)
        _, msg = w.decode(pkt)
        self.assertFalse(msg.valid())
        self.assertTrue(w.Profile(w.MODE_STEREO, w.HAPTIC_MAX_MIN).valid())
        self.assertFalse(w.Profile(3, 100).valid())

    def test_frame_chunk_length_must_match(self):
        pkt = w.encode(w.Frame(1, 2, 320, 240, 10, 0, 0, 1, b"0123456789"), 5)
        with self.assertRaises(w.WireError):
            w.decode(pkt[:-1])
        with self.assertRaises(w.WireError):
            w.Frame(1, 2, 3, 4, 5, 0, 0, 1, b"x" * (w.MAX_CHUNK + 1)).body()


if __name__ == "__main__":
    unittest.main()
