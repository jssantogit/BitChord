package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

class AppleTriSourceEditorialReconcilerTest {
    private val releaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val groupId = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val trackId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val recordingId = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"

    private fun album(id: Long = 101L, title: String = "SUPER REAL ME"): ItunesCatalogItem =
        ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = id, storefront = "br",
            title = title, artistName = "ILLIT", artistId = 99, collectionId = id,
            storeUrl = null, releaseDate = "2024-03-25", genre = "K-Pop",
            durationMs = null, trackNumber = null, discNumber = null,
            artworkUrl = "https://is1-ssl.mzstatic.com/cover.jpg",
            upc = "8801234567890", trackCount = 1,
        )

    private fun song(id: Long = 201, albumId: Long = 101): ItunesCatalogItem =
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK, id = id, storefront = "br",
            title = "Magnetic", artistName = "ILLIT", artistId = 99, collectionId = albumId,
            storeUrl = null, releaseDate = null, genre = null,
            durationMs = 120_000, trackNumber = 1, discNumber = 1,
        )

    private fun mb(
        id: String = releaseId, title: String = "SUPER REAL ME",
        barcode: String = "8801234567890",
    ) = MusicBrainzCatalogReleaseDetail(
        musicBrainzId = id, groupMbid = groupId, title = title,
        artistDisplay = "ILLIT", country = "KR", releaseDate = "2024-03-25",
        tracks = listOf(MusicBrainzCatalogTrack(
            trackId, recordingId, "Magnetic", 1, 1, 120_000,
        )),
        status = "Official", barcode = barcode, artistCreditNames = listOf("ILLIT"),
        hasCompleteTracklist = true,
        completeness = MusicBrainzReleaseJsonCompleteness(
            countsExplicitAndExact = true, offsetsValid = true, positionsExplicitUnique = true,
        ),
    )

    private fun group(type: String = "EP") = MusicBrainzReleaseGroupHit(
        musicBrainzId = groupId, title = "SUPER REAL ME",
        primaryType = type, firstReleaseDate = "2024-03-25",
        artistDisplay = "ILLIT",
    )

    private fun discogs(
        kind: String = "EP", official: Boolean = true,
        now: Long = 1_000,
        releaseId: String = this.releaseId,
    ): DiscogsEditionEnrichment {
        val record = DiscogsRecord(
            link = DiscogsLink(DiscogsKind.RELEASE, 25L),
            title = "SUPER REAL ME", artistNames = listOf("ILLIT"),
            country = "South Korea", year = 2024,
            labels = listOf("BELIFT LAB" to "123"), barcodes = listOf("8801234567890"),
            formats = listOf(kind), tracklist = listOf("1" to "Magnetic"),
            unofficial = !official,
        )
        return DiscogsEditionEnrichment.Verified(DiscogsObservation(
            canonicalMbid = releaseId, kind = DiscogsKind.RELEASE, record = record,
            fetchedAtMillis = now,
        ))
    }

    @Test fun appleChoosesProductsAndExactProofAttachesMbAndDiscogsClassification() {
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(album(), song()),
            mbEditions = listOf(mb()), mbGroups = listOf(group()),
            discogsObservations = listOf(discogs()), nowMillis = 1_100L,
        )
        assertEquals(1, result.size)
        val product = result.single()
        assertEquals("SUPER REAL ME", product.release.title)
        assertEquals("https://is1-ssl.mzstatic.com/cover.jpg", product.release.artworkUrl)
        assertEquals(releaseId, product.release.musicBrainzReleaseId)
        assertEquals(groupId, product.release.musicBrainzReleaseGroupId)
        assertEquals(recordingId, product.release.musicBrainzRecordingIds["itunes:br:track:201"])
        assertEquals(ItunesEditorialReleaseFormat.EP, product.classification.format)
        assertEquals(AppleEditorialFormatStatus.DECIDED, product.classification.status)
        assertEquals(setOf(AppleEditorialFormatSource.DISCOGS, AppleEditorialFormatSource.MUSICBRAINZ),
            product.classification.sources)
        assertEquals("https://www.discogs.com/release/25", product.discogsEdition?.attributionUrl)
    }

    @Test fun sameMusicBrainzReleaseCannotProveTwoDistinctAppleProducts() {
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(
                album(101), song(201, 101), album(102), song(202, 102),
            ),
            mbEditions = listOf(mb()), mbGroups = listOf(group()),
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.release.musicBrainzReleaseId == null })
        assertTrue(result.all { it.release.musicBrainzRecordingIds.isEmpty() })
        assertTrue(result.all { it.match is AppleMusicBrainzEditionMatch.Ambiguous })
        assertTrue(result.all { it.classification.status == AppleEditorialFormatStatus.UNCLASSIFIED })
    }

    @Test fun unmatchedMusicBrainzAndDiscogsDoNotCreateAppleProducts() {
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = emptyList(),
            mbEditions = listOf(mb()), mbGroups = listOf(group()),
            discogsObservations = listOf(discogs()), nowMillis = 1_100,
        )
        assertTrue(result.isEmpty())
    }

    @Test fun wrongUpcPreventsReleaseAndDiscogsIdentityLeakage() {
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(album(), song()),
            mbEditions = listOf(mb(barcode = "1111111111111")),
            mbGroups = listOf(group()),
            discogsObservations = listOf(discogs()), nowMillis = 1_100,
        ).single()
        assertNull(result.release.musicBrainzReleaseId)
        assertNull(result.discogsEdition)
        assertEquals(AppleEditorialFormatStatus.UNCLASSIFIED, result.classification.status)
    }

    @Test fun conflictingMbAndDiscogsCategoriesAreExplicitlyDisputed() {
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(album(), song()),
            mbEditions = listOf(mb()), mbGroups = listOf(group("EP")),
            discogsObservations = listOf(discogs("Single")), nowMillis = 1_100,
        ).single()
        assertEquals(AppleEditorialFormatStatus.CONFLICT, result.classification.status)
        assertEquals(ItunesEditorialReleaseFormat.UNCLASSIFIED, result.classification.format)
    }

    @Test fun expiredOrUnofficialDiscogsCannotEnrichApple() {
        val expired = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(album(), song()), mbEditions = listOf(mb()),
            discogsObservations = listOf(discogs(now = 1000L)),
            nowMillis = 1_000L + DiscogsObservation.TTL_MILLIS,
        ).single()
        assertNull(expired.discogsEdition)
        assertEquals(AppleEditorialFormatStatus.UNCLASSIFIED, expired.classification.status)

        val unofficial = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(album(), song()), mbEditions = listOf(mb()),
            discogsObservations = listOf(discogs(official = false)),
            nowMillis = 1_100L,
        ).single()
        assertNull(unofficial.discogsEdition)
        assertEquals(AppleEditorialFormatStatus.UNCLASSIFIED, unofficial.classification.status)
    }

    @Test fun appleExplicitSingleStaysSingleEvenIfRelatedSourcesDisagree() {
        val result = AppleTriSourceEditorialReconciler.reconcile(
            storefront = "br", appleRows = listOf(album(title = "SUPER REAL ME - Single"), song()),
            mbEditions = listOf(mb()), mbGroups = listOf(group("EP")),
            discogsObservations = listOf(discogs("EP")), nowMillis = 1_100L,
        ).single()
        assertEquals(ItunesEditorialReleaseFormat.SINGLE, result.classification.format)
        assertEquals(AppleEditorialFormatStatus.DECIDED, result.classification.status)
        assertEquals(setOf(ItunesEditorialReleaseFormat.EP),
            result.classification.disputedExternalFormats)
    }
}
