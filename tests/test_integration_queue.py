import copy
import unittest
from tools.integration_queue import IntegrationController, StopIntegration, validate_manifest

M = 'a' * 40
H1 = 'b' * 40
H2 = 'c' * 40
U1 = 'd' * 40
U2 = 'e' * 40
MERGE1 = '1' * 40
MERGE2 = '2' * 40


def manifest(*entries):
    return {'version': 1, 'repository': 'jssantogit/BitChord', 'target': 'main',
            'items': list(entries)}


def item(n, head, files, depends_on=None, base_ref='main'):
    return {'number': n, 'expected_head_sha': head, 'allowed_files': files,
            'depends_on': depends_on, 'expected_base_ref': base_ref}


class FakeAPI:
    def __init__(self, merge_mode='success', pre='success', post='success'):
        self.main = M
        self.calls = []
        self.merge_mode, self.pre, self.post = merge_mode, pre, post
        self.prs = {
            48: {'number': 48, 'state': 'open', 'draft': False, 'merged': False,
                 'head': {'sha': H1, 'ref': 'perf/o2',
                          'repo': {'full_name': 'jssantogit/BitChord'}},
                 'base': {'sha': M, 'ref': 'main'}, 'mergeable': True,
                 'mergeable_state': 'clean'},
            51: {'number': 51, 'state': 'open', 'draft': False, 'merged': False,
                 'head': {'sha': H2, 'ref': 'playback/b2',
                          'repo': {'full_name': 'jssantogit/BitChord'}},
                 'base': {'sha': H1, 'ref': 'perf/o2'}, 'mergeable': True,
                 'mergeable_state': 'clean'},
        }
        self.file_scopes = {48: ['o2.kt'], 51: ['b2.kt']}
        self.merges = [MERGE1, MERGE2]
        self.post_shas = {}

    def repo_settings(self):
        return {'full_name': 'jssantogit/BitChord', 'default_branch': 'main',
                'allow_auto_merge': True, 'allow_update_branch': True,
                'allow_merge_commit': True}

    def branch(self):
        return {'protected': True, 'commit': {'sha': self.main}}

    def ruleset(self):
        return {'enforcement': 'active', 'bypass_actors': [], 'rules': [
            {'type': 'pull_request', 'parameters': {
                'allowed_merge_methods': ['merge'],
                'required_review_thread_resolution': True}},
            {'type': 'required_status_checks', 'parameters': {
                'strict_required_status_checks_policy': True,
                'required_status_checks': [{'context': 'Android · test and build'}]}}]}

    def pr(self, n):
        return copy.deepcopy(self.prs[n])

    def files(self, n):
        return list(self.file_scopes[n])

    def compare(self, base, head):
        return {'behind_by': int(base != M and head == H2)}

    def update_base(self, n, base):
        self.calls.append(('retarget', n, base))
        self.prs[n]['base'] = {'sha': self.main, 'ref': base}

    def update_branch(self, n, head):
        self.calls.append(('sync', n, head))
        assert self.prs[n]['head']['sha'] == head
        new = U1 if n == 48 else U2
        self.prs[n]['head']['sha'] = new
        self.prs[n]['base']['sha'] = self.main
        return new

    def checks(self, sha):
        return [{'name': 'Android · test and build', 'status': 'completed',
                 'conclusion': self.pre}]

    def merge(self, n, head):
        self.calls.append(('merge', n, head))
        if self.merge_mode != 'success':
            raise StopIntegration('merge refused')
        assert self.prs[n]['head']['sha'] == head
        self.prs[n]['state'] = 'closed'
        self.prs[n]['merged'] = True
        new = self.merges[0] if n == 48 else self.merges[1]
        self.main = new
        self.post_shas[new] = self.post
        return {'merged': True, 'sha': new}

    def main_ci(self, sha):
        if sha in self.post_shas:
            return [{'head_sha': sha, 'event': 'push', 'status': 'completed',
                     'conclusion': self.post_shas[sha], 'name': 'Android CI'}]
        return []


class ManifestTests(unittest.TestCase):
    def test_accepts_explicit_single_pilot(self):
        validate_manifest(manifest(item(48, H1, ['o2.kt'])),
                          'jssantogit/BitChord')

    def test_rejects_duplicate_unapproved_or_path_traversal(self):
        bad = [
            manifest(item(48, H1, ['o2.kt']), item(48, H2, ['b2.kt'])),
            manifest(item(49, H1, ['c2.kt'])),
            manifest(item(48, 'not-sha', ['o2.kt'])),
            manifest(item(48, H1, ['../oops'])),
            manifest(item(51, H2, ['b2.kt'], depends_on=48,
                          base_ref='perf/o2')),
        ]
        for case in bad:
            with self.subTest(case=case), self.assertRaises(StopIntegration):
                validate_manifest(case, 'jssantogit/BitChord')


class QueueTests(unittest.TestCase):
    def run_controller(self, api, data=None, mode='plan', expected=M):
        controller = IntegrationController(
            api, 'jssantogit/BitChord', sleep=lambda x: None,
            clock=lambda: 0, timeout_seconds=60, logger=lambda msg: None)
        controller.run(data or manifest(item(48, H1, ['o2.kt'])),
                       mode=mode, expected_main_sha=expected)

    def test_plan_is_read_only(self):
        api = FakeAPI()
        self.run_controller(api)
        self.assertEqual([], api.calls)
        self.assertEqual(M, api.main)

    def test_plan_accepts_missing_optional_switches_from_readonly_token(self):
        api = FakeAPI()
        api.repo_settings = lambda: {
            'full_name': 'jssantogit/BitChord', 'default_branch': 'main',
        }
        self.run_controller(api, mode='plan')
        self.assertEqual([], api.calls)
        self.assertEqual(M, api.main)

    def test_execute_uses_protected_merge_without_optional_settings_visibility(self):
        api = FakeAPI()
        api.repo_settings = lambda: {
            'full_name': 'jssantogit/BitChord', 'default_branch': 'main',
        }
        self.run_controller(api, mode='execute')
        self.assertEqual([('merge', 48, H1)], api.calls)
        self.assertEqual(MERGE1, api.main)

    def test_plan_does_not_require_write_features_enabled(self):
        api = FakeAPI()
        api.repo_settings = lambda: {
            'full_name': 'jssantogit/BitChord', 'default_branch': 'main',
            'allow_auto_merge': False, 'allow_update_branch': False,
            'allow_merge_commit': True,
        }
        self.run_controller(api, mode='plan')
        self.assertEqual([], api.calls)

    def test_wrong_repository_metadata_blocks_plan_and_execute(self):
        for mode in ('plan', 'execute'):
            api = FakeAPI()
            api.repo_settings = lambda: {
                'full_name': 'someone-else/Other', 'default_branch': 'main',
                'allow_auto_merge': True, 'allow_update_branch': True,
                'allow_merge_commit': True,
            }
            with self.subTest(mode=mode), self.assertRaises(StopIntegration):
                self.run_controller(api, mode=mode)
            self.assertEqual([], api.calls)

    def test_explicitly_disabled_merge_commits_still_block_execution(self):
        api = FakeAPI()
        api.repo_settings = lambda: {
            'full_name': 'jssantogit/BitChord', 'default_branch': 'main',
            'allow_merge_commit': False,
        }
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        self.assertEqual([], api.calls)

    def test_prevents_unexpected_main_or_head(self):
        api = FakeAPI()
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute', expected=H2)
        api.prs[48]['head']['sha'] = H2
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        self.assertEqual([], api.calls)

    def test_deny_nonapproved_file_and_draft(self):
        api = FakeAPI()
        api.file_scopes[48] = ['o2.kt', 'backdoor.yml']
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        api = FakeAPI()
        api.prs[48]['draft'] = True
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')

    def test_premerge_red_blocks_merge(self):
        api = FakeAPI(pre='failure')
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        self.assertFalse(any(op[0] == 'merge' for op in api.calls))

    def test_postmerge_red_blocks_second_pr(self):
        api = FakeAPI(post='failure')
        data = manifest(
            item(48, H1, ['o2.kt']),
            item(51, H2, ['b2.kt'], depends_on=48, base_ref='perf/o2'))
        with self.assertRaises(StopIntegration):
            self.run_controller(api, data, mode='execute')
        self.assertEqual([48], [op[1] for op in api.calls if op[0] == 'merge'])

    def test_serial_parent_then_child_with_exact_files(self):
        api = FakeAPI()
        data = manifest(
            item(48, H1, ['o2.kt']),
            item(51, H2, ['b2.kt'], depends_on=48, base_ref='perf/o2'))
        self.run_controller(api, data, mode='execute')
        self.assertEqual([
            ('merge', 48, H1), ('retarget', 51, 'main'),
            ('sync', 51, H2), ('merge', 51, U2)], api.calls)
        self.assertEqual(MERGE2, api.main)

    def test_reject_duplicate_required_check(self):
        api = FakeAPI()
        old = api.checks
        api.checks = lambda sha: old(sha) + old(sha)
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        self.assertFalse(any(op[0] == 'merge' for op in api.calls))

    def test_reject_repo_ruleset_bypass(self):
        api = FakeAPI()
        original = api.ruleset
        def unsafe():
            data = original()
            data['bypass_actors'] = [{'actor_id': 42}]
            return data
        api.ruleset = unsafe
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        self.assertEqual([], api.calls)

    def test_unapproved_file_added_during_sync_stops_before_merge(self):
        api = FakeAPI()
        old = api.update_branch
        def changed(n, head):
            updated = old(n, head)
            api.file_scopes[n].append('unexpected.gradle.kts')
            return updated
        api.update_branch = changed
        data = manifest(
            item(48, H1, ['o2.kt']),
            item(51, H2, ['b2.kt'], depends_on=48, base_ref='perf/o2'))
        with self.assertRaises(StopIntegration):
            self.run_controller(api, data, mode='execute')
        self.assertEqual([48], [op[1] for op in api.calls if op[0] == 'merge'])

    def test_merge_endpoint_refusal_is_terminal(self):
        api = FakeAPI(merge_mode='failure')
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')
        self.assertEqual(M, api.main)

    def test_fail_closed_without_branch_protection(self):
        api = FakeAPI()
        api.branch = lambda: {'protected': False, 'commit': {'sha': M}}
        with self.assertRaises(StopIntegration):
            self.run_controller(api, mode='execute')


    def test_next_pr_preparation_overlaps_previous_main_ci_but_merge_waits(self):
        class ObservedAPI(FakeAPI):
            def __init__(self):
                super().__init__()
                self.timeline = []

            def update_branch(self, n, head):
                self.timeline.append(('sync', n))
                return super().update_branch(n, head)

            def main_ci(self, sha):
                self.timeline.append(('post', sha))
                return super().main_ci(sha)

            def merge(self, n, head):
                self.timeline.append(('merge', n))
                return super().merge(n, head)

        api = ObservedAPI()
        data = manifest(
            item(48, H1, ['o2.kt']),
            item(51, H2, ['b2.kt'], depends_on=48,
                 base_ref='perf/o2'))
        self.run_controller(api, data, mode='execute')
        self.assertLess(api.timeline.index(('merge', 48)),
                        api.timeline.index(('sync', 51)))
        self.assertLess(api.timeline.index(('sync', 51)),
                        api.timeline.index(('post', MERGE1)))
        self.assertLess(api.timeline.index(('post', MERGE1)),
                        api.timeline.index(('merge', 51)))
        self.assertEqual(MERGE2, api.main)

    def test_postmerge_failure_still_blocks_prepared_next_pr(self):
        class ObservedAPI(FakeAPI):
            def __init__(self):
                super().__init__(post='failure')
                self.timeline = []

            def update_branch(self, n, head):
                self.timeline.append(('sync', n))
                return super().update_branch(n, head)

            def main_ci(self, sha):
                self.timeline.append(('post', sha))
                return super().main_ci(sha)

        api = ObservedAPI()
        data = manifest(
            item(48, H1, ['o2.kt']),
            item(51, H2, ['b2.kt'], depends_on=48,
                 base_ref='perf/o2'))
        with self.assertRaisesRegex(StopIntegration, 'Post-merge CI RED'):
            self.run_controller(api, data, mode='execute')
        self.assertLess(api.timeline.index(('sync', 51)),
                        api.timeline.index(('post', MERGE1)))
        self.assertEqual([48], [op[1] for op in api.calls
                                if op[0] == 'merge'])

    def test_next_preparation_rejection_does_not_skip_previous_postmerge_ci(self):
        class ObservedAPI(FakeAPI):
            def __init__(self):
                super().__init__()
                self.observed_main_post_ci = []

            def main_ci(self, sha):
                self.observed_main_post_ci.append(sha)
                return super().main_ci(sha)

        api = ObservedAPI()
        api.file_scopes[51] = ['b2.kt', 'not-approved.kt']
        data = manifest(
            item(48, H1, ['o2.kt']),
            item(51, H2, ['b2.kt'], depends_on=48,
                 base_ref='perf/o2'))
        with self.assertRaises(StopIntegration):
            self.run_controller(api, data, mode='execute')
        self.assertIn(MERGE1, api.observed_main_post_ci)
        self.assertEqual([48], [op[1] for op in api.calls
                                if op[0] == 'merge'])



if __name__ == '__main__':
    unittest.main()
