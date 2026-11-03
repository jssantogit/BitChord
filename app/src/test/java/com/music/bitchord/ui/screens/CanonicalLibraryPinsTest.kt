package com.music.bitchord.ui.screens

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CanonicalLibraryPinsTest {
    private val album = CanonicalLibraryPin(
        CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "release-id"),
        "ALLDAY PROJECT", "ALLDAY PROJECT",
    )
    private val artist = CanonicalLibraryPin(
        CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "release-id"),
        "Artista", "",
    )
    private val track = CanonicalLibraryPin(
        CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, "recording-id"),
        "What's Next, \"Really\"?", "Faixa & Música",
    )

    @Test fun togglesTypedPinsWithoutSilentlyRemovingAnotherTypeOrReordering() {
        var list = toggleCanonicalLibraryPin(emptyList(), album)
        list = toggleCanonicalLibraryPin(list, artist)
        list = toggleCanonicalLibraryPin(list, track)
        assertEquals(listOf(album, artist, track), list)
        assertEquals(listOf(artist, track), toggleCanonicalLibraryPin(list, album))
        assertEquals(listOf(artist, track), toggleCanonicalLibraryPin(list + album, album))
    }

    @Test fun encodedPinsRoundTripUnicodeAndIdsWithoutConfusingSeparators() {
        val mixed = listOf(album, track.copy(title = "Música | Vírgula, Tab\t"))
        assertEquals(mixed, decodeCanonicalLibraryPins(encodeCanonicalLibraryPins(mixed)))
        assertFalse(encodeCanonicalLibraryPins(mixed).contains("https://secret.example"))
    }

    @Test fun pinNavigationNeverGuessesSourcesOrUsesTitlesAsIds() {
        assertEquals(
            CanonicalLibraryPinOpenTarget.PlayRecording("recording-id"),
            canonicalLibraryPinOpenTarget(track),
        )
        assertEquals(
            CanonicalLibraryPinOpenTarget.OpenEntity(
                com.music.bitchord.data.listening.CanonicalSmartFilterContext.ALBUMS,
                album,
            ),
            canonicalLibraryPinOpenTarget(album),
        )
        assertEquals(
            CanonicalLibraryPinOpenTarget.OpenEntity(
                com.music.bitchord.data.listening.CanonicalSmartFilterContext.ARTISTS,
                artist,
            ),
            canonicalLibraryPinOpenTarget(artist),
        )
    }

    @Test fun malformedOrDuplicateRecordsDoNotEraseOtherValidPins() {
        val json = """[
          {"type":"ALBUM","id":"r","title":"Release","subtitle":"Artista"},
          {"type":"ALBUM","id":"r","title":"Duplicate"},
          {"type":"DANGLING","id":"q","title":"Invalid"},
          {"type":"TRACK","id":"  ","title":"Invalid"}
        ]""".trimIndent()
        val decoded = decodeCanonicalLibraryPins(json)
        assertEquals(1, decoded.size)
        assertEquals("r", decoded.single().selection.entityId)
        assertTrue(decodeCanonicalLibraryPins("not json").isEmpty())
    }

    @Test fun artistPinAvailabilityRequiresExactSourceScopedArtistEvidence() {
        assertTrue(canonicalPinnedArtistAccessible(
            "verified-id", setOf("verified-id"), emptySet(),
        ))
        assertFalse(canonicalPinnedArtistAccessible(
            "verified-id", emptySet(), setOf("Someone"),
        ))
        val nameGroup = playlistDisplayArtistGroupId("aespa")
        assertTrue(canonicalPinnedArtistAccessible(
            nameGroup, emptySet(), setOf("aespa"),
        ))
        assertFalse(canonicalPinnedArtistAccessible(
            nameGroup, emptySet(), setOf("aespa 2"),
        ))
    }

    @Test fun savedPinOrderSurvivesRepositoryRecreation() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("canonical-pin-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = CanonicalLibraryPinRepository(prefs)
        assertTrue(first.toggle(album))
        assertTrue(first.toggle(track))
        assertEquals(listOf(album, track), CanonicalLibraryPinRepository(prefs).pins.value)
        assertFalse(first.toggle(album))
        assertEquals(listOf(track), CanonicalLibraryPinRepository(prefs).pins.value)
    }
}
