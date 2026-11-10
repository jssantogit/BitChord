package com.music.bitchord.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.music.bitchord.ui.player.AppleQueueVectors

/** One coherent vector family for the fast playlist editor and full wizard. */
class PlaylistEditorIconsTest {
    @Test fun playlistControlsHaveNativeVectorIdentitiesInsteadOfMaterialGlyphs() {
        val native = listOf(
            PlaylistEditorIcons.Back, PlaylistEditorIcons.Close,
            PlaylistEditorIcons.Filters, PlaylistEditorIcons.Delete,
            PlaylistEditorIcons.Edit,
            PlaylistEditorIcons.ChevronUp,
            PlaylistEditorIcons.ChevronDown, PlaylistEditorIcons.Calendar,
            PlaylistEditorIcons.Audio, PlaylistEditorIcons.Lock,
            PlaylistEditorIcons.Link, PlaylistEditorIcons.Globe,
            PlaylistEditorIcons.Selected, PlaylistEditorIcons.Unselected,
        )
        assertEquals(native.size, native.map { it.name }.toSet().size)
        assertEquals(AppleQueueVectors.More, PlaylistEditorIcons.More)
        native.forEach {
            assertEquals(24f, it.viewportWidth, 0.0001f)
            assertEquals(24f, it.viewportHeight, 0.0001f)
        }
    }

    @Test fun existingBitChordIconsAreReusedForMusicLibraryAndActions() {
        assertEquals(BitChordIcons.MusicNote, PlaylistEditorIcons.Music)
        assertEquals(BitChordIcons.Library, PlaylistEditorIcons.Library)
        assertEquals(BitChordIcons.Plus, PlaylistEditorIcons.Add)
        assertEquals(BitChordIcons.Check, PlaylistEditorIcons.Check)
        assertEquals(BitChordIcons.Clock, PlaylistEditorIcons.History)
        assertNotEquals(PlaylistEditorIcons.Selected.name, PlaylistEditorIcons.Unselected.name)
    }
}
