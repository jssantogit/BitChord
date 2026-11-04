package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalPlaylistRouteTest {
    @Test fun newPlaylistWinsOverTheOpenParentLibrary() {
        assertEquals("canonical_playlist_library",
            canonicalPlaylistRoute(showBuilder = false, showLibrary = true))
        assertEquals("smart_playlist_builder",
            canonicalPlaylistRoute(showBuilder = true, showLibrary = true))
        assertEquals("smart_playlist_builder",
            canonicalPlaylistRoute(showBuilder = true, showLibrary = false))
        assertNull(canonicalPlaylistRoute(showBuilder = false, showLibrary = false))
    }
}
