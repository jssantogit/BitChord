"""Safety contract: configuration-cache compatibility check is isolated and ephemeral."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / '.github/workflows/android-ci-config-cache-pilot.yml'


class ConfigurationCachePilotContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.yaml = WORKFLOW.read_text(encoding='utf-8')

    def test_isolated_nonrequired_and_read_only(self):
        s = self.yaml
        self.assertIn('name: Android Configuration Cache Compatibility Pilot', s)
        self.assertIn('  pull_request:', s)
        self.assertNotIn('\n  push:', s)
        self.assertNotIn('  workflow_dispatch:', s)
        self.assertNotIn('  schedule:', s)
        self.assertIn('  contents: read', s)
        self.assertNotIn('contents: write', s)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", s)
        self.assertIn("github.head_ref == 'infra/android-ci-configcache-compat-v0'", s)
        self.assertIn('cache-read-only: true', s)

    def test_same_android_test_target_both_times_without_secret_cache_export(self):
        s = self.yaml
        target = './gradlew --build-cache --configuration-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest'
        self.assertEqual(2, s.count(target))
        self.assertIn('Reusing configuration cache.', s)
        self.assertIn('GITHUB_STEP_SUMMARY', s)
        self.assertIn('LASTFM_API_KEY=ci-placeholder', s)
        self.assertIn('LASTFM_SECRET=ci-placeholder', s)
        self.assertNotIn('secrets.', s)
        self.assertNotIn('cache-encryption-key:', s)
        self.assertNotIn('upload-artifact', s)
        self.assertNotIn('--configuration-cache-problems=warn', s)
        self.assertNotIn('assembleDevDebug', s)
        self.assertNotIn('integration-apply', s)
        self.assertNotIn('env: GRADLE_ENCRYPTION_KEY', s)


    def test_android_cache_directory_is_initialized_before_capture(self):
        s = self.yaml
        preparation = 'mkdir -p "$HOME/.config/.android/cache"'
        cold = './gradlew --build-cache --configuration-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest'
        self.assertIn(preparation, s)
        self.assertLess(s.index(preparation), s.index(cold))
        self.assertEqual(1, s.count(preparation))


if __name__ == '__main__':
    unittest.main()
