"""DI4d: runner-independent, GET-only artifact provenance & pinning (RED)."""
import copy
from pathlib import Path
import tempfile
import unittest

from tools.delivery_journal import initialize
from tools.delivery_transfer import export_evidence
from tools.delivery_artifact import ArtifactError, inspect_remote_evidence, GitHubArtifactReader

MAIN = 'a' * 40
HEAD = 'b' * 40
REPO = 'jssantogit/BitChord'
BATCH = {
    'version': 1, 'repository': REPO, 'target': 'main',
    'items': [{'number': 90, 'expected_head_sha': HEAD,
               'expected_base_ref': 'main', 'depends_on': None,
               'allowed_files': ['tools/delivery_journal.py']}]
}

class Reader:
    repository = REPO
    def __init__(self):
        self.run_data = {
            'id': 8123, 'run_attempt': 1,
            'path': '.github/workflows/delivery-checkpoint-evidence.yml',
            'event': 'workflow_dispatch', 'head_branch': 'main',
            'head_sha': MAIN, 'status': 'completed', 'conclusion': 'success',
            'repository': {'full_name': REPO},
            'head_repository': {'full_name': REPO}
        }
        self.artifacts_data = [{
            'id': 991, 'name': 'di4-checkpoint-evidence',
            'expired': False, 'size_in_bytes': 1900,
            'workflow_run': {'id': 8123}
        }]
    def workflow_run(self, run_id):
        return copy.deepcopy(self.run_data)
    def artifacts(self, run_id):
        return copy.deepcopy(self.artifacts_data)
    def merge(self, *a):
        raise AssertionError('artifact code must never call a GitHub write')

class ArtifactTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / 'journal.json'
        initialize(path, BATCH, MAIN)
        self.blob, self.digest = export_evidence(
            path, BATCH, run_id=8123, attempt=1, source_main_sha=MAIN,
            event='workflow_dispatch', ref='refs/heads/main')
        self.reader = Reader()

    def inspect(self, reader=None, blob=None, digest=None, **kwargs):
        return inspect_remote_evidence(
            reader or self.reader, BATCH,
            run_id=kwargs.get('run_id', 8123),
            attempt=kwargs.get('attempt', 1),
            expected_main_sha=kwargs.get('expected_main_sha', MAIN),
            artifact_id=kwargs.get('artifact_id', 991),
            artifact_name='di4-checkpoint-evidence',
            content=self.blob if blob is None else blob,
            sha256=self.digest if digest is None else digest)

    def test_valid_cross_runner_evidence_is_never_executable(self):
        result = self.inspect()
        self.assertEqual('require_live_revalidation', result['decision'])
        self.assertEqual('trusted_metadata_and_pinned_digest', result['transport'])
        self.assertTrue(result['approval_required'])
        self.assertFalse(result['executable'])
        self.assertFalse(result['replay_merge'])
        self.assertEqual(8123, result['run_id'])

    def test_wrong_workflow_origin_or_status_or_fork_rejected(self):
        for key, value in [
            ('path', '.github/workflows/integration-queue.yml'),
            ('event', 'pull_request'), ('head_branch', 'feature'),
            ('head_sha', 'f' * 40), ('status', 'in_progress'),
            ('conclusion', 'failure'), ('run_attempt', 2), ('id', 8124),
            ('repository', {'full_name': 'evil/repository'}),
            ('head_repository', {'full_name': 'evil/repository'})]:
            with self.subTest(field=key):
                reader = Reader()
                reader.run_data[key] = value
                with self.assertRaises(ArtifactError):
                    self.inspect(reader)

    def test_missing_expired_duplicate_or_foreign_artifact_rejected(self):
        for change in ('none', 'duplicate', 'expired', 'wrong_name',
                       'wrong_id', 'wrong_run', 'oversize'):
            reader = Reader()
            if change == 'none':
                reader.artifacts_data.clear()
            elif change == 'duplicate':
                reader.artifacts_data.append(copy.deepcopy(reader.artifacts_data[0]))
            elif change == 'expired':
                reader.artifacts_data[0]['expired'] = True
            elif change == 'wrong_name':
                reader.artifacts_data[0]['name'] = 'evil'
            elif change == 'wrong_id':
                reader.artifacts_data[0]['id'] = 990
            elif change == 'wrong_run':
                reader.artifacts_data[0]['workflow_run']['id'] = 111
            elif change == 'oversize':
                reader.artifacts_data[0]['size_in_bytes'] = 5_000_000
            with self.subTest(change=change), self.assertRaises(ArtifactError):
                self.inspect(reader)

    def test_tamper_manifest_and_wrong_independent_digest_rejected(self):
        for blob, digest in [(self.blob + b' ', self.digest),
                             (self.blob, 'c' * 64)]:
            with self.assertRaises(ArtifactError):
                self.inspect(blob=blob, digest=digest)
        changed = copy.deepcopy(BATCH)
        changed['items'][0]['allowed_files'] = ['unexpected.py']
        with self.assertRaises(ArtifactError):
            inspect_remote_evidence(self.reader, changed, run_id=8123,
                attempt=1, expected_main_sha=MAIN, artifact_id=991,
                artifact_name='di4-checkpoint-evidence',
                content=self.blob, sha256=self.digest)

    def test_read_errors_are_redacted_no_retries_or_writes(self):
        class Broken(Reader):
            def artifacts(self, run_id):
                raise RuntimeError('AUTH_TOKEN=my_private_secret')
        with self.assertRaises(ArtifactError) as e:
            self.inspect(Broken())
        self.assertNotIn('my_private_secret', str(e.exception))

    def test_wrong_identity_or_excessive_bytes_block(self):
        for options in [{'run_id': 8124}, {'attempt': 2},
                        {'expected_main_sha': 'f' * 40},
                        {'artifact_id': True}]:
            with self.subTest(options=options), self.assertRaises(ArtifactError):
                self.inspect(**options)
        with self.assertRaises(ArtifactError):
            self.inspect(blob=b'x' * 65537)

    def test_live_reader_restricts_get_paths_and_repo(self):
        reader = GitHubArtifactReader(token=None)
        self.assertEqual(REPO, reader.repository)
        with self.assertRaises(ArtifactError):
            reader._get('//other.host/evil')
        with self.assertRaises(ArtifactError):
            reader._get('/pulls/12', method='POST')

if __name__ == '__main__':
    unittest.main()
