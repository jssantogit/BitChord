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

if __name__ == '__main__':
    unittest.main()
