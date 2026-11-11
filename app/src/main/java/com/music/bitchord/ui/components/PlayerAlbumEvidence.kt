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

/** An album is navigable only when the actual recording-release graph
 * identifies a unique edition, or the exact preferred ID is among its links.
 * The title can be shared by editions, but it must never choose the route.
 */
internal fun sharedVerifiedReleaseId(
    recordingIds: List<String>,
    releaseRows: List<Triple<String, String, String>>,
    preferredReleaseId: String? = null,
): String? {
    if (recordingIds.isEmpty() || recordingIds.any(String::isBlank)) return null
    val byRecording = releaseRows.groupBy { it.first }
    val common = recordingIds.distinct().map { id ->
        byRecording[id].orEmpty().map { it.second }.filter(String::isNotBlank).toSet()
    }.reduceOrNull { a, b -> a.intersect(b) }.orEmpty()
    return preferredReleaseId?.takeIf { it in common } ?: common.singleOrNull()
}

internal suspend fun verifiedPlayerAlbumReleaseId(context: Context, song: Song): String? =
    withContext(Dispatchers.IO) {
        val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
        // The presence of a provider's albumId alone is not MusicBrainz evidence.
        song.albumId?.takeIf(String::isNotBlank)?.let { id ->
            if (dao.releaseById(id) != null) return@withContext id
        }
        val ids = try { verifiedRecordings(context, listOf(song)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            ?: return@withContext null
        val rows = dao.recordingReleaseTitlesByIds(ids)
            .map { Triple(it.recordingId, it.releaseId, it.releaseTitle) }
        sharedVerifiedReleaseId(ids, rows, song.albumId)
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

/**
 * Metadata displayed in Now Playing can be more complete than the service's
 * lightweight queue snapshot. Only enrich the exact matching queue entry;
 * keep its immutable queue identity and queue tier intact.
 */
internal fun effectiveQueueAlbumSeed(queued: Song, displayed: Song): Song {
    if (queued.videoId != displayed.videoId ||
        !queued.title.equals(displayed.title, ignoreCase = true) ||
        !queued.artist.equals(displayed.artist, ignoreCase = true)
    ) return queued
    return queued.copy(
        albumName = queued.albumName ?: displayed.albumName,
        albumId = queued.albumId ?: displayed.albumId,
        playbackSource = queued.playbackSource ?: displayed.playbackSource,
        playbackSourceId = queued.playbackSourceId ?: displayed.playbackSourceId,
    )
}

/**
 * A queue context may contain stripped-down track rows even when the playing
 * track's exact album has already been resolved for the ⋯ action sheet.
 * Use that same evidence only when every next track is from the original
 * context and agrees with the cover/artist or an explicit release identity.
 * This is never applied to the user queue or auto-generated recommendations.
 */
internal fun contextualAlbumFromCurrent(
    current: Song,
    upcoming: List<Song>,
    verifiedCurrentAlbum: String?,
): String? {
    val album = verifiedCurrentAlbum?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (current.queueTier != QueueTier.CONTEXT || upcoming.isEmpty() ||
        upcoming.any { it.queueTier != QueueTier.CONTEXT }
    ) return null
    val currentAlbumId = current.albumId?.takeIf(String::isNotBlank)
    val currentArt = current.thumbnailUrl?.takeIf(String::isNotBlank)
    if (upcoming.any { next ->
            val nextAlbumId = next.albumId?.takeIf(String::isNotBlank)
            val explicitMatch = currentAlbumId != null && nextAlbumId == currentAlbumId
            val artworkMatch = currentArt != null && currentArt == next.thumbnailUrl &&
                current.artist.equals(next.artist, ignoreCase = true)
            (nextAlbumId != null && nextAlbumId != currentAlbumId) ||
                (next.albumName?.isNotBlank() == true &&
                    !next.albumName.equals(album, ignoreCase = true)) ||
                (!explicitMatch && !artworkMatch)
        }
    ) return null
    return album
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
    // The same playing song may already have a verified album in its menu.
    // Retain that provenance for matching context rows whose RecordingId lookup
    // is still unavailable, before attempting to verify the entire queue.
    val currentAlbum = verifiedPlayerAlbumTitle(context, current)
    contextualAlbumFromCurrent(current, upcoming, currentAlbum)?.let { return@withContext it }
    val ids = try { verifiedRecordings(context, selected) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    if (ids == null) return@withContext null
    val rows = dao.recordingReleaseTitlesByIds(ids)
        .map { Triple(it.recordingId, it.releaseId, it.releaseTitle) }
    sharedVerifiedReleaseTitle(ids, rows, preferredId)
}
