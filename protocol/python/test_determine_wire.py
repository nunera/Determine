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
            self.ping[:2] + b"\x02" + self.ping[3:],     # version
            self.ping[:3] + b"\x7f" + self.ping[4:],     # type
            self.ping[:-1],                               # short body
            self.ping + b"\x00",                          # long body
            self.ping[:5],                                # short header
        ]
        for pkt in bad:
            with self.subTest(pkt=pkt.hex()), self.assertRaises(w.WireError):
                w.decode(pkt)

    def test_frame_chunk_length_must_match(self):
        pkt = w.encode(w.Frame(1, 2, 320, 240, 10, 0, 0, 1, b"0123456789"), 5)
        with self.assertRaises(w.WireError):
            w.decode(pkt[:-1])
        with self.assertRaises(w.WireError):
            w.Frame(1, 2, 3, 4, 5, 0, 0, 1, b"x" * (w.MAX_CHUNK + 1)).body()


if __name__ == "__main__":
    unittest.main()
