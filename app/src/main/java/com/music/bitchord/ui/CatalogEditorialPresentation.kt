package com.music.bitchord.ui

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind

/** Presentation is selected by the policy; a shelf never changes owner/ID. */
internal data class CatalogEditorialSelection(
    val provider: ProviderKey?,
    val state: UiState<List<HomeShelf>>,
)

/** No backend is mandatory; a loading primary cannot be bypassed by stale secondary data. */
internal fun selectCatalogEditorial(
    ordered: List<ProviderKey>,
    byProvider: Map<ProviderKey, UiState<List<HomeShelf>>>,
): CatalogEditorialSelection {
    var lastError: String? = null
    for (provider in ordered) {
        when (val result = byProvider[provider] ?: continue) {
            UiState.Loading -> return CatalogEditorialSelection(provider, UiState.Loading)
            is UiState.Success -> if (result.data.isNotEmpty()) {
                return CatalogEditorialSelection(provider, result)
            }
            is UiState.Error -> lastError = result.message
        }
    }
    return CatalogEditorialSelection(
        provider = null,
        state = if (lastError != null) UiState.Error(lastError)
            else UiState.Success(emptyList()),
    )
}

/**
 * An unstarted secondary source is EMPTY, never LOADING. For a composite
 * read the required evidence can itself still be loading. Preserve an
 * already-attributed success if authorized, but never invent a new request.
 */
internal fun compositeEditorialReadState(
    source: UiState<List<HomeShelf>>,
    requestCanStart: Boolean,
    evidenceAuthorized: Boolean = true,
): UiState<List<HomeShelf>> = when {
    !evidenceAuthorized -> UiState.Success(emptyList())
    source is UiState.Success && source.data.isNotEmpty() -> source
    !requestCanStart -> UiState.Success(emptyList())
    else -> source
}

/**
 * A primary provider may fall back only after an actual empty/error result.
 * Loading and unqueried are NOT evidence of an empty catalog.
 */
internal fun <T> editorialPrimaryMiss(
    state: UiState<List<T>>?,
): Boolean = when (state) {
    is UiState.Success -> state.data.isEmpty()
    is UiState.Error -> true
    UiState.Loading, null -> false
}

/** Personal files and authenticated server assets are independent of editorial ordering. */
internal fun personalHomeShelves(shelves: List<HomeShelf>): List<HomeShelf> =
    shelves.mapNotNull { shelf ->
        val own = shelf.items.filter { item ->
            val id = item.browseId.orEmpty()
            id.startsWith("local:") || id.startsWith("server:") ||
                id.startsWith("downloads:")
        }
        shelf.copy(items = own).takeIf { own.isNotEmpty() }
    }

/** MusicBrainz-native public release groups; never turn them into iTunes IDs. */
internal fun musicBrainzHomeEditorialShelves(
    shelves: List<HomeShelf>,
    releasesHeading: String,
): List<HomeShelf> = shelves.filter { it.title == releasesHeading }
    .mapNotNull { shelf ->
        val rows = shelf.items.filter { item ->
            item.browseId.orEmpty().startsWith("catalog:RELEASE_GROUP:") ||
                item.browseId.orEmpty().startsWith("catalog:RELEASE:")
        }
        shelf.copy(items = rows).takeIf { rows.isNotEmpty() }
    }

/** Genre membership comes from the typed MusicBrainz tag query, not a title guess. */
internal fun musicBrainzGenreEditorialShelves(
    rows: List<CanonicalCatalogSearchRow>,
    releasesHeading: String,
): List<HomeShelf> {
    val cards = rows.asSequence()
        .filter { it.kind == CanonicalSearchKind.RELEASE_GROUP }
        .filter { canonicalEditorialReleaseType(it) != null }
        .distinctBy { it.musicBrainzId }
        .take(24)
        .map { row ->
            ShelfItem(
                title = row.title,
                subtitle = row.subtitle.orEmpty(),
                thumbnailUrl = row.artworkUri,
                videoId = null,
                browseId = canonicalNativeBrowseId(row),
            )
        }.toList()
    return if (cards.isEmpty()) emptyList() else listOf(HomeShelf(releasesHeading, cards))
}
