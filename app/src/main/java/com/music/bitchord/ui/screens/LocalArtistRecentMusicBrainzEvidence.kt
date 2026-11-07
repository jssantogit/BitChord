package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.music.bitchord.data.catalog.ReleaseEntity
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearch
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Non-persistent editorial fallback for undated local artist releases.
 *
 * A MusicBrainz name search is not a canonical identity association. Only one
 * exact artist-name match may scope further release-group searches, and each
 * local title must match exactly one dated, typed group for that artist.
 * Never write inferred MBIDs to Room, manufacture a local MediaAsset or use
 * file insertion timestamps as release chronology.
 */
private object LocalArtistRecentMusicBrainzLookup {
    private val search = MusicBrainzCatalogSearch()
    private val lock = Mutex()
    private data class Cached(
        val storedAt: Long,
        val evidence: Map<String, CanonicalArtistReleaseEvidence>,
    )
    private val cache = linkedMapOf<String, Cached>()

    suspend fun query(
        artistName: String,
        local: List<ReleaseEntity>,
    ): Map<String, CanonicalArtistReleaseEvidence> = lock.withLock {
        val name = artistName.trim()
        if (name.length !in 2..100 || name.any(Char::isISOControl) ||
            local.isEmpty() || local.size > 200
        ) return@withLock emptyMap()
        val key = name.lowercase(Locale.ROOT) + "|" +
            local.map { it.title.trim().lowercase(Locale.ROOT) }.sorted().joinToString("|")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf {
            now >= it.storedAt && now - it.storedAt < if (it.evidence.isEmpty())
                5L * 60L * 1_000L else 6L * 60L * 60L * 1_000L
        }?.let { return@withLock it.evidence }

        val artistPage = search.searchArtists(name, limit = 20)
        val artists = (artistPage as? MusicBrainzCatalogSearchResult.Success)?.page
            ?.items.orEmpty()
        val exact = artists.filter { it.name.trim().equals(name, ignoreCase = true) }
        if (exact.size != 1) {
            if (cache.size >= 48) cache.remove(cache.keys.first())
            cache[key] = Cached(now, emptyMap())
            return@withLock emptyMap()
        }

        val groups = mutableListOf<MusicBrainzReleaseGroupHit>()
        var offset = 0
        for (pageIndex in 0 until 3) {
            val result = search.searchReleaseGroupsForArtistMbid(
                exact.single().musicBrainzId, limit = 50, offset = offset,
            )
            val page = (result as? MusicBrainzCatalogSearchResult.Success)?.page
                ?: break
            groups += page.items
            // Stop when every local title has sufficient exact evidence, or
            // when MusicBrainz has no more release groups. Three pages max.
            val known = canonicalLocalArtistExactReleaseDateEvidence(
                name, local, exact, groups,
            )
            if (known.size == local.size) break
            offset = page.nextOffset ?: break
        }
        val evidence = canonicalLocalArtistExactReleaseDateEvidence(
            name, local, exact, groups,
        )
        if (cache.size >= 48) cache.remove(cache.keys.first())
        cache[key] = Cached(System.currentTimeMillis(), evidence)
        evidence
    }
}

/** A release set—not an artwork map or transient Room emission—owns the
 * expensive external MusicBrainz date request. Changing the artist, a release
 * ID or a release title starts fresh work, but cover/state refreshes do not. */
internal fun canonicalLocalArtistRecentLookupKey(
    artistName: String,
    releases: List<ReleaseEntity>,
): String {
    val artist = artistName.trim().lowercase(Locale.ROOT)
    val members = releases.distinctBy { it.id }
        .map { it.id + "\u001f" + it.title.trim().lowercase(Locale.ROOT) }
        .sorted()
    return artist + "\u001e" + members.joinToString("\u001e")
}

/** Keep the existing native Library compositor; only supply corroborated dates.
 * The remote lookup has a stable release-membership key; applying its results
 * always uses the latest native artwork/evidence snapshot. */
@Composable
internal fun rememberLocalArtistExternallyDatedReleases(
    artistName: String,
    native: CanonicalArtistNativeReleases,
): State<CanonicalArtistNativeReleases> {
    val sections = native.sections
    val local = (sections.albums + sections.singlesAndEps + sections.unclassified)
        .distinctBy { it.id }
    val lookupKey = canonicalLocalArtistRecentLookupKey(artistName, local)
    val matches by produceState(
        initialValue = emptyMap<String, CanonicalArtistReleaseEvidence>(),
        key1 = lookupKey,
    ) {
        if (local.isEmpty() || sections.recentRelease != null) return@produceState
        value = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(15_000L) {
                    LocalArtistRecentMusicBrainzLookup.query(artistName, local)
                }
            }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyMap() }
    }
    if (matches.isEmpty() || sections.recentRelease != null) {
        return rememberUpdatedState(native)
    }
    // Native state may update repeatedly as album art, source membership and
    // edition evidence hydrate. Never restore the stale state captured at
    // the beginning of a slow MusicBrainz request.
    val newEvidence = native.evidence + matches.filter { (id, _) ->
        native.evidence[id]?.firstReleaseDate.isNullOrBlank()
    }
    val counts = sections.albums.associate { it.id to 2 } +
        sections.singlesAndEps.associate { it.id to 1 }
    val updated = canonicalArtistReleaseSections(local, newEvidence, counts)
    return rememberUpdatedState(
        if (updated.recentRelease != null)
            native.copy(sections = updated, evidence = newEvidence)
        else native,
    )
}
