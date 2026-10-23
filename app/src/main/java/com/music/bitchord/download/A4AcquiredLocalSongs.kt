package com.music.bitchord.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Acquisition-owned reader contributing to the EXISTING Local Music scan.
 *
 * It adds only already-published local content URIs that the scan did not
 * include (especially user-chosen SAF folders). A URI remains a LOCAL
 * MediaAsset candidate via CanonicalLocalLibrary.sync; the peer's title or
 * filename must NEVER be used to link a Navidrome/MusicBrainz recording.
 *
 * No new tab, special player, file copy, or remote source is created.
 */
internal object A4AcquiredLocalSongs {
    suspend fun append(context:Context,scanned:List<Song>):List<Song> =
        withContext(Dispatchers.IO) {
            val resolver=context.contentResolver
            val entries=runCatching {
                A3AndroidAcquisitionLedger.get(context).all()
            }.getOrElse { return@withContext scanned }
            val already=scanned.mapNotNull(Song::localUri).toSet()
            val uris=A4AvailableLocalUriSelection.choose(entries,already) { raw ->
                runCatching {
                    resolver.openFileDescriptor(Uri.parse(raw),"r")?.use { descriptor ->
                        descriptor.statSize!=0L
                    } == true
                }.getOrDefault(false)
            }
            if(uris.isEmpty())return@withContext scanned
            scanned + uris.mapNotNull { uriString ->
                val uri=Uri.parse(uriString)
                // Treat transcoder/provider failures as transient; never erase
                // durable A3 AVAILABLE entries because of an unreadable URI.
                runCatching {
                    val mime=resolver.getType(uri)
                    // SAF providers sometimes report a generic byte stream
                    // even when the file is valid audio. Trust the actual
                    // MediaMetadataRetriever decode/duration check below.
                    if(mime!=null && !mime.startsWith("audio/") &&
                        mime!="application/octet-stream")return@runCatching null
                    val display=runCatching {
                        resolver.query(
                            uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null
                        )?.use { cursor ->
                            if(cursor.moveToFirst())cursor.getString(0) else null
                        }
                    }.getOrNull()?.takeIf(String::isNotBlank)
                    val retriever=MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context,uri)
                        val duration=retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull()?.takeIf { it>0 }
                            ?:return@runCatching null
                        val title=retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_TITLE
                        )?.takeIf(String::isNotBlank)
                            ?:display?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
                            ?:"Local audio"
                        val artist=retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_ARTIST
                        )?.takeIf { it.isNotBlank() && it!="<unknown>" }
                            ?:"Unknown Artist"
                        val album=retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_ALBUM
                        )?.takeIf { it.isNotBlank() && it!="<unknown>" }
                        Song(
                            videoId=uriString,
                            title=title,
                            artist=artist,
                            thumbnailUrl=null,
                            fromAutoplay=false,
                            durationText="${duration/60000}:${((duration/1000)%60).toString().padStart(2,'0')}",
                            albumName=album,
                            localUri=uriString,
                            localPath=null
                        )
                    }finally{
                        retriever.release()
                    }
                }.getOrNull()
            }
        }
}
