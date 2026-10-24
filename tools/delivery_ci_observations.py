"""DI5: bounded first-party GitHub Actions job observations feeding DI1 metrics.

GET only, fixed repository/host, no speculative PR attribution or raw errors.
"""
import argparse
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

class ObservationError(ValueError):
    pass

REPO = 'jssantogit/BitChord'
CHECK = 'Android · test and build'
_SHA = re.compile(r'^[0-9a-f]{40}$')

def _need(ok, message):
    if not ok:
        raise ObservationError(message)

class GitHubCIReader:
    repository = REPO
    def __init__(self, token=None):
        self.token = token
    def _get(self, path):
        _need(type(path) is str and path.startswith('/') and
              not path.startswith('//'), 'unsafe path')
        request = Request('https://api.github.com/repos/' + REPO + path,
                          method='GET', headers={
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            **({'Authorization': 'Bearer ' + self.token} if self.token else {})})
        try:
            with urlopen(request, timeout=15) as response:
                return json.load(response)
        except (HTTPError, URLError, OSError, ValueError):
            raise ObservationError('read_error') from None
    def runs_page(self, page):
        _need(type(page) is int and 1 <= page <= 2, 'invalid page')
        data = self._get('/actions/workflows/check.yml/runs?per_page=100&page=' + str(page))
        _need(type(data) is dict and type(data.get('workflow_runs')) is list,
              'invalid workflow runs')
        return data['workflow_runs']
    def jobs(self, run_id):
        _need(type(run_id) is int and run_id > 0, 'invalid run')
        data = self._get('/actions/runs/' + str(run_id) + '/jobs?per_page=100')
        _need(type(data) is dict and type(data.get('total_count')) is int and
              0 <= data['total_count'] <= 100 and
              type(data.get('jobs')) is list and
              len(data['jobs']) == data['total_count'],
              'incomplete job page')
        return data['jobs']

def _lane_for_run(event, head_branch):
    """Map only explicitly supplied GitHub run metadata; never export a ref."""
    if type(head_branch) is not str or not head_branch:
        return 'unknown'
    if event == 'push':
        return 'main' if head_branch == 'main' else 'unknown'
    if event == 'pull_request':
        if head_branch.startswith(('catalog/', 'intelligence/')):
            return 'pr_a'
        if head_branch.startswith('playback/'):
            return 'pr_b'
        return 'pr_c'
    return 'unknown'


def collect_observations(reader, limit=25):
    """Return only source timestamps; do not invent job queue durations."""
    _need(getattr(reader, 'repository', None) == REPO, 'unsupported repository')
    _need(type(limit) is int and 1 <= limit <= 30, 'invalid limit')
    try:
        runs = reader.runs_page(1)
        _need(type(runs) is list and len(runs) <= 100, 'invalid run page')
        seen = set()
        jobs = []
        for run in runs[:limit]:
            _need(type(run) is dict, 'invalid run')
            run_id, sha = run.get('id'), run.get('head_sha')
            _need(type(run_id) is int and run_id > 0 and
                  run_id not in seen, 'duplicate/invalid run')
            seen.add(run_id)
            _need(type(sha) is str and bool(_SHA.fullmatch(sha)), 'invalid SHA')
            _need((run.get('head_repository') or {}).get('full_name') == REPO,
                  'untrusted head repository')
            _need(run.get('event') in ('pull_request', 'push'),
                  'unexpected workflow event')
            linked = run.get('pull_requests') or []
            _need(type(linked) is list, 'invalid PR links')
            pr = None
            if run['event'] == 'pull_request' and len(linked) == 1:
                n = linked[0].get('number')
                _need(type(n) is int and n > 0, 'invalid PR number')
                pr = n
            lane = _lane_for_run(run['event'], run.get('head_branch'))
            observations = reader.jobs(run_id)
            _need(type(observations) is list and len(observations) < 100,
                  'incomplete jobs')
            required = [x for x in observations
                        if x.get('name') == CHECK]
            _need(len(required) <= 1, 'duplicate required job')
            for job in required:
                _need(type(job.get('id')) is int and job['id'] > 0,
                      'invalid job identity')
                _need(type(job.get('created_at')) is str,
                      'missing created_at')
                _need(job.get('started_at') is None or
                      type(job['started_at']) is str, 'invalid started_at')
                _need(job.get('completed_at') is None or
                      type(job['completed_at']) is str, 'invalid completed_at')
                _need(job.get('conclusion') in (
                    None, 'success', 'failure', 'cancelled', 'skipped',
                    'timed_out', 'action_required', 'neutral'),
                    'invalid conclusion')
                jobs.append({'run_id': run_id, 'pr_number': pr, 'lane': lane,
                             'head_sha': sha, 'created_at': job['created_at'],
                             'started_at': job.get('started_at'),
                             'completed_at': job.get('completed_at'),
                             'conclusion': job.get('conclusion')})
        return {'prs': [], 'jobs': jobs, 'blocks': []}
    except ObservationError:
        raise
    except Exception:
        raise ObservationError('read_error_or_invalid_observation') from None

def main(argv=None):
    p = argparse.ArgumentParser(description='GET-only Android CI job sample')
    p.add_argument('--repo', required=True)
    p.add_argument('--limit', type=int, default=25)
    args = p.parse_args(argv)
    try:
        _need(args.repo == REPO, 'unsupported repository')
        report = collect_observations(
            GitHubCIReader(os.environ.get('GITHUB_TOKEN')), args.limit)
    except ObservationError as error:
        p.exit(2, 'CI OBSERVATIONS HALTED: ' + str(error) + '\n')
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write('\n')

if __name__ == '__main__':
    main()
