"""Contract tests for owner-only issue commands and read-only contributors preview."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLOSE = ROOT / ".github/workflows/close-issue.yml"
UPDATE = ROOT / ".github/workflows/update-contributors.yml"


class WorkflowSecurityTests(unittest.TestCase):
    def test_issue_close_is_owner_only_exact_command_and_not_pr(self):
        source = CLOSE.read_text(encoding="utf-8")
        self.assertIn("!github.event.issue.pull_request", source)
        self.assertIn("github.event.comment.body == '/close'", source)
        self.assertIn("github.event.comment.user.login == github.repository_owner", source)
        self.assertNotIn("kushagrasinghx", source)
        self.assertNotIn("Galaxyyss", source)

    def test_contributor_update_is_manual_preview_read_only(self):
        source = UPDATE.read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", source)
        self.assertNotIn("  schedule:", source)
        self.assertIn("  contents: read", source)
        self.assertNotIn("contents: write", source)
        self.assertNotIn("git push", source)
        self.assertNotIn("git commit", source)
        self.assertIn("GITHUB_STEP_SUMMARY", source)

    def test_existing_contributor_generation_remains_available(self):
        source = UPDATE.read_text(encoding="utf-8")
        self.assertIn("repos/{repo}/contributors", source)
        self.assertIn("MAINTAINERS.md", source)
        self.assertIn("BEGIN CONTRIBUTORS", source)
        self.assertIn("END CONTRIBUTORS", source)


if __name__ == "__main__":
    unittest.main()
