"""DI4c cross-run checkpoint envelope: recovery evidence, never write authority."""
import json
from pathlib import Path
import tempfile
import unittest
from tools.delivery_journal import initialize
from tools.delivery_transfer import TransferError, export_evidence, inspect_evidence

MAIN = "a" * 40
HEAD = "b" * 40
BATCH = {"version": 1, "repository": "jssantogit/BitChord", "target": "main",
         "items": [{"number": 90, "expected_head_sha": HEAD,
                    "expected_base_ref": "main", "depends_on": None,
                    "allowed_files": ["tests/test_delivery_journal.py"]}]}

class TransferTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "checkpoint.json"
        initialize(self.path, BATCH, MAIN, authorized=False)

    def create(self):
        return export_evidence(self.path, BATCH, run_id=123, attempt=2,
                               source_main_sha=MAIN, event="workflow_dispatch",
                               ref="refs/heads/main")

    def test_roundtrip_requires_independent_expected_digest_and_provenance(self):
        artifact, digest = self.create()
        result = inspect_evidence(artifact, BATCH, sha256=digest, run_id=123,
                                  attempt=2, expected_main_sha=MAIN)
        self.assertTrue(result["approval_required"])
        self.assertFalse(result["executable"])
        self.assertFalse(result["replay_merge"])
        self.assertEqual("require_live_revalidation", result["decision"])
        self.assertEqual([90], [r["number"] for r in result["items"]])

    def test_tampering_and_wrong_out_of_band_digest_fail(self):
        artifact, digest = self.create()
        for blob, sha in [(artifact + b" ", digest), (artifact, "f" * 64),
                          (artifact.replace(b"123", b"999", 1), digest)]:
            with self.subTest(blob=blob[:40]), self.assertRaises(TransferError):
                inspect_evidence(blob, BATCH, sha256=sha, run_id=123,
                                 attempt=2, expected_main_sha=MAIN)

    def test_incorrect_source_identity_or_manifest_never_restores(self):
        artifact, digest = self.create()
        for run, attempt, main in [(124, 2, MAIN), (123, 1, MAIN),
                                   (123, 2, "c" * 40)]:
            with self.subTest(run=run, attempt=attempt), self.assertRaises(TransferError):
                inspect_evidence(artifact, BATCH, sha256=digest, run_id=run,
                                 attempt=attempt, expected_main_sha=main)
        changed = json.loads(json.dumps(BATCH))
        changed["items"][0]["allowed_files"] = ["new-unreviewed.py"]
        with self.assertRaises(TransferError):
            inspect_evidence(artifact, changed, sha256=digest, run_id=123,
                             attempt=2, expected_main_sha=MAIN)

    def test_source_only_from_manually_dispatched_protected_main(self):
        for event, ref in [("pull_request", "refs/heads/main"),
                           ("workflow_dispatch", "refs/heads/branch")]:
            with self.assertRaises(TransferError):
                export_evidence(self.path, BATCH, run_id=123, attempt=2,
                                source_main_sha=MAIN, event=event, ref=ref)

    def test_untrusted_checkpoint_cannot_claim_executable_or_approval(self):
        artifact, digest = self.create()
        doc = json.loads(artifact)
        doc["journal"]["executable"] = True
        forged = json.dumps(doc).encode("utf-8")
        import hashlib
        forged_hash = hashlib.sha256(forged).hexdigest()
        with self.assertRaises(TransferError):
            inspect_evidence(forged, BATCH, sha256=forged_hash,
                             run_id=123, attempt=2, expected_main_sha=MAIN)

    def test_limits_and_unknown_fields_fail_closed(self):
        artifact, digest = self.create()
        with self.assertRaises(TransferError):
            inspect_evidence(artifact + b"x" * 65536, BATCH, sha256=digest,
                             run_id=123, attempt=2, expected_main_sha=MAIN)
        for bad in [True, -1, None, "123"]:
            with self.subTest(run_id=bad), self.assertRaises(TransferError):
                export_evidence(self.path, BATCH, run_id=bad, attempt=2,
                                source_main_sha=MAIN, event="workflow_dispatch",
                                ref="refs/heads/main")

if __name__ == "__main__":
    unittest.main()
