package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSongPlaylistPickerTest {
    @Test fun addingAPlayableSongUsesItsVerifiedCanonicalRecordingNotVideoOrTitle() {
        val target = canonicalSongPlaylistSelection("verified-recording-1")
        assertEquals(CanonicalLibraryEntityType.TRACK, target?.type)
        assertEquals("verified-recording-1", target?.entityId)
        assertNull(canonicalSongPlaylistSelection(null))
        assertNull(canonicalSongPlaylistSelection(""))
        assertNull(canonicalSongPlaylistSelection("  "))
    }

    @Test fun addToPlaylistCanCreateOnlyNormalAndNeverAccessSmartOrFilters() {
        assertFalse(playlistWizardAllowsSmartCreation(normalOnlyCreation = true))
        assertTrue(playlistWizardAllowsSmartCreation(normalOnlyCreation = false))
        assertTrue(initialPlaylistWizardNormalMode(normalOnlyCreation = true))
        assertFalse(initialPlaylistWizardNormalMode(normalOnlyCreation = false))
    }
}
