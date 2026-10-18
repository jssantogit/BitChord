from pathlib import Path

path = Path("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
text = path.read_text()

pairs = [
    (
        "private var audibleFailureEntryKey: String? = null",
        "private var audibleFailureOccurrenceKey: String? = null",
    ),
    (
        "audibleFailureEntryKey = failureEntryKeyOrNull(\n                    exoPlayer,",
        "audibleFailureOccurrenceKey = failureOccurrenceKeyOrNull(\n                    exoPlayer,",
    ),
    (
        "val currentFailureKey = failureEntryKeyOrNull(player, player.currentMediaItemIndex)",
        "val currentFailureOccurrence = failureOccurrenceKeyOrNull(player, player.currentMediaItemIndex)",
    ),
    (
        "neverStarted = currentFailureKey == null || currentFailureKey != audibleFailureEntryKey,",
        "neverStarted = currentFailureOccurrence == null ||\n                currentFailureOccurrence != audibleFailureOccurrenceKey,",
    ),
]

for old, new in pairs:
    count = text.count(old)
    if count == 0 and new in text:
        continue
    if count != 1:
        raise SystemExit(f"expected one match for {old!r}, got {count}")
    text = text.replace(old, new, 1)

path.write_text(text)
