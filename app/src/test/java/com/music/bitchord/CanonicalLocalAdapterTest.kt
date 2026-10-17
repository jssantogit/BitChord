package com.music.bitchord

import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.CanonicalLocalRow
import com.music.bitchord.data.catalog.toLegacySong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalLocalAdapterTest {

    @Test
    fun localRowWithoutExternalIdentityUsesUriAsLegacyVideoId() {
        val row = localRow(youtubeVideoId = null)

        val song = row.toLegacySong()

        assertEquals("content://media/external/audio/media/42", song.videoId)
        assertNotEquals(row.recordingId, song.videoId)
        assertNotEquals(row.mediaAssetId, song.videoId)
        assertEquals(row.uri, song.localUri)
    }

    @Test
    fun knownYouTubeIdentityIsCompatibilityAliasNotCanonicalId() {
        val row = localRow(youtubeVideoId = "yt-video-123")

        val song = row.toLegacySong()

        assertEquals("yt-video-123", song.videoId)
        assertNotEquals(row.recordingId, song.videoId)
        assertEquals("content://media/external/audio/media/42", song.localUri)
    }

    @Test
    fun canonicalMetadataSurvivesLegacyAdapter() {
        val song = localRow(youtubeVideoId = null).toLegacySong()

        assertEquals("Paranoid Android", song.title)
        assertEquals("Radiohead", song.artist)
        assertEquals("OK Computer", song.albumName)
        assertEquals("content://media/external/audio/albumart/7", song.thumbnailUrl)
        assertEquals("6:23", song.durationText)
        assertEquals("/storage/emulated/0/Music/Radiohead/03 Paranoid Android.flac", song.localPath)
        assertEquals(3, song.trackNumber)
        assertEquals(1_700_000_000L, song.localDateAddedSeconds)
        assertEquals(1_700_000_100L, song.localDateModifiedSeconds)
        assertNull(song.albumId)
    }

    @Test
    fun legacyAdapterKeepsLongDurationsAsTotalMinutes() {
        val song = localRow(youtubeVideoId = null)
            .copy(durationMs = 3_723_000L)
            .toLegacySong()

        assertEquals("62:03", song.durationText)
    }

    @Test
    fun roomBackedRowsKeepLegacyTitleOrdering() {
        val rows = listOf(
            localRow(youtubeVideoId = null).copy(title = "Zebra", artistDisplay = "A Artist"),
            localRow(youtubeVideoId = null).copy(title = "Alpha", artistDisplay = "Z Artist"),
        )

        val ordered = CanonicalLocalLibrary.orderLocalRows(rows)

        assertEquals(listOf("Alpha", "Zebra"), ordered.map(CanonicalLocalRow::title))
    }

    @Test
    fun durationParserKeepsValidMinuteAndHourFormats() {
        assertEquals(383_000L, CanonicalLocalLibrary.parseDurationMillis("6:23"))
        assertEquals(3_723_000L, CanonicalLocalLibrary.parseDurationMillis("1:02:03"))
    }

    @Test
    fun durationParserRejectsMalformedSegments() {
        assertNull(CanonicalLocalLibrary.parseDurationMillis("1:x:30"))
        assertNull(CanonicalLocalLibrary.parseDurationMillis("1:70"))
        assertNull(CanonicalLocalLibrary.parseDurationMillis("1:-1"))
    }

    @Test
    fun deletedMediaStoreItemIsVerifiedThroughItsCollectionAndId() {
        val lookup = CanonicalLocalLibrary.mediaStoreLookupForSourceKey(
            "content://media/external/audio/media/42",
        )

        assertEquals("content://media/external/audio/media", lookup?.collectionUri)
        assertEquals(42L, lookup?.mediaId)
    }

    @Test
    fun nonMediaStoreSourceCannotBeTreatedAsAuthoritativelyAbsent() {
        assertNull(
            CanonicalLocalLibrary.mediaStoreLookupForSourceKey(
                "file:///storage/emulated/0/Music/local.flac",
            ),
        )
    }

    @Test
    fun staleSourceKeySelectionHandlesLibrariesBeyondSqliteBindLimits() {
        val existing = (0 until 2_500).map { index ->
            "content://media/external/audio/media/$index"
        }
        val seen = existing.drop(400)

        val stale = CanonicalLocalLibrary.staleSourceKeys(
            existingSourceKeys = existing,
            seenSourceKeys = seen,
            scanComplete = true,
        )

        assertEquals(existing.take(400), stale)
    }

    @Test
    fun incompleteScanNeverMarksExistingAssetsStale() {
        val stale = CanonicalLocalLibrary.staleSourceKeys(
            existingSourceKeys = listOf("local-a", "local-b"),
            seenSourceKeys = emptyList(),
            scanComplete = false,
        )

        assertEquals(emptyList<String>(), stale)
    }

    private fun localRow(youtubeVideoId: String?) = CanonicalLocalRow(
        recordingId = "recording-uuid",
        mediaAssetId = "asset-uuid",
        sourceKey = "content://media/external/audio/media/42",
        title = "Paranoid Android",
        artistDisplay = "Radiohead",
        durationMs = 383_000L,
        albumTitle = "OK Computer",
        artworkUri = "content://media/external/audio/albumart/7",
        uri = "content://media/external/audio/media/42",
        path = "/storage/emulated/0/Music/Radiohead/03 Paranoid Android.flac",
        dateAddedSeconds = 1_700_000_000L,
        dateModifiedSeconds = 1_700_000_100L,
        trackNumber = 3,
        youtubeVideoId = youtubeVideoId,
    )
}
