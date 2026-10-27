package com.music.bitchord.ui

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogGraphResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val permits = Semaphore(maxConcurrent)
    val completions = Channel<Pair<K, V>>(Channel.UNLIMITED)
    val jobs = keys.map { key ->
        launch {
            permits.withPermit {
                fetch(key)?.let { completions.send(key to it) }
            }
        }
    }
    launch {
        jobs.joinAll()
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
