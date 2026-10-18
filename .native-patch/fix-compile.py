from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one bounded match, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1))

replace_once(
    "app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt",
    "fun MediaController.playCanonicalRemote(\n",
    "internal fun MediaController.playCanonicalRemote(\n",
)

replace_once(
    "app/src/main/java/com/music/bitchord/ui/screens/CanonicalRemoteLibraryScreen.kt",
    "                onTrackPlay = { track ->\n"
    "                    controller?.playCanonicalRemote(\n"
    "                        request = CanonicalRemotePlaybackRequest(\n"
    "                            recordingId = track.recordingId,\n"
    "                            mediaAssetId = track.mediaAssetId,\n"
    "                            providerKey = track.providerKey,\n"
    "                        ),\n"
    "                        metadata = CanonicalRemotePlaybackMetadata(\n"
    "                            title = track.title,\n"
    "                            artistDisplay = track.artistDisplay,\n"
    "                        ),\n"
    "                    )\n"
    "                },\n",
    "                onTrackPlay = { track ->\n"
    "                    track.availableAssets.firstOrNull()?.let { asset ->\n"
    "                        controller?.playCanonicalRemote(\n"
    "                            request = CanonicalRemotePlaybackRequest(\n"
    "                                recordingId = track.recordingId,\n"
    "                                mediaAssetId = asset.mediaAssetId,\n"
    "                                providerKey = asset.providerKey,\n"
    "                            ),\n"
    "                            metadata = CanonicalRemotePlaybackMetadata(\n"
    "                                title = track.title,\n"
    "                                artistDisplay = track.artistDisplay,\n"
    "                            ),\n"
    "                        )\n"
    "                    }\n"
    "                },\n",
)

print("compile fixes applied")
