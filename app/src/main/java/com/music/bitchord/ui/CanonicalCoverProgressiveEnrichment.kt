package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * A MusicBrainz Recording search hit already carries a direct Official
 * audio Release -> ReleaseGroup association. Check that group's Front-v5
 * *before* traversing Room or contacting CAA. If it is unavailable, one
 * batched indexed Room query may supply an independently proven Track edge.
 *
 * Several tracks of the same album share ONE cache read and exact URL.
 * Neither common track titles nor common artists are identity evidence.
 * Caller supplies the CoverArtArchive Front-v5 validator as cachedCover.
 */
internal suspend fun canonicalCachedRecordingArtForVisibleRows(
    rows: List<com.music.bitchord.ui.screens.CanonicalCatalogSearchRow>,
    databaseLinks: suspend (List<String>) ->
        List<com.music.bitchord.data.catalog.CanonicalRecordingCoverGroupRow>,
    cachedCover: suspend (String) -> String?,
    /** Optional Room batch: one SQL read for many verified Front-v5 groups. */
    cachedCovers: (suspend (List<String>) -> Map<String, String>)? = null,
): Map<String, String> {
    val recordings = rows.filter {
        it.kind == com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING
    }.distinctBy { it.musicBrainzId }
    if (recordings.isEmpty()) return emptyMap()
    val proven = linkedMapOf<String, String>()
    val groupCache = mutableMapOf<String, String?>()

    suspend fun loadGroups(groups: List<String>) {
        val pending = groups.filter(String::isNotBlank).distinct()
            .filterNot(groupCache::containsKey)
        if (pending.isEmpty()) return
        // The set of requested MBIDs is the identity boundary. A corrupt or
        // stale batch must never associate an unexpected group with a song.
        val fetched = if (cachedCovers != null) try {
            cachedCovers(pending)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Batch storage can be temporarily unavailable. Fall back to
            // individual verified cache reads without losing prior covers.
            null
        } else null
        if (fetched != null) {
            pending.forEach { groupCache[it] = fetched[it] }
        } else {
            for (id in pending) {
                groupCache[id] = try {
                    cachedCover(id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    fun adopt(recordingMbid: String, groupMbid: String) {
        if (recordingMbid in proven) return
        groupCache[groupMbid]?.let { proven[recordingMbid] = it }
    }
    // Direct Official Recording -> ReleaseGroup proof wins over a compilation
    // found later via cached Room track links.
    loadGroups(recordings.mapNotNull { it.coverGroupMbid })
    recordings.forEach { row ->
        row.coverGroupMbid?.let { adopt(row.musicBrainzId, it) }
    }
    val missing = recordings.map { it.musicBrainzId }.filterNot(proven::containsKey)
    if (missing.isNotEmpty()) {
        val eligible = missing.toSet()
        val edges = try {
            databaseLinks(missing).filter { it.recordingMbid in eligible }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The optional offline graph never destroys proven direct art.
            emptyList()
        }
        // A second batched lookup handles only the groups that the direct
        // upstream evidence couldn't resolve. Many tracks share one URL.
        loadGroups(edges.map { it.groupMbid })
        edges.forEach { adopt(it.recordingMbid, it.groupMbid) }
    }
    return proven
}


/**
 * Cache hydration is repeatable across search → artist → album → search.
 * Only a proven Recording MBID may receive a previously verified album sleeve;
 * unrelated release rows and track-owned artwork must never be overwritten.
 */
internal fun canonicalRowsWithVerifiedRecordingArt(
    rows: List<com.music.bitchord.ui.screens.CanonicalCatalogSearchRow>,
    proven: Map<String, String>,
): List<com.music.bitchord.ui.screens.CanonicalCatalogSearchRow> =
    rows.map { row ->
        val cover = proven[row.musicBrainzId]
        if (row.kind == com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING &&
            row.artworkUri.isNullOrBlank() && cover != null
        ) row.copy(artworkUri = cover) else row
    }


/**
 * When an Official ReleaseGroup cover arrives after search results, publish it
 * to recordings which have the same direct upstream group MBID. This costs no
 * new Room/CAA lookup and never equates works by their title or artist.
 * Room graph-based links remain a separate, stronger revisit fallback.
 */
internal fun canonicalRowsWithNewVerifiedGroupArt(
    rows: List<com.music.bitchord.ui.screens.CanonicalCatalogSearchRow>,
    groupMbid: String,
    artwork: String,
): List<com.music.bitchord.ui.screens.CanonicalCatalogSearchRow> {
    if (groupMbid.isBlank() || artwork.isBlank()) return rows
    val direct = rows.asSequence().filter {
        it.kind == com.music.bitchord.ui.screens.CanonicalSearchKind.RECORDING &&
            it.coverGroupMbid == groupMbid
    }.associate { it.musicBrainzId to artwork }
    return canonicalRowsWithVerifiedRecordingArt(rows, direct)
}

/**
 * Deliver verified covers as soon as each independent lookup finishes.
 *
 * Waiting for the entire CAA wave previously made a quick 500px cover wait
 * behind the slowest metadata request (p95 on device was ~5.8 seconds).
 * Drain already-ready completions into one immutable batch to avoid a
 * separate recomposition for responses arriving in the same scheduler turn.
 * Cancellation still cancels every child; missing art never emits a row.
 */
internal suspend fun <K, V : Any> loadCanonicalCoversProgressively(
    keys: List<K>,
    maxConcurrent: Int = 3,
    fetch: suspend (K) -> V?,
    publish: suspend (Map<K, V>) -> Unit,
) = coroutineScope {
    require(maxConcurrent > 0)
    // Long artist pages may contain hundreds of groups. Use exactly
    // maxConcurrent worker coroutines rather than spawning one suspended
    // coroutine per artwork candidate. Producers never wait for a slow wave
    // to finish before fast downstream covers can be delivered.
    val queue = Channel<K>(Channel.UNLIMITED)
    val completions = Channel<Pair<K, V>>(Channel.UNLIMITED)
    keys.forEach { queue.trySend(it).getOrThrow() }
    queue.close()
    val workers = List(minOf(maxConcurrent, keys.size)) {
        launch {
            for (key in queue) {
                fetch(key)?.let { completions.send(key to it) }
            }
        }
    }
    launch {
        workers.joinAll()
        completions.close()
    }
    for ((key, value) in completions) {
        val batch = linkedMapOf(key to value)
        while (true) {
            val next = completions.tryReceive().getOrNull() ?: break
            batch[next.first] = next.second
        }
        publish(batch)
    }
}

/**
 * The first covers are already visible. Later MusicBrainz artist edition
 * pages are complementary evidence, not a barrier before the second image
 * wave. Fetch them as a sibling of the bounded cover worker pool, and await
 * both before the caller inspects newly discovered group IDs for a retry.
 * Edition-page failures are opportunistic; cancellation is never swallowed.
 */
internal suspend fun <K, V : Any> loadCanonicalCoversWhileExpandingEditions(
    keys: List<K>,
    maxConcurrent: Int = 3,
    expandEditions: suspend () -> Unit,
    fetch: suspend (K) -> V?,
    publish: suspend (Map<K, V>) -> Unit,
) = coroutineScope {
    val expansion = launch {
        try {
            expandEditions()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The existing edition evidence and cached art remain valid.
        }
    }
    loadCanonicalCoversProgressively(keys, maxConcurrent, fetch, publish)
    expansion.join()
}

/**
 * Long discographies can contain hundreds of Official releases. Fetching an
 * entire second/third 100-edition artist page costs one MusicBrainz request
 * per page, versus one rate-limited /release?release-group= request per
 * uncovered album. Bounded pages retain a real ReleaseGroup MBID association
 * and do not invent title links or prune cached editions on failure.
 *
 * The caller invokes this after painting the first initial cover wave, so
 * large discographies cannot delay the first meaningful artwork.
 */
internal suspend fun collectCanonicalArtistOfficialEditionPages(
    firstPage: MusicBrainzCatalogPage<MusicBrainzReleaseHit>,
    maxAdditionalPages: Int = 3,
    fetch: suspend (Int) -> MusicBrainzCatalogGraphResult<MusicBrainzCatalogPage<MusicBrainzReleaseHit>>,
    onPage: suspend (Map<String, List<MusicBrainzReleaseHit>>) -> Unit,
): Map<String, List<MusicBrainzReleaseHit>> {
    val indexed = linkedMapOf<String, LinkedHashMap<String, MusicBrainzReleaseHit>>()
    fun index(releases: List<MusicBrainzReleaseHit>) {
        releases.filter {
            isCuratedMusicBrainzRelease(it) && !it.releaseGroupMbid.isNullOrBlank()
        }.forEach { edition ->
            indexed.getOrPut(requireNotNull(edition.releaseGroupMbid)) {
                linkedMapOf()
            }[edition.musicBrainzId] = edition
        }
    }
    fun snapshot(): Map<String, List<MusicBrainzReleaseHit>> =
        indexed.mapValues { it.value.values.toList() }

    index(firstPage.items)
    var next = firstPage.nextOffset
    val visited = mutableSetOf<Int>()
    repeat(maxAdditionalPages.coerceIn(0, 4)) {
        val offset = next ?: return snapshot()
        if (!visited.add(offset)) return snapshot()
        val page = when (val answer = fetch(offset)) {
            is MusicBrainzCatalogGraphResult.Success -> answer.value
            is MusicBrainzCatalogGraphResult.Failure -> return snapshot()
        }
        if (page.offset != offset || page.total != firstPage.total ||
            page.consumedCount <= 0
        ) return snapshot()
        index(page.items)
        next = page.nextOffset
        onPage(snapshot())
    }
    return snapshot()
}
