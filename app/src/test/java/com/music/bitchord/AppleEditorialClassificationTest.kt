package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleEditorialClassification
import com.music.bitchord.data.catalog.metadata.AppleEditorialFormatEvidence
import com.music.bitchord.data.catalog.metadata.AppleEditorialFormatSource
import com.music.bitchord.data.catalog.metadata.AppleEditorialFormatStatus
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleEditorialClassificationTest {
    private val group = "f2eaa98b-fef5-4327-9e7f-7d6500fdb973"

    private fun apple(
        id: Long = 11L,
        label: ItunesEditorialReleaseFormat = ItunesEditorialReleaseFormat.UNCLASSIFIED,
        country: String = "br",
        mbid: String? = group,
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:$country:album:$id",
        appleCollectionId = id,
        storefront = country,
        title = "HOT",
        artistName = "LE SSERAFIM",
        artistId = 30,
        releaseDate = "2025-03-14",
        genre = "Pop",
        format = label,
        artworkUrl = null,
        trackCount = null,
        tracks = emptyList(),
        musicBrainzReleaseGroupId = mbid,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
    )

    private fun mb(
        format: ItunesEditorialReleaseFormat,
        appleKey: String = "itunes:br:album:11",
        mbid: String = group,
        verified: Boolean = true,
    ) = AppleEditorialFormatEvidence(
        source = AppleEditorialFormatSource.MUSICBRAINZ,
        appleScopedId = appleKey,
        sourceId = mbid,
        format = format,
        exactMatchVerified = verified,
    )

    private fun discogs(
        format: ItunesEditorialReleaseFormat,
        appleKey: String = "itunes:br:album:11",
        discogsKey: String = "discogs:release:121",
        verified: Boolean = true,
    ) = AppleEditorialFormatEvidence(
        source = AppleEditorialFormatSource.DISCOGS,
        appleScopedId = appleKey,
        sourceId = discogsKey,
        format = format,
        exactMatchVerified = verified,
    )

    @Test fun appleExplicitClassificationIsPrimaryWithoutOverridingItsRelease() {
        val apple = apple(label = ItunesEditorialReleaseFormat.EP)
        val originalTitle = apple.title
        val resolution = AppleEditorialClassification.resolve(
            apple,
            listOf(
                mb(ItunesEditorialReleaseFormat.ALBUM),
                discogs(ItunesEditorialReleaseFormat.ALBUM),
            ),
        )
        assertEquals(ItunesEditorialReleaseFormat.EP, resolution.format)
        assertEquals(AppleEditorialFormatStatus.DECIDED, resolution.status)
        assertEquals(setOf(ItunesEditorialReleaseFormat.ALBUM), resolution.disputedExternalFormats)
        assertTrue(AppleEditorialFormatSource.APPLE in resolution.sources)
        assertEquals(originalTitle, apple.title)
    }

    @Test fun independentlyVerifiedMusicBrainzAndDiscogsCanFillAppleUnknownTogether() {
        val resolution = AppleEditorialClassification.resolve(
            apple(), listOf(
                mb(ItunesEditorialReleaseFormat.EP),
                discogs(ItunesEditorialReleaseFormat.EP),
            )
        )
        assertEquals(ItunesEditorialReleaseFormat.EP, resolution.format)
        assertEquals(AppleEditorialFormatStatus.DECIDED, resolution.status)
        assertEquals(
            setOf(AppleEditorialFormatSource.MUSICBRAINZ, AppleEditorialFormatSource.DISCOGS),
            resolution.sources,
        )
    }

    @Test fun exactDiscogsReleaseMayClassifyAppleOnlyWithoutMusicBrainzIdentity() {
        val resolution = AppleEditorialClassification.resolve(
            apple(mbid = null),
            listOf(discogs(ItunesEditorialReleaseFormat.SINGLE)),
        )
        assertEquals(ItunesEditorialReleaseFormat.SINGLE, resolution.format)
        assertEquals(setOf(AppleEditorialFormatSource.DISCOGS), resolution.sources)
    }

    @Test fun verifiedDisagreementNeverSilentlyPicksMusicBrainzOrDiscogs() {
        val result = AppleEditorialClassification.resolve(
            apple(), listOf(
                mb(ItunesEditorialReleaseFormat.ALBUM),
                discogs(ItunesEditorialReleaseFormat.SINGLE),
            ),
        )
        assertEquals(AppleEditorialFormatStatus.CONFLICT, result.status)
        assertEquals(ItunesEditorialReleaseFormat.UNCLASSIFIED, result.format)
        assertEquals(
            setOf(ItunesEditorialReleaseFormat.ALBUM, ItunesEditorialReleaseFormat.SINGLE),
            result.disputedExternalFormats,
        )
    }

    @Test fun missingExactIdentityOrForeignStorefrontIsNotClassificationEvidence() {
        val release = apple()
        val result = AppleEditorialClassification.resolve(release, listOf(
            mb(ItunesEditorialReleaseFormat.EP, verified = false),
            mb(ItunesEditorialReleaseFormat.EP, appleKey = "itunes:us:album:11"),
            mb(ItunesEditorialReleaseFormat.EP, mbid = "not-a-real-mbid"),
            discogs(ItunesEditorialReleaseFormat.SINGLE, verified = false),
            discogs(ItunesEditorialReleaseFormat.SINGLE, discogsKey = "discogs:master:121"),
            discogs(ItunesEditorialReleaseFormat.SINGLE, discogsKey = "discogs:release:0"),
        ))
        assertEquals(AppleEditorialFormatStatus.UNCLASSIFIED, result.status)
        assertEquals(ItunesEditorialReleaseFormat.UNCLASSIFIED, result.format)
    }

    @Test fun conflictingSnapshotsOfOneExactReleaseAreNotTwoVotes() {
        val result = AppleEditorialClassification.resolve(
            apple(mbid = null),
            listOf(
                discogs(ItunesEditorialReleaseFormat.ALBUM),
                discogs(ItunesEditorialReleaseFormat.EP),
            ),
        )
        assertEquals(AppleEditorialFormatStatus.CONFLICT, result.status)
        assertEquals(ItunesEditorialReleaseFormat.UNCLASSIFIED, result.format)
    }

    @Test fun sameTitleDifferentAppleIdsCannotShareExternalClassification() {
        val second = AppleEditorialClassification.resolve(
            apple(id = 12),
            listOf(mb(ItunesEditorialReleaseFormat.EP),
                discogs(ItunesEditorialReleaseFormat.EP)),
        )
        assertEquals(AppleEditorialFormatStatus.UNCLASSIFIED, second.status)
    }

    @Test fun identicalRepeatEvidenceDoesNotBecomeMultipleIndependentVotes() {
        val release = apple()
        val proofs = listOf(mb(ItunesEditorialReleaseFormat.ALBUM),
            mb(ItunesEditorialReleaseFormat.ALBUM))
        val result = AppleEditorialClassification.resolve(release, proofs)
        assertEquals(ItunesEditorialReleaseFormat.ALBUM, result.format)
        assertEquals(setOf(AppleEditorialFormatSource.MUSICBRAINZ), result.sources)
    }
}
