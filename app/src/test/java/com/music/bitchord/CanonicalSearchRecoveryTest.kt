package com.music.bitchord

import com.music.bitchord.ui.mergeCanonicalSearchRows
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchRecoveryTest {
    private val artist = CanonicalCatalogSearchRow(
        "6b128dd7-e998-4edb-a4f5-400fef190445", CanonicalSearchKind.ARTIST,
        "ILLIT", "Cached", fromOfflineCache = true,
    )
    private val liveArtist = artist.copy(subtitle = "Live", fromOfflineCache = false)
    private val recording = CanonicalCatalogSearchRow(
        "e614e432-ca77-41c1-82c1-4e3a24ebbb0c", CanonicalSearchKind.RECORDING,
        "Magnetic", "ILLIT",
    )

    @Test fun failedArtistEndpointRetainsCachedArtistAlongsideSuccessfulRecordingPage() {
        val result = mergeCanonicalSearchRows(
            live = listOf(recording),
            cached = listOf(artist),
            failedKinds = setOf(CanonicalSearchKind.ARTIST),
        )
        assertEquals(listOf(recording, artist), result)
    }

    @Test fun successfulArtistEndpointUsesLiveEvidenceAndDoesNotInjectStaleCache() {
        val result = mergeCanonicalSearchRows(
            live = listOf(liveArtist, recording),
            cached = listOf(artist),
            failedKinds = emptySet(),
        )
        assertEquals(listOf(liveArtist, recording), result)
        assertFalse(result.first().fromOfflineCache)
    }

    @Test fun sameMbidMayExistAsDistinctRecordingAndReleaseWithNoIdentityCollision() {
        val unusualRelease = recording.copy(kind = CanonicalSearchKind.RELEASE, fromOfflineCache = true)
        val result = mergeCanonicalSearchRows(
            live = listOf(recording),
            cached = listOf(unusualRelease, artist),
            failedKinds = setOf(CanonicalSearchKind.RELEASE, CanonicalSearchKind.ARTIST),
        )
        assertEquals(3, result.size)
        assertTrue(result.any { it.kind == CanonicalSearchKind.RELEASE })
        assertTrue(result.any { it.kind == CanonicalSearchKind.RECORDING })
    }
}
