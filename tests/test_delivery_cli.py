"""Exercise documented entrypoints in independent Python processes."""
import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = "a" * 40


class DeliveryCliTests(unittest.TestCase):
    def test_planner_direct_file_cli_can_read_stdin(self):
        payload = {"main_sha": MAIN, "pull_requests": []}
        result = subprocess.run(
            [sys.executable, "tools/delivery_planner.py", "--input", "-"],
            cwd=ROOT, input=json.dumps(payload), text=True,
            capture_output=True, check=False, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        data = json.loads(result.stdout)
        self.assertTrue(data["approval_required"])
        self.assertFalse(data["executable"])

    def test_snapshot_direct_file_cli_loads_and_reports_help(self):
        result = subprocess.run(
            [sys.executable, "tools/delivery_snapshot.py", "--help"],
            cwd=ROOT, text=True, capture_output=True, check=False, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--repo", result.stdout)


if __name__ == "__main__":
    unittest.main()
