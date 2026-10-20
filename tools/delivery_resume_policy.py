"""DI4f: read-only fail-closed adjudication after a possibly interrupted queue.

This module NEVER makes GitHub requests or performs CAS writes. A future
external store must independently provide authenticated revision/CAS semantics,
and the protected queue remains the sole write authority.
"""
import re


class ResumePolicyError(ValueError):
    pass


_SHA = re.compile(r'^[0-9a-f]{40}$')
_DIGEST = re.compile(r'^[0-9a-f]{64}$')
_REPO = 'jssantogit/BitChord'
_STAGES = frozenset(('planned', 'preflight_green', 'merge_intent',
                     'merged_observed', 'post_ci_green'))
_ANDROID = frozenset(('success', 'failure', 'pending', 'missing', None))


def _need(ok):
    if not ok:
        raise ResumePolicyError('invalid_or_untrusted_resume_evidence')


def _sha(s):
    return type(s) is str and bool(_SHA.fullmatch(s))


def _hash(s):
    return type(s) is str and bool(_DIGEST.fullmatch(s))


def _result(decision, number):
    return {'number': number, 'decision': decision,
            'approval_required': True, 'execute': False,
            'replay_merge': False}


def adjudicate_resume(checkpoint, observed, *,
                      expected_revision, expected_manifest_digest):
    """Give an inert human-review/revalidation decision, never a write permit.

    Validating a manifest digest is only a consistency check: not a signature,
    not proof of an approved plan, not CAS, not a GitHub merge authorization.
    """
    _need(type(checkpoint) is dict and type(observed) is dict)
    _need(set(checkpoint) in (
        {'version', 'repository', 'manifest_digest', 'main_sha',
         'pr_number', 'expected_head_sha', 'revision', 'stage',
         'approval_required', 'executable'},
        {'version', 'repository', 'manifest_digest', 'main_sha',
         'pr_number', 'expected_head_sha', 'revision', 'stage',
         'merge_sha', 'approval_required', 'executable'}
    ))
    _need(set(observed) == {
        'repository', 'main_sha', 'pr_number', 'head_sha',
        'pr_state', 'merged', 'merge_sha', 'android_check'})
    _need(type(checkpoint['version']) is int and checkpoint['version'] == 1 and
          checkpoint['repository'] == _REPO and
          observed['repository'] == _REPO and
          checkpoint['approval_required'] is True and
          checkpoint['executable'] is False)
    _need(type(checkpoint['revision']) is int and
          checkpoint['revision'] >= 0 and
          type(expected_revision) is int and expected_revision >= 0)
    _need(_hash(checkpoint['manifest_digest']) and
          _hash(expected_manifest_digest))
    _need(_sha(checkpoint['main_sha']) and
          _sha(checkpoint['expected_head_sha']) and
          _sha(observed['main_sha']) and
          _sha(observed['head_sha']))
    _need(type(checkpoint['pr_number']) is int and
          checkpoint['pr_number'] > 0 and
          type(observed['pr_number']) is int and
          observed['pr_number'] == checkpoint['pr_number'])
    _need(type(checkpoint['stage']) is str and
          checkpoint['stage'] in _STAGES and
          type(observed['pr_state']) is str and
          observed['pr_state'] in ('open', 'closed') and
          type(observed['merged']) is bool and
          (observed['merge_sha'] is None or _sha(observed['merge_sha'])) and
          (observed['android_check'] is None or
           (type(observed['android_check']) is str and
            observed['android_check'] in _ANDROID)))
    stage = checkpoint['stage']
    if stage in ('merged_observed', 'post_ci_green'):
        _need(set(checkpoint).issuperset({'merge_sha'}) and
              _sha(checkpoint['merge_sha']))
    else:
        _need('merge_sha' not in checkpoint)
    n = checkpoint['pr_number']
    if checkpoint['revision'] != expected_revision:
        return _result('halt_stale_revision', n)
    if checkpoint['manifest_digest'] != expected_manifest_digest:
        return _result('halt_manifest_drift', n)
    if observed['head_sha'] != checkpoint['expected_head_sha']:
        return _result('halt_head_drift', n)
    if stage == 'merge_intent':
        # Crash between issuing PUT and recording response: never repeat PUT.
        return _result('manual_review_uncertain_merge', n)
    if stage in ('merged_observed', 'post_ci_green'):
        if not (observed['pr_state'] == 'closed' and
                observed['merged'] is True and
                observed['merge_sha'] == checkpoint['merge_sha'] and
                observed['main_sha'] == checkpoint['merge_sha']):
            return _result('halt_postmerge_identity_drift', n)
        if observed['android_check'] != 'success':
            return _result('halt_postmerge_ci_red', n)
        return _result('manual_confirm_completion' if stage == 'post_ci_green'
                       else 'manual_postmerge_ci_review', n)
    if observed['pr_state'] != 'open' or observed['merged'] is not False:
        return _result('halt_pr_closed', n)
    if observed['main_sha'] != checkpoint['main_sha']:
        return _result('halt_main_drift', n)
    if observed['android_check'] != 'success':
        return _result('halt_android_unverified', n)
    return _result('require_live_preflight', n)
