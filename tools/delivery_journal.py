"""DI4a: protected, local-only checkpoint journal for *future* resume flows.

No GitHub writes, no approval synthesis. A runner's ephemeral filesystem is
NOT durable across separate GitHub Actions jobs; external persistence is a
distinct security-reviewed slice.
"""
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

class JournalError(ValueError):
    pass

_SHA = re.compile(r'^[0-9a-f]{40}$')
_FILE = re.compile(r'^[A-Za-z0-9_./@+\\-]+$')
_STAGES = {'planned': ('preflight_green',), 'preflight_green': (),
           'merge_intent': (), 'merged_observed': (), 'post_ci_green': ()}

def _need(ok, reason):
    if not ok:
        raise JournalError(reason)

def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')

def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()

def _validate_manifest(data):
    _need(type(data) is dict and set(data) ==
          {'version', 'repository', 'target', 'items'}, 'invalid manifest')
    _need(data['version'] == 1 and
          data['repository'] == 'jssantogit/BitChord' and
          data['target'] == 'main', 'invalid repository')
    rows = data['items']
    _need(type(rows) is list and 1 <= len(rows) <= 3, 'invalid batch size')
    seen = set()
    for row in rows:
        _need(type(row) is dict and set(row) ==
              {'number', 'expected_head_sha', 'expected_base_ref',
               'depends_on', 'allowed_files'}, 'invalid manifest row')
        n, sha, base, dep = (row['number'], row['expected_head_sha'],
                             row['expected_base_ref'], row['depends_on'])
        _need(type(n) is int and n > 0 and n != 49 and n not in seen,
              'invalid PR')
        _need(type(sha) is str and bool(_SHA.fullmatch(sha)), 'invalid HEAD')
        _need(type(base) is str and 0 < len(base) <= 128 and
              bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9/_.-]*', base))
              and '..' not in base and not base.endswith('/'), 'invalid base')
        _need((dep is None and base == 'main') or
              (type(dep) is int and dep in seen and base != 'main'),
              'invalid dependency')
        files = row['allowed_files']
        _need(type(files) is list and 0 < len(files) <= 100 and
              all(type(f) is str for f in files) and
              len(set(files)) == len(files), 'invalid file list')
        _need(all(_FILE.fullmatch(f) and not f.startswith('/') and
                  all(piece not in ('', '.', '..') for piece in f.split('/'))
                  for f in files), 'unsafe file list')
        seen.add(n)

def _read(path):
    path = Path(path)
    _need(not path.is_symlink(), 'symlink journal rejected')
    try:
        st = path.stat()
        _need(stat.S_ISREG(st.st_mode) and not (st.st_mode & 0o077),
              'unsafe journal permissions')
        data = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError, UnicodeError):
        raise JournalError('missing or invalid journal') from None
    _need(type(data) is dict and set(data) ==
          {'version', 'repository', 'main_sha', 'manifest_digest',
           'revision', 'items', 'approval_required', 'executable', 'integrity'},
          'invalid journal format')
    integrity = data.pop('integrity')
    valid = type(integrity) is str and integrity == _digest(data)
    data['integrity'] = integrity
    _need(valid, 'journal integrity violation')
    _need(data['version'] == 1 and data['repository'] == 'jssantogit/BitChord'
          and type(data['revision']) is int and data['revision'] >= 0 and
          data['approval_required'] is True and data['executable'] is False
          and type(data['main_sha']) is str and _SHA.fullmatch(data['main_sha'])
          and type(data['manifest_digest']) is str and
          bool(re.fullmatch(r'[0-9a-f]{64}', data['manifest_digest'])),
          'invalid journal state')
    rows = data['items']
    _need(type(rows) is list and 1 <= len(rows) <= 3, 'invalid journal rows')
    seen = set()
    for row in rows:
        _need(type(row) is dict and set(row) ==
              {'number', 'expected_head_sha', 'stage'} and
              type(row['number']) is int and row['number'] > 0 and
              row['number'] not in seen and
              type(row['expected_head_sha']) is str and
              bool(_SHA.fullmatch(row['expected_head_sha'])) and
              row['stage'] in _STAGES, 'invalid journal entry')
        seen.add(row['number'])
    return data

def load(path):
    """Read-only verification of a private, sealed checkpoint."""
    return _read(path)

def _store(path, state):
    path = Path(path)
    _need(not path.is_symlink(), 'symlink journal rejected')
    content = dict(state)
    content.pop('integrity', None)
    content['integrity'] = _digest(content)
    fd, temp = tempfile.mkstemp(prefix='.delivery-journal-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(content, stream, sort_keys=True, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temp, 0o600)
        os.replace(temp, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)
    return content

@contextmanager
def _locked(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    sidecar = path.with_name(path.name + '.lock')
    try:
        fd = os.open(sidecar, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    except OSError:
        raise JournalError('journal lock unavailable') from None
    try:
        info = os.fstat(fd)
        _need(stat.S_ISREG(info.st_mode) and not (info.st_mode & 0o077),
              'unsafe journal lock')
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)

def initialize(path, proposal, main_sha, *, authorized=False):
    """Create or reuse the *same* unapproved batch; never execute."""
    _validate_manifest(proposal)
    _need(authorized is False, 'approval cannot be inferred')
    _need(type(main_sha) is str and bool(_SHA.fullmatch(main_sha)),
          'invalid main SHA')
    digest = _digest(proposal)
    with _locked(path):
        p = Path(path)
        if p.exists() or p.is_symlink():
            old = _read(p)
            _need(old['manifest_digest'] == digest and old['main_sha'] == main_sha,
                  'journal belongs to different batch')
            return old
        return _store(p, {'version': 1, 'repository': proposal['repository'],
                          'main_sha': main_sha, 'manifest_digest': digest,
                          'revision': 0, 'approval_required': True,
                          'executable': False,
                          'items': [{'number': x['number'],
                                     'expected_head_sha': x['expected_head_sha'],
                                     'stage': 'planned'} for x in proposal['items']]})

def advance(path, expected_revision, number, next_stage, *, expected_head):
    """Compare-and-swap checkpoint advance; write-capable stages unavailable."""
    _need(type(expected_revision) is int and expected_revision >= 0,
          'invalid revision')
    with _locked(path):
        state = _read(path)
        _need(state['revision'] == expected_revision, 'stale revision')
        _need(type(number) is int and type(expected_head) is str,
              'invalid observation')
        row = next((x for x in state['items'] if x['number'] == number), None)
        _need(row is not None and row['expected_head_sha'] == expected_head,
              'HEAD changed')
        _need(next_stage in _STAGES[row['stage']], 'stage not approved')
        row['stage'] = next_stage
        state['revision'] += 1
        return _store(path, state)

def reconcile_checkpoint(state, number, *, observed_main, observed_pr_merged,
                         observed_merge_sha):
    """Read-only, never replay a possibly completed merge."""
    _need(type(state) is dict and state.get('executable') is False,
          'untrusted journal')
    row = next((x for x in state.get('items', [])
                if x.get('number') == number), None)
    _need(row is not None, 'unknown PR')
    if observed_pr_merged is True:
        decision = ('manual_verify_postmerge'
                    if type(observed_merge_sha) is str and
                    _SHA.fullmatch(observed_merge_sha) and
                    observed_main == observed_merge_sha
                    else 'halt_ambiguous_merge')
    elif observed_pr_merged is False and observed_main == state['main_sha']:
        decision = ('revalidate_before_merge' if row['stage'] == 'preflight_green'
                    else 'revalidate_from_start')
    else:
        decision = 'halt_observed_drift'
    return {'number': number, 'decision': decision, 'execute': False,
            'approval_required': True}
