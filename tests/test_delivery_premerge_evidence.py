"""Regression coverage for privacy-safe premerge GitHub readiness wait diagnostics."""
import unittest

from tools.integration_queue import IntegrationController, StopIntegration

MAIN = 'a' * 40
HEAD = 'b' * 40
ENTRY = {
    'number': 200,
    'expected_head_sha': HEAD,
    'expected_base_ref': 'main',
    'allowed_files': ['app/src/test/java/example/SafeTest.kt'],
    'depends_on': None,
}


class ReadinessAPI:
    def __init__(self, snapshots):
        self.snapshots = snapshots
        self.index = 0
        self.now = 0
        self.logs = []
        self.merges = 0

    @property
    def snapshot(self):
        return self.snapshots[min(self.index, len(self.snapshots) - 1)]

    def branch(self):
        return {'protected': True, 'commit': {'sha': MAIN}}

    def pr(self, number):
        assert number == ENTRY['number']
        shot = self.snapshot
        return {
            'state': 'open', 'draft': False, 'merged': False,
            'head': {'sha': HEAD, 'repo': {'full_name': 'jssantogit/BitChord'}},
            'base': {'ref': 'main', 'sha': MAIN},
            'mergeable': shot.get('mergeable'),
            'mergeable_state': shot.get('mergeable_state'),
            'body': 'never-log-private-body-or-url',
        }

    def files(self, number):
        return list(ENTRY['allowed_files'])

    def checks(self, sha):
        assert sha == HEAD
        shot = self.snapshot
        if shot.get('check') == 'absent':
            return []
        return [{
            'name': 'Android · test and build',
            'status': shot.get('check', 'completed'),
            'conclusion': shot.get('conclusion', 'success'),
            'output': {'text': 'never-log-private-check-output'},
        }]

    def sleep(self, seconds):
        self.now += seconds
        self.index += 1


def exercise(snapshots, *, timeout=300):
    api = ReadinessAPI(snapshots)
    controller = IntegrationController(
        api, 'jssantogit/BitChord', sleep=api.sleep,
        clock=lambda: api.now, timeout_seconds=timeout,
        logger=api.logs.append,
    )
    controller.wait_premerge(ENTRY, MAIN, HEAD, api.now + timeout)
    return api


class PremergeWaitEvidenceTests(unittest.TestCase):
    def test_emits_fixed_reason_on_changes_until_ready(self):
        api = exercise([
            {'check': 'absent'},
            {'check': 'in_progress'},
            {'check': 'completed', 'mergeable': None,
             'mergeable_state': 'unknown'},
            {'check': 'completed', 'mergeable': True,
             'mergeable_state': 'blocked'},
            {'check': 'completed', 'mergeable': True,
             'mergeable_state': 'clean'},
        ])
        self.assertEqual([
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=check_absent elapsed_s=0',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=check_pending elapsed_s=15',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=mergeability_unknown elapsed_s=30',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=github_blocked elapsed_s=45',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=ready elapsed_s=60',
        ], api.logs)
        self.assertEqual(0, api.merges)

    def test_heartbeat_at_most_once_per_minute_for_same_state(self):
        blocked = {'mergeable': True, 'mergeable_state': 'blocked'}
        ready = {'mergeable': True, 'mergeable_state': 'clean'}
        api = exercise([blocked] * 5 + [ready])
        self.assertEqual([
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=github_blocked elapsed_s=0',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=github_blocked elapsed_s=60',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=ready elapsed_s=75',
        ], api.logs)

    def test_unrecognized_github_status_never_leaks_api_content(self):
        api = exercise([
            {'mergeable': True, 'mergeable_state': 'private-token-123'},
            {'mergeable': True, 'mergeable_state': 'clean'},
        ])
        self.assertEqual([
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=github_other elapsed_s=0',
            'QUEUE_WAIT v=1 pr=200 phase=premerge_ci reason=ready elapsed_s=15',
        ], api.logs)
        self.assertNotIn('private-token-123', repr(api.logs))
        self.assertNotIn('never-log', repr(api.logs))

    def test_red_android_check_still_aborts_without_ready(self):
        api = ReadinessAPI([{
            'check': 'completed', 'conclusion': 'failure',
            'mergeable': True, 'mergeable_state': 'clean',
        }])
        controller = IntegrationController(
            api, 'jssantogit/BitChord', sleep=api.sleep,
            clock=lambda: api.now, timeout_seconds=60,
            logger=api.logs.append,
        )
        with self.assertRaises(StopIntegration):
            controller.wait_premerge(ENTRY, MAIN, HEAD, 60)
        self.assertEqual(0, api.merges)
        self.assertFalse(any('reason=ready' in line for line in api.logs))


if __name__ == '__main__':
    unittest.main()
