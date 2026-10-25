package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.download.SavedCollection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CanonicalDiscoveryScreenComposeTest {
    @get:Rule val compose = createComposeRule()

    private val artist = CanonicalCatalogSearchRow(
        musicBrainzId = "6b128dd7-e998-4edb-a4f5-400fef190445",
        kind = CanonicalSearchKind.ARTIST,
        title = "ILLIT",
        subtitle = "South Korea",
        fromOfflineCache = true,
    )

    @Test fun discoverySearchActionOpensCanonicalCatalogAndArtistTapKeepsTypedMbid() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CanonicalDiscoveryScreen(
                    surface = CanonicalDiscoverySurface.EXPLORE,
                    catalog = UiState.Success(listOf(artist)),
                    downloadedCollections = emptyList(),
                    localPlaylists = emptyList(),
                    onSearchCatalog = { actions += "search:canonical" },
                    onOpenLibrary = {},
                    onOpenDownloadedCollection = {},
                    onOpenLocalPlaylist = {},
                    onOpenCatalogEntry = { actions += "artist:${it.kind}:${it.musicBrainzId}" },
                    onRetryCatalog = {},
                    onRefresh = {},
                    refreshing = false,
                    onPlayRecording = { actions += "play:$it" },
                    onQueueRecording = { actions += "queue:$it" },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                )
            }
        }

        compose.onNodeWithText("Search MusicBrainz").performClick()
        compose.onNodeWithText("ILLIT").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf("search:canonical", "artist:ARTIST:6b128dd7-e998-4edb-a4f5-400fef190445"),
                actions,
            )
        }
    }

    @Test fun homeOpensPersistedDownloadAndLocalPlaylistPages() {
        val downloaded = SavedCollection(
            id = "downloaded-release",
            title = "Downloaded album",
            subtitle = "ILLIT",
            playlist = false,
        )
        val playlist = LocalPlaylist("local-list", "Travel playlist", emptyList())
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CanonicalDiscoveryScreen(
                    surface = CanonicalDiscoverySurface.HOME,
                    catalog = UiState.Success(emptyList()),
                    downloadedCollections = listOf(downloaded),
                    localPlaylists = listOf(playlist),
                    onSearchCatalog = {},
                    onOpenLibrary = {},
                    onOpenDownloadedCollection = { opened += "download:${it.id}" },
                    onOpenLocalPlaylist = { opened += "playlist:${it.browseId}" },
                    onOpenCatalogEntry = {},
                    onRetryCatalog = {},
                    onRefresh = {},
                    refreshing = false,
                    onPlayRecording = {},
                    onQueueRecording = {},
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                )
            }
        }

        compose.onNodeWithText("Downloaded album").performClick()
        compose.onNodeWithText("Travel playlist").performClick()

        compose.runOnIdle {
            assertEquals(
                listOf("download:downloaded-release", "playlist:local:playlist:local-list"),
                opened,
            )
        }
    }

    @Test fun homeRendersOfflineCatalogAndNavigatesArtistWithoutYoutubeFallback() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CanonicalDiscoveryScreen(
                    surface = CanonicalDiscoverySurface.HOME,
                    catalog = UiState.Success(listOf(artist)),
                    downloadedCollections = emptyList(),
                    localPlaylists = emptyList(),
                    onSearchCatalog = {},
                    onOpenLibrary = {},
                    onOpenDownloadedCollection = {},
                    onOpenLocalPlaylist = {},
                    onOpenCatalogEntry = { opened += "${it.kind}:${it.musicBrainzId}" },
                    onRetryCatalog = {},
                    onRefresh = {},
                    refreshing = false,
                    onPlayRecording = {},
                    onQueueRecording = {},
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                )
            }
        }
        compose.onNodeWithText("ILLIT").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("ARTIST:6b128dd7-e998-4edb-a4f5-400fef190445"), opened,
            )
        }
    }

    @Test fun metadataOnlyRecordingHasNoPlaybackActionsButCatalogedAssetUsesDelegatedGate() {
        val recordingMbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
        val actions = mutableListOf<String>()
        val catalog = mutableStateOf<UiState<List<CanonicalCatalogSearchRow>>>(
            UiState.Success(listOf(CanonicalCatalogSearchRow(
                musicBrainzId = recordingMbid,
                kind = CanonicalSearchKind.RECORDING,
                title = "Magnetic",
                subtitle = "ILLIT",
            ))),
        )
        compose.setContent {
            MaterialTheme {
                CanonicalDiscoveryScreen(
                    surface = CanonicalDiscoverySurface.EXPLORE,
                    catalog = catalog.value,
                    downloadedCollections = emptyList(),
                    localPlaylists = emptyList(),
                    onSearchCatalog = {},
                    onOpenLibrary = {},
                    onOpenDownloadedCollection = {},
                    onOpenLocalPlaylist = {},
                    onOpenCatalogEntry = {},
                    onRetryCatalog = {},
                    onRefresh = {},
                    refreshing = false,
                    onPlayRecording = { actions += "play:$it" },
                    onQueueRecording = { actions += "queue:$it" },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                )
            }
        }

        compose.onNodeWithText("Magnetic").assertExists()
        compose.onNodeWithText("Metadata only · no cataloged audio").assertExists()
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithText("Add to queue").assertDoesNotExist()
        compose.runOnIdle {
            catalog.value = UiState.Success(listOf(CanonicalCatalogSearchRow(
                musicBrainzId = recordingMbid,
                kind = CanonicalSearchKind.RECORDING,
                title = "Magnetic",
                subtitle = "ILLIT",
                hasCatalogedAsset = true,
            )))
        }
        compose.onNodeWithText("Audio asset cataloged · playback will verify access").assertExists()
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithText("Add to queue").performClick()

        compose.runOnIdle {
            assertEquals(listOf("play:$recordingMbid", "queue:$recordingMbid"), actions)
        }
    }
}
