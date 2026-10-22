"""Parse exactly one full Android Kotlin/KSP unit-test run.

Task summaries are bounded, typed, allowlisted and source-path free.
No source filenames, arbitrary Gradle output or environment variables escape.
"""
import argparse
import json
from pathlib import Path
import re
import sys


class ProbeError(ValueError):
    pass


_TASKS = {
    ":shared:compileDebugKotlinAndroid": "shared_kotlin",
    ":sharedUi:compileDebugKotlinAndroid": "sharedui_kotlin",
    ":app:kspDevDebugKotlin": "app_ksp",
    ":app:compileDevDebugKotlin": "app_kotlin",
    ":app:kspDevDebugUnitTestKotlin": "app_unit_ksp",
    ":app:compileDevDebugUnitTestKotlin": "app_unit_kotlin",
    ":app:testDevDebugUnitTest": "app_unit_tests",
}
_CASES = ("app", "shared", "sharedUi", "control")
_STATES = {
    "": "executed",
    "FROM-CACHE": "from_cache",
    "UP-TO-DATE": "up_to_date",
    "NO-SOURCE": "no_source",
    "SKIPPED": "skipped",
}
_ANSI = re.compile(r"\x1b\[[0-9;]*m")
_DURATION = re.compile(
    r"^BUILD SUCCESSFUL in (?:(\d+)m(?: (\d+)s)?|(\d+)s)$"
)
_TOTAL = re.compile(
    r"^(\d+) actionable tasks: (.+)$"
)


def _need(ok):
    if not ok:
        raise ProbeError("invalid_or_incomplete_invalidation_evidence")


def analyze_task_log(log, source_case):
    _need(type(log) is str and 0 < len(log) <= 8_000_000)
    _need(type(source_case) is str and source_case in _CASES)
    results = {}
    duration = None
    actionable = None
    for raw in log.splitlines():
        line = _ANSI.sub("", raw).strip()
        if line.startswith("BUILD SUCCESSFUL"):
            _need(duration is None)
            m = _DURATION.fullmatch(line)
            _need(m is not None)
            minutes, seconds, only_secs = m.groups()
            duration = (
                int(minutes) * 60 + int(seconds or 0)
                if minutes is not None else int(only_secs)
            )
        elif line.startswith("BUILD FAILED"):
            raise ProbeError("invalid_or_incomplete_invalidation_evidence")
        elif line.startswith("> Task "):
            # Distinguish an unexpected status on one of our named tasks from
            # an unobserved task. Do not emit arbitrary task names.
            for task, safe in _TASKS.items():
                if line == "> Task " + task or line.startswith("> Task " + task + " "):
                    _need(safe not in results)
                    suffix = line[len("> Task " + task):].strip()
                    _need(suffix in _STATES)
                    results[safe] = _STATES[suffix]
                    break
        elif _TOTAL.fullmatch(line):
            _need(actionable is None)
            actionable = int(_TOTAL.fullmatch(line).group(1))
    _need(duration is not None and actionable is not None)
    _need(actionable >= len(_TASKS) and results.keys() == set(_TASKS.values()))
    return {
        "version": 1,
        "source_case": source_case,
        "gradle_seconds": duration,
        "actionable_tasks": actionable,
        "tasks": results,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Bounded per-module Kotlin/KSP task outcome probe")
    parser.add_argument("--log", required=True)
    parser.add_argument("--case", required=True, choices=_CASES)
    args = parser.parse_args(argv)
    try:
        data = Path(args.log).read_bytes()
        _need(len(data) <= 8_000_000)
        output = analyze_task_log(data.decode("utf-8"), args.case)
    except (OSError, UnicodeError, ProbeError):
        parser.exit(2, "MODULE PROBE HALTED: invalid_or_incomplete_invalidation_evidence\n")
    json.dump(output, sys.stdout, ensure_ascii=False, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
