"""DI4c: portable, independently pinned read-only restart evidence.

Produces an inert envelope suitable for a separately reviewed durable transport.
No authenticated network calls, GitHub refs, merge operations, or approvals.
"""
import hashlib
import hmac
import json
import re

from tools.delivery_journal import load, _validate_manifest

class TransferError(ValueError):
    pass

SHA = re.compile(r"^[0-9a-f]{40}$")
HASH = re.compile(r"^[0-9a-f]{64}$")
REPO = "jssantogit/BitChord"
REF = "refs/heads/main"
EVENT = "workflow_dispatch"
MAX_BYTES = 65536

def _need(ok):
    if not ok:
        raise TransferError("invalid_or_untrusted_checkpoint")

def _canonical(data):
    return json.dumps(data, sort_keys=True, ensure_ascii=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")

def _digest(data):
    return hashlib.sha256(_canonical(data)).hexdigest()

def _validate_source(run_id, attempt, sha, event, ref):
    _need(type(run_id) is int and run_id > 0 and
          type(attempt) is int and attempt > 0 and attempt <= 100 and
          type(sha) is str and bool(SHA.fullmatch(sha)) and
          event == EVENT and ref == REF)

def _validate_checkpoint(journal, manifest, main_sha):
    _need(type(journal) is dict and set(journal) ==
          {"version", "repository", "main_sha", "manifest_digest",
           "revision", "items", "approval_required", "executable", "integrity"})
    integrity = journal["integrity"]
    content = {k: v for k, v in journal.items() if k != "integrity"}
    _need(type(integrity) is str and bool(HASH.fullmatch(integrity)) and
          hmac.compare_digest(integrity, _digest(content)))
    _need(journal["version"] == 1 and journal["repository"] == REPO and
          journal["approval_required"] is True and
          journal["executable"] is False and
          journal["main_sha"] == main_sha and
          journal["manifest_digest"] == _digest(manifest) and
          type(journal["revision"]) is int and journal["revision"] >= 0)
    items = journal["items"]
    _need(type(items) is list and len(items) == len(manifest["items"]))
    for row, pinned in zip(items, manifest["items"]):
        _need(type(row) is dict and set(row) ==
              {"number", "expected_head_sha", "stage"} and
              type(row["number"]) is int and row["number"] == pinned["number"] and
              type(row["expected_head_sha"]) is str and
              row["expected_head_sha"] == pinned["expected_head_sha"] and
              row["stage"] in ("planned", "preflight_green"))
    return items

def export_evidence(path, manifest, *, run_id, attempt, source_main_sha,
                    event, ref):
    """Create a private/reviewable artifact, not execution authority.

    Caller must publish its digest out of band alongside source workflow-run ID.
    """
    try:
        _validate_manifest(manifest)
        _validate_source(run_id, attempt, source_main_sha, event, ref)
        state = load(path)
        _validate_checkpoint(state, manifest, source_main_sha)
        envelope = {
            "version": 1, "repository": REPO,
            "source": {"run_id": run_id, "attempt": attempt,
                       "main_sha": source_main_sha, "event": event, "ref": ref},
            "journal": state,
        }
        blob = _canonical(envelope)
        _need(len(blob) <= MAX_BYTES)
        return blob, _digest(envelope)
    except Exception:
        raise TransferError("invalid_or_untrusted_checkpoint") from None

def inspect_evidence(blob, manifest, *, sha256, run_id, attempt,
                     expected_main_sha):
    """Compare independently pinned artifact bytes, validate identity, stay inert."""
    try:
        _validate_manifest(manifest)
        _validate_source(run_id, attempt, expected_main_sha, EVENT, REF)
        _need(type(sha256) is str and bool(HASH.fullmatch(sha256)) and
              type(blob) is bytes and len(blob) <= MAX_BYTES)
        _need(hmac.compare_digest(hashlib.sha256(blob).hexdigest(), sha256))
        data = json.loads(blob.decode("utf-8"))
        _need(type(data) is dict and set(data) ==
              {"version", "repository", "source", "journal"} and
              data["version"] == 1 and data["repository"] == REPO)
        src = data["source"]
        _need(type(src) is dict and set(src) ==
              {"run_id", "attempt", "main_sha", "event", "ref"} and
              type(src["run_id"]) is int and src["run_id"] == run_id and
              type(src["attempt"]) is int and src["attempt"] == attempt and
              src["main_sha"] == expected_main_sha and
              src["event"] == EVENT and src["ref"] == REF)
        rows = _validate_checkpoint(data["journal"], manifest, expected_main_sha)
        return {"version": 1, "repository": REPO,
                "run_id": run_id, "run_attempt": attempt,
                "main_sha": expected_main_sha,
                "approval_required": True, "executable": False,
                "replay_merge": False, "decision": "require_live_revalidation",
                "items": [{"number": x["number"], "stage": x["stage"]}
                          for x in rows]}
    except Exception:
        raise TransferError("invalid_or_untrusted_checkpoint") from None
