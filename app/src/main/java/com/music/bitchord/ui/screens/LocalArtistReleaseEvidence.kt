package com.music.bitchord.ui.screens

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Álbum recente" is shown only if a release date is linked to the same
 * recording through a trusted MusicBrainz release identity. MediaStore's
 * DATE_ADDED / DATE_MODIFIED is never mistaken for an album release date.
 */
internal suspend fun mostRecentVerifiedLocalArtistAlbum(
    context: Context,
    songs: List<Song>,
    shelves: List<HomeShelf>,
): ShelfItem? = withContext(Dispatchers.IO) {
    val available = shelves.flatMap(HomeShelf::items)
        .associateBy { it.title.trim().lowercase() }
    if (available.isEmpty()) return@withContext null
    val sourceKeys = songs.mapNotNull(Song::localUri).distinct()
    if (sourceKeys.isEmpty()) return@withContext null

    val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()
    val assets = sourceKeys.chunked(300).flatMap {
        dao.localAssetsBySourceKeys(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER, it)
    }
    val songByUri = songs.filter { it.localUri != null }.associateBy { it.localUri }
    val albumByRecording = assets.mapNotNull { asset ->
        val album = songByUri[asset.sourceKey]?.albumName
            ?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        asset.recordingId to album
    }.toMap()
    if (albumByRecording.isEmpty()) return@withContext null
    val ids = albumByRecording.keys.toList()
    val titles = ids.chunked(300).flatMap { dao.recordingReleaseTitlesByIds(it) }
    val dates = ids.chunked(300).flatMap { dao.recordingReleaseDatesByIds(it) }
        .mapNotNull { date ->
            // Full ISO day required to compare chronology without guessing.
            val day = date.releaseDate?.takeIf {
                it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            } ?: return@mapNotNull null
            Triple(date.recordingId, date.releaseId, day)
        }
    val releases = titles.associateBy { it.recordingId to it.releaseId }
    val newest = dates.mapNotNull { (recordingId, releaseId, day) ->
        val title = releases[recordingId to releaseId]?.releaseTitle
            ?: return@mapNotNull null
        val local = albumByRecording[recordingId] ?: return@mapNotNull null
        if (!title.equals(local, ignoreCase = true)) return@mapNotNull null
        val card = available[local.lowercase()] ?: return@mapNotNull null
        day to card
    }.maxByOrNull { it.first } ?: return@withContext null
    newest.second.copy(subtitle = "Álbum · ${newest.first.take(4)}")
}
