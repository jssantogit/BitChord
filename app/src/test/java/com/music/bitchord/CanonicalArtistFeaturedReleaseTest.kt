package com.music.bitchord

import com.music.bitchord.ui.canonicalPublicArtistFeaturedRelease
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalArtistFeaturedReleaseTest {
    private fun release(id: String, kind: String, date: String?) =
        CanonicalCatalogSearchRow(
            musicBrainzId = id, kind = CanonicalSearchKind.RELEASE_GROUP,
            title = id, subtitle = "Verified artist",
            releaseKind = kind, releaseDate = date,
        )

    @Test fun onlineArtistHeroChoosesLatestVerifiedSingleOverOlderAlbum() {
        val album = release("album", "Album", "2025-01-15")
        val single = release("single", "Single", "2026-09-17")
        val ep = release("ep", "EP", "2026-01-20")
        assertEquals("single", canonicalPublicArtistFeaturedRelease(
            listOf(album, single, ep), "2026-11-07",
        )?.musicBrainzId)
    }

    @Test fun onlineHeroRejectsPartialFutureUnknownAndRecordingRows() {
        val recording = release("recording", "Single", "2026-10-01")
            .copy(kind = CanonicalSearchKind.RECORDING)
        assertNull(canonicalPublicArtistFeaturedRelease(
            listOf(
                release("partial", "Single", "2026"),
                release("future", "Album", "2027-01-03"),
                release("unknown", "Other", "2026-11-01"),
                recording,
            ), "2026-11-07",
        ))
    }

    @Test fun onlineHeroFallsBackToLatestDatedEpIfNewerSingleHasNoDay() {
        assertEquals("ep", canonicalPublicArtistFeaturedRelease(
            listOf(release("ep", "EP", "2026-03-22"),
                release("single", "Single", "2026-10")),
            "2026-11-07",
        )?.musicBrainzId)
    }
}
