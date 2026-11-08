package com.music.bitchord.data.catalog.metadata.discogs

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Short-lived artist-scoped discoveries from a completed MusicBrainz browse.
 * These entries are Discogs metadata links, NOT canonical MBIDs, stream URLs
 * or persisted releases. No credentials or user queries are retained.
 */
internal class DiscogsSourceOnlyShelfStore {
    internal data class Snapshot(
        val releases: List<DiscogsShadowRelease>,
        val expiresAtMillis: Long,
    )
    private val mutable = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, Snapshot>> = mutable.asStateFlow()
    private val artistId =
        Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

    fun update(
        artistMbid: String,
        releases: List<DiscogsShadowRelease>,
        nowMillis: Long,
    ) {
        val key = artistMbid.trim().lowercase(Locale.ROOT).takeIf(artistId::matches) ?: return
        if (nowMillis < 0L) return
        val safe = releases.distinctBy { it.discogsReleaseId }.take(12).filter { release ->
            release.discogsReleaseId in 1..999_999_999_999L &&
                release.musicBrainzId == null && !release.canPlay &&
                release.attributionUrl ==
                    "https://www.discogs.com/release/${release.discogsReleaseId}"
        }
        mutable.update { current ->
            val live = current.filterValues { it.expiresAtMillis > nowMillis }
                .toMutableMap()
            if (safe.isEmpty()) live.remove(key)
            else live[key] = Snapshot(safe, nowMillis + DiscogsObservation.TTL_MILLIS)
            while (live.size > 12) live.remove(live.keys.first())
            live
        }
    }

    fun active(nowMillis: Long): List<DiscogsShadowRelease> =
        mutable.value.values.asSequence().filter { nowMillis < it.expiresAtMillis }
            .flatMap { it.releases.asSequence() }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<DiscogsShadowRelease> { it.year }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.discogsReleaseId })
            .take(24).toList()

    fun openUrl(browseId: String?, nowMillis: Long): String? {
        val prefix = "discogs:release:"
        if (browseId?.startsWith(prefix) != true) return null
        val releaseId = browseId.removePrefix(prefix).toLongOrNull()
            ?.takeIf { it in 1..999_999_999_999L } ?: return null
        return active(nowMillis).firstOrNull { it.discogsReleaseId == releaseId }
            ?.attributionUrl
    }

    fun nextExpiryMillis(nowMillis: Long): Long? =
        mutable.value.values.asSequence().map { it.expiresAtMillis }
            .filter { it > nowMillis }.minOrNull()

    fun prune(nowMillis: Long) {
        mutable.update { current ->
            current.filterValues { it.expiresAtMillis > nowMillis }
        }
    }

    fun clear() {
        mutable.value = emptyMap()
    }
}

/** Volatile catalog discoveries, cleared on provider rollback or app restart. */
internal object DiscogsSourceOnlyDiscovery {
    val store = DiscogsSourceOnlyShelfStore()
}
