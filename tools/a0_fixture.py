#!/usr/bin/env python3
"""A0 reproducible, user-owned Soulseek test WAV. No external calls or credentials."""

import argparse
import hashlib
import json
import struct
import wave
from pathlib import Path

NAME = "bitchord-a0-owned-synthetic-8000hz.wav"
MANIFEST = "bitchord-a0-fixture-manifest.json"
RATE = 8000
SECONDS = 2


def audio_bytes() -> bytes:
    # Deterministic integer triangle tone (no random seed, codecs or downloads).
    pcm = bytearray()
    period = 80
    for frame in range(RATE * SECONDS):
        phase = frame % period
        triangular = phase if phase <= period // 2 else period - phase
        value = (triangular - period // 4) * 400
        pcm.extend(struct.pack("<h", value))
    from io import BytesIO
    memory = BytesIO()
    with wave.open(memory, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm)
    return memory.getvalue()


def generate(directory: Path) -> tuple[Path, Path]:
    directory = Path(directory).expanduser().resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    sound = directory / NAME
    manifest = directory / MANIFEST
    if sound.exists() or manifest.exists():
        raise FileExistsError("Existing test fixture must not be overwritten")
    payload = audio_bytes()
    info = {
        "filename": NAME,
        "sha256": hashlib.sha256(payload).hexdigest(),
        "size_bytes": len(payload),
        "duration_seconds": SECONDS,
        "sample_rate_hz": RATE,
        "channels": 1,
        "sample_width_bytes": 2,
        "rights": "Original synthetic tone; test owner permits this fixture to be shared for A0 validation",
        "provenance": "Generated offline by tools/a0_fixture.py; never obtained from Soulseek",
    }
    try:
        with sound.open("xb") as fh:
            fh.write(payload)
        with manifest.open("x", encoding="utf-8") as fh:
            json.dump(info, fh, ensure_ascii=False, sort_keys=True, indent=2)
            fh.write("\n")
    except BaseException:
        sound.unlink(missing_ok=True)
        manifest.unlink(missing_ok=True)
        raise
    return sound, manifest


def verify(directory: Path) -> bool:
    directory = Path(directory).expanduser().resolve()
    wav = directory / NAME
    manifest = directory / MANIFEST
    if not wav.is_file() or not manifest.is_file():
        return False
    try:
        info = json.loads(manifest.read_text(encoding="utf-8"))
        if info.get("filename") != NAME:
            return False
        if info.get("sample_rate_hz") != RATE or info.get("duration_seconds") != SECONDS:
            return False
        length = 0
        hash_value = hashlib.sha256()
        with wav.open("rb") as inp:
            while block := inp.read(8192):
                length += len(block)
                hash_value.update(block)
        if length != info.get("size_bytes") or hash_value.hexdigest() != info.get("sha256"):
            return False
        # Also reject a matching manifest paired with a different WAV fixture.
        if hash_value.hexdigest() != hashlib.sha256(audio_bytes()).hexdigest():
            return False
        with wave.open(str(wav), "rb") as sound:
            return (sound.getnchannels(), sound.getsampwidth(), sound.getframerate(), sound.getnframes()) == (
                1, 2, RATE, RATE * SECONDS
            )
    except (OSError, ValueError, KeyError, wave.Error, TypeError):
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Create/verify synthetic A0 Soulseek proof fixture without networking")
    parser.add_argument("--directory", type=Path, required=True, help="New dedicated folder; share only its WAV via an owner-controlled peer")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--generate", action="store_true")
    mode.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.generate:
        wav, manifest = generate(args.directory)
        print(f"Created owned WAV: {wav.name}")
        print(f"Created reference manifest: {manifest.name}")
        print(f"Verification: {'PASS' if verify(args.directory) else 'FAIL'}")
        return 0
    result = verify(args.directory)
    print(f"Fixture verification: {'PASS' if result else 'FAIL'}")
    return 0 if result else 1


if __name__ == "__main__":
    raise SystemExit(main())
