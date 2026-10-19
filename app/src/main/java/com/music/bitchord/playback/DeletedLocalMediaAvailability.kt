package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.FileNotFoundException
import java.net.URI

/**
 * Only *definitive* local deletion is eligible for pruning LastPlayed.
 * Access denied, an unmounted volume, or an unsupported URI is unknown,
 * not evidence that the user removed the file.
 */
internal object DeletedLocalMediaAvailability {
    fun definitelyMissing(context: Context, song: Song): Boolean {
        val locator = song.localUri
            ?: song.videoId.takeIf { it.startsWith("content://media/") || it.startsWith("file://") }
        return when {
            locator?.startsWith("content://media/") == true ->
                missingMediaStoreRow(context, locator)
            locator?.startsWith("file://") == true ->
                missingReadableFileUri(locator)
            locator == null && !song.localPath.isNullOrBlank() ->
                missingReadableFile(File(requireNotNull(song.localPath)))
            else -> false // Canonical remote, SMB, HTTP, Downloads or unknown provider.
        }
    }

    private fun missingMediaStoreRow(context: Context, locator: String): Boolean {
        if (!isMediaStoreAudioItemUri(locator)) return false
        return try {
            // Query the exact identity from the MediaStore adapter, not a full
            // library scan and never by display name or album. No deletion when
            // the provider cannot supply a cursor (unknown availability).
            context.contentResolver.query(
                Uri.parse(locator),
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { !it.moveToFirst() } ?: false
        } catch (_: FileNotFoundException) {
            true
        } catch (_: SecurityException) {
            false // Permissions may be temporarily missing.
        } catch (_: Exception) {
            false // Provider unavailable: do not mistake it for deletion.
        }
    }

    private fun missingReadableFileUri(locator: String): Boolean {
        val file = runCatching { File(URI(locator)) }.getOrNull() ?: return false
        return missingReadableFile(file)
    }

    private fun missingReadableFile(file: File): Boolean = try {
        // The parent must still be readable; otherwise loss of a storage mount
        // or temporary access cannot be distinguished from deletion.
        file.parentFile?.canRead() == true && !file.isFile
    } catch (_: SecurityException) {
        false
    }

    /** Only exact MediaStore audio-row URIs are authoritative deletion probes. */
    internal fun isMediaStoreAudioItemUri(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "content" || uri.host != "media" ||
            uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null
        ) return false
        val parts = uri.path.orEmpty().trim('/').split('/')
        return parts.size == 4 &&
            parts[0].isNotBlank() && parts[1] == "audio" && parts[2] == "media" &&
            parts[3].toLongOrNull()?.let { it >= 0L } == true
    }
}
