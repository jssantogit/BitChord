package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CatalogMetadataRecordingLabelTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    @Test fun offlineMetadataLabelDoesNotClaimPlayableAsset() {
        val label = CatalogMetadataRecordingLabel(id, "UFO (ATTENTION)", "SAY MY NAME")
        assertTrue(validCatalogMetadataRecordingLabel(label))
        assertEquals(listOf(label),
            decodeCatalogMetadataRecordingLabels(encodeCatalogMetadataRecordingLabels(listOf(label))))
        val unavailable = metadataOnlyUnavailablePlaylistRow(id, label)
        assertEquals("UFO (ATTENTION)", unavailable.title)
        assertFalse(unavailable.isAvailablePlaylistMember())
        assertTrue(V2DisplayRow(id, "Local recording", "Artist").isAvailablePlaylistMember())
    }

    @Test fun fabricatedIdsOrIncorrectLabelKeyCannotImpersonateVerifiedCatalog() {
        assertFalse(validCatalogMetadataRecordingLabel(
            CatalogMetadataRecordingLabel("https://musicbrainz.org/recording/$id", "Song", "Artist")))
        assertEquals("Gravação indisponível",
            metadataOnlyUnavailablePlaylistRow(id, CatalogMetadataRecordingLabel(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Wrong", "Artist")).title)
    }
}
