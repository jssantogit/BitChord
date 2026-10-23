"""Classify PR workload without exposing files or accepting stale HEAD evidence."""
import unittest

from tools.delivery_ci_workload import WorkloadError, classify_files, read_pr_workload


SHA = "a" * 40


class Reader:
    repository = "jssantogit/BitChord"

    def __init__(self, paths, head=SHA):
        self.head = head
        self.paths = paths
        self.pr_reads = 0
        self.file_reads = 0

    def pr(self, number):
        self.pr_reads += 1
        return {"number": number, "head": {"sha": self.head}}

    def files(self, number):
        self.file_reads += 1
        return [{"filename": p, "status": "modified"} for p in self.paths]


class WorkloadTests(unittest.TestCase):
    def test_classifies_real_workload_shapes_without_file_path_leaks(self):
        cases = [
            (["app/src/main/java/com/music/bitchord/Player.kt",
              "app/src/test/java/com/music/bitchord/PlayerTest.kt"],
             "kotlin_production"),
            (["app/src/test/java/com/music/bitchord/PlayerTest.kt",
              "docs/changes.md"], "kotlin_tests"),
            (["app/build.gradle.kts", "gradle.properties"], "gradle_configuration"),
            ([".github/workflows/check.yml", "tests/test_android_ci_queue.py",
              "docs/ci-audit.md"], "ci_workflows"),
            (["tools/delivery_metrics.py", "tests/test_delivery_metrics.py",
              "docs/audit.md"], "ci_scripts"),
            (["README.md", "docs/readme.md"], "documentation"),
            ([".github/workflows/check.yml",
              "app/src/main/java/com/music/bitchord/Player.kt"], "mixed"),
            (["assets/other.bin"], "other"),
        ]
        for paths, category in cases:
            with self.subTest(category=category):
                output = classify_files(
                    [{"filename": p, "status": "modified"} for p in paths])
                self.assertEqual(category, output["workload_class"])
                self.assertEqual(len(paths), output["changed_files"])
                for p in paths:
                    self.assertNotIn(p, str(output))
                self.assertNotIn("filename", str(output))

    def test_exact_pr_sha_attestation_and_no_branch_or_filenames(self):
        reader = Reader(["app/src/main/java/org/example/Secret.kt",
                         "app/src/test/java/org/example/SecretTest.kt"])
        out = read_pr_workload(reader, 141, SHA)
        self.assertEqual(141, out["pr_number"])
        self.assertEqual(SHA, out["head_sha"])
        self.assertEqual("kotlin_production", out["workload_class"])
        self.assertEqual(2, reader.pr_reads)
        self.assertEqual(1, reader.file_reads)
        self.assertNotIn("Secret.kt", str(out))

    def test_fail_closed_on_wrong_repository_mismatched_heads_and_drift(self):
        reader = Reader(["docs/file.md"])
        reader.repository = "attacker/another"
        with self.assertRaises(WorkloadError):
            read_pr_workload(reader, 150, SHA)
        reader.repository = "jssantogit/BitChord"
        reader.head = "b" * 40
        with self.assertRaises(WorkloadError):
            read_pr_workload(reader, 150, SHA)
        reader = Reader(["docs/file.md"])
        def drift(n):
            reader.pr_reads += 1
            return {"number": n, "head": {"sha": SHA if reader.pr_reads == 1 else "b" * 40}}
        reader.pr = drift
        with self.assertRaises(WorkloadError):
            read_pr_workload(reader, 150, SHA)

    def test_rejects_bad_pr_file_provenance_and_incomplete_pages(self):
        for data in (
            [],
            [{"filename": "/private/path.kt", "status": "added"}],
            [{"filename": "../secrets.kt", "status": "added"}],
            [{"filename": "docs/file.md", "status": "unrecognized"}],
            [{"filename": "docs/file.md", "status": "added"}] * 2,
            [{"filename": f"docs/item{i}.md", "status": "added"} for i in range(100)],
        ):
            with self.subTest(size=len(data)), self.assertRaises(WorkloadError):
                classify_files(data)
        for num, sha in [(0, SHA), (1, "bad"), (True, SHA)]:
            with self.assertRaises(WorkloadError):
                read_pr_workload(Reader(["README.md"]), num, sha)

    def test_read_failures_are_sanitized(self):
        reader = Reader(["README.md"])
        reader.files = lambda n: (_ for _ in ()).throw(RuntimeError("TOKEN=private-secret"))
        with self.assertRaises(WorkloadError) as err:
            read_pr_workload(reader, 148, SHA)
        self.assertNotIn("private-secret", str(err.exception))


if __name__ == "__main__":
    unittest.main()
