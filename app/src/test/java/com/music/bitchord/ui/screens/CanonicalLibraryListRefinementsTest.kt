package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.data.listening.CanonicalEntityRatingEntity
import com.music.bitchord.data.listening.CanonicalSmartFilterContext
import com.music.bitchord.ui.theme.rememberArtworkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Visual/identity regression for the three root Library categories. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalLibraryListRefinementsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryActionsAreOnlyForSongsAndAlbumsNotArtistsOrAlbumDetail() {
        assertTrue(shouldShowCanonicalLibraryCategoryPlayback(
            CanonicalSmartFilterContext.SONGS, isAlbumDetail = false, hasPlayableTracks = true,
        ))
        assertTrue(shouldShowCanonicalLibraryCategoryPlayback(
            CanonicalSmartFilterContext.ALBUMS, isAlbumDetail = false, hasPlayableTracks = true,
        ))
        assertFalse(shouldShowCanonicalLibraryCategoryPlayback(
            CanonicalSmartFilterContext.ARTISTS, isAlbumDetail = false, hasPlayableTracks = true,
        ))
        assertFalse(shouldShowCanonicalLibraryCategoryPlayback(
            CanonicalSmartFilterContext.SONGS, isAlbumDetail = true, hasPlayableTracks = true,
        ))
        assertFalse(shouldShowCanonicalLibraryCategoryPlayback(
            CanonicalSmartFilterContext.ALBUMS, isAlbumDetail = false, hasPlayableTracks = false,
        ))
    }

    @Test fun albumCategoryStartsWithAnExpandedNativeSearchAndRealPlayActions() {
        compose.setContent {
            MaterialTheme {
                NativeUnifiedLibraryCategoryHeader(
                    title = "Álbuns",
                    context = CanonicalSmartFilterContext.ALBUMS,
                    query = "",
                    onQueryChange = {},
                    searching = false,
                    palette = rememberArtworkPalette(imageUrl = null),
                    hasPlayableTracks = true,
                    onPlayAll = {},
                )
            }
        }
        compose.onNodeWithText("Álbuns").assertExists()
        compose.onNodeWithTag("native-detail-search-field").assertExists()
        compose.onNodeWithTag("canonical-library-category-play-actions").assertExists()
        // The root category must not reuse the release-detail placeholder sleeve.
        compose.onNodeWithTag("native-release-header-artwork").assertDoesNotExist()
    }

    @Test fun artistCategoryStartsWithSearchButWithoutTheAlbumPlaybackControls() {
        compose.setContent {
            MaterialTheme {
                NativeUnifiedLibraryCategoryHeader(
                    title = "Artistas",
                    context = CanonicalSmartFilterContext.ARTISTS,
                    query = "",
                    onQueryChange = {},
                    searching = false,
                    palette = rememberArtworkPalette(imageUrl = null),
                    hasPlayableTracks = true,
                    onPlayAll = {},
                )
            }
        }
        compose.onNodeWithText("Artistas").assertExists()
        compose.onNodeWithTag("native-detail-search-field").assertExists()
        compose.onNodeWithTag("canonical-library-category-play-actions").assertDoesNotExist()
    }

    @Test fun nativeAlbumAndArtistRatingsAreScopedToTheirExactTypedIdentities() {
        val displayArtistId = playlistDisplayArtistGroupId("aespa")
        val ratings = canonicalLibraryEntityRatingsByTarget(listOf(
            CanonicalEntityRatingEntity("ALBUM", "same-id", 4.5, 1),
            CanonicalEntityRatingEntity("ARTIST", "same-id", 2.5, 2),
            CanonicalEntityRatingEntity("DISPLAY_ARTIST", displayArtistId, 3.5, 3),
            CanonicalEntityRatingEntity("ALBUM", "other-release", 0.3, 4),
        ))
        val album = CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "same-id")
        val artist = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, "same-id")
        val displayArtist = CanonicalLibrarySelection(CanonicalLibraryEntityType.ARTIST, displayArtistId)
        assertEquals(4.5, canonicalLibraryEntityRatingForRow(ratings, album))
        assertEquals(2.5, canonicalLibraryEntityRatingForRow(ratings, artist))
        assertEquals(3.5, canonicalLibraryEntityRatingForRow(ratings, displayArtist))
        assertEquals(null, canonicalLibraryEntityRatingForRow(ratings,
            CanonicalLibrarySelection(CanonicalLibraryEntityType.TRACK, "same-id")))
        assertEquals(null, canonicalLibraryEntityRatingForRow(ratings,
            CanonicalLibrarySelection(CanonicalLibraryEntityType.ALBUM, "other-release")))
    }
}
