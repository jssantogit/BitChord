package com.music.bitchord.ui.components

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.androidCanonicalQueueResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The queue receives playable songs without reliable albumName even when the
 * canonical release-track graph already contains the exact Release title.
 * Never recover album names by artist/title matching or by choosing a random
 * edition of a Recording.
 */
internal fun sharedVerifiedReleaseTitle(
    recordingIds: List<String>,
    releaseRows: List<Triple<String, String, String>>,
    preferredReleaseId: String? = null,
): String? {
    if (recordingIds.isEmpty() || recordingIds.any(String::isBlank)) return null
    val byRecording = releaseRows.groupBy { it.first }
    val commonIds = recordingIds.distinct().map { recordingId ->
        byRecording[recordingId].orEmpty().map { it.second }.toSet()
    }.reduceOrNull { a, b -> a.intersect(b) }.orEmpty()
    if (commonIds.isEmpty()) return null
    val chosen = preferredReleaseId?.takeIf { it in commonIds }
    if (chosen != null) return releaseRows.firstOrNull { it.second == chosen }?.third?.takeIf(String::isNotBlank)
    val titles = releaseRows.asSequence()
        .filter { it.second in commonIds }
        .map { it.third.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    // Distinct region/reissue releases with the same verified name are safe
    // for a display title; differing titles must remain unknown.
    return titles.singleOrNull()
}

private suspend fun verifiedRecordings(context: Context, songs: List<Song>): List<String>? {
    val resolver = androidCanonicalQueueResolver(context)
    val identities = resolver.identifyAll(songs)
    if (identities.size != songs.size || identities.any { it == null }) return null
    val assets = resolver.resolveAll(identities)
    if (assets.size != identities.size) return null
    if (identities.indices.any { i -> assets[i] == null || assets[i]?.identity != identities[i] }) return null
    return identities.map { it!!.recordingId }
}

internal suspend fun verifiedPlayerAlbumTitle(context: Context, song: Song): String? =
    withContext(Dispatchers.IO) {
        song.albumName?.trim()?.takeIf(String::isNotBlank)?.let { return@withContext it }
        val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
        song.albumId?.let { releaseId ->
            dao.releaseById(releaseId)?.title?.trim()?.takeIf(String::isNotBlank)?.let {
                return@withContext it
            }
        }
        val ids = try { verifiedRecordings(context, listOf(song)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (ids == null) return@withContext null
        val rows = dao.recordingReleaseTitlesByIds(ids)
            .map { Triple(it.recordingId, it.releaseId, it.releaseTitle) }
        sharedVerifiedReleaseTitle(ids, rows, song.albumId)
    }

/** Only active, context-tier tracks may supply album provenance. AutoPlay is
 * never relabeled as an album just because one recommendation is on a release. */
internal suspend fun verifiedQueueAlbumTitle(
    context: Context,
    queue: List<Song>,
    currentIndex: Int,
): String? = withContext(Dispatchers.IO) {
    if (currentIndex !in queue.indices) return@withContext null
    val upcoming = queue.drop(currentIndex + 1).filter { it.queueTier == QueueTier.CONTEXT }
    if (upcoming.isEmpty()) return@withContext null
    val current = queue[currentIndex]
    val selected = (if (current.queueTier == QueueTier.CONTEXT) listOf(current) else emptyList()) +
        upcoming
    val distinctSourceIds = selected.mapNotNull { it.playbackSourceId?.takeIf(String::isNotBlank) }.distinct()
    val distinctAlbumIds = selected.mapNotNull { it.albumId?.takeIf(String::isNotBlank) }.distinct()
    val preferredId = (distinctSourceIds.singleOrNull() ?: distinctAlbumIds.singleOrNull())
    val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
    // A proven context ReleaseId is strongest: don't guess based on a title.
    preferredId?.let {
        dao.releaseById(it)?.title?.trim()?.takeIf(String::isNotBlank)?.let { title ->
            return@withContext title
        }
    }
    val ids = try { verifiedRecordings(context, selected) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    if (ids == null) return@withContext null
    val rows = dao.recordingReleaseTitlesByIds(ids)
        .map { Triple(it.recordingId, it.releaseId, it.releaseTitle) }
    sharedVerifiedReleaseTitle(ids, rows, preferredId)
}
