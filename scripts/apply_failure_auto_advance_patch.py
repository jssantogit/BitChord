from pathlib import Path

path = Path("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
text = path.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "    private var consecutiveErrorSkips = 0\n",
    """    private var consecutiveErrorSkips = 0

    /** Runtime-only loop guard for selected-asset playback failures. */
    private val failureCascade = PlaybackFailureCascade()

    /** Logical queue entry that most recently produced audible playback. */
    private var audibleFailureEntryKey: String? = null
""",
)

replace_once(
    """                audibleMediaId = exoPlayer.currentMediaItem?.mediaId
                consecutiveErrorSkips = 0
""",
    """                audibleMediaId = exoPlayer.currentMediaItem?.mediaId
                consecutiveErrorSkips = 0
                audibleFailureEntryKey = failureEntryKeyOrNull(
                    exoPlayer,
                    exoPlayer.currentMediaItemIndex,
                )
                failureCascade.onPlaybackStarted()
""",
)

replace_once(
    "        onUserIntent = { partySync?.onLocalIntent() },\n",
    """        onUserIntent = {
            failureCascade.onExplicitUserIntent()
            partySync?.onLocalIntent()
        },
""",
)

replace_once(
    """        val item = player.currentMediaItem ?: return
        // Provider-specific failures are terminal for this direct-play vertical;
        // do not reinterpret the canonical id as a YouTube/source fallback key.
        if (item.isCanonicalRemotePlayback()) return
        val mediaId = item.mediaId
""",
    """        val item = player.currentMediaItem ?: return
        val currentFailureKey = failureEntryKeyOrNull(player, player.currentMediaItemIndex)
        val handling = failureCascade.handlePlaybackFailure(
            error = error,
            player = player,
            neverStarted = currentFailureKey == null || currentFailureKey != audibleFailureEntryKey,
        )
        if (handling != PlaybackFailureHandling.NOT_HANDLED) return
        val mediaId = item.mediaId
""",
)

path.write_text(text)
