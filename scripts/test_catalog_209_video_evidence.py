import unittest
from catalog_209_video_evidence import (
    VIDEO_RELEASE, VIDEO_GROUP, VIDEO_RECORDING, VIDEO_TRACK,
    evaluate_release, evaluate_recording, inspect_video_case,
)


def sample_release(*, video=True, format_name="Digital Media", group=VIDEO_GROUP,
                   missing_recording=False, second_audio=False):
    records = [{"id": VIDEO_TRACK, "title": "New Jeans",
                "recording": {"id": VIDEO_RECORDING, "title": "New Jeans"}}]
    if not missing_recording:
        records[0]["recording"]["video"] = video
    if second_audio:
        records.append({"id": "33333333-3333-4333-8333-333333333333",
                        "recording": {"id": "44444444-4444-4444-8444-444444444444",
                                      "video": False}})
    return {
        "id": VIDEO_RELEASE, "title": "New Jeans", "status": "Official",
        "release-group": {"id": group, "primary-type": "Other"},
        "media": [{"format": format_name, "tracks": records,
                   "track-count": len(records)}],
    }


class VideoEvidenceTest(unittest.TestCase):
    def test_video_only_digital_media_is_positive_from_recordings(self):
        status, evidence = evaluate_release(sample_release())
        self.assertEqual("PASS", status)
        self.assertEqual("Digital Media", evidence["media_formats"][0])
        self.assertEqual(1, evidence["recording_video_true_count"])
        self.assertTrue(evidence["is_video_only_content"])
        self.assertEqual(VIDEO_RECORDING, evidence["recording_mbid"])

    def test_digital_media_without_flags_is_not_proof_of_audio_or_video(self):
        status, evidence = evaluate_release(sample_release(missing_recording=True))
        self.assertEqual("INCONCLUSIVE", status)
        self.assertIsNone(evidence["is_video_only_content"])

    def test_explicit_audio_or_mixed_release_is_not_video_only(self):
        for payload in (sample_release(video=False), sample_release(second_audio=True)):
            with self.subTest(payload=payload):
                status, evidence = evaluate_release(payload)
                self.assertEqual("FAIL", status)
                self.assertFalse(evidence["is_video_only_content"])

    def test_group_identity_mismatch_fails_closed(self):
        status, evidence = evaluate_release(sample_release(
            group="99999999-9999-4999-8999-999999999999"))
        self.assertEqual("FAIL", status)
        self.assertFalse(evidence["release_group_identity_matches"])

    def test_dvd_video_is_positive_evidence_even_without_recording_flag(self):
        status, evidence = evaluate_release(sample_release(
            format_name="DVD-Video", missing_recording=True))
        self.assertEqual("PASS", status)
        self.assertTrue(evidence["is_video_only_content"])

    def test_independent_recording_video_true(self):
        self.assertEqual(("PASS", {"recording_mbid": VIDEO_RECORDING, "video": True}),
                         evaluate_recording({"id": VIDEO_RECORDING, "video": True}))
        self.assertEqual("INCONCLUSIVE",
                         evaluate_recording({"id": VIDEO_RECORDING})[0])
        self.assertEqual("FAIL",
                         evaluate_recording({"id": VIDEO_RECORDING, "video": False})[0])

    def test_two_exact_lookup_operations_only(self):
        calls = []
        def get(kind, ident, **params):
            calls.append((kind, ident, params))
            if kind == "release":
                return sample_release()
            if kind == "recording":
                return {"id": VIDEO_RECORDING, "video": True}
            raise AssertionError("unbounded discovery is not permitted")
        report = inspect_video_case(get)
        self.assertEqual("PASS", report["status"])
        self.assertEqual([("release", VIDEO_RELEASE),
                          ("recording", VIDEO_RECORDING)],
                         [(k, i) for k, i, _ in calls])

    def test_provider_timeout_is_not_release_absence(self):
        def get(*args, **kwargs):
            raise TimeoutError("upstream unavailable")
        report = inspect_video_case(get)
        self.assertEqual("ERROR", report["status"])
        self.assertEqual("PUBLIC_API_NOT_ANDROID_UI", report["scope"])
        self.assertNotIn("source_exists", report)


if __name__ == "__main__":
    unittest.main()
