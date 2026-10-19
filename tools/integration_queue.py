#!/usr/bin/env python3
"""Fail-closed, serial BitChord integration controller; no writes in plan mode."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

SHA = re.compile(r'^[0-9a-f]{40}$')
PATH = re.compile(r'^[A-Za-z0-9_./@+\-]+$')
REQUIRED = 'Android · test and build'
DENIED_PRS = {49}  # C2 is explicitly revoked.
MAX_ITEMS = 3  # Keep the batch below a GitHub App token's one-hour lifetime.


class StopIntegration(RuntimeError):
    """A blocked, unknown, or unsafe condition; do not advance the queue."""


def need(condition, message):
    if not condition:
        raise StopIntegration(message)


def validate_manifest(manifest, repository):
    need(type(manifest) is dict and set(manifest) ==
         {'version', 'repository', 'target', 'items'}, 'Unknown manifest fields')
    need(manifest['version'] == 1 and manifest['repository'] == repository and
         manifest['target'] == 'main', 'Wrong batch version/repository/target')
    items = manifest['items']
    need(type(items) is list and 0 < len(items) <= MAX_ITEMS,
         'Batch must contain 1–3 PRs')
    seen = set()
    for p in items:
        need(type(p) is dict and set(p) ==
             {'number', 'expected_head_sha', 'allowed_files',
              'depends_on', 'expected_base_ref'}, 'Invalid PR entry fields')
        n = p['number']
        need(type(n) is int and n > 0 and n not in DENIED_PRS and n not in seen,
             f'PR number denied, invalid or duplicate: {n}')
        need(type(p['expected_head_sha']) is str and
             SHA.fullmatch(p['expected_head_sha']), f'PR #{n} unpinned head')
        files = p['allowed_files']
        need(type(files) is list and 0 < len(files) <= 100 and
             len(set(files)) == len(files), f'PR #{n} invalid files')
        need(all(type(f) is str and PATH.fullmatch(f) and
                 not f.startswith('/') and
                 not any(part in ('', '.', '..') for part in f.split('/'))
                 for f in files), f'PR #{n} unsafe file path')
        dependency = p['depends_on']
        need(dependency is None or
             (type(dependency) is int and dependency in seen),
             f'PR #{n} dependency must occur earlier in batch')
        ref = p['expected_base_ref']
        need(type(ref) is str and
             re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9/_.-]{0,127}', ref) and
             '..' not in ref and not ref.endswith('/'),
             f'PR #{n} unsafe original base')
        need((dependency is None and ref == 'main') or
             (dependency is not None and ref != 'main'),
             f'PR #{n}: base/dependency mismatch')
        seen.add(n)
    return items


class GitHubREST:
    """Never log authentication headers or sensitive response bodies."""
    def __init__(self, repository, token):
        need(isinstance(token, str) and bool(token), 'GitHub token missing')
        self.base = 'https://api.github.com/repos/' + repository
        self.token = token

    def call(self, path, method='GET', body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = Request(self.base + path, data=data, method=method,
                      headers={'Accept': 'application/vnd.github+json',
                               'X-GitHub-Api-Version': '2022-11-28',
                               'Authorization': 'Bearer ' + self.token,
                               'User-Agent': 'BitChord-integration-controller',
                               'Content-Type': 'application/json'})
        try:
            with urlopen(req, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as error:
            raise StopIntegration(
                f'GitHub API HTTP {error.code}: {method} {path}') from None
        except (URLError, TimeoutError):
            raise StopIntegration(
                f'GitHub API unavailable: {method} {path}') from None

    def repo_settings(self):
        return self.call('')

    def branch(self):
        return self.call('/branches/main')

    def ruleset(self):
        return self.call('/rulesets/25365768')

    def pr(self, number):
        return self.call(f'/pulls/{number}')

    def files(self, number):
        found = []
        for page in range(1, 12):
            rows = self.call(
                f'/pulls/{number}/files?per_page=100&page={page}')
            need(isinstance(rows, list), 'Invalid PR file API response')
            found.extend(row.get('filename') for row in rows)
            if len(rows) < 100:
                return found
        raise StopIntegration(f'PR #{number}: over 1000 changed files')

    def compare(self, base, head):
        return self.call('/compare/' + quote(base, safe='') + '...' +
                         quote(head, safe=''))

    def update_base(self, number, base):
        return self.call(f'/pulls/{number}', 'PATCH', {'base': base})

    def update_branch(self, number, head):
        return self.call(f'/pulls/{number}/update-branch', 'PUT',
                         {'expected_head_sha': head})

    def checks(self, sha):
        result = self.call(f'/commits/{sha}/check-runs?per_page=100')
        need(result.get('total_count', 0) <= 100, 'Ambiguous check pagination')
        return result.get('check_runs', [])

    def merge(self, number, head):
        return self.call(f'/pulls/{number}/merge', 'PUT',
                         {'sha': head, 'merge_method': 'merge'})

    def main_ci(self, sha):
        result = self.call(
            '/actions/workflows/check.yml/runs?branch=main&event=push&per_page=30')
        return [r for r in result.get('workflow_runs', [])
                if r.get('head_sha') == sha]


class IntegrationController:
    def __init__(self, api, repository, *, sleep=time.sleep,
                 clock=time.monotonic, timeout_seconds=2400, logger=print):
        self.api, self.repository = api, repository
        self.sleep, self.clock = sleep, clock
        self.timeout_seconds, self.log = timeout_seconds, logger

    def main_sha(self):
        branch = self.api.branch()
        need(branch.get('protected') is True, 'Main is not protected')
        sha = branch.get('commit', {}).get('sha')
        need(isinstance(sha, str) and SHA.fullmatch(sha), 'Invalid main SHA')
        return sha

    def verify_rules(self):
        repo = self.api.repo_settings()
        need(repo.get('allow_update_branch') is True and
             repo.get('allow_auto_merge') is True and
             repo.get('allow_merge_commit') is True,
             'Required repository switches are disabled')
        rules = self.api.ruleset()
        need(rules.get('enforcement') == 'active' and
             not rules.get('bypass_actors'),
             'Ruleset disabled or bypass enabled')
        types = {r['type']: r.get('parameters', {})
                 for r in rules.get('rules', [])}
        pull = types.get('pull_request', {})
        need(pull.get('allowed_merge_methods') == ['merge'] and
             pull.get('required_review_thread_resolution') is True,
             'Merge-only PR protection changed')
        checks = types.get('required_status_checks', {})
        need(checks.get('strict_required_status_checks_policy') is True and
             any(s.get('context') == REQUIRED
                 for s in checks.get('required_status_checks', [])),
             'Required strict Android check changed')

    def checked_pr(self, entry, base, *, initial):
        n = entry['number']
        pr = self.api.pr(n)
        need(pr.get('state') == 'open' and
             pr.get('draft') is False and
             pr.get('merged') is False,
             f'PR #{n}: closed, merged or draft')
        head = pr.get('head') or {}
        need((head.get('repo') or {}).get('full_name') == self.repository,
             f'PR #{n}: external fork head refused')
        if initial:
            need(head.get('sha') == entry['expected_head_sha'],
                 f'PR #{n}: approved HEAD changed')
            need((pr.get('base') or {}).get('ref') ==
                 entry['expected_base_ref'],
                 f'PR #{n}: approved original base changed')
        else:
            need((pr.get('base') or {}).get('ref') == base,
                 f'PR #{n}: unexpected base')
        need(sorted(self.api.files(n)) == sorted(entry['allowed_files']),
             f'PR #{n}: diff outside approved file allowlist')
        return pr

    def poll(self, operation, description, deadline):
        while True:
            result = operation()
            if result is not None:
                return result
            need(self.clock() < deadline, f'Timeout: {description}')
            self.sleep(15)

    def wait_head_update(self, n, old, main, deadline):
        def check():
            need(self.main_sha() == main,
                 f'PR #{n}: main changed during synchronization')
            pr = self.api.pr(n)
            need(pr.get('state') == 'open', f'PR #{n}: PR closed during sync')
            sha = pr['head']['sha']
            if sha == old:
                return None
            need(isinstance(sha, str) and SHA.fullmatch(sha),
                 'Updated HEAD invalid')
            need(pr['base']['ref'] == 'main', 'Unexpected base after sync')
            return sha
        return self.poll(check, f'PR #{n} sync', deadline)

    def wait_premerge(self, entry, main, head, deadline):
        n = entry['number']
        def check():
            need(self.main_sha() == main,
                 f'PR #{n}: main changed during required CI')
            pr = self.checked_pr(entry, 'main', initial=False)
            need(pr['head']['sha'] == head, f'PR #{n}: head drift')
            need(pr['base']['sha'] == main, f'PR #{n}: base SHA drift')
            checks = [c for c in self.api.checks(head)
                      if c.get('name') == REQUIRED]
            need(len(checks) <= 1, f'PR #{n}: duplicate Android checks')
            if checks and checks[0].get('status') == 'completed':
                need(checks[0].get('conclusion') == 'success',
                     f'PR #{n}: pre-merge CI RED')
                state = pr.get('mergeable_state')
                need(state not in ('dirty', 'unstable'),
                     f'PR #{n}: conflicts / unresolved review')
                if pr.get('mergeable') is True and state == 'clean':
                    return True
            return None
        return self.poll(check, f'PR #{n} required CI', deadline)

    def wait_postmerge(self, merged_sha, deadline):
        def check():
            need(self.main_sha() == merged_sha,
                 'Main changed during post-merge CI')
            runs = self.api.main_ci(merged_sha)
            need(len(runs) <= 1, 'Ambiguous post-merge CI')
            if runs:
                run = runs[0]
                need(run.get('event') == 'push' and
                     run.get('head_sha') == merged_sha,
                     'Post-merge CI was not from main push at exact SHA')
                if run.get('status') == 'completed':
                    need(run.get('conclusion') == 'success',
                         f'Post-merge CI RED: {merged_sha}')
                    checks = [c for c in self.api.checks(merged_sha)
                              if c.get('name') == REQUIRED]
                    need(len(checks) == 1 and
                         checks[0].get('status') == 'completed' and
                         checks[0].get('conclusion') == 'success',
                         'Post-merge required check not GREEN')
                    return True
            return None
        return self.poll(check, f'post-merge CI {merged_sha}', deadline)

    def run(self, manifest, mode, expected_main_sha):
        entries = validate_manifest(manifest, self.repository)
        need(mode in ('plan', 'execute'), 'Invalid mode')
        need(isinstance(expected_main_sha, str) and
             SHA.fullmatch(expected_main_sha), 'Exact main SHA is mandatory')
        self.verify_rules()
        main = self.main_sha()
        need(main == expected_main_sha,
             'Main changed since approval of this batch')
        if mode == 'plan':
            for entry in entries:
                pr = self.checked_pr(
                    entry, entry['expected_base_ref'], initial=True)
                self.log(f"PLAN #{entry['number']}: head={pr['head']['sha']} "
                         f"base={pr['base']['ref']} "
                         f"files={len(entry['allowed_files'])}; no writes")
            self.log('PLAN OK: read-only, zero writes')
            return

        deadline = self.clock() + self.timeout_seconds
        merged = set()
        for entry in entries:
            need(self.clock() < deadline, 'Queue budget exceeded')
            n = entry['number']
            need(self.main_sha() == main,
                 f'PR #{n}: main changed outside queue')
            pr = self.checked_pr(
                entry, entry['expected_base_ref'], initial=True)
            dependency = entry['depends_on']
            if dependency is not None:
                need(dependency in merged,
                     f'PR #{n}: parent must merge earlier in same batch')
                self.api.update_base(n, 'main')
                pr = self.checked_pr(entry, 'main', initial=False)
            head = pr['head']['sha']
            compare = self.api.compare('main', head)
            need(type(compare.get('behind_by')) is int,
                 'Unknown divergence; cannot sync')
            if compare['behind_by'] > 0:
                self.api.update_branch(n, head)
                head = self.wait_head_update(n, head, main, deadline)
            need(self.api.compare('main', head).get('behind_by') == 0,
                 f'PR #{n}: still behind main after sync')
            self.wait_premerge(entry, main, head, deadline)
            need(self.main_sha() == main,
                 f'PR #{n}: main changed before merge')
            self.log(f'MERGE #{n}: head={head}, main={main}')
            result = self.api.merge(n, head)
            new_main = result.get('sha')
            need(result.get('merged') is True and
                 isinstance(new_main, str) and SHA.fullmatch(new_main),
                 f'PR #{n}: GitHub did not confirm merge')
            need(self.main_sha() == new_main,
                 f'PR #{n}: merged SHA not main HEAD')
            self.log(f'POST-CI #{n}: waiting for {new_main}')
            self.wait_postmerge(new_main, deadline)
            merged.add(n)
            main = new_main
            self.log(f'GREEN #{n}: new main={main}')
        self.log(f'BATCH GREEN: {len(merged)} PRs; main={main}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['plan', 'execute'], default='plan')
    parser.add_argument('--batch', required=True)
    parser.add_argument('--expected-main-sha', required=True)
    args = parser.parse_args()
    need(re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,60}', args.batch),
         'Unsafe batch identifier')
    need(os.environ.get('GITHUB_REPOSITORY') == 'jssantogit/BitChord',
         'Only the canonical repository may run the queue')
    need(os.environ.get('GITHUB_REF') == 'refs/heads/main',
         'Workflow must be dispatched from main')
    if args.mode == 'execute':
        need(os.environ.get('INTEGRATION_WRITE_ENABLED') == 'true',
             'Owner write gate is disabled')
        need(os.environ.get('INTEGRATION_APP_TOKEN'),
             'Dedicated GitHub App installation token is required')
    token = (os.environ.get('INTEGRATION_APP_TOKEN') if args.mode == 'execute'
             else os.environ.get('GITHUB_TOKEN'))
    path = Path('.github/integration-batches') / (args.batch + '.json')
    need(path.is_file(), 'Approved batch manifest not found')
    batch = json.loads(path.read_text(encoding='utf-8'))
    IntegrationController(
        GitHubREST('jssantogit/BitChord', token),
        'jssantogit/BitChord').run(
            batch, mode=args.mode, expected_main_sha=args.expected_main_sha)


if __name__ == '__main__':
    try:
        main()
    except (StopIntegration, ValueError, KeyError) as error:
        print(f'INTEGRATION HALTED: {error}', file=sys.stderr)
        sys.exit(1)
