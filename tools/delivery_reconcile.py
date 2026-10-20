"""DI4b: reconcile a pinned DI4a journal against live GitHub GET observations.

No write methods, approvals, retries, branch syncs or merge replay.
"""
import hashlib
import json

from tools.delivery_journal import JournalError, _validate_manifest


def _digest(value):
    return hashlib.sha256(json.dumps(
        value, ensure_ascii=True, sort_keys=True,
        separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()


def _halt_all(rows, decision, drift):
    return {'version': 1, 'main_drift': drift,
            'approval_required': True, 'executable': False,
            'results': [{'number': row['number'], 'decision': decision,
                         'replay_merge': False} for row in rows]}


def reconcile_live(journal, manifest, reader):
    """Read-only, conservative answer. Even clean PRs require preflight again."""
    _validate_manifest(manifest)
    if (type(journal) is not dict or journal.get('version') != 1 or
            journal.get('approval_required') is not True or
            journal.get('executable') is not False or
            journal.get('manifest_digest') != _digest(manifest) or
            getattr(reader, 'repository', None) != 'jssantogit/BitChord'):
        raise JournalError('invalid or mismatched checkpoint')
    rows = journal.get('items')
    if (type(rows) is not list or
            [(x['number'], x['expected_head_sha']) for x in rows] !=
            [(x['number'], x['expected_head_sha']) for x in manifest['items']]):
        raise JournalError('checkpoint contents differ')
    try:
        main = reader.main_sha()
    except Exception:
        main = None
    if main != journal.get('main_sha'):
        return _halt_all(rows, 'halt_main_drift', True)
    outputs = []
    for row, expected in zip(rows, manifest['items']):
        n = row['number']
        try:
            p = reader.pr(n)
            files = reader.files(n)
            check = reader.check(expected['expected_head_sha'])
            if p.get('merged') is True or p.get('state') == 'closed':
                decision = 'manual_postmerge_review'
            elif (p.get('state') != 'open' or p.get('merged') is not False or
                  p.get('head_repo') != reader.repository or
                  p.get('head_sha') != expected['expected_head_sha'] or
                  p.get('base_ref') != expected['expected_base_ref'] or
                  (p.get('base_ref') == 'main' and p.get('base_sha') != main) or
                  p.get('draft') is not False or p.get('mergeable') != 'clean' or
                  type(files) is not list or
                  sorted(files) != sorted(expected['allowed_files']) or
                  check != 'success'):
                decision = 'blocked'
            else:
                decision = 'preflight_revalidation_required'
        except Exception:
            decision = 'blocked'
        outputs.append({'number': n, 'decision': decision, 'replay_merge': False})
    try:
        drifted = reader.main_sha() != main
    except Exception:
        drifted = True
    if drifted:
        return _halt_all(rows, 'halt_main_drift', True)
    return {'version': 1, 'main_sha': main, 'main_drift': False,
            'approval_required': True, 'executable': False, 'results': outputs}
