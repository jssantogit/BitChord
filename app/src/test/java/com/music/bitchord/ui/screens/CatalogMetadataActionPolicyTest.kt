package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMetadataActionPolicyTest {
    private val validRecording = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test fun exactMusicBrainzRecordingMayBeSavedWithoutMediaAssetButNeverPlayed() {
        assertTrue(isVerifiedCatalogRecordingId(validRecording))
        assertFalse(isVerifiedCatalogRecordingId("local:title:Artist - Song"))
        assertFalse(isVerifiedCatalogRecordingId("https://musicbrainz.org/recording/$validRecording"))
        assertEquals(
            listOf(validRecording),
            planMetadataOnlyPlaylistMembers(emptyList(), validRecording),
        )
        assertEquals(
            listOf(validRecording),
            planMetadataOnlyPlaylistMembers(listOf(validRecording), validRecording),
        )
    }

    @Test fun invalidCatalogMetadataCannotEnterUserPlaylists() {
        assertEquals(null, planMetadataOnlyPlaylistMembers(emptyList(), "not-mbid"))
        assertEquals(null, planMetadataOnlyPlaylistMembers(emptyList(), ""))
    }
}
