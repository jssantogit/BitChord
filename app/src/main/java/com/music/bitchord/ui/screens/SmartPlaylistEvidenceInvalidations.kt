package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Invalidation-only clock for an already opened smart playlist preview.
 *
 * Catalog membership, release metadata, eligible assets and scoped remote
 * ratings are tracked alongside local listening outcomes and explicit ratings.
 * All database observers are removed automatically when collection is cancelled
 * (navigation, changing the draft rule, or leaving the screen).
 *
 * The first emission is used for watcher readiness. UI can drop it because the
 * user explicitly requested the initial preview; subsequent emissions refresh
 * only the already-visible preview. Provider account preferences live outside
 * Room, so a separate manual refresh remains available after account switches.
 */
@OptIn(FlowPreview::class)
internal fun smartPlaylistEvidenceChanges(
    catalog: CanonicalCatalogDatabase,
    listening: ListeningEventDatabase,
): Flow<Unit> = merge(
    catalog.invalidationTracker.createFlow(
        "catalog_recording",
        "catalog_release",
        "catalog_release_track",
        "catalog_artist",
        "catalog_recording_artist_credit",
        "catalog_release_artist_credit",
        "musicbrainz_release_enrichment",
        "catalog_external_identity", // Release dates depend on a verified MusicBrainz alias.
        "catalog_media_asset",
        "catalog_remote_rating_evidence",
        "catalog_remote_rating_coverage",
    ),
    listening.invalidationTracker.createFlow(
        "listening_event",
        "recording_rating_current",
    ),
).debounce(200L).map { Unit }

/**
 * Separate definition DB: an edited referenced playlist must cause a currently
 * open dependent preview to resolve its DAG again. No saved list is cached.
 */
@OptIn(FlowPreview::class)
internal fun smartPlaylistReferenceDefinitionChanges(
    database: SmartPlaylistDefinitionDatabase,
): Flow<Unit> = database.invalidationTracker
    .createFlow("smart_playlist_v2_definition")
    .debounce(200L)
    .map { Unit }
