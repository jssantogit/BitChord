"""DI4d: GET-only GitHub Actions artifact provenance for cross-run DI4c evidence.

An artifact and its digest must be supplied independently. Metadata alone does
not authorize a merge, restore mutable state or prove reviewer approval.
"""
import argparse
import json
import os
from pathlib import Path
import re
import stat
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from tools.delivery_transfer import inspect_evidence, MAX_BYTES

REPO = 'jssantogit/BitChord'
SOURCE_WORKFLOW = '.github/workflows/delivery-checkpoint-evidence.yml'
ARTIFACT_NAME = 'di4-checkpoint-evidence'
MAX_ARCHIVE_BYTES = 262144
SHA = re.compile(r'^[0-9a-f]{40}$')
HASH = re.compile(r'^[0-9a-f]{64}$')


class ArtifactError(ValueError):
    pass


def _need(condition):
    if not condition:
        raise ArtifactError('untrusted_or_incomplete_artifact')


class GitHubArtifactReader:
    """Read-only, fixed repository and host API adapter. No download redirects."""
    repository = REPO

    def __init__(self, token=None):
        self._token = token

    def _get(self, path, *, method='GET'):
        _need(type(path) is str and method == 'GET' and bool(re.fullmatch(
            r'/actions/runs/[1-9][0-9]*(?:/artifacts\?per_page=100&page=1)?',
            path)))
        headers = {
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28'
        }
        if self._token:
            headers['Authorization'] = 'Bearer ' + self._token
        request = Request('https://api.github.com/repos/' + REPO + path,
                          headers=headers, method='GET')
        try:
            with urlopen(request, timeout=15) as response:
                return json.load(response)
        except (HTTPError, URLError, ValueError, OSError):
            raise ArtifactError('github_metadata_unavailable') from None

    def workflow_run(self, run_id):
        _need(type(run_id) is int and run_id > 0)
        return self._get('/actions/runs/' + str(run_id))

    def artifacts(self, run_id):
        _need(type(run_id) is int and run_id > 0)
        body = self._get('/actions/runs/' + str(run_id) +
                         '/artifacts?per_page=100&page=1')
        _need(type(body) is dict and
              type(body.get('total_count')) is int and
              0 <= body['total_count'] <= 100 and
              type(body.get('artifacts')) is list and
              len(body['artifacts']) == body['total_count'])
        return body['artifacts']


def inspect_remote_evidence(reader, manifest, *, run_id, attempt,
                            expected_main_sha, artifact_id,
                            artifact_name, content, sha256):
    """Validate independent source metadata + content, output inert decisions.

    'content' must be downloaded separately with a trusted read-only tool,
    e.g. actions/download-artifact and passed as bytes; never follow an
    untrusted archive URL supplied by run/artifact metadata.
    """
    try:
        _need(getattr(reader, 'repository', None) == REPO and
              type(run_id) is int and run_id > 0 and
              type(attempt) is int and 1 <= attempt <= 100 and
              type(artifact_id) is int and artifact_id > 0 and
              artifact_name == ARTIFACT_NAME and
              type(expected_main_sha) is str and
              bool(SHA.fullmatch(expected_main_sha)) and
              type(sha256) is str and bool(HASH.fullmatch(sha256)) and
              type(content) is bytes and 0 < len(content) <= MAX_BYTES)
        run = reader.workflow_run(run_id)
        _need(type(run) is dict and type(run.get('id')) is int and
              run['id'] == run_id and
              type(run.get('run_attempt')) is int and
              run['run_attempt'] == attempt and
              run.get('path') == SOURCE_WORKFLOW and
              run.get('event') == 'workflow_dispatch' and
              run.get('head_branch') == 'main' and
              run.get('head_sha') == expected_main_sha and
              run.get('status') == 'completed' and
              run.get('conclusion') == 'success' and
              type(run.get('repository')) is dict and
              run['repository'].get('full_name') == REPO and
              type(run.get('head_repository')) is dict and
              run['head_repository'].get('full_name') == REPO)
        items = reader.artifacts(run_id)
        _need(type(items) is list and 1 <= len(items) <= 100 and
              all(type(x) is dict for x in items))
        named = [a for a in items if a.get('name') == artifact_name]
        _need(len(named) == 1)
        artifact = named[0]
        _need(type(artifact.get('id')) is int and
              artifact['id'] == artifact_id and
              artifact.get('expired') is False and
              type(artifact.get('size_in_bytes')) is int and
              0 < artifact['size_in_bytes'] <= MAX_ARCHIVE_BYTES)
        bound_run = artifact.get('workflow_run')
        _need(bound_run is None or
              (type(bound_run) is dict and
               type(bound_run.get('id')) is int and
               bound_run['id'] == run_id))
        result = inspect_evidence(
            content, manifest, sha256=sha256,
            run_id=run_id, attempt=attempt, expected_main_sha=expected_main_sha)
        _need(result.get('approval_required') is True and
              result.get('executable') is False and
              result.get('replay_merge') is False)
        return {**result,
                'artifact_id': artifact_id,
                'transport': 'trusted_metadata_and_pinned_digest'}
    except Exception:
        raise ArtifactError('untrusted_or_incomplete_artifact') from None


def read_local_artifact(path):
    """Read through one no-follow file descriptor, avoiding path-swap races."""
    try:
        # NONBLOCK prevents an attacker-provided FIFO from hanging before fstat.
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW |
                     os.O_NONBLOCK | os.O_CLOEXEC)
        with os.fdopen(fd, 'rb') as source:
            info = os.fstat(source.fileno())
            _need(stat.S_ISREG(info.st_mode) and
                  0 < info.st_size <= MAX_BYTES)
            blob = source.read(MAX_BYTES + 1)
            _need(len(blob) == info.st_size)
            return blob
    except Exception:
        raise ArtifactError('untrusted_or_incomplete_artifact') from None


def main(argv=None):
    parser = argparse.ArgumentParser(description='Read-only DI4d cross-run evidence inspection')
    parser.add_argument('--repo', required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--content', type=Path, required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--attempt', type=int, required=True)
    parser.add_argument('--artifact-id', type=int, required=True)
    parser.add_argument('--expected-main-sha', required=True)
    parser.add_argument('--sha256', required=True,
                        help='Independently reviewed digest, NEVER taken from artifact itself')
    args = parser.parse_args(argv)
    try:
        _need(args.repo == REPO)
        manifest = json.loads(args.manifest.read_text('utf-8'))
        result = inspect_remote_evidence(
            GitHubArtifactReader(os.environ.get('GITHUB_TOKEN')),
            manifest, run_id=args.run_id, attempt=args.attempt,
            expected_main_sha=args.expected_main_sha,
            artifact_id=args.artifact_id, artifact_name=ARTIFACT_NAME,
            content=read_local_artifact(args.content), sha256=args.sha256)
    except Exception:
        parser.exit(2, 'DI4D HALTED: untrusted_or_incomplete_artifact\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
