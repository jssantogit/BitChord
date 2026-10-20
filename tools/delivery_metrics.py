"""DI1: deterministic read-only delivery observations; never persist raw API payloads.

Consumes an explicitly supplied JSON snapshot (no authenticated calls or writes).
Outputs an allowlisted, versioned report without raw errors, tokens or actor data.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import statistics
import sys


class DeliveryDataError(ValueError):
    pass


_SHA = re.compile(r"^[0-9a-f]{40}$")
_STAGES = {"discovery", "plan", "sync", "pre_ci", "merge", "post_ci"}
_REASONS = {
    "HTTP_401": "authentication",
    "HTTP_403": "permissions",
    "HTTP_409": "conflict",
    "HTTP_422": "policy",
    "SHA_DRIFT": "stale_sha",
    "DIFF_DRIFT": "unexpected_files",
    "CI_RED": "ci_failure",
    "CI_TIMEOUT": "ci_timeout",
    "REVIEW_BLOCKED": "review",
}
_LANES = ('main', 'pr_a', 'pr_b', 'pr_c', 'unknown')
_CONCLUSIONS = {None, "success", "failure", "cancelled", "skipped",
                "timed_out", "action_required", "neutral"}


def _need(ok, reason):
    if not ok:
        raise DeliveryDataError(reason)


def _number(value, field, *, nullable=False):
    if nullable and value is None:
        return None
    _need(type(value) is int and value > 0, "invalid " + field)
    return value


def _instant(value, field, *, nullable=False):
    if nullable and value is None:
        return None
    _need(isinstance(value, str), "invalid " + field)
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise DeliveryDataError("invalid " + field) from exc
    _need(moment.tzinfo is not None and moment.utcoffset() is not None,
          "missing timezone for " + field)
    return moment.astimezone(timezone.utc)


def _elapsed(start, end):
    if start is None or end is None:
        return None
    seconds = int((end - start).total_seconds())
    _need(seconds >= 0, "nonmonotonic timestamps")
    return seconds


def _median(values):
    return statistics.median(values) if values else None


def summarize(payload):
    """Return safe normalized data from first-party PR/job/block observations.

    Does not infer missing timestamps, queue delays or actual CI attribution.
    Only explicitly supplied numeric PR links are associated with jobs.
    """
    _need(isinstance(payload, dict), "invalid report payload")
    prs = payload.get("prs", [])
    jobs = payload.get("jobs", [])
    blocks = payload.get("blocks", [])
    _need(all(isinstance(v, list) for v in (prs, jobs, blocks)),
          "observations must be arrays")
    output_prs, output_jobs, output_blocks = [], [], []
    seen_pr, seen_jobs = set(), set()

    for raw in prs:
        _need(isinstance(raw, dict), "invalid PR")
        n = _number(raw.get("number"), "PR number")
        _need(n not in seen_pr, "duplicate PR")
        seen_pr.add(n)
        created = _instant(raw.get("created_at"), "PR creation")
        merged = _instant(raw.get("merged_at"), "PR merge", nullable=True)
        output_prs.append({"number": n,
                           "lifetime_seconds": _elapsed(created, merged),
                           "merged": merged is not None})

    for raw in jobs:
        _need(isinstance(raw, dict), "invalid job")
        run = _number(raw.get("run_id"), "run ID")
        n = _number(raw.get("pr_number"), "job PR", nullable=True)
        sha = raw.get("head_sha")
        _need(isinstance(sha, str) and bool(_SHA.fullmatch(sha)),
              "invalid job HEAD")
        key = (run, n, sha)
        _need(key not in seen_jobs, "duplicate job")
        seen_jobs.add(key)
        created = _instant(raw.get("created_at"), "job queued")
        started = _instant(raw.get("started_at"), "job start", nullable=True)
        ended = _instant(raw.get("completed_at"), "job finish", nullable=True)
        _need(ended is None or started is not None,
              "finished job without start")
        conclusion = raw.get("conclusion")
        _need(conclusion in _CONCLUSIONS, "invalid job conclusion")
        lane = raw.get("lane", "unknown")
        _need(type(lane) is str and lane in _LANES, "invalid job lane")
        output_jobs.append({
            "run_id": run, "pr_number": n, "head_sha": sha,
            "lane": lane, "queue_seconds": _elapsed(created, started),
            "execution_seconds": _elapsed(started, ended),
            "conclusion": conclusion,
        })

    for raw in blocks:
        _need(isinstance(raw, dict), "invalid block")
        n = _number(raw.get("pr_number"), "block PR", nullable=True)
        stage = raw.get("stage")
        code = raw.get("code")
        _need(stage in _STAGES, "invalid block stage")
        _need(isinstance(code, str) and bool(re.fullmatch(r"[A-Z0-9_]{1,48}", code)),
              "invalid block code")
        output_blocks.append({"pr_number": n, "stage": stage,
                              "reason": _REASONS.get(code, "other"),
                              "code": code if code in _REASONS else "UNKNOWN"})

    output_prs.sort(key=lambda p: p["number"])
    output_jobs.sort(key=lambda j: (j["run_id"], j["pr_number"] or 0, j["head_sha"]))
    output_blocks.sort(key=lambda b: (b["pr_number"] or 0, b["stage"], b["reason"]))
    queued = [j["queue_seconds"] for j in output_jobs if j["queue_seconds"] is not None]
    executed = [j["execution_seconds"] for j in output_jobs if j["execution_seconds"] is not None]
    metrics_by_lane = {}
    for lane in _LANES:
        group = [job for job in output_jobs if job["lane"] == lane]
        queue_times = [job["queue_seconds"] for job in group
                       if job["queue_seconds"] is not None]
        run_times = [job["execution_seconds"] for job in group
                     if job["execution_seconds"] is not None]
        metrics_by_lane[lane] = {
            "jobs": len(group),
            "finished_jobs": len(run_times),
            "median_queue_seconds": _median(queue_times),
            "median_execution_seconds": _median(run_times),
        }
    return {"version": 1, "prs": output_prs, "jobs": output_jobs,
            "blocks": output_blocks, "metrics_by_lane": metrics_by_lane,
            "metrics": {
                "observed_prs": len(output_prs),
                "merged_prs": sum(p["merged"] for p in output_prs),
                "observed_jobs": len(output_jobs),
                "finished_jobs": len(executed),
                "median_queue_seconds": _median(queued),
                "median_execution_seconds": _median(executed),
                "observed_blocks": len(output_blocks),
            }}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only delivery report from JSON")
    parser.add_argument("--input", required=True, help="local JSON snapshot or '-' for stdin")
    args = parser.parse_args(argv)
    try:
        if args.input == "-":
            data = json.load(sys.stdin)
        else:
            with open(args.input, encoding="utf-8") as source:
                data = json.load(source)
        report = summarize(data)
    except (ValueError, OSError) as exc:
        parser.exit(2, "DELIVERY REPORT HALTED: " + type(exc).__name__ + "\n")
    json.dump(report, sys.stdout, sort_keys=True, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
