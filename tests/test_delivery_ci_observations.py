"""DI5 first-party CI metrics: no speculative PR attribution."""
import unittest
from tools.delivery_ci_observations import collect_observations, ObservationError

SHA = 'a' * 40

class Reader:
    repository = 'jssantogit/BitChord'
    def __init__(self):
        self.runs = [{'id': 100, 'head_sha': SHA, 'event': 'pull_request',
                      'head_repository': {'full_name': self.repository},
                      'pull_requests': [{'number': 90}]},
                     {'id': 101, 'head_sha': 'b' * 40, 'event': 'push',
                      'head_repository': {'full_name': self.repository},
                      'pull_requests': []}]
    def runs_page(self, page):
        return self.runs if page == 1 else []
    def jobs(self, run):
        return [{'id': 10, 'name': 'Android · test and build',
                 'created_at': '2026-10-19T20:00:00Z',
                 'started_at': '2026-10-19T20:02:00Z',
                 'completed_at': '2026-10-19T20:06:00Z',
                 'conclusion': 'success'}]

class ObservationTests(unittest.TestCase):
    def test_exact_timestamps_without_guessing_pr_for_main_push(self):
        data = collect_observations(Reader(), limit=10)
        self.assertEqual([100, 101], [x['run_id'] for x in data['jobs']])
        self.assertEqual(90, data['jobs'][0]['pr_number'])
        self.assertIsNone(data['jobs'][1]['pr_number'])
        self.assertEqual('2026-10-19T20:02:00Z', data['jobs'][0]['started_at'])
        self.assertNotIn('head_repository', str(data))

    def test_rejects_wrong_repository_and_ambiguous_job_list(self):
        r = Reader()
        r.repository = 'foreign/repo'
        with self.assertRaises(ObservationError):
            collect_observations(r)
        r = Reader()
        r.jobs = lambda n: [{'name': 'Android · test and build'}] * 100
        with self.assertRaises(ObservationError):
            collect_observations(r)

    def test_missing_job_never_fabricates_duration(self):
        r = Reader()
        r.jobs = lambda n: []
        self.assertEqual([], collect_observations(r)['jobs'])

    def test_errors_redacted(self):
        r = Reader()
        r.jobs = lambda n: (_ for _ in ()).throw(RuntimeError('TOKEN=private'))
        with self.assertRaises(ObservationError) as ctx:
            collect_observations(r)
        self.assertNotIn('private', str(ctx.exception))

    def test_duplicate_android_job_and_malformed_head_are_rejected(self):
        r = Reader()
        r.jobs = lambda n: [{'id': 12, 'name': 'Android · test and build',
                               'created_at': '2026-10-19T20:00:00Z'}] * 2
        with self.assertRaises(ObservationError):
            collect_observations(r)
        r = Reader()
        r.runs[0]['head_sha'] = 'bad'
        with self.assertRaises(ObservationError):
            collect_observations(r)

    def test_lane_classification_uses_explicit_run_source_only(self):
        reader = Reader()
        reader.runs[0]['head_branch'] = 'catalog/c13-test'
        reader.runs[1]['head_branch'] = 'main'
        observed = collect_observations(reader)
        self.assertEqual(['pr_a', 'main'],
                         [job['lane'] for job in observed['jobs']])
        self.assertNotIn('catalog/c13-test', str(observed))
        self.assertNotIn('head_branch', str(observed))

    def test_lane_b_and_unknown_never_guess_from_pr_or_sha(self):
        reader = Reader()
        reader.runs[0]['head_branch'] = 'playback/b13'
        reader.runs[1]['head_branch'] = 'not-the-main-branch'
        self.assertEqual(['pr_b', 'unknown'], [
            job['lane'] for job in collect_observations(reader)['jobs']])
        del reader.runs[0]['head_branch']
        self.assertEqual('unknown',
                         collect_observations(reader)['jobs'][0]['lane'])
        reader.runs[0]['head_branch'] = 'token=SECRET_IN_REF'
        data = collect_observations(reader)
        self.assertEqual('pr_b', data['jobs'][0]['lane'])
        self.assertNotIn('SECRET_IN_REF', str(data))

if __name__ == '__main__':
    unittest.main()
