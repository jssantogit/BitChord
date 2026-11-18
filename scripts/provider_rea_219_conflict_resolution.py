#!/usr/bin/env python3
"""Explicit, fail-closed #219 x frozen #212 *disposable CI* reconciliation.

Runs ONLY after a normal no-commit merge reports the four reviewed conflicts.
This is neither a branch merge nor permission to rewrite or bypass REA UI.
No ours/theirs blanket preference is applied: all 18 conflict blocks are
identified by expected old/new context and reconciled per semantic contract.
An unrecognized change halts the CI so a newer REA cannot silently disappear.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = "app/src/main/java/com/music/bitchord/MainActivity.kt"
HELD = "app/src/main/java/com/music/bitchord/ui/components/HeldContextMenu.kt"
DETAIL = "app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt"
SEARCH = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt"
EXPECTED = {
    MAIN: [
        ("canonicalAppleArtworkPromotion", "selectedSearchProviderKey"),
        ("targetState = when", "targetState = pageRouteKey"),
        ("communityMetadataRecording = recording", "LongPressOrigin.consume()"),
        ("refreshCanonicalGenreEditorial", "viewModel.loadCanonicalGenre"),
        ("val visualDetail = ", "toolbarOwner == NativeTopBarRouteOwner.CATALOG_DETAIL"),
        ("openedPublicPlaylist != null", "NativeTopBarRouteOwner.PUBLIC_PLAYLIST"),
        ("unifiedLibraryCategory != null", "NativeTopBarRouteOwner.UNIFIED_LIBRARY"),
        ("appleNativeKind == AppleNativeDetailKind.ALBUM", "appleExploreGenreChrome"),
        ("accessory = if (", "accessory = if (appleSearchRootChrome)"),
        ("if (appleSharedVisual != null", "toolbarOwner != NativeTopBarRouteOwner.PLAYLIST_LIBRARY"),
        ("!appleNativeDetailOpen", "!appleSearchRootChrome"),
        ("!isReleaseDetail && !appleNativeDetailOpen", "!appleSearchRootChrome && !isReleaseDetail"),
    ],
    HELD: [("Color(0xFF29292D)", "nativeCompactPlateColor")],
    DETAIL: [("Same shared lifted metadata action", "Full-screen lifted metadata menu")],
    SEARCH: [
        ("LocalUriHandler", "testTag"),
        ("appleResults: UiState", "searchCommitted: Boolean"),
        ("searchingCanonical -> when", "canonicalSearchEditing"),
        ("else -> canonicalMusicBrainzResults(", "searchingAddons && serverSearchEditing"),
    ],
}
PATTERN = re.compile(
    r"(?m)^<<<<<<< HEAD\r?\n(?P<ours>.*?)^=======\r?\n"
    r"(?P<rea>.*?)^>>>>>>> refs/remotes/origin/rea-212\r?\n",
    re.S | re.M,
)


def glue(*parts: str) -> str:
    return "\n".join(x.rstrip("\n") for x in parts if x.strip()) + "\n"


def replace_once(text: str, old: str, new: str, where: str) -> str:
    if text.count(old) != 1:
        raise RuntimeError(f"{where}: expected one anchor {old!r}, got {text.count(old)}")
    return text.replace(old, new, 1)


def replace_main(i: int, ours: str, rea: str) -> str:
    if i == 1:
        # Independently owned states: source-native Apple AND REA search tabs.
        return glue(ours, rea)
    if i == 2:
        # REA owns tab animation, top-bar route identity and page transitions.
        # Apple-native route is added into pageRouteKey, not a second when.
        return rea
    if i == 3:
        # REA owns the held action; metadata-only tap without a registered
        # long-press still opens its non-playable detail, never Media3.
        return glue(
            "                            onCanonicalTrackHold = { recording ->",
            "                                val origin = LongPressOrigin.consume()",
            "                                if (origin != null) {",
            "                                    heldCatalogSearchOrigin = origin",
            "                                    heldCatalogSearchRow = recording",
            "                                } else {",
            "                                    communityMetadataRecording = recording",
            "                                }",
        )
    if i == 4:
        # No unguarded legacy genre retry; the policy-aware editorial path wins.
        return ours
    if i == 5:
        # Keep REA route ownership but admit source-native Apple detail ONLY
        # while the Apple route is really open.
        return glue(
            "                val visualDetail = detail ?: appleSharedVisual?.page",
            "                val isDetailVisible =",
            "                    ((toolbarOwner == NativeTopBarRouteOwner.CATALOG_DETAIL && detail != null) ||",
            "                        (appleNativeDetailOpen && appleSharedVisual != null)) &&",
            "                    (visualDetail?.type == BrowseType.ALBUM ||",
            "                        visualDetail?.type == BrowseType.PLAYLIST ||",
            "                        visualDetail?.type == BrowseType.ARTIST) &&",
        )
    if i == 6:
        return glue(
            "                        toolbarOwner == NativeTopBarRouteOwner.PUBLIC_PLAYLIST ->",
            "                            openedPublicPlaylist?.title.orEmpty()",
            "                        visualDetail != null && detailActiveShelf != null ->",
            "                            detailActiveShelf?.title.orEmpty()",
        )
    if i == 7:
        return glue(
            "                            toolbarOwner == NativeTopBarRouteOwner.UNIFIED_LIBRARY ||",
            "                            (toolbarOwner == NativeTopBarRouteOwner.CATALOG_DETAIL &&",
            "                                detail != null && detailActiveShelf != null) ||",
            "                            (appleNativeDetailOpen && visualDetail != null &&",
            "                                detailActiveShelf != null) -> true",
            "                        toolbarOwner == NativeTopBarRouteOwner.PLAYLIST_LIBRARY ->",
        )
    if i == 8:
        # Different destinations: Apple native back and REA Explore back.
        return glue(ours, rea)
    if i == 9:
        return rea
    if i == 10:
        # Apple native album search/sort and artist share actions are additive.
        # The history button copied from old #219 is not: REA deliberately
        # removed its private NativeHistoryTopBarAction function and owns
        # top chrome/history. Keep only REA's root toolbar guard after Apple.
        tail = "                        if (!showSettings && !showAccountScrobbling && !showPlaylistLibrary) {"
        if ours.count(tail) != 1 or ours.count("NativeHistoryTopBarAction(") != 1:
            raise RuntimeError("MainActivity: legacy history toolbar changed")
        apple_actions = ours.split(tail, 1)[0]
        return glue(apple_actions, rea)
    if i == 11:
        return ours.replace(
            "unifiedLibraryCategory == null && !appleNativeDetailOpen",
            "unifiedLibraryCategory == null && !appleSearchRootChrome && !appleNativeDetailOpen",
        )
    if i == 12:
        return rea.replace(
            "if (!appleSearchRootChrome && !isReleaseDetail &&",
            "if (!appleSearchRootChrome && !appleNativeDetailOpen && !isReleaseDetail &&",
        )
    raise RuntimeError(f"MainActivity unexpected conflict #{i}")


def apple_filtered_rows() -> str:
    # Called only after the provider-policy planner chose the Apple source.
    # The same native REA Search list/compositor remains in use.
    return """                } else if (!catalogSearchMusicBrainzVisible && canonicalDetail == null &&
                    (appleResults != null || appleArtistResults != null || appleTrackResults != null)
                ) {
                    when (filter) {
                        SearchFilter.ARTISTS -> appleEditorialArtistRows(
                            appleArtistResults, onAppleOpen,
                            paging = applePaging[AppleSearchSection.ARTISTS],
                            onLoadMore = { onAppleLoadMore(AppleSearchSection.ARTISTS) },
                        )
                        SearchFilter.ALBUMS -> appleEditorialAlbumRows(
                            appleResults, onAppleOpen,
                            paging = applePaging[AppleSearchSection.ALBUMS],
                            onLoadMore = { onAppleLoadMore(AppleSearchSection.ALBUMS) },
                        )
                        SearchFilter.SONGS -> appleEditorialTrackRows(
                            appleTrackResults, onAppleTrackOpen,
                            paging = applePaging[AppleSearchSection.TRACKS],
                            onLoadMore = { onAppleLoadMore(AppleSearchSection.TRACKS) },
                        )
                        SearchFilter.ALL -> {
                            appleEditorialArtistRows(
                                appleArtistResults, onAppleOpen, showHeading = true,
                                paging = applePaging[AppleSearchSection.ARTISTS],
                                onLoadMore = { onAppleLoadMore(AppleSearchSection.ARTISTS) },
                            )
                            appleEditorialTrackRows(
                                appleTrackResults, onAppleTrackOpen, showHeading = true,
                                paging = applePaging[AppleSearchSection.TRACKS],
                                onLoadMore = { onAppleLoadMore(AppleSearchSection.TRACKS) },
                            )
                            appleEditorialAlbumRows(
                                appleResults, onAppleOpen, showHeading = true,
                                paging = applePaging[AppleSearchSection.ALBUMS],
                                onLoadMore = { onAppleLoadMore(AppleSearchSection.ALBUMS) },
                            )
                        }
                        else -> {}
                    }
"""


def replace_search(i: int, ours: str, rea: str) -> str:
    if i in (1, 2):
        # Import addition and parameter addition; both independent interfaces.
        return glue(ours, rea)
    if i == 3:
        # REA's real edit/submit suggestions and native long-press dominate.
        return rea
    if i == 4:
        anchor = "                } else canonicalMusicBrainzResults(\n"
        # Renderer remains provider-specific only as a data source; the
        # precedence comes from gate.ordered(CATALOG_SEARCH) in ViewModel.
        return replace_once(rea, anchor, apple_filtered_rows() + anchor, "SearchScreen:renderer")
    raise RuntimeError(f"SearchScreen unexpected conflict #{i}")


def resolve_block(path: str, i: int, ours: str, rea: str) -> str:
    expected_ours, expected_rea = EXPECTED[path][i - 1]
    if expected_ours not in ours or expected_rea not in rea:
        raise RuntimeError(
            f"{path}: conflict #{i} changed; expected {expected_ours!r} / {expected_rea!r}"
        )
    if path == MAIN:
        return replace_main(i, ours, rea)
    if path == SEARCH:
        return replace_search(i, ours, rea)
    if path in (HELD, DETAIL):
        # REA is sole authority for this visual statement; source data
        # contracts live in adjacent code, not this conflicting line.
        return rea
    raise RuntimeError(f"Unreviewed path: {path}")


def resolve(path: str, data: str) -> tuple[str, int]:
    matches = list(PATTERN.finditer(data))
    if len(matches) != len(EXPECTED[path]):
        raise RuntimeError(
            f"{path}: expected {len(EXPECTED[path])} reviewed conflicts, got {len(matches)}"
        )
    index = [0]
    def substitute(match: re.Match) -> str:
        index[0] += 1
        return resolve_block(path, index[0], match.group("ours"), match.group("rea"))
    result = PATTERN.sub(substitute, data)
    if "<<<<<<<" in result or ">>>>>>>" in result or re.search(r"(?m)^=======\s*$", result):
        raise RuntimeError(f"{path}: leftover merge marker")
    return result, len(matches)


def extract_rea_search_source_state(main: str) -> str:
    """Shrink the combined BitChordApp bytecode without changing REA UI.

    Both PRs compile separately; the real merged Compose body exceeded JVM's
    64 KiB per-method limit. Move the complete native Search tab observation
    into its own @Composable (the same state, effects, labels and ordering).
    Keep selectedSearchProviderKey in the caller: root Search actions use it.
    No wrapper, pixel, route or provider behavior changes.
    """
    start = "    val configuredSearchApis by SourceRegistry.configs.collectAsStateWithLifecycle()\n"
    end = "    val libraryResults by viewModel.libraryResults.collectAsStateWithLifecycle()\n"
    if main.count(start) != 1 or main.count(end) != 1:
        raise RuntimeError("Combined MainActivity: REA search state anchors changed")
    a, b = main.index(start), main.index(end)
    if b <= a:
        raise RuntimeError("Combined MainActivity: invalid Search state order")
    body = main[a:b]
    must_have = [
        "configuredSearchApis", "unfilteredLocalSearchSongs", "indexedSearchProviderKeys",
        "produceState(initialValue = emptyList<String>())", "LaunchedEffect(selectedTab)",
        "LaunchedEffect(visibleSearchSources, searchSource, selectedSearchProviderKey, selectedTab)",
        "val visibleSearchSources = availableSearchProviderTabs(",
        "if (!validSelection) viewModel.setSearchSource(SearchSource.CANONICAL)",
    ]
    for value in must_have:
        if value not in body:
            raise RuntimeError(f"REA Search state contract changed: {value}")
    # There must be no untracked caller dependency in this exact reviewed
    # region: it uses only those five arguments, the original global imports,
    # and the existing return value.
    call = (
        "    val visibleSearchSources = rememberIntegratedSearchProviderTabs(\n"
        "        context = context, viewModel = viewModel, selectedTab = selectedTab,\n"
        "        searchSource = searchSource, selectedSearchProviderKey = selectedSearchProviderKey,\n"
        "    )\n"
    )
    new_main = main[:a] + call + main[b:]
    if "private fun rememberIntegratedSearchProviderTabs(" in new_main:
        raise RuntimeError("Combined MainActivity: already extracted Search state")
    function = (
        "\n/** Same REA search-source behavior, split from BitChordApp for JVM method size. */\n"
        "@Composable\n"
        "private fun rememberIntegratedSearchProviderTabs(\n"
        "    context: Context,\n"
        "    viewModel: MainViewModel,\n"
        "    selectedTab: Int,\n"
        "    searchSource: SearchSource,\n"
        "    selectedSearchProviderKey: String?,\n"
        "): List<com.music.bitchord.ui.SearchProviderTab> {\n"
        + body +
        "    return visibleSearchSources\n"
        "}\n"
    )
    return new_main.rstrip() + "\n" + function


def extract_rea_route_calculation(main: str) -> str:
    """Put the REA/Apple route decision into a plain Kotlin local function.

    It is pure and still evaluated on every composition; this preserves the
    existing REA transition owner while removing one large when from the
    enclosing JVM method (which already exceeded 64 KiB).
    """
    old = "        val pageRouteKey = when {\n"
    marker = "        // AnimatedContent is the route authority."
    if main.count(old) != 1 or main.count(marker) != 1:
        raise RuntimeError("Combined MainActivity: pageRouteKey anchors changed")
    new_main = replace_once(
        main, old, "        fun integratedPageRouteKey(): String = when {\n",
        "MainActivity:route decision extraction",
    )
    return replace_once(
        new_main, marker,
        "        val pageRouteKey = integratedPageRouteKey()\n" + marker,
        "MainActivity:route binding",
    )


def extract_common_canonical_feed_state(main: str) -> str:
    """Move seven unchanged, provider-neutral state reads outside BitChordApp.

    Reuses exactly the same flows and Compose lifecycle; returns their
    inferred value types using a typed generic data class. No provider
    ordering, list layout, identity, or REA visual behavior is altered.
    """
    names = [
        ("canonicalDiscoveryCatalog", "canonicalDiscoveryCatalog"),
        ("canonicalHomeLocalPicks", "canonicalHomeLocalPicks"),
        ("canonicalHomeRemotePicks", "canonicalHomeRemotePicks"),
        ("canonicalTrendingMbids", "canonicalTrendingRecordingMbids"),
        ("canonicalTrendingLocalSongs", "canonicalTrendingLocalSongs"),
        ("canonicalExplorePublicPopularity", "canonicalExplorePublicPopularity"),
        ("canonicalExploreSitewide", "canonicalExploreSitewide"),
    ]
    old = "".join(
        f"    val {name} by viewModel.{flow}.collectAsStateWithLifecycle()\n"
        for name, flow in names
    )
    if main.count(old) != 1:
        raise RuntimeError("Combined MainActivity: canonical feed state changed")
    names_line = ", ".join(name for name, _ in names)
    new = (
        "    val (" + names_line + ") =\n"
        "        rememberIntegratedCanonicalFeedStates(viewModel)\n"
    )
    main = replace_once(main, old, new, "MainActivity:canonical feed extraction")
    fields = ",\n".join(
        f"    val {name}: {typ}"
        for (name, _), typ in zip(names, "ABCDEFG")
    )
    collected = ",\n".join(
        f"        viewModel.{flow}.collectAsStateWithLifecycle().value"
        for _, flow in names
    )
    helper = (
        "\n/** State-only refactor of frozen REA Home/Explore; no UI layout. */\n"
        "private data class IntegratedCanonicalFeedStates<A, B, C, D, E, F, G>(\n"
        + fields + ",\n)\n\n"
        "@Composable\n"
        "private fun rememberIntegratedCanonicalFeedStates(viewModel: MainViewModel) =\n"
        "    IntegratedCanonicalFeedStates(\n"
        + collected + ",\n    )\n"
    )
    if "private data class IntegratedCanonicalFeedStates" in main:
        raise RuntimeError("Combined MainActivity: feed observation already split")
    return main.rstrip() + "\n" + helper


def extract_contiguous_state_observers(
    main: str, name: str, entries: list[tuple[str, str]],
) -> str:
    """Typed, exact-only Compose-method-size split, no changed state source."""
    if not (2 <= len(entries) <= 18):
        raise RuntimeError(f"{name}: invalid reviewed state group")
    old = "".join(
        f"    val {variable} by {flow}.collectAsStateWithLifecycle()\n"
        for variable, flow in entries
    )
    if main.count(old) != 1:
        raise RuntimeError(f"Combined MainActivity: {name} state group changed")
    vars_ = ", ".join(variable for variable, _ in entries)
    function_name = f"observeIntegrated{name}States"
    class_name = f"Integrated{name}States"
    uses_vm = any(flow.startswith("viewModel.") for _, flow in entries)
    param = "viewModel: MainViewModel" if uses_vm else ""
    actual = "viewModel" if uses_vm else ""
    call = (
        f"    val ({vars_}) =\n"
        f"        {function_name}({actual})\n"
    )
    main = replace_once(main, old, call, f"MainActivity:{name} observation")
    types = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"[:len(entries)]
    fields = ",\n".join(
        f"    val {variable}: {type_name}"
        for (variable, _), type_name in zip(entries, types)
    )
    reads = ",\n".join(
        f"        {flow}.collectAsStateWithLifecycle().value"
        for _, flow in entries
    )
    helper = (
        f"\n/** Unchanged {name} Flow reads, separated from BitChordApp bytecode. */\n"
        f"private data class {class_name}<{', '.join(types)}>(\n"
        + fields + ",\n)\n\n"
        "@Composable\n"
        f"private fun {function_name}({param}) =\n"
        f"    {class_name}(\n"
        + reads + ",\n    )\n"
    )
    if f"private data class {class_name}<" in main:
        raise RuntimeError(f"{name}: state observers already extracted")
    return main.rstrip() + "\n" + helper


def extract_additional_common_state(main: str) -> str:
    groups = [
        ("AccountLyricsSearch", [
            ("account", "viewModel.account"),
            ("selectedChannelName", "viewModel.selectedChannelName"),
            ("googleAccounts", "viewModel.googleAccounts"),
            ("activeAccountId", "viewModel.activeAccountId"),
            ("activeProfileId", "viewModel.activeProfileId"),
            ("historyState", "viewModel.history"),
            ("lyrics", "viewModel.lyrics"),
            ("lyricsSource", "viewModel.lyricsSource"),
            ("lyricsChecked", "viewModel.lyricsChecked"),
            ("lyricsProviderStates", "viewModel.lyricsProviderStates"),
            ("searchHistory", "viewModel.searchHistory"),
            ("searchSuggestions", "viewModel.suggestions"),
            ("searchLoadingMore", "viewModel.searchLoadingMore"),
            ("searchScrollReset", "viewModel.searchScrollReset"),
            ("detailStack", "viewModel.detailStack"),
            ("releaseLibrary", "viewModel.releaseLibrary"),
        ]),
        ("SupplementalHome", [
            ("canonicalPersonalRecommendations", "viewModel.canonicalExplorePersonalRecommendations"),
            ("listenBrainzAccountEnabled", "AppSettings.listenBrainzEnabled"),
            ("listenBrainzAccountToken", "AppSettings.listenBrainzToken"),
            ("homeLoadingMore", "viewModel.homeLoadingMore"),
            ("homeRecentlyPlayedLoading", "viewModel.homeRecentlyPlayedLoading"),
        ]),
        ("DownloadSettings", [
            ("savedDownloads", "Downloads.saved"),
            ("localMusicFolderUri", "AppSettings.localMusicFolderUri"),
            ("filterNonMusicAudio", "AppSettings.filterNonMusicAudio"),
            ("webdavUrl", "AppSettings.webdavUrl"),
            ("smbHost", "AppSettings.smbHost"),
            ("librarySort", "AppSettings.librarySort"),
        ]),
        ("PlaybackList", [
            ("pinnedToOriginal", "OriginalVersion.pinned"),
            ("qualityUpgradesInFlight", "NerdStats.racingLossless"),
            ("playlists", "viewModel.playlists"),
            ("playlistsLoading", "viewModel.playlistsLoading"),
        ]),
        ("FolderDisplay", [
            ("downloadedMusicSort", "AppSettings.downloadedMusicSort"),
            ("localMusicSort", "AppSettings.localMusicSort"),
            ("downloadedMusicViewType", "AppSettings.downloadedMusicViewType"),
            ("localMusicViewType", "AppSettings.localMusicViewType"),
        ]),
    ]
    for group, entries in groups:
        main = extract_contiguous_state_observers(main, group, entries)
    return main


def extract_integrated_root_shell(main: str) -> str:
    """Bound the JVM method of the *combined* Compose root without changing UI.

    Box's app-shell content can expand into BitChordApp's bytecode. Give the
    exact existing screen composition its own non-inline @Composable lambda;
    it still sees all existing state/callbacks, draws in the same order and
    retains its original BoxScope. No visual components or navigation routes
    are copied or moved into a different owner.
    """
    marker = (
        "    Box(\n"
        "        Modifier\n"
        "            .fillMaxSize()\n"
        "            .background(MaterialTheme.colorScheme.background),\n"
        "    ) {\n"
    )
    # Only operate on the known top-level app shell after the other semantic
    # reconciliation transformations. Never split arbitrary nested Boxes.
    if main.count(marker) != 1:
        raise RuntimeError("Combined MainActivity: root Box anchor changed")
    start = main.index(marker)
    end = main.find("\n}\n", start)
    if end < 0:
        raise RuntimeError("Combined MainActivity: app shell end missing")
    original = main[start:end]
    for invariant in (
        "val routeBackHandlers: @Composable () -> Unit",
        "if (confirmJioSaavn)",
        "HeldContextMenu(",
        "targetState = pageRouteKey",
    ):
        if invariant not in original:
            raise RuntimeError(f"Combined MainActivity: root Box missing {invariant}")
    if not original.rstrip().endswith("    }"):
        raise RuntimeError("Combined MainActivity: root Box is not last body statement")
    if "renderIntegratedRootShell()" in main:
        raise RuntimeError("Combined MainActivity: root shell split already applied")

    # The combined REA+provider root is larger than either standalone tree.
    # Even when Kotlin/Compose compiles, its synthetic lambda helper can exceed
    # the JVM's 255 parameter-slot limit. Rebind immutable outer locals INSIDE
    # the root lambda through small, typed anonymous holders. Unlike an Any/Map
    # cast, the original Kotlin types remain statically checked. This is only
    # in the disposable combined CI tree, never either upstream branch.
    #
    # Each holder has at most 16 captured arguments; the root lambda captures
    # the holder references, not every individual immutable local. Mutable
    # vars retain their original delegated state/callback ownership.
    app_entry = main.find("private fun BitChordApp(")
    if app_entry < 0 or app_entry >= start:
        raise RuntimeError("Combined MainActivity: root owner missing")
    before = main[app_entry:start]
    outer_names = []
    for line in before.splitlines():
        match = re.match(r"^    val ([A-Za-z_][A-Za-z0-9_]*)\s*(?:by\b|=|:)", line)
        if match:
            outer_names.append(match.group(1))
        destructured = re.match(r"^    val \(([^)]+)\)\s*=", line)
        if destructured:
            outer_names.extend(part.strip() for part in destructured.group(1).split(","))
    captures = []
    for name in dict.fromkeys(outer_names):
        if name != "_" and re.search(rf"(?<![A-Za-z0-9_.]){re.escape(name)}\b", original):
            captures.append(name)
    if not (45 <= len(captures) <= 210):
        raise RuntimeError(
            f"Combined MainActivity: immutable root capture inventory changed ({len(captures)})"
        )
    if "providerReaRootCapture" in main:
        raise RuntimeError("Combined MainActivity: root captures already packed")
    groups = [captures[i:i + 16] for i in range(0, len(captures), 16)]
    holder_lines = []
    alias_lines = []
    for i, group in enumerate(groups):
        holder = f"providerReaRootCapture{i}"
        holder_lines.append(f"    val {holder} = object {{")
        holder_lines.extend(f"        val {name} = {name}" for name in group)
        holder_lines.append("    }")
        alias_lines.extend(f"        val {name} = {holder}.{name}" for name in group)
    # No mutation of the drawn subtree: its local bindings are restored under
    # the exact same names and original Kotlin types before composing the Box.
    indented = "\n".join(
        "    " + line if line else "" for line in original.split("\n")
    )
    replacement = (
        "\n".join(holder_lines) + "\n"
        + "    val renderIntegratedRootShell: @Composable () -> Unit = {\n"
        + "\n".join(alias_lines) + "\n"
        + indented + "\n"
        + "    }\n"
        + "    renderIntegratedRootShell()"
    )
    return main[:start] + replacement + main[end:]


def reconcile_combined_routes(main: str) -> str:
    # The REA uses one top-bar/AnimatedContent route authority. Add the Apple
    # provider's already-implemented detail underneath it, never reinstate
    # the separate old targetState branch that caused tab ghosts.
    anchor = (
        "                 detail != null -> detail.browseId\n"
        "                 rediscoveryRecordingId != null ->"
    )
    replacement = (
        "                 detail != null -> detail.browseId\n"
        "                 appleNativeDetailOpen -> if (appleNativeKind == AppleNativeDetailKind.ALBUM)\n"
        "                     \"apple_native_album\" else \"apple_native_artist\"\n"
        "                 rediscoveryRecordingId != null ->"
    )
    # Normalize indentation to the existing REA code, preserving local style.
    if anchor not in main:
        match = re.search(
            r"(?m)^(\s*)detail != null -> detail\.browseId\n"
            r"\1rediscoveryRecordingId != null ->",
            main,
        )
        if not match or main.count("detail != null -> detail.browseId") != 1:
            raise RuntimeError("MainActivity: cannot uniquely locate REA pageRouteKey")
        indent = match.group(1)
        anchor = (
            f"{indent}detail != null -> detail.browseId\n"
            f"{indent}rediscoveryRecordingId != null ->"
        )
        replacement = (
            f"{indent}detail != null -> detail.browseId\n"
            f"{indent}appleNativeDetailOpen -> if (appleNativeKind == AppleNativeDetailKind.ALBUM)\n"
            f"{indent}    \"apple_native_album\" else \"apple_native_artist\"\n"
            f"{indent}rediscoveryRecordingId != null ->"
        )
    reconciled = replace_once(main, anchor, replacement, "MainActivity:pageRouteKey")
    stage = extract_common_canonical_feed_state(
        extract_rea_route_calculation(extract_rea_search_source_state(reconciled))
    )
    return extract_integrated_root_shell(extract_additional_common_state(stage))



def reconcile_combined_search(combined: str) -> str:
    """Keep the *entire* REA edit/result branch, then insert Apple rows.

    Git may auto-merge portions of #219's old 'when' between two actual
    conflict blocks. Resolving only <<<<<<< markers leaves dangling
    SearchFilter.FOO -> clauses inside REA's 'if' grammar. Source this one
    visual/composition region from the exact fetched REA HEAD; leave all
    other automatically merged code (and the #219-only Apple functions)
    untouched.
    """
    original = subprocess.check_output(
        ["git", "show", f"refs/remotes/origin/rea-212:{SEARCH}"],
        cwd=ROOT, text=True,
    )
    start = "                searchingCanonical -> {\n"
    end = "                searchingLibrary || searchingAddons -> if (\n"

    def span(data: str, what: str) -> tuple[int, int]:
        if data.count(start) != 1 or data.count(end) != 1:
            raise RuntimeError(f"{what}: canonical search region is not unique")
        left = data.index(start)
        right = data.index(end, left)
        if right <= left:
            raise RuntimeError(f"{what}: bad search region order")
        return left, right

    rl, rr = span(original, "REA")
    cl, cr = span(combined, "combined")
    rea_region = original[rl:rr]
    anchor = "                } else canonicalMusicBrainzResults(\n"
    rea_region = replace_once(
        rea_region, anchor,
        apple_filtered_rows() + anchor,
        "SearchScreen:REA canonical Apple/MB renderer",
    )
    # Strict invariants keep the provider-controlled dispatch and REAL
    # REA suggestions, including the server pre-submit path.
    for name in (
        "canonicalSearchEditing(", "searchingAddons && serverSearchEditing(",
        "catalogSearchMusicBrainzVisible", "appleEditorialArtistRows(",
        "appleEditorialAlbumRows(", "appleEditorialTrackRows(",
    ):
        if name not in rea_region:
            raise RuntimeError(f"Reconciled Search region missing {name!r}")
    return combined[:cl] + rea_region + combined[cr:]



def verify_contracts(main: str, search: str, held: str) -> None:
    for needle in [
        "targetState = pageRouteKey", "appleNativeDetailOpen -> if",
        "canonicalAppleArtworkPromotion", "visibleSearchSources",
        "refreshCanonicalGenreEditorial", "heldCatalogSearchOrigin = origin",
        "toolbarOwner == NativeTopBarRouteOwner.PUBLIC_PLAYLIST",
        "NativeCollectionSearchAction", "NativeArtistShareTopBarAction",
    ]:
        if needle not in main:
            raise RuntimeError(f"Combined MainActivity lost {needle!r}")
    if "NativeHistoryTopBarAction(" in main:
        raise RuntimeError("Legacy History button from #219 leaked into frozen REA chrome")
    for needle in [
        "searchCommitted", "appleResultTabs",
        "canonicalSearchEditing", "onCanonicalLongPress",
        "catalogSearchMusicBrainzVisible", "appleEditorialArtistRows(",
        "appleEditorialAlbumRows(", "appleEditorialTrackRows(",
        "onAppleLoadMore", "onCanonicalRecordingInfo",
        "serverSearchEditing",
    ]:
        if needle not in search:
            raise RuntimeError(f"Combined SearchScreen lost {needle!r}")
    if "heldSpotlightCutout(" not in held or "nativeCompactPlateColor(" not in held:
        raise RuntimeError("Combined hold menu lost frozen source spotlight or REA palette")


def run() -> int:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=U"],
        cwd=ROOT, check=True, stdout=subprocess.PIPE, text=True,
    )
    paths = set(result.stdout.splitlines())
    if paths != set(EXPECTED):
        raise RuntimeError(f"Unknown integration conflicts: {sorted(paths)}; expected {sorted(EXPECTED)}")

    changed = {}
    for path in EXPECTED:
        file = ROOT / path
        data = file.read_text(encoding="utf-8")
        combined, n = resolve(path, data)
        if path == MAIN:
            combined = reconcile_combined_routes(combined)
        if path == SEARCH:
            combined = reconcile_combined_search(combined)
            # The REA deleted this import, but the #219 Apple pagination
            # still uses OutlinedButton in its metadata-only result rows.
            if "OutlinedButton(" in combined and (
                "import androidx.compose.material3.OutlinedButton\n" not in combined
            ):
                combined = replace_once(
                    combined,
                    "import androidx.compose.material3.MaterialTheme\n",
                    "import androidx.compose.material3.MaterialTheme\n"
                    "import androidx.compose.material3.OutlinedButton\n",
                    "SearchScreen:Apple pagination import",
                )
        changed[path] = (combined, n)
    verify_contracts(changed[MAIN][0], changed[SEARCH][0], changed[HELD][0])

    # No edits until ALL conflict signatures and invariants have passed.
    for path, (content, count) in changed.items():
        (ROOT / path).write_text(content, encoding="utf-8")
        print(f"Explicitly reconciled {path} ({count} verified conflicts)")
        if path == SEARCH:
            # Provenance of the exact CI-generated source, not the unmerged
            # PR file. Include only the currently failing Kotlin section.
            for line_no, line in enumerate(content.splitlines(), 1):
                if 450 <= line_no <= 525:
                    print(f"COMBINED_SEARCH {line_no}: {line}")
    subprocess.run(["git", "add", "--", *EXPECTED], cwd=ROOT, check=True)
    pending = subprocess.check_output(
        ["git", "diff", "--name-only", "--diff-filter=U"],
        cwd=ROOT, text=True,
    )
    if pending.strip():
        raise RuntimeError(f"Unresolved files after reconciliation: {pending}")
    print("PROVISIONAL COMBINED TREE ONLY; original branches remain unmodified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except (RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"FAIL-CLOSED provider/REA reconciliation: {exc}", file=sys.stderr)
        raise SystemExit(1)
