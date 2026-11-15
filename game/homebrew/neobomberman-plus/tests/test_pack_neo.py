import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from pack_neo import NGH, pack


class NeoPackagingTest(unittest.TestCase):
    def test_layout_and_interleave(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            prom = bytearray(512)
            prom[0x108:0x10A] = NGH.to_bytes(2, "little")
            for suffix, data in {
                "p1.p1": prom, "s1.s1": b"SS", "m1.m1": b"MM",
                "v1.v1": b"VV", "c1.c1": b"abc", "c2.c2": b"ABC",
            }.items():
                (folder / ("bomberplus-" + suffix)).write_bytes(data)
            dest = folder / "game.neo"
            pack(folder, "bomberplus", dest)
            blob = dest.read_bytes()
            self.assertEqual(blob[:4], b"NEO\x01")
            self.assertEqual(struct.unpack_from("<6I", blob, 4), (512, 2, 2, 2, 0, 6))
            self.assertEqual(struct.unpack_from("<I", blob, 40)[0], NGH)
            self.assertEqual(blob[4096+512:], b"SSMMVVaAbBcC")

    def test_refuses_wrong_program_id(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            for suffix in ("p1.p1", "s1.s1", "m1.m1", "v1.v1", "c1.c1", "c2.c2"):
                (folder / ("bomberplus-" + suffix)).write_bytes(b"\0" * 512)
            with self.assertRaisesRegex(ValueError, "NGH mismatch"):
                pack(folder, "bomberplus", folder / "bad.neo")


if __name__ == "__main__":
    unittest.main()
