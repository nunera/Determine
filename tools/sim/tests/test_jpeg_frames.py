import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import jpeg as jpg  # noqa: E402
import jpegdec  # noqa: E402
from dtwire import dw  # noqa: E402
from phonelib import FrameAssembler  # noqa: E402


def psnr(a: bytes, b: bytes) -> float:
    mse = sum((x - y) ** 2 for x, y in zip(a, b)) / len(a)
    return 99.0 if mse == 0 else 10 * math.log10(255 ** 2 / mse)


class JpegEncoderTest(unittest.TestCase):
    def test_decodes_back_to_the_source_image(self):
        # 40×24 is not a multiple of 16: exercises the edge padding of 4:2:0 MCUs.
        for (w, h) in ((40, 24), (64, 48)):
            rgb = jpg.synthetic_rgb(w, h, seed=3)
            data = jpg.encode_rgb(w, h, rgb, quality=90)
            self.assertEqual(data[:2], b"\xFF\xD8")
            self.assertEqual(data[-2:], b"\xFF\xD9")
            self.assertEqual(jpg.jpeg_size(data), (w, h))
            dw_, dh, out = jpegdec.decode(data)
            self.assertEqual((dw_, dh), (w, h))
            self.assertGreater(psnr(rgb, out), 26.0, "decoded image does not match the source")

    def test_flat_colour_is_exact_enough(self):
        w, h = 32, 16
        rgb = bytes([200, 30, 60]) * (w * h)
        _, _, out = jpegdec.decode(jpg.encode_rgb(w, h, rgb, quality=95))
        for c in range(3):
            self.assertLessEqual(abs(out[c] - rgb[c]), 4)

    def test_comment_keeps_stream_valid(self):
        data = jpg.encode_rgb(16, 16, bytes(16 * 16 * 3), 75)
        tagged = jpg.with_comment(data, "frame=7")
        self.assertEqual(tagged[2:4], b"\xFF\xFE")
        self.assertIn(b"frame=7", tagged[:20])
        self.assertEqual(jpg.jpeg_size(tagged), (16, 16))
        self.assertEqual(jpegdec.decode(tagged)[:2], (16, 16))

    def test_byte_stuffing_present_when_needed(self):
        # Noise-heavy content at high quality produces 0xFF bytes in the entropy segment; any
        # unstuffed 0xFF would be read as a marker by the decoder and fail.
        rng = random.Random(5)
        rgb = bytes(rng.randrange(256) for _ in range(48 * 32 * 3))
        data = jpg.encode_rgb(48, 32, rgb, 98)
        jpegdec.decode(data)

    def test_rejects_bad_buffer(self):
        with self.assertRaises(ValueError):
            jpg.encode_rgb(4, 4, b"\x00" * 10)


class ChunkingTest(unittest.TestCase):
    def test_boundaries(self):
        for total, chunks in ((1, 1), (1400, 1), (1401, 2), (2800, 2), (2801, 3)):
            data = bytes(range(256)) * (total // 256 + 1)
            data = data[:total]
            fr = jpg.chunk_frame(9, 123, 320, 240, data)
            self.assertEqual(len(fr), chunks, total)
            self.assertEqual([f.chunk_idx for f in fr], list(range(chunks)))
            self.assertTrue(all(f.chunk_count == chunks and f.total_len == total for f in fr))
            self.assertEqual(b"".join(f.data for f in fr), data)
            off = 0
            for f in fr:
                self.assertEqual(f.offset, off)
                self.assertLessEqual(len(f.data), dw.MAX_CHUNK)
                off += len(f.data)
                pkt = dw.encode(f, 0)
                self.assertLessEqual(len(pkt), 1438)  # PROTOCOL.md FRAME ≤ 1438 total
                _, back = dw.decode(pkt)
                self.assertEqual(back, f)

    def test_reassembly_out_of_order_gives_identical_valid_jpeg(self):
        data = jpg.with_comment(jpg.make_test_jpegs(1, 160, 120)[0], "x")
        fr = jpg.chunk_frame(1, 5, 160, 120, data)
        self.assertGreater(len(fr), 1)
        random.Random(2).shuffle(fr)
        asm = FrameAssembler()
        done = [asm.add(f, 1000 + i) for i, f in enumerate(fr)]
        self.assertTrue(all(d is None for d in done[:-1]))
        self.assertEqual(done[-1].jpeg, data)
        self.assertEqual(done[-1].jpeg[:2], b"\xFF\xD8")
        self.assertEqual(done[-1].jpeg[-2:], b"\xFF\xD9")

    def test_incomplete_frame_dropped_after_150ms(self):
        fr = jpg.chunk_frame(1, 5, 1, 1, bytes(3000))
        asm = FrameAssembler()
        asm.add(fr[0], 0)
        asm.add(fr[1], 100_000)
        self.assertIsNone(asm.add(fr[2], 150_001))  # first chunk arrived 150.001 ms ago → frame gone
        self.assertEqual(asm.dropped_timeout, 1)
        self.assertEqual(asm.completed, 0)
        asm2 = FrameAssembler()
        asm2.add(fr[0], 0)
        asm2.add(fr[1], 100_000)
        self.assertIsNotNone(asm2.add(fr[2], 150_000))  # exactly at the bound still completes

    def test_inconsistent_chunks_discard_frame(self):
        a = jpg.chunk_frame(4, 5, 1, 1, bytes(2000))
        b = jpg.chunk_frame(4, 5, 1, 1, bytes(2500))
        asm = FrameAssembler()
        asm.add(a[0], 0)
        self.assertIsNone(asm.add(b[1], 1))
        self.assertEqual(asm.dropped_bad, 1)


if __name__ == "__main__":
    unittest.main()
