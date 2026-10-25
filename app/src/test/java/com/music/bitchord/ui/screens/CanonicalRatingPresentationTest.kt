package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalRatingAssetRow
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.ui.components.RatingDisplayKind
import com.music.bitchord.ui.components.visibleStarValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalRatingPresentationTest {
    private val provider = "opensubsonic:account-one"
    private val otherProvider = "opensubsonic:account-two"
    private val scope = "scope-one"

    private fun asset(
        recording: String = "recording-one",
        id: String = "asset-one",
        source: String = provider,
        uri: String? = null,
    ) = CanonicalRatingAssetRow(
        recordingId = recording,
        mediaAssetId = id,
        providerKey = source,
        uri = uri,
        path = null,
    )

    @Test
    fun remoteFiveAndThreeAreSeparateRealNumericRatingsNotFavoriteFlags() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset(), asset("recording-two", "asset-two")),
            localValues = emptyMap(),
            remote = listOf(
                CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, 5, starred = false),
                CanonicalRemoteRatingInput("recording-two", "asset-two", provider, scope, 3, starred = true),
            ),
            activeProvider = provider,
            activeScope = scope,
        )
        assertEquals(5, result.byMediaAssetId["asset-one"]?.remoteValue)
        assertEquals(3, result.byMediaAssetId["asset-two"]?.remoteValue)
        assertEquals(RatingDisplayKind.RATED, result.byMediaAssetId["asset-two"]?.kind)
    }

    @Test
    fun localAndRemoteConflictRemainsVisibleWithoutOverwritingEitherSource() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset()),
            localValues = mapOf("recording-one" to 4.5),
            remote = listOf(CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, 3, false)),
            activeProvider = provider,
            activeScope = scope,
        )
        val display = result.byMediaAssetId.getValue("asset-one")
        assertEquals(4.5, display.localValue!!, 0.0)
        assertEquals(3, display.remoteValue)
        assertEquals(RatingDisplayKind.CONFLICT, display.kind)
    }

    @Test
    fun starredAloneNeverCreatesFiveStars() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset()),
            localValues = emptyMap(),
            remote = listOf(CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, null, true)),
            activeProvider = provider,
            activeScope = scope,
        )
        assertNull(result.byMediaAssetId["asset-one"])
    }

    @Test
    fun foreignAccountAndOldScopeCannotLeakRemoteRatings() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset(), asset("recording-two", "foreign-asset", otherProvider)),
            localValues = emptyMap(),
            remote = listOf(
                CanonicalRemoteRatingInput("recording-one", "asset-one", provider, "old-scope", 5, false),
                CanonicalRemoteRatingInput("recording-two", "foreign-asset", otherProvider, scope, 5, false),
            ),
            activeProvider = provider,
            activeScope = scope,
        )
        assertTrue(result.byMediaAssetId.isEmpty())
    }

    @Test
    fun localOnlyAndNoRatingBehaveWithoutInventedRemoteNotes() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(
                asset(source = "local-mediastore", uri = "content://media/audio/1"),
                asset("unrated", "asset-unrated", "local-mediastore", "content://media/audio/2"),
            ),
            localValues = mapOf("recording-one" to 2.0),
            remote = emptyList(),
            activeProvider = null,
            activeScope = null,
        )
        assertEquals(2.0, result.bySongKey["content://media/audio/1"]?.localValue!!, 0.0)
        assertFalse(result.bySongKey.containsKey("content://media/audio/2"))
    }

    @Test
    fun recordingBadgeIsAvailableOnlyWhenItsMediaAssetsAgree() {
        val same = buildCanonicalRatingPresentation(
            assets = listOf(
                asset("r", "a", "local-mediastore", "content://music/a"),
                asset("r", "b", "local-mediastore", "content://music/b"),
            ),
            localValues = mapOf("r" to 4.5),
            remote = emptyList(),
            activeProvider = null,
            activeScope = null,
        )
        assertEquals(4.5, same.byRecordingId["r"]?.localValue!!, 0.0)

        val ambiguous = buildCanonicalRatingPresentation(
            assets = listOf(
                asset("r", "a", provider),
                asset("r", "b", provider),
            ),
            localValues = emptyMap(),
            remote = listOf(
                CanonicalRemoteRatingInput("r", "a", provider, scope, 5, false),
                CanonicalRemoteRatingInput("r", "b", provider, scope, 3, false),
            ),
            activeProvider = provider,
            activeScope = scope,
        )
        assertFalse(ambiguous.byRecordingId.containsKey("r"))
    }

    @Test
    fun invalidNumbersNeverBecomeStars() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset()),
            localValues = mapOf("recording-one" to 100.0),
            remote = listOf(CanonicalRemoteRatingInput("recording-one", "asset-one", provider, scope, 7, false)),
            activeProvider = provider,
            activeScope = scope,
        )
        assertTrue(result.byMediaAssetId.isEmpty())
    }

    @Test
    fun compactStarStripHasNoDuplicateValueWhenRemoteAndLocalAgree() {
        assertEquals(listOf(5.0), RatingDisplay(localValue = 5.0, remoteValue = 5).visibleStarValues())
        assertEquals(listOf(4.5, 3.0), RatingDisplay(localValue = 4.5, remoteValue = 3).visibleStarValues())
        assertEquals(listOf(3.0), RatingDisplay(remoteValue = 3).visibleStarValues())
        assertTrue(RatingDisplay().visibleStarValues().isEmpty())
        assertTrue(RatingDisplay(remoteAvailable = false).visibleStarValues().isEmpty())
    }

    @Test
    fun unratedLocalRecordingCanOpenEditorByVerifiedUriWithoutNetwork() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset(
                recording = "recording-local",
                id = "asset-local",
                source = "local-mediastore",
                uri = "content://media/external/audio/123",
            )),
            localValues = emptyMap(),
            remote = emptyList(),
            activeProvider = null,
            activeScope = null,
        )
        val song = Song(videoId = "content://media/external/audio/123", title = "Ignored",
            artist = "Ignored", thumbnailUrl = null)
        assertTrue(result.bySongKey.isEmpty())
        assertEquals("recording-local", result.recordingIdForSong(song))
    }

    @Test
    fun unavailableProviderNeverExposesForeignOfflineRatingTarget() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset(source = otherProvider, uri = "content://foreign")),
            localValues = emptyMap(), remote = emptyList(),
            activeProvider = provider, activeScope = scope,
        )
        val song = Song(videoId = "content://foreign", title = "A", artist = "B", thumbnailUrl = null)
        assertNull(result.recordingIdForSong(song))
    }

    @Test
    fun conflictingLocalUrisFailClosedInsteadOfChoosingArbitraryRecording() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(
                asset("one", "asset-one", "local-mediastore", "content://media/shared"),
                asset("two", "asset-two", "local-mediastore", "content://media/shared"),
            ),
            localValues = emptyMap(), remote = emptyList(),
            activeProvider = null, activeScope = null,
        )
        val song = Song(videoId = "content://media/shared", title = "A", artist = "B", thumbnailUrl = null)
        assertNull(result.recordingIdForSong(song))
    }

    @Test
    fun firstLocalRatingOfCatalogRecordingAppearsInEveryVerifiedSourceWithoutProviderWrite() {
        val catalogAssets = listOf(
            asset("canonical-mb-recording", "device-a", "local-mediastore", "content://media/music/901"),
            asset("canonical-mb-recording", "navidrome-a", provider),
        )
        val before = buildCanonicalRatingPresentation(
            assets = catalogAssets, localValues = emptyMap(), remote = emptyList(),
            activeProvider = provider, activeScope = scope,
        )
        assertTrue(before.byMediaAssetId.isEmpty())
        assertEquals("canonical-mb-recording",
            before.recordingIdBySongKey["content://media/music/901"])
        val after = buildCanonicalRatingPresentation(
            assets = catalogAssets,
            localValues = mapOf("canonical-mb-recording" to 3.5),
            remote = emptyList(),
            activeProvider = provider, activeScope = scope,
        )
        assertEquals(3.5, after.byMediaAssetId["device-a"]?.localValue)
        assertEquals(3.5, after.byMediaAssetId["navidrome-a"]?.localValue)
        assertNull(after.byMediaAssetId["navidrome-a"]?.remoteValue)
        assertEquals(3.5, after.byRecordingId["canonical-mb-recording"]?.localValue)
    }

    @Test
    fun ambiguousCanonicalMediaKeyNeverDisplaysAnotherRecordingScore() {
        val sharedUri = "content://media/external/audio/shared"
        val presentation = buildCanonicalRatingPresentation(
            assets = listOf(
                asset("canonical-one", "local-one", "local-mediastore", sharedUri),
                asset("canonical-two", "local-two", "local-mediastore", sharedUri),
            ),
            localValues = mapOf("canonical-one" to 5.0),
            remote = emptyList(),
            activeProvider = null, activeScope = null,
        )
        val song = Song(videoId = sharedUri, title = "Duplicate", artist = "Unknown", thumbnailUrl = null)
        assertNull(presentation.forSong(song))
        assertNull(presentation.recordingIdForSong(song))
        // Rating by exact MediaAssetId remains available in the canonical catalog.
        assertEquals(5.0, presentation.byMediaAssetId["local-one"]?.localValue)
    }

    @Test
    fun unscoredRemoteAssetCannotBorrowOtherRemoteAssetsRatingInRecordingOnlyRows() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset("r", "remote-rated"), asset("r", "remote-unrated")),
            localValues = emptyMap(),
            remote = listOf(CanonicalRemoteRatingInput("r", "remote-rated", provider, scope, 5, false)),
            activeProvider = provider,
            activeScope = scope,
        )
        assertEquals(5, result.byMediaAssetId["remote-rated"]?.remoteValue)
        assertNull(result.byMediaAssetId["remote-unrated"])
        assertNull("No asset was chosen; the recording must not inherit a partial remote rating",
            result.byRecordingId["r"])
    }

    @Test
    fun conflictingRemoteMediaAssetsMustNotHideIndependentLocalRecordingRating() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(asset("r", "remote-a"), asset("r", "remote-b")),
            localValues = mapOf("r" to 4.5),
            remote = listOf(
                CanonicalRemoteRatingInput("r", "remote-a", provider, scope, 5, false),
                CanonicalRemoteRatingInput("r", "remote-b", provider, scope, 3, false),
            ),
            activeProvider = provider,
            activeScope = scope,
        )
        val recording = result.byRecordingId["r"]
        assertEquals("The native note is always a RecordingId-owned preference", 4.5,
            recording?.localValue ?: 0.0, 0.0)
        assertNull("Conflicting remote assets cannot imply a single remote score", recording?.remoteValue)
        assertEquals(RatingDisplayKind.RATED, recording?.kind)
    }

    @Test
    fun aLocalFileAndOneUnambiguousRemoteAssetRetainBothSourcesAtRecordingLevel() {
        val result = buildCanonicalRatingPresentation(
            assets = listOf(
                asset("r", "local-a", "local-mediastore", "content://music/a"),
                asset("r", "remote-a"),
            ),
            localValues = mapOf("r" to 4.5),
            remote = listOf(CanonicalRemoteRatingInput("r", "remote-a", provider, scope, 3, false)),
            activeProvider = provider,
            activeScope = scope,
        )
        val recording = result.byRecordingId.getValue("r")
        assertEquals(4.5, recording.localValue!!, 0.0)
        assertEquals(3, recording.remoteValue)
        assertEquals(RatingDisplayKind.CONFLICT, recording.kind)
    }

}
