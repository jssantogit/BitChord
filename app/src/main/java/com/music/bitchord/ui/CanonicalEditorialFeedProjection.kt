package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ListenBrainzPublicPlaylist
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow

/**
 * Pure projection for the existing Home/Explore widgets.
 * Keeping these provider-neutral selections outside BitChordApp prevents a
 * giant Compose function from exceeding the JVM's 64 KiB method limit.
 * No UI elements, composable invocation, identity conversion or network read.
 */
internal fun projectCanonicalHomeEditorial(
    canonicalHomeShelves: List<HomeShelf>,
    appleHomeReleases: UiState<List<ItunesEditorialRelease>>,
    catalogEditorialOrder: List<ProviderKey>,
    canonicalWorldwideLoading: Boolean,
    releasesHeading: String,
    discoveryHeading: String,
    todayIsoDate: String,
): CatalogEditorialSelection {
    val mb = BuiltInProviderKeys.MUSICBRAINZ
    val apple = BuiltInProviderKeys.ITUNES
    val musicBrainzShelves = musicBrainzHomeEditorialShelves(
        canonicalHomeShelves, releasesHeading,
    )
    val nativeAppleState: UiState<List<HomeShelf>> = when (val source = appleHomeReleases) {
        is UiState.Success -> UiState.Success(
            appleFirstHomeShelves(
                appleAlbums = source.data,
                existingLocalShelves = emptyList(),
                releasesHeading = releasesHeading,
                discoveryHeading = discoveryHeading,
                todayIsoDate = todayIsoDate,
            ),
        )
        UiState.Loading -> UiState.Loading
        is UiState.Error -> source
    }
    val appleState = compositeEditorialReadState(
        nativeAppleState,
        // An empty completed MB primary makes Apple an actively scheduled
        // fallback, not an unstarted/empty source. Preserve Loading until
        // the Apple request settles; never promote Apple before MB finishes.
        requestCanStart = catalogEditorialOrder.firstOrNull() == apple ||
            (catalogEditorialOrder.firstOrNull() == mb &&
                !canonicalWorldwideLoading && musicBrainzShelves.isEmpty()),
        evidenceAuthorized = apple in catalogEditorialOrder,
    )
    return selectCatalogEditorial(
        catalogEditorialOrder, mapOf(
            mb to if (canonicalWorldwideLoading && musicBrainzShelves.isEmpty())
                UiState.Loading else UiState.Success(musicBrainzShelves),
            apple to appleState,
        ),
    )
}

/** Retain personal shelves regardless of catalog preference or upstream status. */
internal fun projectCanonicalHomeDisplay(
    canonicalHomeShelves: List<HomeShelf>,
    selection: CatalogEditorialSelection,
    publicPlaylistShelf: HomeShelf?,
): List<HomeShelf> =
    (selection.state as? UiState.Success)?.data.orEmpty() +
        personalHomeShelves(canonicalHomeShelves) +
        listOfNotNull(publicPlaylistShelf?.takeIf { it.items.isNotEmpty() })

internal fun projectCanonicalHomeState(
    displayed: List<HomeShelf>,
    selected: CatalogEditorialSelection,
): UiState<List<HomeShelf>> = when {
    displayed.isNotEmpty() -> UiState.Success(displayed)
    selected.state is UiState.Loading -> UiState.Loading
    else -> UiState.Error("Nenhum lançamento disponível nas fontes editoriais habilitadas.")
}

/** Genre tabs use original provider IDs, with public playlists outside fallback. */
internal fun projectCanonicalGenreState(
    tag: String?,
    genrePlaylists: List<ListenBrainzPublicPlaylist>,
    playlistHeading: String,
    appleByTag: Map<String, UiState<List<ItunesEditorialRelease>>>,
    musicBrainzRows: UiState<List<CanonicalCatalogSearchRow>>,
    ordered: List<ProviderKey>,
    releasesHeading: String,
    /** The exact requested tag, never inferred from an old MBID row. */
    musicBrainzResultTag: String? = tag,
): UiState<List<HomeShelf>> {
    val playlistShelf = canonicalPublicPlaylistShelf(playlistHeading, genrePlaylists)
    if (tag == null) return UiState.Success(emptyList())
    val appleState: UiState<List<HomeShelf>> = when (
        val source = appleByTag[tag] ?: UiState.Loading
    ) {
        is UiState.Success -> UiState.Success(
            appleFirstGenreShelves(
                tag, source.data, publicPlaylists = null,
                releaseHeading = releasesHeading,
            ),
        )
        UiState.Loading -> UiState.Loading
        is UiState.Error -> source
    }
    val mbState: UiState<List<HomeShelf>> = when (
        val source = if (musicBrainzResultTag == tag) musicBrainzRows else UiState.Loading
    ) {
        is UiState.Success -> UiState.Success(
            musicBrainzGenreEditorialShelves(source.data, releasesHeading),
        )
        UiState.Loading -> UiState.Loading
        is UiState.Error -> source
    }
    val chosen = selectCatalogEditorial(ordered, mapOf(
        BuiltInProviderKeys.MUSICBRAINZ to mbState,
        BuiltInProviderKeys.ITUNES to appleState,
    ))
    val releases = (chosen.state as? UiState.Success)?.data.orEmpty()
    val all = releases + listOfNotNull(playlistShelf?.takeIf { it.items.isNotEmpty() })
    return when {
        all.isNotEmpty() -> UiState.Success(all)
        chosen.state is UiState.Loading -> UiState.Loading
        chosen.state is UiState.Error -> chosen.state
        else -> UiState.Error("Nenhum lançamento disponível nas fontes editoriais habilitadas.")
    }
}
