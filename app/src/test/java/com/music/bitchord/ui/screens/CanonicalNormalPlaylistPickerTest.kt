package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalNormalPlaylistPickerTest {
    @Test fun togglingAddsAndRemovesCanonicalRecordingIdsWithoutChangingOtherOrder() {
        val initial = listOf("recording-one", "recording-two")
        val added = toggleCanonicalStaticMember(initial, "recording-three")
        assertEquals(listOf("recording-one", "recording-two", "recording-three"), added)
        val removed = toggleCanonicalStaticMember(added, "recording-two")
        assertEquals(listOf("recording-one", "recording-three"), removed)
        assertEquals(initial, toggleCanonicalStaticMember(removed, "recording-two")
            .let { listOf(it[0], it[2]) })
    }

    @Test fun reorderPreservesAllCanonicalMembersAndHonoursBoundaries() {
        val initial = listOf("rec-a", "rec-b", "rec-c")
        assertEquals(listOf("rec-b", "rec-a", "rec-c"),
            moveCanonicalStaticMember(initial, "rec-b", towardStart = true))
        assertEquals(listOf("rec-a", "rec-c", "rec-b"),
            moveCanonicalStaticMember(initial, "rec-b", towardStart = false))
        assertEquals(initial, moveCanonicalStaticMember(initial, "rec-a", towardStart = true))
        assertEquals(initial, moveCanonicalStaticMember(initial, "rec-c", towardStart = false))
        assertEquals(initial, moveCanonicalStaticMember(initial, "absent", towardStart = true))
    }

    @Test fun browsingAlbumsAndArtistsUsesCanonicalEntityIdsNotDisplayNames() {
        val album = CanonicalPlaylistBrowseGroup(
            "release-1", "Get Up", CanonicalPlaylistBrowseType.ALBUMS)
        val duplicate = album.copy()
        val edition = CanonicalPlaylistBrowseGroup(
            "release-2", "Get Up", CanonicalPlaylistBrowseType.ALBUMS)
        val artist = CanonicalPlaylistBrowseGroup(
            "artist-1", "NewJeans", CanonicalPlaylistBrowseType.ARTISTS)
        assertEquals(listOf(album, edition),
            filterCanonicalPlaylistBrowseGroups(listOf(album, duplicate, edition, artist), "get"))
        assertEquals(listOf(artist),
            filterCanonicalPlaylistBrowseGroups(listOf(album, artist), "NEW"))
    }

    @Test fun artistDisplayFallbackIsOnlyABrowseKeyNotARecordingId() {
        assertEquals(
            "browse-display-artist:NewJeans",
            playlistDisplayArtistGroupId("NewJeans"),
        )
        assertEquals(
            "browse-display-artist:aespa, G-Dragon",
            playlistDisplayArtistGroupId("aespa, G-Dragon"),
        )
    }

    @Test fun canonicalPickerIncludesLocalSourceWithoutConfiguredRemoteAccounts() {
        assertEquals(listOf("local-mediastore"),
            canonicalPlaylistProviderKeys(emptyList(), null))
        assertEquals(listOf("local-mediastore", "opensubsonic:server-a"),
            canonicalPlaylistProviderKeys(setOf("opensubsonic:server-a"), null))
        assertEquals(listOf("local-mediastore"),
            canonicalPlaylistProviderKeys(listOf("opensubsonic:server-a"), "local-mediastore"))
        assertEquals(listOf("opensubsonic:server-a"),
            canonicalPlaylistProviderKeys(listOf("opensubsonic:server-a"), "opensubsonic:server-a"))
        assertEquals(listOf("local-mediastore", "opensubsonic:server-a"),
            canonicalPlaylistProviderKeys(listOf("opensubsonic:server-a"), null))
        assertEquals(emptyList<String>(),
            canonicalPlaylistProviderKeys(emptyList(), "opensubsonic:offline"))
    }

    @Test fun albumSelectionIsCompleteOnlyWhenEveryCurrentMemberIsSelected() {
        val album = listOf("rec-a", "rec-b", "rec-c")
        assertEquals(false, allCanonicalGroupMembersSelected(album, listOf("rec-a")))
        assertEquals(true, allCanonicalGroupMembersSelected(album, album))
        assertEquals(listOf("rec-c"),
            toggleCanonicalGroupMembers(album, listOf("rec-a", "rec-b")))
        assertEquals(emptyList<String>(), toggleCanonicalGroupMembers(album, album))
        assertEquals(listOf("rec-external", "rec-a", "rec-b", "rec-c"),
            toggleCanonicalGroupMembers(album, listOf("rec-external")))
    }

    @Test fun localOnlySourceDefaultsToLocalButNeverOverridesMultisourceOrEditing() {
        assertEquals("local-mediastore", defaultCanonicalNormalPlaylistSource(
            listOf("local-mediastore"),
        ))
        assertEquals(null, defaultCanonicalNormalPlaylistSource(
            listOf("local-mediastore", "opensubsonic:server-a"),
        ))
        assertEquals(null, defaultCanonicalNormalPlaylistSource(emptyList()))
    }

    @Test fun noDuplicateRecordingIsIntroducedByRepeatedSelection() {
        val first = toggleCanonicalStaticMember(emptyList(), "rec-a")
        val second = toggleCanonicalStaticMember(first, "rec-a")
        val third = toggleCanonicalStaticMember(second, "rec-a")
        assertEquals(listOf("rec-a"), third)
    }
}
