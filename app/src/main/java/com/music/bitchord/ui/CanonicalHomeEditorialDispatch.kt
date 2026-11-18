package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderKey

/**
 * Headless dispatch for existing Home. Keep this out of BitChordApp's huge
 * Compose method so new provider conditions do not inflate JVM bytecode.
 * No provider ID or data is ever converted to another provider's identity.
 */
/**
 * Uses the same *visible* MB-native release shelf that Home projects.
 * A raw query may contain only untyped/filtered rows, or personal rows may
 * fill the feed without proving the MB catalog returned a usable release.
 */
internal fun shouldStartAppleHomeEditorial(
    ordered: List<ProviderKey>,
    mbLoading: Boolean,
    visibleHomeShelves: List<HomeShelf>,
    releasesHeading: String,
): Boolean {
    if (BuiltInProviderKeys.ITUNES !in ordered) return false
    return when (ordered.firstOrNull()) {
        BuiltInProviderKeys.ITUNES -> true
        BuiltInProviderKeys.MUSICBRAINZ ->
            !mbLoading &&
                musicBrainzHomeEditorialShelves(visibleHomeShelves, releasesHeading).isEmpty()
        else -> false
    }
}

internal object CanonicalHomeEditorialDispatch {
    fun preferred(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        force: Boolean = false,
    ) {
        when (ordered.firstOrNull()) {
            BuiltInProviderKeys.MUSICBRAINZ ->
                viewModel.loadCanonicalWorldwideReleases(force)
            BuiltInProviderKeys.ITUNES ->
                viewModel.loadAppleHomeEditorial(force)
        }
    }

    fun maybeStartApple(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        @Suppress("UNUSED_PARAMETER") mbLoading: Boolean,
        visibleHomeShelves: List<HomeShelf>,
        releasesHeading: String,
    ) {
        // Compose may have captured the old Loading=false before the
        // primary provider's LaunchedEffect starts its IO request. Always
        // dispatch the preferred MB adapter FIRST and read its current flow
        // after that synchronous admission/loading transition. This is
        // idempotent while active or in the existing positive/negative TTL.
        if (ordered.firstOrNull() == BuiltInProviderKeys.MUSICBRAINZ) {
            viewModel.loadCanonicalWorldwideReleases()
        }
        if (shouldStartAppleHomeEditorial(
            ordered,
            viewModel.canonicalWorldwideLoading.value,
            visibleHomeShelves, releasesHeading,
        )) viewModel.loadAppleHomeEditorial()
    }

    fun maybeStartMusicBrainz(
        viewModel: MainViewModel,
        ordered: List<ProviderKey>,
        appleState: UiState<List<ItunesEditorialRelease>>,
    ) {
        if (ordered.firstOrNull() == BuiltInProviderKeys.ITUNES &&
            BuiltInProviderKeys.MUSICBRAINZ in ordered &&
            editorialPrimaryMiss(appleState)
        ) viewModel.loadCanonicalWorldwideReleases()
    }
}
