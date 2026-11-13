package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalRecordingReleaseGroupLinkRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Album navigation needs a unique real ReleaseGroup relationship, not a title match. */
class CanonicalPlaylistAlbumActionTest {
    private val recording = "d71b77d4-ef51-4b5e-9942-59aad703f963"
    private val group = "6c5ab5ba-5855-4c81-b990-2aa55d53f929"

    @Test fun exactRecordingLinkedToOneReleaseGroupCanOfferOpenAlbum() {
        val links = listOf(
            CanonicalRecordingReleaseGroupLinkRow(recording, group),
            CanonicalRecordingReleaseGroupLinkRow(recording, group),
        )
        assertEquals(group, canonicalPlaylistAlbumForRecording(recording, links))
    }

    @Test fun ambiguousOrUnrelatedAssociationsNeverGuessAnAlbum() {
        val another = "1b8c678c-985f-4482-9cae-5a3cc8049f0f"
        val links = listOf(
            CanonicalRecordingReleaseGroupLinkRow(recording, group),
            CanonicalRecordingReleaseGroupLinkRow(recording, another),
        )
        assertNull(canonicalPlaylistAlbumForRecording(recording, links))
        assertNull(canonicalPlaylistAlbumForRecording("no-identity", links))
        assertNull(canonicalPlaylistAlbumForRecording(recording,
            listOf(CanonicalRecordingReleaseGroupLinkRow(recording, "fake release title"))))
    }
}
