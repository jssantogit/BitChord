package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.ReleaseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract before extracting the canonical artist detail data source. */
class CanonicalArtistDetailStateTest {
    @Test fun onlyVerifiedRecordingToReleaseMembershipEntersArtistDetails() {
        val linked = ReleaseEntity("linked-release", "同名", "aespa", null)
        val homonym = ReleaseEntity("other-release", "同名", "aespa", null)
        val releases = listOf(homonym, linked, linked.copy(artworkUri = "later-cover"))

        assertEquals(
            listOf("linked-release"),
            canonicalArtistVerifiedReleases(releases, setOf(linked.id)).map { it.id },
        )
    }

    @Test fun scopeDuplicatesNeverCauseDuplicateAlbumsAndOrderIsStable() {
        val first = ReleaseEntity("release-b", "B", "artist", null)
        val second = ReleaseEntity("release-a", "A", "artist", null)
        assertEquals(
            listOf("release-b", "release-a"),
            canonicalArtistVerifiedReleases(
                listOf(first, second, first), setOf("release-a", "release-b"),
            ).map { it.id },
        )
        assertTrue(canonicalArtistVerifiedReleases(
            listOf(first, second), emptySet(),
        ).isEmpty())
    }
    @Test fun nestedAlbumUsesAvailableSourceUnionWithoutDuplicateRecordings() {
        assertEquals(
            listOf("recording-b", "recording-a", "recording-c"),
            canonicalArtistNestedRecordingOrder(
                listOf(
                    listOf("recording-b", "recording-a"),
                    listOf("recording-a", "recording-c"),
                ),
            ),
        )
        assertTrue(canonicalArtistNestedRecordingOrder(emptyList()).isEmpty())
    }

    @Test fun artistRatingStateRequiresATypedExistingLibraryIdentity() {
        assertEquals(null, canonicalArtistRatingTarget(null))
        assertEquals(null, canonicalArtistRatingTarget("  "))
        val target = canonicalArtistRatingTarget("artist-id")
        assertEquals("ARTIST", target?.entityType)
        assertEquals("artist-id", target?.entityId)
    }

    @Test fun nestedNavigationKeepsVerifiedReleaseIdentityAndCanClose() {
        val selected = androidx.compose.runtime.mutableStateOf<ReleaseEntity?>(null)
        val navigation = CanonicalArtistNestedNavigation(selected)
        val release = ReleaseEntity("release-id-1", "同名", "artist", null)
        val homonym = ReleaseEntity("release-id-2", "同名", "artist", null)

        assertEquals(null, navigation.openedRelease)
        navigation.open(release)
        assertEquals("release-id-1", navigation.openedRelease?.id)
        navigation.open(homonym)
        assertEquals("release-id-2", navigation.openedRelease?.id)
        navigation.close()
        assertEquals(null, navigation.openedRelease)
    }

    @Test fun nestedAlbumNavigationKeysFollowIdentityNotOnlyArtistName() {
        val homonymOne = canonicalArtistDetailNavigationKey("artist-a", "同名")
        val homonymTwo = canonicalArtistDetailNavigationKey("artist-b", "同名")
        assertTrue(homonymOne != homonymTwo)
        assertEquals(homonymOne, canonicalArtistDetailNavigationKey("artist-a", "New stage name"))
        assertTrue(
            canonicalArtistDetailNavigationKey(null, "aespa") !=
                canonicalArtistDetailNavigationKey(null, "エスパ"),
        )
        assertTrue(canonicalArtistDetailNavigationKey(null, "artist-a") != homonymOne)
    }

}
