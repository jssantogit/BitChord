package com.music.bitchord.ui.screens

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlists have their own persisted identity namespace; never disguise a
 * playlist as a RecordingId/ReleaseId/ArtistId in canonical music pins.
 */
internal data class LibraryPlaylistPin(
    val id: String,
    val title: String,
)

internal fun validLibraryPlaylistPin(pin: LibraryPlaylistPin): Boolean =
    (pin.id.startsWith("normal:") || pin.id.startsWith("v2:") ||
        pin.id.startsWith("local:local:playlist:")) &&
        pin.id.substringAfter(':').isNotBlank() &&
        pin.id.length <= 512 && pin.id == pin.id.trim() &&
        pin.title.isNotBlank() && pin.title.length <= 240

internal fun toggleLibraryPlaylistPin(
    current: List<LibraryPlaylistPin>,
    target: LibraryPlaylistPin,
): List<LibraryPlaylistPin> {
    if (!validLibraryPlaylistPin(target)) return current
    val distinct = current.distinctBy(LibraryPlaylistPin::id)
    return if (distinct.any { it.id == target.id }) distinct.filterNot { it.id == target.id }
    else if (distinct.size >= 100) distinct else distinct + target
}

/** A title changes presentation only; a playlist pin never receives a new ID. */
internal fun renameLibraryPlaylistPin(
    current: List<LibraryPlaylistPin>,
    id: String,
    title: String,
): List<LibraryPlaylistPin> {
    val clean = title.trim()
    if (clean.isBlank() || clean.length > 240) return current
    return current.map { existing ->
        if (existing.id == id) existing.copy(title = clean) else existing
    }
}

internal fun encodeLibraryPlaylistPins(pins: List<LibraryPlaylistPin>): String =
    JSONArray().apply {
        pins.asSequence().filter(::validLibraryPlaylistPin)
            .distinctBy(LibraryPlaylistPin::id).take(100).forEach { pin ->
                put(JSONObject().apply {
                    put("id", pin.id)
                    put("title", pin.title)
                })
            }
    }.toString()

internal fun decodeLibraryPlaylistPins(raw: String?): List<LibraryPlaylistPin> {
    val list = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    val output = ArrayList<LibraryPlaylistPin>()
    val ids = HashSet<String>()
    for (i in 0 until minOf(list.length(), 300)) {
        val value = list.optJSONObject(i) ?: continue
        val pin = LibraryPlaylistPin(value.optString("id"), value.optString("title"))
        if (validLibraryPlaylistPin(pin) && ids.add(pin.id)) output += pin
        if (output.size == 100) break
    }
    return output
}

internal class LibraryPlaylistPinRepository(private val preferences: SharedPreferences) {
    val pins = MutableStateFlow(decodeLibraryPlaylistPins(preferences.getString(KEY, null)))

    @Synchronized
    fun toggle(pin: LibraryPlaylistPin): Boolean {
        val current = pins.value
        val next = toggleLibraryPlaylistPin(current, pin)
        if (next != current && preferences.edit()
                .putString(KEY, encodeLibraryPlaylistPins(next)).commit()) pins.value = next
        return pins.value.any { it.id == pin.id }
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val next = renameLibraryPlaylistPin(pins.value, id, title)
        if (next != pins.value && preferences.edit()
                .putString(KEY, encodeLibraryPlaylistPins(next)).commit()) pins.value = next
    }

    @Synchronized
    fun remove(id: String) {
        val next = pins.value.filterNot { it.id == id }
        if (next != pins.value && preferences.edit()
                .putString(KEY, encodeLibraryPlaylistPins(next)).commit()) pins.value = next
    }

    companion object {
        private const val KEY = "pinned_library_playlists_v1"
        @Volatile private var singleton: LibraryPlaylistPinRepository? = null
        fun get(context: Context): LibraryPlaylistPinRepository =
            singleton ?: synchronized(this) {
                singleton ?: LibraryPlaylistPinRepository(
                    context.applicationContext.getSharedPreferences(
                        "bitchord_settings", Context.MODE_PRIVATE,
                    ),
                ).also { singleton = it }
            }
    }
}
