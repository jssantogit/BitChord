package com.music.bitchord.ui.screens

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A trusted online Recording label may be preserved for a user's playlist,
 * but it never establishes a MediaAsset, download, playability or local artist. */
internal const val CATALOG_METADATA_NO_AUDIO_SUFFIX = " · Sem áudio"

internal data class CatalogMetadataRecordingLabel(
    val recordingId: String,
    val title: String,
    val artist: String,
)

internal fun validCatalogMetadataRecordingLabel(value: CatalogMetadataRecordingLabel): Boolean =
    isVerifiedCatalogRecordingId(value.recordingId) &&
        value.title.isNotBlank() && value.title.length <= 240 &&
        value.artist.isNotBlank() && value.artist.length <= 240 &&
        (value.title + value.artist).none { it.isISOControl() }

internal fun metadataOnlyUnavailablePlaylistRow(
    recordingId: String,
    label: CatalogMetadataRecordingLabel?,
): V2DisplayRow =
    if (label != null && label.recordingId == recordingId &&
        validCatalogMetadataRecordingLabel(label)) {
        V2DisplayRow(recordingId, label.title, label.artist + CATALOG_METADATA_NO_AUDIO_SUFFIX)
    } else V2DisplayRow(recordingId, "Gravação indisponível", "Fonte não disponível")

internal fun encodeCatalogMetadataRecordingLabels(
    labels: List<CatalogMetadataRecordingLabel>,
): String = JSONArray().apply {
    labels.asSequence().filter(::validCatalogMetadataRecordingLabel)
        .distinctBy(CatalogMetadataRecordingLabel::recordingId).toList().takeLast(1200)
        .forEach { label ->
            put(JSONObject().apply {
                put("id", label.recordingId)
                put("title", label.title)
                put("artist", label.artist)
            })
        }
}.toString()

internal fun decodeCatalogMetadataRecordingLabels(raw: String?): List<CatalogMetadataRecordingLabel> {
    val data = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    val result = ArrayList<CatalogMetadataRecordingLabel>()
    val seen = HashSet<String>()
    for (i in 0 until minOf(data.length(), 1500)) {
        val o = data.optJSONObject(i) ?: continue
        val label = CatalogMetadataRecordingLabel(
            o.optString("id"), o.optString("title"), o.optString("artist"),
        )
        if (validCatalogMetadataRecordingLabel(label) && seen.add(label.recordingId)) result.add(label)
        if (result.size == 1200) break
    }
    return result
}

internal class CatalogMetadataRecordingLabelRepository(private val preferences: SharedPreferences) {
    val labels = MutableStateFlow(
        decodeCatalogMetadataRecordingLabels(preferences.getString(KEY, null)),
    )

    @Synchronized fun rememberLabel(label: CatalogMetadataRecordingLabel): Boolean {
        if (!validCatalogMetadataRecordingLabel(label)) return false
        val updated = (labels.value.filterNot { it.recordingId == label.recordingId } + label)
            .takeLast(1200)
        if (!preferences.edit().putString(KEY, encodeCatalogMetadataRecordingLabels(updated))
                .commit()) return false
        labels.value = updated
        return true
    }

    companion object {
        private const val KEY = "catalog_metadata_recording_labels_v1"
        @Volatile private var singleton: CatalogMetadataRecordingLabelRepository? = null
        fun get(context: Context): CatalogMetadataRecordingLabelRepository =
            singleton ?: synchronized(this) {
                singleton ?: CatalogMetadataRecordingLabelRepository(
                    context.applicationContext.getSharedPreferences(
                        "bitchord_settings", Context.MODE_PRIVATE,
                    ),
                ).also { singleton = it }
            }
    }
}
