"""Read-only, privacy-safe evidence for Android CI Gradle cache policy.

Consumes explicit GitHub Actions job-log text. Never fetches authenticated data,
echoes raw log lines, cache keys, app/user content or job environment values.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import sys


class EvidenceError(ValueError):
    pass


_SHA = re.compile(r"^[0-9a-f]{40}$")
_TIMESTAMP = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z) (.*)$")
_ANSI = re.compile(r"\x1b\[[0-9;]*m")
_DURATION = re.compile(r"^BUILD SUCCESSFUL in (?:(\d+)m(?: (\d+)s)?|(\d+)s)$")
_COUNTS = re.compile(r"^(\d+) actionable tasks: (\d+) executed, (\d+) from cache$")
_TASK = re.compile(r"^> Task (:[A-Za-z0-9:_-]+)(?: (FROM-CACHE|UP-TO-DATE|NO-SOURCE|SKIPPED))?$")
_RUN = "##[group]Run ./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest"
_WRITE_START = "##[group]Caching Gradle state"
_END = "##[endgroup]"
_READ_ONLY = "Cache is read-only: will not save state for use in subsequent builds."
# Only classify the workflow component of a valid Gradle home restore key.
# Keys can contain sensitive metadata: the original string is NEVER returned.
_RESTORE_FAMILY = re.compile(
    r"^Cache hit for restore-key: gradle-home-v1\\|Linux-X64\\|"
    r"([a-z0-9][a-z0-9-]{0,63})\\[[0-9a-f]{32}\\]-[0-9a-f]{40}$"
)
_FAMILY_LABELS = {
    "android": "android_workflow",
    "build-smoke-apk": "build_smoke_apk_workflow",
}
_TASKS = {
    ":app:kspDevDebugKotlin": "ksp_main",
    ":app:compileDevDebugKotlin": "kotlin_main",
    ":app:kspDevDebugUnitTestKotlin": "ksp_tests",
    ":app:compileDevDebugUnitTestKotlin": "kotlin_tests",
    ":app:testDevDebugUnitTest": "android_tests",
    ":shared:compileDebugKotlinAndroid": "shared_kotlin",
}
_STATUS = {"FROM-CACHE": "from_cache", "UP-TO-DATE": "up_to_date",
           "NO-SOURCE": "no_source", "SKIPPED": "skipped"}


def _need(ok):
    if not ok:
        raise EvidenceError("incomplete_or_invalid_cache_evidence")


def _seconds(label):
    m = _DURATION.fullmatch(label)
    _need(m is not None)
    minutes, seconds, only_seconds = m.groups()
    return 60 * int(minutes) + int(seconds or 0) if minutes is not None else int(only_seconds)


def summarize_cache_job(log, head_sha, lane):
    """Summarize a single complete job log, never inferring write behavior by lane."""
    _need(type(log) is str and len(log) <= 8_000_000)
    _need(type(head_sha) is str and bool(_SHA.fullmatch(head_sha)))
    _need(lane in ("main", "pr") and type(lane) is str)
    lines = log.splitlines()
    _need(0 < len(lines) <= 100_000)
    started = False
    duration = None
    counts = None
    task_results = {name: None for name in _TASKS.values()}
    write_started = None
    write_seconds = None
    write_count = 0
    read_only = False
    last_instant = None
    restored_family = None

    for raw in lines:
        match = _TIMESTAMP.match(raw)
        _need(match is not None)
        timestamp, message = match.groups()
        try:
            instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        except ValueError:
            raise EvidenceError("incomplete_or_invalid_cache_evidence") from None
        _need(instant.tzinfo is not None)
        instant = instant.astimezone(timezone.utc)
        _need(last_instant is None or instant >= last_instant)
        last_instant = instant
        message = _ANSI.sub("", message).strip()

        restore = _RESTORE_FAMILY.fullmatch(message)
        if restore:
            family = _FAMILY_LABELS.get(restore.group(1), "other_workflow")
            _need(restored_family is None or restored_family == family)
            restored_family = family

        if message == _RUN:
            _need(not started)
            started = True
        if started and duration is None:
            m = _TASK.fullmatch(message)
            if m and m.group(1) in _TASKS:
                task_results[_TASKS[m.group(1)]] = _STATUS.get(m.group(2), "executed")
            if message.startswith("BUILD SUCCESSFUL in "):
                duration = _seconds(message)
            elif message == "BUILD FAILED":
                raise EvidenceError("incomplete_or_invalid_cache_evidence")
        elif started and counts is None:
            match_counts = _COUNTS.fullmatch(message)
            if match_counts:
                total, executed, from_cache = [int(n) for n in match_counts.groups()]
                _need(executed + from_cache <= total)
                counts = (total, executed, from_cache)

        if message == _WRITE_START:
            _need(duration is not None and counts is not None)
            _need(write_started is None and write_seconds is None and not read_only)
            write_started = instant
        elif message == _END and write_started is not None:
            write_seconds = int((instant - write_started).total_seconds())
            _need(write_seconds >= 0)
            write_started = None
        elif write_started is not None and message.startswith("Saved cache entry with key "):
            write_count += 1

        if message == _READ_ONLY:
            _need(write_started is None and write_seconds is None)
            read_only = True

    _need(started and duration is not None and counts is not None)
    _need(write_started is None)
    if read_only:
        policy = "read_only"
        write_seconds = 0
        saved = 0
    elif write_seconds is not None:
        policy = "written"
        saved = write_count
    else:
        policy = "unobserved"
        saved = None

    return {
        "version": 1,
        "head_sha": head_sha,
        "lane": lane,
        "gradle_seconds": duration,
        "actionable_tasks": counts[0],
        "executed_tasks": counts[1],
        "from_cache_tasks": counts[2],
        "write_policy": policy,
        # Workflow family from a syntactically valid *reported* restore key,
        # NOT proof of which branch created it or that PR outputs can reach main.
        "cache_restore_status": "hit" if restored_family is not None else "unobserved",
        "cache_restore_workflow_family": restored_family,
        "cache_write_phase_seconds": write_seconds,
        "saved_cache_entries": saved,
        "tasks": task_results,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read-only Android CI cache evidence")
    parser.add_argument("--input", required=True, help="local job log file or '-'")
    parser.add_argument("--sha", required=True, help="actual exact 40-char job HEAD")
    parser.add_argument("--lane", required=True, choices=("main", "pr"))
    args = parser.parse_args(argv)
    try:
        if args.input == "-":
            raw = sys.stdin.read(8_000_001)
        else:
            with open(args.input, encoding="utf-8") as fd:
                raw = fd.read(8_000_001)
        result = summarize_cache_job(raw, args.sha, args.lane)
    except (OSError, UnicodeError, EvidenceError):
        parser.exit(2, "CI CACHE EVIDENCE HALTED: incomplete_or_invalid_cache_evidence\n")
    json.dump(result, sys.stdout, ensure_ascii=False, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
