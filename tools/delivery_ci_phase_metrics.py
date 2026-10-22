"""Read-only phase timing of the existing required Android GitHub Actions job.

Use GitHub's own step timestamps, not inferred Gradle durations. Output is a
bounded allowlist: no raw branch names, step logs, actors, secrets or cache keys.
"""
import argparse
from datetime import datetime, timezone
import json
import os
import re
import statistics

from tools.delivery_ci_observations import (
    CHECK, REPO, GitHubCIReader, _lane_for_run,
)


class PhaseError(ValueError):
    pass


_SHA = re.compile(r"^[0-9a-f]{40}$")
_LANES = ("main", "pr_a", "pr_b", "pr_c", "pr_d", "unknown")
_STEPS = {
    "Set up Gradle": "gradle_setup_seconds",
    "Run Android unit tests": "android_test_step_seconds",
    "Post Set up Gradle": "gradle_post_seconds",
}
_CONCLUSIONS = (None, "success", "failure", "cancelled", "skipped",
                "timed_out", "action_required", "neutral")


def _need(condition):
    if not condition:
        raise PhaseError("invalid_or_incomplete_android_phase_observation")


def _time(value, optional=False):
    if optional and value is None:
        return None
    _need(type(value) is str)
    try:
        instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise PhaseError("invalid_or_incomplete_android_phase_observation") from None
    _need(instant.tzinfo is not None and instant.utcoffset() is not None)
    return instant.astimezone(timezone.utc)


def _delta(start, end):
    if start is None or end is None:
        return None
    elapsed = int((end - start).total_seconds())
    _need(elapsed >= 0)
    return elapsed


def _median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def _p90(values):
    values = sorted(v for v in values if v is not None)
    # Never publish a tail estimate from fewer than three observations.
    if len(values) < 3:
        return None
    return values[(9 * len(values) + 9) // 10 - 1]


def _job_phase(job, run, lane):
    _need(type(job) is dict and job.get("name") == CHECK)
    _need(type(job.get("id")) is int and job["id"] > 0)
    _need(job.get("conclusion") in _CONCLUSIONS)
    created = _time(job.get("created_at"))
    started = _time(job.get("started_at"), optional=True)
    finished = _time(job.get("completed_at"), optional=True)
    _need(started is not None or finished is None)
    _need(finished is None or job.get("conclusion") is not None)
    _need(finished is not None or job.get("conclusion") is None)
    queue_seconds = _delta(created, started)
    job_seconds = _delta(started, finished)
    steps = job.get("steps") or []
    _need(type(steps) is list and len(steps) <= 50)
    seen = set()
    phase = dict.fromkeys(_STEPS.values())
    completed_steps = set()
    for raw in steps:
        _need(type(raw) is dict)
        name = raw.get("name")
        if name not in _STEPS:
            continue
        _need(name not in seen)
        seen.add(name)
        s = _time(raw.get("started_at"), optional=True)
        e = _time(raw.get("completed_at"), optional=True)
        _need(s is not None or e is None)
        if s is not None:
            _need(started is not None and s >= started)
        if e is not None:
            _need(finished is not None and e <= finished)
        elapsed = _delta(s, e)
        if raw.get("conclusion") == "skipped":
            elapsed = None
        if elapsed is not None:
            completed_steps.add(name)
        phase[_STEPS[name]] = elapsed

    if finished is not None and job["conclusion"] == "success":
        _need("Run Android unit tests" in completed_steps)
        test = next(x for x in steps if x.get("name") == "Run Android unit tests")
        _need(test.get("conclusion") == "success")
    non_test = None
    if job_seconds is not None and phase["android_test_step_seconds"] is not None:
        non_test = job_seconds - phase["android_test_step_seconds"]
        _need(non_test >= 0)
    return {
        "run_id": run["id"],
        "head_sha": run["head_sha"],
        "lane": lane,
        "conclusion": job["conclusion"],
        "queue_seconds": queue_seconds,
        "job_seconds": job_seconds,
        **phase,
        "non_test_step_seconds": non_test,
    }


def collect_phase_observations(reader, limit=25):
    """Observe job + allowlisted step phases, never speculating about queue cause."""
    _need(getattr(reader, "repository", None) == REPO)
    _need(type(limit) is int and 1 <= limit <= 30)
    try:
        runs = reader.runs_page(1)
        _need(type(runs) is list and len(runs) <= 100)
        seen = set()
        jobs = []
        for run in runs[:limit]:
            _need(type(run) is dict)
            run_id = run.get("id")
            sha = run.get("head_sha")
            _need(type(run_id) is int and run_id > 0 and run_id not in seen)
            seen.add(run_id)
            _need(type(sha) is str and bool(_SHA.fullmatch(sha)))
            _need((run.get("head_repository") or {}).get("full_name") == REPO)
            event = run.get("event")
            _need(event in ("pull_request", "push"))
            lane = _lane_for_run(event, run.get("head_branch"))
            _need(lane in _LANES)
            observed = reader.jobs(run_id)
            _need(type(observed) is list and len(observed) < 100)
            required = [x for x in observed if type(x) is dict and x.get("name") == CHECK]
            _need(len(required) <= 1)
            for job in required:
                jobs.append(_job_phase(job, run, lane))
        jobs.sort(key=lambda x: x["run_id"])
        metrics_by_lane = {}
        for lane in _LANES:
            group = [j for j in jobs if j["lane"] == lane]
            metrics_by_lane[lane] = {
                "jobs": len(group),
                "finished_jobs": sum(j["job_seconds"] is not None for j in group),
                "median_queue_seconds": _median(j["queue_seconds"] for j in group),
                "p90_queue_seconds": _p90(j["queue_seconds"] for j in group),
                "median_job_seconds": _median(j["job_seconds"] for j in group),
                "median_gradle_setup_seconds": _median(j["gradle_setup_seconds"] for j in group),
                "median_android_test_step_seconds": _median(j["android_test_step_seconds"] for j in group),
                "median_gradle_post_seconds": _median(j["gradle_post_seconds"] for j in group),
                "median_non_test_step_seconds": _median(j["non_test_step_seconds"] for j in group),
            }
        return {"version": 1, "jobs": jobs, "metrics_by_lane": metrics_by_lane}
    except PhaseError:
        raise
    except Exception:
        # Never surface raw API payloads or unexpected exception messages.
        raise PhaseError("read_error_or_invalid_phase_observation") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description="GET-only required Android CI step timings")
    parser.add_argument("--repo", required=True, help="fixed repository: jssantogit/BitChord")
    parser.add_argument("--limit", type=int, default=25)
    args = parser.parse_args(argv)
    try:
        _need(args.repo == REPO)
        data = collect_phase_observations(
            GitHubCIReader(os.environ.get("GITHUB_TOKEN")), args.limit)
    except PhaseError:
        parser.exit(2, "ANDROID PHASE REPORT HALTED: read_error_or_invalid_phase_observation\n")
    json.dump(data, __import__("sys").stdout, indent=2, sort_keys=True)
    print()


if __name__ == "__main__":
    main()
