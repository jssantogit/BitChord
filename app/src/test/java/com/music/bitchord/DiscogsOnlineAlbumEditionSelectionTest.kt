package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.discogsOnlineAlbumEditionMbid
import com.music.bitchord.ui.screens.CanonicalCatalogDetailPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The album tile is a MusicBrainz ReleaseGroup, not a Discogs edition.
 * Metadata for a pressing may be shown only when the native tracklist came
 * from a specifically selected, typed Release inside that exact group.
 */
class DiscogsOnlineAlbumEditionSelectionTest {
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val otherGroup = "bd6eeae4-2754-4cb8-8ed2-741afc2249a7"
    private val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"

    private fun page(
        selectedReleaseMbid: String? = release,
        selectedReleaseGroupMbid: String? = group,
    ) = CanonicalCatalogDetailPage(
        title = "SUPER REAL ME",
        summary = null,
        rows = emptyList(),
        selectedReleaseMbid = selectedReleaseMbid,
        selectedReleaseGroupMbid = selectedReleaseGroupMbid,
    )

    @Test fun releaseGroupUsesOnlyEditionThatActuallySuppliedItsTracklist() {
        val resolved = page()
        assertEquals(release, discogsOnlineAlbumEditionMbid(
            browseId = "catalog:RELEASE_GROUP:$group",
            selectedReleaseMbid = resolved.selectedReleaseMbid,
            selectedReleaseGroupMbid = resolved.selectedReleaseGroupMbid,
        ))
    }

    @Test fun oldGroupResponseCannotAnnotateAnotherReleaseGroup() {
        val old = page()
        assertNull(discogsOnlineAlbumEditionMbid(
            browseId = "catalog:RELEASE_GROUP:$otherGroup",
            selectedReleaseMbid = old.selectedReleaseMbid,
            selectedReleaseGroupMbid = old.selectedReleaseGroupMbid,
        ))
        assertNull(discogsOnlineAlbumEditionMbid(
            browseId = "catalog:RELEASE_GROUP:$group",
            selectedReleaseMbid = release,
            selectedReleaseGroupMbid = otherGroup,
        ))
    }

    @Test fun unknownOrMalformedGroupAndEditionNeverSynthesizeDiscogsIdentity() {
        for ((edition, relatedGroup) in listOf(
            null to group, release to null, "bad-id" to group,
            release to "not-an-mbid",
        )) {
            val detail = page(edition, relatedGroup)
            assertNull(discogsOnlineAlbumEditionMbid(
                "catalog:RELEASE_GROUP:$group",
                detail.selectedReleaseMbid,
                detail.selectedReleaseGroupMbid,
            ))
        }
        assertNull(discogsOnlineAlbumEditionMbid(
            "catalog:RELEASE_GROUP:$group:extra", release, group,
        ))
        assertNull(discogsOnlineAlbumEditionMbid(
            "catalog:ARTIST:$group", release, group,
        ))
        assertNull(discogsOnlineAlbumEditionMbid(
            "local:$group", release, group,
        ))
        assertNull(discogsOnlineAlbumEditionMbid(
            "catalog:RELEASE_GROUP:bad-id", release, group,
        ))
    }

    @Test fun explicitReleaseRouteKeepsItsOwnIdentityWithoutAGroupInference() {
        assertEquals(release, discogsOnlineAlbumEditionMbid(
            "catalog:RELEASE:$release", null, null,
        ))
        assertEquals(release, discogsOnlineAlbumEditionMbid(
            "catalog:RELEASE:$release", "wrong-id", otherGroup,
        ))
        assertNull(discogsOnlineAlbumEditionMbid(
            "catalog:RELEASE:$release:extra", null, null,
        ))
    }
}
