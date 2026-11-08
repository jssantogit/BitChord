package com.music.bitchord.data.catalog.metadata.discogs

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraph
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val OFFICIAL_MBID =
    Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

/** Only actual Official editions from a scoped MusicBrainz group browse. */
internal fun discogsCorroborationEditionIds(
    selectedReleaseMbid: String,
    groupReleases: List<MusicBrainzReleaseHit>,
): List<String> {
    val selected = selectedReleaseMbid.lowercase(Locale.ROOT)
    if (!OFFICIAL_MBID.matches(selected)) return emptyList()
    return groupReleases.asSequence()
        .filter { it.status.equals("Official", ignoreCase = true) }
        .map { it.musicBrainzId.lowercase(Locale.ROOT) }
        .filter { OFFICIAL_MBID.matches(it) && it != selected }
        .distinct()
        .take(2).toList()
}

/**
 * A user opening an exact MusicBrainz ReleaseGroup can trigger at most one
 * short, source-scoped corroboration pass every 30 minutes per group.
 * It runs off the Compose render path, honors Discogs request budgets and
 * never changes any canonical release, local tags or playback identity.
 */
internal object DiscogsGroupCorroborator {
    private val mutex = Mutex()
    private val checked = linkedMapOf<String, Long>()
    private val graph = MusicBrainzCatalogGraph()

    suspend fun inspect(groupMbid: String?, selectedReleaseMbid: String?) {
        val group = groupMbid?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(OFFICIAL_MBID::matches) ?: return
        val selected = selectedReleaseMbid?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(OFFICIAL_MBID::matches) ?: return
        if (!DiscogsEditionAccess.ENABLED) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if ((checked[group] ?: 0L) > now - 30 * 60_000L) return@withLock
            val result = try {
                graph.browseGroupReleases(group, limit = 12)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock
            }
            val hits = (result as? MusicBrainzCatalogGraphResult.Success)?.value?.items
                ?: return@withLock
            val additional = discogsCorroborationEditionIds(selected, hits)
            checked[group] = now
            while (checked.size > 64) checked.remove(checked.keys.first())
            for (editionMbid in additional) {
                // Every result is independently checked for complete tracklist,
                // official status, group membership and verified Discogs release.
                DiscogsEditionAccess.coordinator.observeRelease(editionMbid)
            }
        }
    }

    suspend fun clear() = mutex.withLock { checked.clear() }
}
