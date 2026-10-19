"""DI6: avoid known workflow-write permission 403 before attempting PUT."""
import unittest
from tools.integration_queue import StopIntegration, workflow_sync_guard

class WorkflowSyncTests(unittest.TestCase):
    def test_behind_workflow_patch_requires_owner_preparation(self):
        with self.assertRaises(StopIntegration) as caught:
            workflow_sync_guard(['.github/workflows/check.yml', 'tests/test.py'], 1)
        self.assertIn('workflow_file_sync_requires_manual_preparation',
                      str(caught.exception))

    def test_already_synced_workflow_is_allowed(self):
        workflow_sync_guard(['.github/workflows/check.yml'], 0)

    def test_regular_files_retain_existing_update_branch_behavior(self):
        workflow_sync_guard(['app/src/main/Test.kt'], 3)

    def test_invalid_compare_and_input_fail_closed(self):
        for behind in (-1, None, '2', True):
            with self.subTest(behind=behind), self.assertRaises(StopIntegration):
                workflow_sync_guard(['.github/workflows/check.yml'], behind)
        with self.assertRaises(StopIntegration):
            workflow_sync_guard('wrong', 2)

if __name__ == '__main__':
    unittest.main()
