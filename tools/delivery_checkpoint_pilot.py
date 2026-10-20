"""DI4e: publish inert DI4c evidence from an explicitly manual protected run.

Never import integration_queue, never dispatch Actions or update repository refs.
"""
import argparse
import json
import os
from pathlib import Path
import re
import tempfile

from tools.delivery_journal import initialize
from tools.delivery_transfer import export_evidence

REPO = 'jssantogit/BitChord'


class PilotError(ValueError):
    pass


def prepare_evidence(*, manifest, output, main_sha, run_id, attempt,
                     event, ref):
    """Create sealed, immutable-per-path evidence atomically, with no authority."""
    try:
        # Initialize and export validate all manifest/provenance fields.
        with tempfile.TemporaryDirectory(prefix='di4-pilot-') as folder:
            local = Path(folder) / 'private-journal.json'
            initialize(local, manifest, main_sha, authorized=False)
            blob, digest = export_evidence(
                local, manifest, run_id=run_id, attempt=attempt,
                source_main_sha=main_sha, event=event, ref=ref)
        dst = Path(output)
        if not dst.parent.is_dir() or dst.parent.is_symlink():
            raise PilotError('unsafe_output_directory')
        # Atomic create-if-absent: never replace existing or symlink target.
        fd, temp = tempfile.mkstemp(prefix='.di4-evidence-', dir=dst.parent)
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(blob)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temp, 0o600)
            os.link(temp, dst)  # EEXIST refuses unsafe replacement
            parent_fd = os.open(dst.parent, os.O_RDONLY)
            try:
                os.fsync(parent_fd)
            finally:
                os.close(parent_fd)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
        return {'sha256': digest, 'bytes': len(blob),
                'approval_required': True, 'executable': False}
    except Exception:
        raise PilotError('evidence_export_halted') from None


def main(argv=None):
    p = argparse.ArgumentParser(description='Explicit read-only DI4c evidence export')
    p.add_argument('--repo', required=True)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--main-sha', required=True)
    p.add_argument('--run-id', type=int, required=True)
    p.add_argument('--attempt', type=int, required=True)
    p.add_argument('--event', required=True)
    p.add_argument('--ref', required=True)
    args = p.parse_args(argv)
    try:
        if args.repo != REPO:
            raise PilotError('unsupported_repository')
        manifest = json.loads(args.manifest.read_text('utf-8'))
        result = prepare_evidence(
            manifest=manifest, output=args.output,
            main_sha=args.main_sha, run_id=args.run_id,
            attempt=args.attempt, event=args.event, ref=args.ref)
        print(json.dumps(result, sort_keys=True))
    except Exception:
        p.exit(2, 'DI4E HALTED: evidence_export_halted\n')


if __name__ == '__main__':
    main()
