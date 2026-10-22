"""Calibrated, queue-only what-if replay for the proposed Android PR lane D.

No GitHub writes, workflow modifications, raw refs or personal data in output.
Calibrating against the observed legacy three-lane queue is mandatory before
the tool publishes even an optimistic estimated wait reduction.
"""
import argparse
from datetime import datetime, timezone
import json
import os
import re
import statistics
import sys

from tools.delivery_ci_observations import GitHubCIReader, REPO, CHECK


class LaneReplayError(ValueError):
    pass


_SHA = re.compile(r"^[0-9a-f]{40}$")
_MAX_ERROR_SECONDS = 30
_MIN_SAMPLES = 8
_MIN_PER_C_GROUP = 3


def _need(ok):
    if not ok:
        raise LaneReplayError("unsafe_or_unreliable_ci_lane_replay")


def _instant(value):
    _need(type(value) is str and len(value) <= 48)
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise LaneReplayError("unsafe_or_unreliable_ci_lane_replay") from None
    _need(dt.tzinfo is not None and dt.utcoffset() is not None)
    return dt.astimezone(timezone.utc).timestamp()


def _source_group(ref):
    _need(type(ref) is str and 0 < len(ref) <= 512)
    if ref.startswith(("catalog/", "intelligence/")):
        return "a"
    if ref.startswith("playback/"):
        return "b"
    return "c"


def _replay(events, candidate):
    available = {}
    total_wait = 0.0
    waits = []
    for event in sorted(events, key=lambda x: (x["created"], x["run_id"])):
        lane = "d" if candidate and event["eligible_d"] else event["baseline_lane"]
        start = max(event["created"], available.get(lane, event["created"]))
        wait = start - event["created"]
        _need(wait >= 0)
        available[lane] = start + event["duration"]
        total_wait += wait
        waits.append(wait)
    return total_wait, waits


def _median(values):
    return statistics.median(values) if values else None


def replay_recent_ci(reader, limit=30):
    """Compare idealized no-contention legacy and fourth-lane queue schedules.

    Actual GitHub Action timestamps are source evidence; this deterministic
    replay does not simulate shared-runner availability, global API limits or
    additional unrelated job traffic. Never claim a measured speedup.
    """
    _need(getattr(reader, "repository", None) == REPO)
    _need(type(limit) is int and 1 <= limit <= 30)
    try:
        runs = reader.runs_page(1)
        _need(type(runs) is list and 0 < len(runs) < 100)
        selected = runs[:limit]
        seen = set()
        events = []
        omitted = 0
        for run in selected:
            _need(type(run) is dict)
            rid = run.get("id")
            _need(type(rid) is int and rid > 0 and rid not in seen)
            seen.add(rid)
            sha = run.get("head_sha")
            _need(type(sha) is str and bool(_SHA.fullmatch(sha)))
            _need(run.get("event") in ("push", "pull_request"))
            if run.get("event") != "pull_request":
                continue
            _need((run.get("head_repository") or {}).get("full_name") == REPO)
            ref = run.get("head_branch")
            base_lane = _source_group(ref)
            observed = reader.jobs(rid)
            _need(type(observed) is list and len(observed) < 100)
            required = [j for j in observed if type(j) is dict
                        and j.get("name") == CHECK]
            _need(len(required) <= 1)
            if not required:
                omitted += 1
                continue
            job = required[0]
            _need(type(job.get("id")) is int and job["id"] > 0)
            if job.get("conclusion") != "success":
                # Never infer a queue or runtime duration for a pending/failed job.
                omitted += 1
                continue
            created = _instant(job.get("created_at"))
            started = _instant(job.get("started_at"))
            ended = _instant(job.get("completed_at"))
            _need(created <= started < ended)
            events.append({
                "run_id": rid,
                "created": created,
                "observed_wait": started - created,
                "duration": ended - started,
                "baseline_lane": base_lane,
                "eligible_d": ref.startswith("perf/"),
            })

        perf = sum(e["eligible_d"] for e in events)
        other_c = sum(e["baseline_lane"] == "c" and not e["eligible_d"]
                      for e in events)
        out = {
            "version": 1,
            "model": "idealized_serial_per_lane_no_shared_runner_limit",
            "status": "insufficient_sample",
            "included_jobs": len(events),
            "excluded_unfinished_or_missing_jobs": omitted,
            "performance_jobs": perf,
            "other_c_jobs": other_c,
            "baseline_median_absolute_error_seconds": None,
            "baseline_idealized_total_wait_seconds": None,
            "candidate_idealized_total_wait_seconds": None,
            "idealized_total_wait_reduction_seconds": None,
        }
        enough = (len(events) >= _MIN_SAMPLES and
                  perf >= _MIN_PER_C_GROUP and other_c >= _MIN_PER_C_GROUP and
                  omitted == 0)
        if not enough:
            return out

        baseline_total, baseline_waits = _replay(events, candidate=False)
        idealized_candidate, _ = _replay(events, candidate=True)
        observed_by_id = {
            e["run_id"]: e["observed_wait"] for e in events
        }
        ordered = sorted(events, key=lambda x: (x["created"], x["run_id"]))
        errors = [abs(predicted - observed_by_id[e["run_id"]])
                  for e, predicted in zip(ordered, baseline_waits)]
        calibration = _median(errors)
        out["baseline_median_absolute_error_seconds"] = round(calibration, 2)
        if calibration > _MAX_ERROR_SECONDS:
            out["status"] = "uncalibrated"
            return out
        out.update({
            "status": "calibrated_sample_only",
            "baseline_idealized_total_wait_seconds": round(baseline_total, 2),
            "candidate_idealized_total_wait_seconds": round(idealized_candidate, 2),
            "idealized_total_wait_reduction_seconds":
                round(max(0.0, baseline_total - idealized_candidate), 2),
        })
        return out
    except LaneReplayError:
        raise
    except Exception:
        raise LaneReplayError("unsafe_or_unreliable_ci_lane_replay") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only candidate lane D replay")
    parser.add_argument("--repo", required=True)
    parser.add_argument("--limit", type=int, default=30)
    args = parser.parse_args(argv)
    try:
        _need(args.repo == REPO)
        result = replay_recent_ci(
            GitHubCIReader(os.environ.get("GITHUB_TOKEN")), args.limit)
    except LaneReplayError:
        parser.exit(2, "CI LANE REPLAY HALTED: unsafe_or_unreliable_ci_lane_replay\n")
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
