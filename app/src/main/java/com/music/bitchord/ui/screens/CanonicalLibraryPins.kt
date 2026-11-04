package com.music.bitchord.ui.screens

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-controlled pins are typed canonical IDs, not source URLs, display
 * titles or fake Song.videoIds. Titles/subtitles are a minimal offline label
 * cache; artwork and playback eligibility are always re-read from Room.
 *
 * Local per-install state intentionally shares BitChord's existing preference
 * file (and backup/export policy) without a Room schema migration.
 */
internal data class CanonicalLibraryPin(
    val selection: CanonicalLibrarySelection,
    val title: String,
    val subtitle: String = "",
)

/** Typed pin navigation: a Recording plays; a Release/Artist opens the
 * existing unified detail. No provider-owned IDs or display-name routing. */
internal sealed interface CanonicalLibraryPinOpenTarget {
    data class PlayRecording(val recordingId: String) : CanonicalLibraryPinOpenTarget
    data class OpenEntity(
        val category: com.music.bitchord.data.listening.CanonicalSmartFilterContext,
        val pin: CanonicalLibraryPin,
    ) : CanonicalLibraryPinOpenTarget
}

internal fun canonicalLibraryPinOpenTarget(
    pin: CanonicalLibraryPin,
): CanonicalLibraryPinOpenTarget = when (pin.selection.type) {
    CanonicalLibraryEntityType.TRACK ->
        CanonicalLibraryPinOpenTarget.PlayRecording(pin.selection.entityId)
    CanonicalLibraryEntityType.ALBUM -> CanonicalLibraryPinOpenTarget.OpenEntity(
        com.music.bitchord.data.listening.CanonicalSmartFilterContext.ALBUMS, pin,
    )
    CanonicalLibraryEntityType.ARTIST -> CanonicalLibraryPinOpenTarget.OpenEntity(
        com.music.bitchord.data.listening.CanonicalSmartFilterContext.ARTISTS, pin,
    )
}

/** Availability is not implied by an Artist row surviving an account removal.
 * A display-artist group is only valid if recordings with that exact display
 * name still have a MediaAsset in an active source. */
internal fun canonicalPinnedArtistAccessible(
    artistId: String,
    verifiedScopedArtistIds: Set<String>,
    scopedDisplayNames: Set<String>,
): Boolean {
    val display = playlistDisplayArtistNameFromGroupId(artistId)
    return if (display != null) display in scopedDisplayNames
    else artistId in verifiedScopedArtistIds
}

internal const val MAX_CANONICAL_LIBRARY_PINS = 100

internal fun validCanonicalLibraryPin(pin: CanonicalLibraryPin): Boolean =
    pin.selection.entityId.isNotBlank() &&
        pin.selection.entityId == pin.selection.entityId.trim() &&
        pin.selection.entityId.length <= 512 &&
        pin.title.isNotBlank() && pin.title.length <= 240 &&
        pin.subtitle.length <= 240

internal fun toggleCanonicalLibraryPin(
    pins: List<CanonicalLibraryPin>,
    target: CanonicalLibraryPin,
): List<CanonicalLibraryPin> {
    if (!validCanonicalLibraryPin(target)) return pins
    val current = pins.distinctBy { it.selection }
    if (current.any { it.selection == target.selection }) {
        return current.filterNot { it.selection == target.selection }
    }
    return if (current.size >= MAX_CANONICAL_LIBRARY_PINS) current else current + target
}

internal fun encodeCanonicalLibraryPins(pins: List<CanonicalLibraryPin>): String =
    JSONArray().apply {
        pins.asSequence().filter(::validCanonicalLibraryPin)
            .distinctBy { it.selection }
            .take(MAX_CANONICAL_LIBRARY_PINS)
            .forEach { pin ->
                put(JSONObject().apply {
                    put("type", pin.selection.type.name)
                    put("id", pin.selection.entityId)
                    put("title", pin.title)
                    put("subtitle", pin.subtitle)
                })
            }
    }.toString()

internal fun decodeCanonicalLibraryPins(raw: String?): List<CanonicalLibraryPin> {
    val array = try { JSONArray(raw ?: "[]") } catch (_: Exception) { return emptyList() }
    val pins = ArrayList<CanonicalLibraryPin>()
    val seen = HashSet<CanonicalLibrarySelection>()
    for (i in 0 until minOf(array.length(), MAX_CANONICAL_LIBRARY_PINS * 3)) {
        val value = array.optJSONObject(i) ?: continue
        val type = CanonicalLibraryEntityType.entries
            .firstOrNull { it.name == value.optString("type") } ?: continue
        val pin = CanonicalLibraryPin(
            CanonicalLibrarySelection(type, value.optString("id")),
            value.optString("title"),
            value.optString("subtitle"),
        )
        if (validCanonicalLibraryPin(pin) && seen.add(pin.selection)) pins.add(pin)
        if (pins.size == MAX_CANONICAL_LIBRARY_PINS) break
    }
    return pins
}

/** One source of truth for the Home of Library and all canonical action menus.
 * Synchronized updates prevent rapid taps from losing a pin or its order. */
internal class CanonicalLibraryPinRepository(
    private val preferences: SharedPreferences,
) {
    val pins = MutableStateFlow(
        decodeCanonicalLibraryPins(preferences.getString(KEY, null)),
    )

    @Synchronized
    fun toggle(pin: CanonicalLibraryPin): Boolean {
        val old = pins.value
        val updated = toggleCanonicalLibraryPin(old, pin)
        if (updated == old) return pin.selection in old.map { it.selection }
        // Commit before notifying Compose: a UI success cannot precede a failed
        // disk write, and a killed process should not lose the latest tap.
        if (!preferences.edit().putString(KEY, encodeCanonicalLibraryPins(updated)).commit()) {
            runCatching {
                Diagnostics.event(
                    category = DiagnosticCategory.UI,
                    name = "canonical_library_pin_write_failed_v1",
                    metadata = mapOf("entityType" to pin.selection.type.name),
                )
            }
            return pin.selection in old.map { it.selection }
        }
        pins.value = updated
        val pinnedNow = pin.selection in updated.map { it.selection }
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.UI,
                name = "canonical_library_pin_toggled_v1",
                metadata = mapOf(
                    "entityType" to pin.selection.type.name,
                    "pinned" to pinnedNow.toString(),
                    "count" to updated.size.toString(),
                ),
            )
        }
        return pinnedNow
    }

    companion object {
        private const val KEY = "pinned_canonical_library_v1"
        @Volatile private var singleton: CanonicalLibraryPinRepository? = null

        fun get(context: Context): CanonicalLibraryPinRepository =
            singleton ?: synchronized(this) {
                singleton ?: CanonicalLibraryPinRepository(
                    context.applicationContext.getSharedPreferences(
                        "bitchord_settings", Context.MODE_PRIVATE,
                    ),
                ).also { singleton = it }
            }
    }
}
