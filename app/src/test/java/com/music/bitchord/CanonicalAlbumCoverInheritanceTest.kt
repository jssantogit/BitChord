package com.music.bitchord

import com.music.bitchord.ui.canonicalReleaseTracksWithArtwork
import com.music.bitchord.ui.canonicalVerifiedGroupDetailArtwork
import com.music.bitchord.ui.screens.CanonicalArtworkProvenance
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A54 #178: exactly inherited album sleeves must follow a corrected Front-v5. */
class CanonicalAlbumCoverInheritanceTest {
    private val group = CanonicalCatalogSearchRow(
        musicBrainzId = "11111111-1111-4111-8111-111111111111",
        kind = CanonicalSearchKind.RELEASE_GROUP,
        title = "Album",
        subtitle = "Artist",
        releaseKind = "EP",
    )
    private val old500 = "https://archive.org/old-edition-front-500.jpg"
    private val verified500 = "https://archive.org/official-front-500.jpg"
    private val low250 =
        "https://coverartarchive.org/release/11111111-1111-4111-8111-111111111111/front-250"

    private fun track(title: String = "Track") = CanonicalCatalogSearchRow(
        musicBrainzId = "22222222-2222-4222-8222-222222222222",
        kind = CanonicalSearchKind.RECORDING,
        title = title,
        subtitle = "Artist",
    )

    @Test fun filledAlbumDetailRevalidatesExactFrontV5BeforePaintingHeaderAndTracks() {
        assertEquals(verified500, canonicalVerifiedGroupDetailArtwork(
            group.copy(artworkUri = old500), verified500,
        ))
        assertEquals(old500, canonicalVerifiedGroupDetailArtwork(
            group.copy(artworkUri = old500), null,
        ))
        assertEquals(old500, canonicalVerifiedGroupDetailArtwork(
            group.copy(artworkUri = old500), low250,
        ))
        assertEquals(verified500, canonicalVerifiedGroupDetailArtwork(group, verified500))
        assertNull(canonicalVerifiedGroupDetailArtwork(
            group.copy(releaseKind = "Other"), verified500,
        ))
        assertNull(canonicalVerifiedGroupDetailArtwork(
            group.copy(kind = CanonicalSearchKind.RELEASE), verified500,
        ))
    }

    @Test fun inheritedGroupTrackArtIsTaggedAndCanFollowVerifiedSameSizeReplacement() {
        val inherited = canonicalReleaseTracksWithArtwork(
            listOf(track()), old500, inheritedFromGroup = true,
        )
        assertEquals(CanonicalArtworkProvenance.INHERITED_GROUP,
            inherited.single().artworkProvenance)
        val corrected = canonicalReleaseTracksWithArtwork(
            inherited, verified500,
            previousInheritedArtwork = old500,
            inheritedFromGroup = true,
        )
        assertEquals(verified500, corrected.single().artworkUri)
        assertEquals(CanonicalArtworkProvenance.INHERITED_GROUP,
            corrected.single().artworkProvenance)
        assertEquals(corrected, canonicalReleaseTracksWithArtwork(
            corrected, verified500,
            previousInheritedArtwork = verified500,
            inheritedFromGroup = true,
        ))
        assertEquals(corrected, canonicalReleaseTracksWithArtwork(
            corrected, low250,
            previousInheritedArtwork = verified500,
            inheritedFromGroup = true,
        ))
    }

    @Test fun trackOwnedSleeveNeverChangesEvenWhenItsUrlHappensToMatchFormerAlbum() {
        val owned = track().copy(artworkUri = old500)
        val inherited = canonicalReleaseTracksWithArtwork(
            listOf(track().copy(musicBrainzId = "33333333-3333-4333-8333-333333333333")),
            old500, inheritedFromGroup = true,
        ).single()
        val result = canonicalReleaseTracksWithArtwork(
            listOf(owned, inherited), verified500,
            previousInheritedArtwork = old500,
            inheritedFromGroup = true,
        )
        assertEquals(old500, result[0].artworkUri)
        assertNull(result[0].artworkProvenance)
        assertEquals(verified500, result[1].artworkUri)
    }

    @Test fun exactEditionArtworkIsProvisionalNotOwnedTrackArt() {
        val inherited = canonicalReleaseTracksWithArtwork(listOf(track()), old500)
        assertEquals(CanonicalArtworkProvenance.INHERITED_RELEASE,
            inherited.single().artworkProvenance)
    }
}
