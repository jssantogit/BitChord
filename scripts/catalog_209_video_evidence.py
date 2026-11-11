#!/usr/bin/env python3
"""Two-call exact-MBID source probe for video-only MusicBrainz release #209.

The probe does not import to Room, infer audio availability or verify Android UI.
"""

import argparse
import json
from pathlib import Path
import re
from typing import Callable

VIDEO_RELEASE = "c3d3777d-2d77-4d6f-9473-ec480b66c27a"
VIDEO_GROUP = "cd544c25-e9c4-4e52-a146-0761ef6e006f"
VIDEO_RECORDING = "2c124b3c-e76b-4046-9980-e88c5ffa4ab2"
VIDEO_TRACK = "38f39d28-3f1d-40ce-b0e2-f77ea6d5eba7"
MBID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
VIDEO_FORMATS = frozenset({
    "dvd-video", "dualdisc (dvd-video side)", "dvdplus (dvd-video side)",
    "vcd", "svcd", "vhs", "betamax", "laserdisc", '8" laserdisc', '12" laserdisc',
})
AUDIO_FORMATS = frozenset({
    "cd", "dvd-audio", "vinyl", "cassette", "sacd", "minidisc",
    "dualdisc (cd side)", "dualdisc (dvd-audio side)",
    "dvdplus (cd side)", "dvdplus (dvd-audio side)",
})


def evaluate_release(payload: object) -> tuple[str, dict]:
    """Match the Kotlin source-evidence policy: Digital Media alone proves neither."""
    if not isinstance(payload, dict):
        return "FAIL", {"invalid_release": True}
    group = payload.get("release-group")
    group_id = group.get("id") if isinstance(group, dict) else None
    proof = {
        "release_mbid": payload.get("id"), "release_group_mbid": group_id,
        "release_group_identity_matches": group_id == VIDEO_GROUP,
        "status": payload.get("status"), "media_formats": [],
        "recording_mbid": None, "recording_video_true_count": 0,
        "recording_video_false_count": 0, "recording_video_unknown_count": 0,
        "is_video_only_content": None,
    }
    if (payload.get("id") != VIDEO_RELEASE or group_id != VIDEO_GROUP or
            payload.get("status") != "Official"):
        return "FAIL", proof
    media = payload.get("media")
    if not isinstance(media, list) or not media:
        return "INCONCLUSIVE", proof
    formats = []
    flags = []
    found_expected = False
    all_valid = True
    for medium in media:
        if not isinstance(medium, dict):
            all_valid = False
            continue
        fmt = medium.get("format")
        formats.append(fmt if isinstance(fmt, str) else None)
        tracks = medium.get("tracks")
        if not isinstance(tracks, list) or not tracks:
            all_valid = False
            continue
        for track in tracks:
            if not isinstance(track, dict):
                all_valid = False
                continue
            rec = track.get("recording")
            track_id = track.get("id")
            rec_id = rec.get("id") if isinstance(rec, dict) else None
            if not isinstance(track_id, str) or not MBID.fullmatch(track_id):
                all_valid = False
            if not isinstance(rec_id, str) or not MBID.fullmatch(rec_id):
                all_valid = False
            if track_id == VIDEO_TRACK and rec_id == VIDEO_RECORDING:
                found_expected = True
                proof["recording_mbid"] = rec_id
            video = rec.get("video") if isinstance(rec, dict) else None
            flag = video if type(video) is bool else None
            flags.append(flag)
            key = ("recording_video_true_count" if flag is True else
                   "recording_video_false_count" if flag is False else
                   "recording_video_unknown_count")
            proof[key] += 1
    proof["media_formats"] = formats
    if not all_valid:
        return "INCONCLUSIVE", proof
    if not found_expected:
        return "FAIL", proof
    video_formats = bool(formats) and all(
        isinstance(fmt, str) and fmt.lower() in VIDEO_FORMATS for fmt in formats)
    audio_formats = any(
        isinstance(fmt, str) and fmt.lower() in AUDIO_FORMATS for fmt in formats)
    evidence = (True if video_formats or (flags and all(x is True for x in flags))
                else False if audio_formats or any(x is False for x in flags)
                else None)
    proof["is_video_only_content"] = evidence
    return ("PASS" if evidence is True else "FAIL" if evidence is False
            else "INCONCLUSIVE"), proof


def evaluate_recording(payload: object) -> tuple[str, dict]:
    if not isinstance(payload, dict):
        return "FAIL", {"invalid_recording": True}
    evidence = {"recording_mbid": payload.get("id"), "video": payload.get("video")}
    if payload.get("id") != VIDEO_RECORDING:
        return "FAIL", evidence
    if payload.get("video") is True:
        return "PASS", evidence
    if payload.get("video") is False:
        return "FAIL", evidence
    return "INCONCLUSIVE", evidence


def inspect_video_case(get: Callable) -> dict:
    """Just one exact Release and one exact Recording, no search/browse."""
    result = {
        "scope": "PUBLIC_API_NOT_ANDROID_UI",
        "case": "NEWJEANS-NEW-JEANS-MUSIC-VIDEO",
        "evidence_type": "EXACT_PUBLIC_MUSICBRAINZ_API",
        "status": "INCONCLUSIVE", "release": None, "recording": None,
        "http_lookup_budget": 2,
    }
    try:
        release = get("release", VIDEO_RELEASE, inc="recordings+release-groups")
        release_status, release_evidence = evaluate_release(release)
        result["release"] = {"status": release_status, "evidence": release_evidence}
        if release_status != "PASS":
            result["status"] = release_status
            return result
        rec = get("recording", VIDEO_RECORDING)
        rec_status, rec_evidence = evaluate_recording(rec)
        result["recording"] = {"status": rec_status, "evidence": rec_evidence}
        result["status"] = rec_status
    except Exception as exc:
        # A failed provider response does not imply a missing release.
        result["status"] = "ERROR"
        result["source_error"] = type(exc).__name__
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    from catalog_178_musicbrainz_live_probe import PublicMusicBrainz

    client = PublicMusicBrainz(interval=1.1, timeout=15.0, max_http_requests=4)
    report = inspect_video_case(client.get)
    report["http_attempt_count"] = len(client.requests)
    report["max_http_requests"] = 4
    report["http_status_counts"] = {
        str(code): sum(1 for item in client.requests if item.get("http_status") == code)
        for code in sorted({item.get("http_status") for item in client.requests},
                           key=lambda x: (x is None, str(x)))
    }
    args.output.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({k: report[k] for k in (
        "case", "status", "http_attempt_count",
        "max_http_requests", "http_status_counts",
    )}, indent=2))
    return 0 if report["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
