import hashlib
import importlib.util
import json
import tempfile
import unittest
import wave
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'tools' / 'a0_fixture.py'


class FixtureTest(unittest.TestCase):
    def load(self):
        spec = importlib.util.spec_from_file_location('a0_fixture', SOURCE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_deterministic_audio_and_manifest(self):
        mod = self.load()
        with tempfile.TemporaryDirectory() as temp:
            first = Path(temp) / 'first'
            second = Path(temp) / 'second'
            p, meta = mod.generate(first)
            q, other = mod.generate(second)
            self.assertEqual(p.read_bytes(), q.read_bytes())
            self.assertEqual(meta.read_bytes(), other.read_bytes())
            self.assertEqual(hashlib.sha256(p.read_bytes()).hexdigest(), json.loads(meta.read_text())['sha256'])
            self.assertTrue(mod.verify(first))
            with wave.open(str(p), 'rb') as wav:
                self.assertEqual(wav.getnframes(), 16000)
                self.assertEqual(wav.getframerate(), 8000)
                self.assertEqual(wav.getsampwidth(), 2)
                self.assertEqual(wav.getnchannels(), 1)

    def test_corruption_fails_verification(self):
        mod = self.load()
        with tempfile.TemporaryDirectory() as temp:
            p, _ = mod.generate(Path(temp))
            data = bytearray(p.read_bytes())
            data[-1] ^= 1
            p.write_bytes(data)
            self.assertFalse(mod.verify(Path(temp)))

    def test_refuses_to_overwrite_owned_fixture(self):
        mod = self.load()
        with tempfile.TemporaryDirectory() as temp:
            mod.generate(Path(temp))
            with self.assertRaises(FileExistsError):
                mod.generate(Path(temp))

    def test_missing_fixture_fails_cleanly(self):
        mod = self.load()
        with tempfile.TemporaryDirectory() as temp:
            self.assertFalse(mod.verify(Path(temp)))


if __name__ == '__main__':
    unittest.main()
