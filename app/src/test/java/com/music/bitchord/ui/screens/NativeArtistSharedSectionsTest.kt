package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the single native artist carousel used by catalog and Library. */
class NativeArtistSharedSectionsTest {
    @Test fun artistCarouselCapsToTwentyAndKeepsOriginalOrder() {
        val tracks = (1..27).map { "recording-$it" }
        val columns = nativeArtistTrackColumns(tracks)
        assertEquals(listOf(4, 4, 4, 4, 4), columns.map { it.size })
        assertEquals(tracks.take(20), columns.flatten())
    }

    @Test fun artistCarouselRespectsFilteredOrderAndPartialLastColumn() {
        val tracks = listOf("mbid-b", "mbid-a", "mbid-c", "mbid-d", "mbid-e")
        assertEquals(listOf(tracks.take(4), tracks.takeLast(1)),
            nativeArtistTrackColumns(tracks))
    }

    @Test fun emptyArtistDoesNotCreatePhantomTrackColumns() {
        assertTrue(nativeArtistTrackColumns(emptyList<String>()).isEmpty())
    }
    @Test fun localArtistCannotDisplayTopTracksEvenWhenPopularityFlagLeaksIn() {
        val shown = nativeArtistVisibleSections(
            NativeArtistSectionEvidence(
                context = NativeArtistContext.LIBRARY,
                hasTracks = true,
                hasVerifiedPopularity = true,
                hasAlbums = true,
                hasSinglesAndEps = true,
            ),
        )
        assertTrue(NativeArtistSection.TRACKS in shown)
        assertTrue(NativeArtistSection.TOP_TRACKS !in shown)
        assertTrue(NativeArtistSection.ALBUMS in shown)
        assertTrue(NativeArtistSection.SINGLES_EPS in shown)
    }

    @Test fun onlineArtistUsesTopTracksOnlyWhenProviderSuppliesRealRanking() {
        val ranked = nativeArtistVisibleSections(
            NativeArtistSectionEvidence(
                context = NativeArtistContext.ONLINE_CATALOG,
                hasTracks = true,
                hasVerifiedPopularity = true,
                hasUnclassifiedReleases = true,
            ),
        )
        assertTrue(NativeArtistSection.TOP_TRACKS in ranked)
        assertTrue(NativeArtistSection.TRACKS !in ranked)
        assertTrue(NativeArtistSection.RELEASES in ranked)

        val unranked = nativeArtistVisibleSections(
            NativeArtistSectionEvidence(
                context = NativeArtistContext.ONLINE_CATALOG,
                hasTracks = true,
                hasVerifiedPopularity = false,
            ),
        )
        assertTrue(NativeArtistSection.TRACKS in unranked)
        assertTrue(NativeArtistSection.TOP_TRACKS !in unranked)
    }

    @Test fun emptyAndUnprovenSectionsAreHiddenInsteadOfRenderingEmptyHeadings() {
        val sections = nativeArtistVisibleSections(
            NativeArtistSectionEvidence(
                context = NativeArtistContext.ONLINE_CATALOG,
                hasTracks = false,
                hasVerifiedPopularity = true,
                hasRecentAlbum = false,
                hasAlbums = false,
                hasSinglesAndEps = true,
                hasUnclassifiedReleases = false,
            ),
        )
        assertEquals(setOf(NativeArtistSection.SINGLES_EPS), sections)
        assertTrue(sections.none {
            it == NativeArtistSection.TRACKS || it == NativeArtistSection.TOP_TRACKS
        })
    }

    @Test fun everyArtistContextHasAtMostOneTrackShelf() {
        for (context in NativeArtistContext.entries) {
            for (hasTracks in listOf(false, true)) {
                for (verified in listOf(false, true)) {
                    val sections = nativeArtistVisibleSections(
                        NativeArtistSectionEvidence(context, hasTracks, verified),
                    )
                    assertTrue(sections.count {
                        it == NativeArtistSection.TRACKS ||
                            it == NativeArtistSection.TOP_TRACKS
                    } <= 1)
                }
            }
        }
    }

}
