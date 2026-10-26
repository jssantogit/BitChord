package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.CatalogEvidenceBasis
import com.music.bitchord.data.catalog.metadata.CatalogEvidenceDecision
import com.music.bitchord.data.catalog.metadata.CatalogExternalProvider
import com.music.bitchord.data.catalog.metadata.CatalogTargetKind
import com.music.bitchord.data.catalog.metadata.ExternalEditorialEvidence
import com.music.bitchord.data.catalog.metadata.CatalogArtworkRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEditorialEvidenceTest {
    private val mbid = "b51c672b-85e0-48fe-8648-470a2422229f"

    @Test fun scopedAppleCatalogLinksRemainCandidatesWithoutVerifiedIdentityEvidence() {
        val apple = ExternalEditorialEvidence(
            CatalogExternalProvider.APPLE_MUSIC, "1214574815", CatalogTargetKind.RELEASE,
            mbid, CatalogEvidenceBasis.UPC_AND_ARTIST, storefront = "br",
        )
        assertEquals(CatalogEvidenceDecision.CANDIDATE, apple.assess())
        assertEquals("apple_music:br:1214574815", apple.scopedExternalKey())
        assertFalse(apple.canSupplyArtwork())
        assertEquals(CatalogEvidenceDecision.REJECTED,
            apple.copy(storefront = null).assess())
        assertEquals(CatalogEvidenceDecision.REJECTED,
            apple.copy(basis = CatalogEvidenceBasis.TITLE_ONLY).assess())
    }

    @Test fun strongMusicBrainzListenerObservationsDoNotInventPlaybackOrCoverRights() {
        val listen = ExternalEditorialEvidence(
            CatalogExternalProvider.LISTENBRAINZ, mbid, CatalogTargetKind.RECORDING,
            mbid, CatalogEvidenceBasis.DIRECT_MBID,
        )
        assertEquals(CatalogEvidenceDecision.VERIFIED, listen.assess())
        assertFalse(listen.canSupplyArtwork())
        assertFalse(listen.canClaimPlayableAudio())
        val lastfm = listen.copy(provider = CatalogExternalProvider.LAST_FM,
            externalId = "newjeans", targetKind = CatalogTargetKind.ARTIST)
        assertEquals(CatalogEvidenceDecision.VERIFIED, lastfm.assess())
        assertEquals(CatalogEvidenceDecision.REJECTED,
            lastfm.copy(canonicalMbid = "NewJeans").assess())
    }

    @Test fun appleArtworkNeedsVerifiedEvidenceAndExplicitRights() {
        val apple = ExternalEditorialEvidence(
            CatalogExternalProvider.APPLE_MUSIC, "1214574815",
            CatalogTargetKind.RELEASE, mbid, CatalogEvidenceBasis.DIRECT_MBID,
            storefront = "us", artworkRight = CatalogArtworkRight.ALLOWED,
        )
        assertTrue(apple.canSupplyArtwork())
        assertFalse(apple.copy(artworkRight = CatalogArtworkRight.UNKNOWN).canSupplyArtwork())
        assertFalse(apple.copy(basis = CatalogEvidenceBasis.ISRC_AND_ARTIST).canSupplyArtwork())
        assertFalse(apple.copy(canonicalMbid = "unknown").canSupplyArtwork())
    }
}
