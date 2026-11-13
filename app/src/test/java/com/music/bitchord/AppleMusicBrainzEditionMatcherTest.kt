package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleMusicBrainzEditionMatcher
import com.music.bitchord.data.catalog.metadata.AppleMusicBrainzEditionMatch
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseJsonCompleteness
import org.junit.Assert.*
import org.junit.Test

class AppleMusicBrainzEditionMatcherTest {
    private val releaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val secondId = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
    private val groupId = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val recA = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val recB = "3e619ba2-34f0-4649-83d8-05483e2fabe5"

    private fun song(id: Long, name: String, number: Int, collection: Long = 101L) =
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK,
            id = id, storefront = "br", title = name,
            artistName = "ILLIT", artistId = 99, collectionId = collection,
            storeUrl = null, releaseDate = null, genre = null, durationMs = 120_000,
            trackNumber = number, discNumber = 1,
        )

    private fun apple(
        title: String = "SUPER REAL ME - EP",
        tracks: List<ItunesCatalogItem> = listOf(
            song(201, "My World", 1), song(202, "Magnetic", 2),
        ),
        upc: String? = "8801234567890",
        mbid: String? = null,
        appleId: Long = 101L,
    ) = ItunesEditorialRelease(
        appleScopedId = "itunes:br:album:$appleId",
        appleCollectionId = appleId, storefront = "br",
        title = title, artistName = "ILLIT", artistId = 99,
        releaseDate = "2024-03-25", genre = "K-Pop",
        format = ItunesEditorialReleaseFormat.EP,
        artworkUrl = "https://is1-ssl.mzstatic.com/picture.jpg",
        trackCount = 2,
        tracks = tracks,
        musicBrainzReleaseGroupId = mbid,
        musicBrainzArtistId = null,
        musicBrainzRecordingIds = emptyMap(),
        upc = upc,
    )

    private fun candidate(
        release: String = releaseId,
        group: String? = groupId,
        title: String = "SUPER REAL ME",
        barcode: String? = "8801234567890",
        status: String? = "Official",
        tracks: List<MusicBrainzCatalogTrack> = listOf(
            MusicBrainzCatalogTrack(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", recA, "My World", 1, 1, 120_000,
            ),
            MusicBrainzCatalogTrack(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", recB, "Magnetic", 1, 2, 120_000,
            ),
        ),
        complete: Boolean = true,
        artist: String = "ILLIT",
    ) = MusicBrainzCatalogReleaseDetail(
        musicBrainzId = release,
        groupMbid = group,
        title = title,
        artistDisplay = artist,
        country = "KR",
        releaseDate = "2024-03-25",
        tracks = tracks,
        hasCompleteTracklist = complete,
        status = status,
        barcode = barcode,
        artistCreditNames = listOf(artist),
        completeness = MusicBrainzReleaseJsonCompleteness(
            countsExplicitAndExact = complete, offsetsValid = true, positionsExplicitUnique = true,
        ),
    )

    @Test fun onlyCompleteExactEditionEvidenceProducesAppleToMbReleaseAndRecordingLinks() {
        val apple = apple()
        val result = AppleMusicBrainzEditionMatcher.match(apple, listOf(candidate()))
        assertTrue(result is AppleMusicBrainzEditionMatch.Verified)
        val match = result as AppleMusicBrainzEditionMatch.Verified
        assertEquals("itunes:br:album:101", match.appleScopedId)
        assertEquals(releaseId, match.musicBrainzReleaseId)
        assertEquals(groupId, match.musicBrainzReleaseGroupId)
        assertEquals(listOf("itunes:br:track:201" to recA, "itunes:br:track:202" to recB),
            match.recordingLinks.map { it.appleScopedId to it.musicBrainzRecordingId })
    }

    @Test fun missingBarcodeOrPartialAppleTracklistCannotProveEdition() {
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(upc = null),
            listOf(candidate())) is AppleMusicBrainzEditionMatch.Verified)
        assertFalse(AppleMusicBrainzEditionMatcher.match(
            apple(tracks = listOf(song(201, "My World", 1))),
            listOf(candidate())) is AppleMusicBrainzEditionMatch.Verified)
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(),
            listOf(candidate(complete = false))) is AppleMusicBrainzEditionMatch.Verified)
    }

    @Test fun sharedBarcodeAloneCannotMergeDifferentAppleOrMusicBrainzEditions() {
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(title = "HOWSWEET & Bubblegum"),
            listOf(candidate())) is AppleMusicBrainzEditionMatch.Verified)
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(tracks = listOf(
            song(201, "Magnetic", 1), song(202, "My World", 2),
        )), listOf(candidate())) is AppleMusicBrainzEditionMatch.Verified)
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(),
            listOf(candidate(artist = "Other Artist"))) is AppleMusicBrainzEditionMatch.Verified)
    }

    @Test fun noOfficialStatusOrNoVerifiedGroupCannotCreateCanonicalLinks() {
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(),
            listOf(candidate(status = "Bootleg"))) is AppleMusicBrainzEditionMatch.Verified)
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(),
            listOf(candidate(group = null))) is AppleMusicBrainzEditionMatch.Verified)
    }

    @Test fun multipleIndistinguishableExactMusicBrainzEditionsAreAmbiguous() {
        val resolution = AppleMusicBrainzEditionMatcher.match(apple(),
            listOf(candidate(), candidate(release = secondId)))
        assertTrue(resolution is AppleMusicBrainzEditionMatch.Ambiguous)
        assertEquals(setOf(releaseId, secondId),
            (resolution as AppleMusicBrainzEditionMatch.Ambiguous).musicBrainzReleaseIds.toSet())
    }

    @Test fun contradictorySnapshotsOfSameMusicBrainzReleaseFailClosed() {
        val resolution = AppleMusicBrainzEditionMatcher.match(apple(), listOf(
            candidate(), candidate(title = "Other title"),
        ))
        assertFalse(resolution is AppleMusicBrainzEditionMatch.Verified)
    }

    @Test fun unrelatedContradictoryMbEditionDoesNotBlockUniqueVerifiedAppleEdition() {
        val unrelated = candidate(release = secondId, title = "Different unrelated release",
            barcode = "1111111111111")
        val result = AppleMusicBrainzEditionMatcher.match(apple(), listOf(
            candidate(),
            unrelated,
            unrelated.copy(title = "Another unrelated release"),
        ))
        assertTrue(result is AppleMusicBrainzEditionMatch.Verified)
        assertEquals(releaseId, (result as AppleMusicBrainzEditionMatch.Verified).musicBrainzReleaseId)
    }

    @Test fun sameTitleAppleEditsNeedSameExactStorefrontAndCollectionIdentity() {
        val other = apple(appleId = 102L, tracks = listOf(
            song(201, "My World", 1), song(202, "Magnetic", 2),
        ))
        val result = AppleMusicBrainzEditionMatcher.match(other, listOf(candidate()))
        assertFalse(result is AppleMusicBrainzEditionMatch.Verified)
    }

    @Test fun invalidRecordingIdBlocksRecordingLinksAndEditionCertification() {
        val broken = candidate(tracks = listOf(
            MusicBrainzCatalogTrack("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "invalid", "My World", 1, 1, 120_000),
            MusicBrainzCatalogTrack("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                recB, "Magnetic", 1, 2, 120_000),
        ))
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(), listOf(broken))
            is AppleMusicBrainzEditionMatch.Verified)
    }

    @Test fun olderOrDifferentRegionalDateDoesNotAutomaticallyStealEdition() {
        val changed = candidate().copy(releaseDate = "2017-03-25")
        assertFalse(AppleMusicBrainzEditionMatcher.match(apple(), listOf(changed))
            is AppleMusicBrainzEditionMatch.Verified)
    }
}
