"""DI6: avoid known workflow-write permission 403 before attempting PUT."""
import unittest
from tools.integration_queue import IntegrationController, StopIntegration, workflow_sync_guard
from test_integration_queue import FakeAPI, manifest, item, M, H1

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

    def test_controller_halts_before_any_workflow_branch_write(self):
        api = FakeAPI()
        api.file_scopes[48] = ['.github/workflows/check.yml']
        api.compare = lambda base, head: {'behind_by': 1}
        queue = IntegrationController(api, 'jssantogit/BitChord',
                                      sleep=lambda seconds: None,
                                      clock=lambda: 0, logger=lambda _: None)
        with self.assertRaises(StopIntegration) as caught:
            queue.run(manifest(item(48, H1, ['.github/workflows/check.yml'])),
                      'execute', M)
        self.assertIn('workflow_file_sync_requires_manual_preparation',
                      str(caught.exception))
        self.assertEqual([], api.calls)
        self.assertEqual(M, api.main)

    def test_controller_keeps_already_synced_workflow_pr_eligible(self):
        api = FakeAPI()
        api.file_scopes[48] = ['.github/workflows/check.yml']
        queue = IntegrationController(api, 'jssantogit/BitChord',
                                      sleep=lambda seconds: None,
                                      clock=lambda: 0, logger=lambda _: None)
        queue.run(manifest(item(48, H1, ['.github/workflows/check.yml'])),
                  'execute', M)
        self.assertEqual([('merge', 48, H1)], api.calls)

    def test_invalid_compare_and_input_fail_closed(self):
        for behind in (-1, None, '2', True):
            with self.subTest(behind=behind), self.assertRaises(StopIntegration):
                workflow_sync_guard(['.github/workflows/check.yml'], behind)
        with self.assertRaises(StopIntegration):
            workflow_sync_guard('wrong', 2)

if __name__ == '__main__':
    unittest.main()
