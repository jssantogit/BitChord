package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.music.bitchord.ui.screens.CanonicalPublicPlaylistSearchRow
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylistDetail
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState

/** Small state adapter: expensive Compose flows and effects live outside
 * BitChordApp, whose JVM method has a hard 64KB bytecode limit. */
internal data class CanonicalPublicPlaylistUi(
    val playlists: Map<String, List<ListenBrainzPublicPlaylist>>,
    val detail: UiState<ListenBrainzPublicPlaylistDetail>,
    val homeShelf: HomeShelf?,
    val homeNotice: String?,
    val genreNotice: String?,
)

@Composable
internal fun rememberCanonicalPublicPlaylistUi(
    viewModel: MainViewModel,
    homeSelected: Boolean,
    exploreSelected: Boolean,
    genreTag: String?,
): CanonicalPublicPlaylistUi {
    val playlists by viewModel.canonicalPublicPlaylists.collectAsStateWithLifecycle()
    val status by viewModel.canonicalPublicPlaylistSearchStatus.collectAsStateWithLifecycle()
    val detail by viewModel.canonicalPublicPlaylistDetail.collectAsStateWithLifecycle()

    LaunchedEffect(homeSelected, exploreSelected, genreTag) {
        when {
            homeSelected -> viewModel.loadCanonicalPublicPlaylists()
            exploreSelected && genreTag == null -> viewModel.loadCanonicalExploreGenreCovers()
            exploreSelected -> viewModel.loadCanonicalPublicPlaylists(genreTag)
        }
    }

    val homeShelf = remember(playlists) {
        canonicalPublicPlaylistShelf(
            "Playlists para descobrir", playlists["home"].orEmpty(),
        )
    }
    val homeNotice = if (homeShelf != null) null else when (val current = status["home"]) {
        is UiState.Error -> current.message
        is UiState.Success -> "Nenhuma playlist pública de descoberta encontrada."
        else -> "Procurando playlists públicas..."
    }
    val genreNotice = genreTag?.let { tag ->
        if (!playlists[tag].isNullOrEmpty()) null else when (val current = status[tag]) {
            is UiState.Error -> current.message
            is UiState.Success -> "Nenhuma playlist pública encontrada para este gênero."
            else -> "Procurando playlists deste gênero..."
        }
    }
    return CanonicalPublicPlaylistUi(playlists, detail, homeShelf, homeNotice, genreNotice)
}


/** Keep the public playlist search boundary outside the large main Compose method. */
@Composable
internal fun rememberCanonicalPublicPlaylistSearchUi(
    viewModel: MainViewModel,
    query: String,
    active: Boolean,
): UiState<List<CanonicalPublicPlaylistSearchRow>>? {
    val state by viewModel.canonicalPublicPlaylistQuery.collectAsStateWithLifecycle()
    val typed = query.trim()
    LaunchedEffect(active, typed) {
        if (active && typed.length in 2..80) {
            delay(650L)
            viewModel.searchCanonicalPublicPlaylists(typed)
        }
    }
    if (!active || typed.isEmpty()) return null
    if (typed.length < 2) return UiState.Success(emptyList())
    val queryState = state?.takeIf { it.first == typed }?.second ?: return UiState.Loading
    return when (queryState) {
        UiState.Loading -> UiState.Loading
        is UiState.Error -> UiState.Error(queryState.message)
        is UiState.Success -> UiState.Success(queryState.data.map { item ->
            CanonicalPublicPlaylistSearchRow(
                mbid = item.mbid,
                title = item.title,
                creator = item.creator,
                artworkUri = item.artworkUrl,
            )
        })
    }
}
