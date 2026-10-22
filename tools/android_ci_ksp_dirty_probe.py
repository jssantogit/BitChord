"""Privacy-safe, bounded KSP2 incremental log presence and size diagnostics.

Raw files often include source paths, symbol names, timestamps and output paths.
Never return their text or infer a causal dirty-set reason from line counts.
"""
import argparse
import json
from pathlib import Path
import re
import sys


class ProbeError(ValueError):
    pass


_SCOPES = ("devDebug", "devDebugUnitTest")
_LOGS = {
    "dirty_set": "kspDirtySet.log",
    "dirty_by_deps": "kspDirtySetByDeps.log",
    "dirty_by_outputs": "kspDirtySetByOutputs.log",
    "source_to_outputs": "kspSourceToOutputs.log",
}
_TASKS = {
    ":app:kspDevDebugKotlin": "app_ksp_main",
    ":app:kspDevDebugUnitTestKotlin": "app_ksp_unit",
    ":app:compileDevDebugKotlin": "app_kotlin_main",
    ":app:testDevDebugUnitTest": "app_tests",
}
_STATES = {
    "": "executed",
    "FROM-CACHE": "from_cache",
    "UP-TO-DATE": "up_to_date",
    "NO-SOURCE": "no_source",
    "SKIPPED": "skipped",
}
_ALLOWED_CASES = ("sharedUi", "control")
_ALLOWED_PHASES = ("before", "after")
_ANSI = re.compile(r"\x1b\[[0-9;]*m")
_DURATION = re.compile(r"^BUILD SUCCESSFUL in (?:(\d+)m(?: (\d+)s)?|(\d+)s)$")
_TOTAL = re.compile(r"^(\d+) actionable tasks: (.+)$")
_MAX_LOG = 1_000_000


def _need(condition):
    if not condition:
        raise ProbeError("unsafe_or_incomplete_ksp_dirty_set_evidence")


def _parse_gradle(log):
    _need(type(log) is str and 0 < len(log) <= 8_000_000)
    tasks = {}
    duration = None
    actionable = None
    for raw in log.splitlines():
        line = _ANSI.sub("", raw).strip()
        if line.startswith("BUILD FAILED"):
            raise ProbeError("unsafe_or_incomplete_ksp_dirty_set_evidence")
        if line.startswith("BUILD SUCCESSFUL"):
            _need(duration is None)
            match = _DURATION.fullmatch(line)
            _need(match is not None)
            minutes, seconds, seconds_only = match.groups()
            duration = (int(minutes) * 60 + int(seconds or 0)
                        if minutes is not None else int(seconds_only))
        elif line.startswith("> Task "):
            for task, name in _TASKS.items():
                if line == "> Task " + task or line.startswith("> Task " + task + " "):
                    _need(name not in tasks)
                    suffix = line[len("> Task " + task):].strip()
                    _need(suffix in _STATES)
                    tasks[name] = _STATES[suffix]
                    break
        else:
            total = _TOTAL.fullmatch(line)
            if total:
                _need(actionable is None)
                actionable = int(total.group(1))
    _need(duration is not None and duration > 0 and actionable is not None)
    _need(actionable >= len(_TASKS) and tasks.keys() == set(_TASKS.values()))
    return duration, actionable, tasks


def _log_counts(ksp_root):
    root = Path(ksp_root)
    _need(not root.is_symlink())
    total_logs = 0
    result = {}
    presence = {}
    for scope in _SCOPES:
        scoped = root / scope
        directory = scoped / "logs"
        _need(not scoped.is_symlink() and not directory.is_symlink())
        row = {}
        present_row = {}
        for key, filename in _LOGS.items():
            path = directory / filename
            _need(not path.is_symlink())
            if not path.exists():
                row[key] = 0
                present_row[key] = False
                continue
            present_row[key] = True
            _need(path.is_file())
            _need(path.stat().st_size <= _MAX_LOG)
            payload = path.read_bytes()
            _need(len(payload) <= _MAX_LOG)
            try:
                data = payload.decode("utf-8")
            except UnicodeError:
                raise ProbeError("unsafe_or_incomplete_ksp_dirty_set_evidence") from None
            lines = data.splitlines()
            _need(len(lines) <= 20_000)
            row[key] = sum(bool(item.strip()) for item in lines)
            total_logs += 1
        result[scope] = row
        presence[scope] = present_row
    status = ("unobserved" if total_logs == 0 else
              "observed" if total_logs == len(_SCOPES) * len(_LOGS) else
              "partial")
    return result, presence, status


def summarize_probe(gradle_log, ksp_root, case, phase):
    _need(type(case) is str and case in _ALLOWED_CASES)
    _need(type(phase) is str and phase in _ALLOWED_PHASES)
    try:
        duration, actionable, tasks = _parse_gradle(gradle_log)
        counts, present, state = _log_counts(ksp_root)
        return {
            "version": 1, "case": case, "phase": phase,
            "gradle_seconds": duration,
            "actionable_tasks": actionable,
            "tasks": tasks,
            "ksp_log_availability": state,
            "record_lines": counts,
            "log_present": present,
            # Log line counts alone cannot distinguish classpath full
            # reprocessing from aggregating processor input invalidation.
            "ksp_causal_reason_proven": False,
        }
    except ProbeError:
        raise
    except Exception:
        raise ProbeError("unsafe_or_incomplete_ksp_dirty_set_evidence") from None


# Gradle's --info task up-to-date diagnoses may include source paths,
# configuration values, and other private identifiers. Recognize only the
# fixed task identity and *input property kind*, never return any raw token.
_INFO_REASONS = {
    ":app:kspDevDebugKotlin": "app_ksp_main",
    ":app:kspDevDebugUnitTestKotlin": "app_ksp_unit",
}
_INPUT_PROPERTY = re.compile(r"""^\s+Input property ['"]([A-Za-z][A-Za-z0-9_]*)['"](?=\s|$)""")
_OUTPUT_PROPERTY = re.compile(r"""^\s+Output property ['"][^'"]+['"](?=\s|$)""")
_REASON_LIMIT = 40


def _safe_input_reason(line):
    match = _INPUT_PROPERTY.match(line)
    if match:
        prop = match.group(1).lower()
        if "classpath" in prop:
            return "classpath_changed"
        if prop.startswith("source") or prop.endswith("sources"):
            return "sources_changed"
        if ("option" in prop or "argument" in prop or "compilerplugin" in prop):
            return "config_changed"
    if _OUTPUT_PROPERTY.match(line):
        return "outputs_changed"
    if line.lstrip().lower().startswith("no history is available"):
        return "history_missing"
    return None


def summarize_gradle_input_reasons(gradle_log, case):
    """Classify Gradle's --info reason categories, without leaking inputs.

    This describes Gradle task up-to-date evidence, not an independently
    proven KSP/Room source or classpath root cause.
    """
    _need(type(case) is str and case in _ALLOWED_CASES)
    duration, actionable, task_states = _parse_gradle(gradle_log)
    _need(type(gradle_log) is str)
    result = {}
    for task, alias in _INFO_REASONS.items():
        state = task_states[alias]
        header = "Task '" + task + "' is not up-to-date because:"
        matches = [i for i, line in enumerate(gradle_log.splitlines())
                   if _ANSI.sub("", line).strip() == header]
        _need(len(matches) <= 1)
        if state != "executed":
            _need(not matches)
            result[alias] = {
                "task_state": state,
                "reason_availability": "not_applicable",
                "categories": [],
            }
            continue
        if not matches:
            result[alias] = {
                "task_state": state,
                "reason_availability": "unobserved",
                "categories": [],
            }
            continue
        lines = gradle_log.splitlines()
        categories = set()
        for raw in lines[matches[0]+1:matches[0]+1+_REASON_LIMIT]:
            line = _ANSI.sub("", raw)
            if (line.startswith("> Task ") or
                line.startswith("Task ':") or
                line.startswith("Caching disabled for task") or
                (line and not line[0].isspace())):
                break
            category = _safe_input_reason(line)
            if category:
                categories.add(category)
        result[alias] = {
            "task_state": state,
            "reason_availability": ("observed" if categories
                                    else "observed_unclassified"),
            "categories": sorted(categories),
        }
    return {
        "version": 1,
        "case": case,
        "gradle_seconds": duration,
        "actionable_tasks": actionable,
        "tasks": result,
        "root_cause_proven": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="Local redacted KSP2 dirty-set summaries")
    parser.add_argument("--log", required=True)
    parser.add_argument("--ksp-root", required=True)
    parser.add_argument("--case", required=True, choices=_ALLOWED_CASES)
    parser.add_argument("--phase", required=True, choices=_ALLOWED_PHASES)
    options = parser.parse_args(argv)
    try:
        data = Path(options.log).read_bytes()
        _need(len(data) <= 8_000_000)
        report = summarize_probe(
            data.decode("utf-8"), options.ksp_root, options.case, options.phase)
    except Exception:
        parser.exit(2, "KSP DIRTY PROBE HALTED: unsafe_or_incomplete_evidence\n")
    json.dump(report, sys.stdout, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
