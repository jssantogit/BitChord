package com.music.bitchord.ui.screens

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Distinct online/catalog-only pin identity; no fake local MediaAsset. */
internal data class CatalogMetadataPin(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
)

internal fun trustedCatalogMetadataPinArtwork(url: String?): String? {
    if (url == null || url.length > 2048 ||
        url.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return url.takeIf {
        uri.scheme == "https" && uri.userInfo == null && (
            host == "coverartarchive.org" || host == "archive.org" ||
            host.endsWith(".archive.org") ||
            host == "is1-ssl.mzstatic.com" || host.endsWith(".mzstatic.com")
        )
    }
}

internal fun validCatalogMetadataPin(pin: CatalogMetadataPin): Boolean =
    (canonicalCatalogEntityRatingTarget(pin.browseId) != null ||
        pin.browseId.removePrefix("catalog:RECORDING:")
            .takeIf { pin.browseId.startsWith("catalog:RECORDING:") }
            ?.let(::isVerifiedCatalogRecordingId) == true) &&
        pin.title.isNotBlank() && pin.title == pin.title.trim() &&
        pin.title.length <= 240 && pin.subtitle.length <= 240 &&
        (pin.artworkUrl == null || trustedCatalogMetadataPinArtwork(pin.artworkUrl) != null)

internal fun toggleCatalogMetadataPin(
    old: List<CatalogMetadataPin>, target: CatalogMetadataPin,
): List<CatalogMetadataPin> {
    if (!validCatalogMetadataPin(target)) return old
    val current = old.distinctBy(CatalogMetadataPin::browseId)
    return if (current.any { it.browseId == target.browseId })
        current.filterNot { it.browseId == target.browseId }
    else if (current.size >= 100) current else current + target
}

internal fun encodeCatalogMetadataPins(pins: List<CatalogMetadataPin>): String =
    JSONArray().apply {
        pins.asSequence().filter(::validCatalogMetadataPin)
            .distinctBy(CatalogMetadataPin::browseId).take(100).forEach { pin ->
                put(JSONObject().apply {
                    put("id", pin.browseId)
                    put("title", pin.title)
                    put("subtitle", pin.subtitle)
                    pin.artworkUrl?.let { put("art", it) }
                })
            }
    }.toString()

internal fun decodeCatalogMetadataPins(raw: String?): List<CatalogMetadataPin> {
    val array = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    val result = ArrayList<CatalogMetadataPin>()
    val seen = HashSet<String>()
    for (i in 0 until minOf(array.length(), 300)) {
        val row = array.optJSONObject(i) ?: continue
        val pin = CatalogMetadataPin(
            row.optString("id"), row.optString("title"), row.optString("subtitle"),
            row.optString("art").takeIf(String::isNotBlank),
        )
        if (validCatalogMetadataPin(pin) && seen.add(pin.browseId)) result += pin
        if (result.size >= 100) break
    }
    return result
}

internal class CatalogMetadataPinRepository(private val prefs: SharedPreferences) {
    val pins = MutableStateFlow(decodeCatalogMetadataPins(prefs.getString(KEY, null)))

    @Synchronized fun toggle(pin: CatalogMetadataPin): Boolean {
        val current = pins.value
        val updated = toggleCatalogMetadataPin(current, pin)
        if (updated != current && prefs.edit()
                .putString(KEY, encodeCatalogMetadataPins(updated)).commit()) {
            pins.value = updated
        }
        return pins.value.any { it.browseId == pin.browseId }
    }

    companion object {
        private const val KEY = "pinned_catalog_metadata_v1"
        @Volatile private var singleton: CatalogMetadataPinRepository? = null
        fun get(context: Context): CatalogMetadataPinRepository =
            singleton ?: synchronized(this) {
                singleton ?: CatalogMetadataPinRepository(
                    context.applicationContext.getSharedPreferences(
                        "bitchord_settings", Context.MODE_PRIVATE,
                    ),
                ).also { singleton = it }
            }
    }
}
