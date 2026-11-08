package com.music.bitchord.data.library

import android.content.Context
import com.music.bitchord.data.model.Song
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent first-party playback Favorites, distinct from native 1–5 ratings
 * and retired-provider thumb reactions. Only hashes of exact media identities
 * are persisted; URLs, credentials, titles and private paths never are.
 */
internal fun nativeTrackFavoriteKey(song: Song): String? {
    val raw = song.localUri?.takeIf(String::isNotBlank)?.let { "local:$it" }
        ?: song.videoId.takeIf(String::isNotBlank)?.let { "media:$it" }
        ?: return null
    val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val n = byte.toInt() and 0xff
            append(alphabet[n ushr 4])
            append(alphabet[n and 15])
        }
    }
}

internal class NativeTrackFavorites(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("native_track_favorites_v1", Context.MODE_PRIVATE)
    private val name = "favorite_hashes"
    private val _keys = MutableStateFlow(prefs.getStringSet(name, emptySet()).orEmpty().toSet())
    val favoriteKeys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun toggle(song: Song): Boolean {
        val id = nativeTrackFavoriteKey(song) ?: return false
        synchronized(this) {
            val updated = _keys.value.toMutableSet()
            val selected = if (updated.remove(id)) false else { updated.add(id); true }
            prefs.edit().putStringSet(name, updated.toSet()).apply()
            _keys.value = updated.toSet()
            return selected
        }
    }
}
