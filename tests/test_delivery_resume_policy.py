"""DI4f RED: cross-run resume decisions never become merge authority."""
import unittest
from tools.delivery_resume_policy import ResumePolicyError, adjudicate_resume

MAIN = 'a' * 40
HEAD = 'b' * 40
MERGE = 'c' * 40
DIGEST = 'd' * 64

def checkpoint():
    return {'version': 1, 'repository': 'jssantogit/BitChord',
            'manifest_digest': DIGEST, 'main_sha': MAIN, 'pr_number': 97,
            'expected_head_sha': HEAD, 'revision': 3,
            'stage': 'preflight_green',
            'approval_required': True, 'executable': False}

def observed():
    return {'repository': 'jssantogit/BitChord', 'main_sha': MAIN,
            'pr_number': 97, 'head_sha': HEAD, 'pr_state': 'open',
            'merged': False, 'merge_sha': None, 'android_check': 'success'}

class ResumePolicyTests(unittest.TestCase):
    def decide(self, state=None, live=None, **kwargs):
        return adjudicate_resume(checkpoint() if state is None else state,
            observed() if live is None else live,
            expected_revision=kwargs.get('expected_revision', 3),
            expected_manifest_digest=kwargs.get('expected_manifest_digest', DIGEST))

    def test_healthy_preflight_still_needs_fresh_authorization(self):
        result = self.decide()
        self.assertEqual('require_live_preflight', result['decision'])
        self.assertTrue(result['approval_required'])
        self.assertFalse(result['execute'])
        self.assertFalse(result['replay_merge'])

    def test_stale_revision_or_manifest_digest_halts(self):
        self.assertEqual('halt_stale_revision',
                         self.decide(expected_revision=2)['decision'])
        self.assertEqual('halt_manifest_drift',
                         self.decide(expected_manifest_digest='e' * 64)['decision'])

    def test_ambiguous_merge_intent_is_never_retried(self):
        state = checkpoint()
        state['stage'] = 'merge_intent'
        self.assertEqual('manual_review_uncertain_merge',
                         self.decide(state=state)['decision'])
        live = observed()
        live['merged'] = True
        live['pr_state'] = 'closed'
        live['main_sha'] = MERGE
        live['merge_sha'] = MERGE
        self.assertEqual('manual_review_uncertain_merge',
                         self.decide(state=state, live=live)['decision'])

    def test_merge_observed_only_allows_read_only_post_ci_review(self):
        state = checkpoint()
        state['stage'] = 'merged_observed'
        state['merge_sha'] = MERGE
        live = observed()
        live['pr_state'] = 'closed'
        live['merged'] = True
        live['merge_sha'] = MERGE
        live['main_sha'] = MERGE
        self.assertEqual('manual_postmerge_ci_review', self.decide(state, live)['decision'])
        state['stage'] = 'post_ci_green'
        self.assertEqual('manual_confirm_completion', self.decide(state, live)['decision'])
        live['android_check'] = 'failure'
        self.assertEqual('halt_postmerge_ci_red', self.decide(state, live)['decision'])

    def test_closed_unmerged_and_head_drift_fail_closed(self):
        live = observed()
        live['pr_state'] = 'closed'
        self.assertEqual('halt_pr_closed', self.decide(live=live)['decision'])
        live = observed()
        live['head_sha'] = 'f' * 40
        self.assertEqual('halt_head_drift', self.decide(live=live)['decision'])
        live = observed()
        live['main_sha'] = 'f' * 40
        self.assertEqual('halt_main_drift', self.decide(live=live)['decision'])

    def test_red_or_missing_android_check_blocks(self):
        for check in ('failure', 'pending', 'missing', None):
            with self.subTest(check=check):
                live = observed()
                live['android_check'] = check
                self.assertEqual('halt_android_unverified',
                                 self.decide(live=live)['decision'])

    def test_untrusted_or_executable_evidence_rejected(self):
        state = checkpoint()
        for field, value in [('approval_required', False), ('executable', True),
                             ('repository', 'foreign/repo'), ('revision', True),
                             ('expected_head_sha', 'wrong')]:
            bad = {**state, field: value}
            with self.subTest(field=field), self.assertRaises(ResumePolicyError):
                self.decide(state=bad)
        live = observed()
        live['repository'] = 'foreign/repo'
        with self.assertRaises(ResumePolicyError):
            self.decide(live=live)

    def test_unhashable_untrusted_values_are_typed_policy_errors(self):
        for field, value in [('stage', []), ('stage', {}),
                             ('version', True), ('stage', 1)]:
            with self.subTest(field=field, value=str(value)):
                state = checkpoint()
                state[field] = value
                with self.assertRaises(ResumePolicyError):
                    self.decide(state=state)
        for field, value in [('android_check', []), ('pr_state', []),
                             ('merged', []), ('android_check', {})]:
            with self.subTest(field=field, value=str(value)):
                live = observed()
                live[field] = value
                with self.assertRaises(ResumePolicyError):
                    self.decide(live=live)

    def test_unexpected_fields_refused_and_no_write_calls_exist(self):
        state = checkpoint()
        state['write_token'] = 'secret'
        with self.assertRaises(ResumePolicyError):
            self.decide(state=state)
        live = observed()
        live['merged'] = None
        with self.assertRaises(ResumePolicyError):
            self.decide(live=live)

if __name__ == '__main__':
    unittest.main()
