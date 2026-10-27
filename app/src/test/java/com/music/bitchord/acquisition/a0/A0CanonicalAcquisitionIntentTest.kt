package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** #177: identity hints must not become MusicBrainz proof through a text query. */
class A0CanonicalAcquisitionIntentTest {
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"
    private val group = "33333333-3333-4333-8333-333333333333"

    @Test fun titleOnlyManualIntentNeverFabricatesArtistOrCanonicalIdentity() {
        val target = A0CanonicalAcquisitionIntent.manual(title = "Magnetic")
        assertEquals("Magnetic", target.title)
        assertTrue(target.artistCredits.isEmpty())
        assertNull(target.ids)
        assertNull(target.requestedAlbum)
        assertEquals(A0CanonicalAcquisitionIntent.Origin.MANUAL_QUERY, target.origin)
        assertFalse(target.strictExactRelease)
    }

    @Test fun manuallySpecifiedAlbumAndVersionRemainExplicitButUnverified() {
        val target = A0CanonicalAcquisitionIntent.manual(
            title = "  Blue Sky  ", artistCredits = listOf("Mira"),
            requestedAlbum = "Blue Sky", 
            requestedVersion = setOf(A0CanonicalAcquisitionIntent.VersionTag.LIVE),
            expectedDurationSeconds = 181,
        )
        assertEquals("Blue Sky", target.requestedAlbum)
        assertEquals(listOf("Mira"), target.artistCredits)
        assertEquals(setOf(A0CanonicalAcquisitionIntent.VersionTag.LIVE), target.requestedVersion)
        assertEquals(181, target.expectedDurationSeconds)
        assertNull(target.ids)
        assertFalse(target.strictExactRelease)
    }

    @Test fun exactEditionAndRecordingRemainDistinctAndRequireTrustedCatalogOrigin() {
        val target = A0CanonicalAcquisitionIntent.confirmedCatalog(
            title = "Blue Sky", artistCredits = listOf("Mira"),
            requestedAlbum = "Blue Sky", strictExactRelease = true,
            ids = A0CanonicalAcquisitionIntent.CanonicalIds(
                recordingMbid = recording, releaseMbid = release,
                releaseGroupMbid = group,
            )
        )
        assertEquals(A0CanonicalAcquisitionIntent.Origin.CANONICAL_CATALOG, target.origin)
        assertEquals(recording, target.ids?.recordingMbid)
        assertEquals(release, target.ids?.releaseMbid)
        assertEquals(group, target.ids?.releaseGroupMbid)
        assertTrue(target.strictExactRelease)
    }

    @Test fun groupMembershipCannotStandInForAnExactEdition() {
        assertThrows(IllegalArgumentException::class.java) {
            A0CanonicalAcquisitionIntent.confirmedCatalog(
                title = "Blue Sky", strictExactRelease = true,
                ids = A0CanonicalAcquisitionIntent.CanonicalIds(releaseGroupMbid = group)
            )
        }
    }

    @Test fun malformedCanonicalIdsAndInvalidDurationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            A0CanonicalAcquisitionIntent.CanonicalIds(recordingMbid = "search-result-123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            A0CanonicalAcquisitionIntent.manual(title = "Magnetic", expectedDurationSeconds = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            A0CanonicalAcquisitionIntent.manual(title = " ")
        }
    }

    @Test fun qualityProfileSnapshotPreservesCodecConstraintsWithoutAssumingTranscoding() {
        val quality = A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
            preset = A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM,
            allowedCodecs = setOf(A0CanonicalAcquisitionIntent.Codec.FLAC),
            minBitrateKbps = null, minSampleRateHz = 96000,
            minBitDepth = 24, strict = true,
        )
        val intent = A0CanonicalAcquisitionIntent.manual(
            title = "Blue Sky", quality = quality
        )
        assertEquals(quality, intent.quality)
        assertEquals(setOf(A0CanonicalAcquisitionIntent.Codec.FLAC), intent.quality.allowedCodecs)
        assertTrue(intent.quality.strict)
        assertEquals(A0CanonicalAcquisitionIntent.QualityPreset.CUSTOM, intent.quality.preset)
    }

    @Test fun tracesMustNotAccidentallyIncludePrivateRequestOrIds() {
        val target = A0CanonicalAcquisitionIntent.confirmedCatalog(
            title = "SECRET QUERY", artistCredits = listOf("PRIVATE ARTIST"),
            ids = A0CanonicalAcquisitionIntent.CanonicalIds(recordingMbid = recording)
        )
        val repr = target.toString() + target.ids.toString()
        assertFalse(repr.contains("SECRET"))
        assertFalse(repr.contains("PRIVATE"))
        assertFalse(repr.contains(recording))
    }
}
