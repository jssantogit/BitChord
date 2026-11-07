package com.music.bitchord.data.catalog.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Identity-only evidence from public top-recordings-for-artist.
 * ListenBrainz titles, portraits and releases never become canonical metadata.
 * A MusicBrainz Recording lookup and a separate MediaAsset check are required
 * before adding a row to an artist page or offering playback.
 */
internal data class ListenBrainzArtistTopRecording(
    val recordingMbid: String,
    val listens: Long,
    val listeners: Long,
    /** Source-provided Cover Art Archive identity; never a guessed edition. */
    val artworkUri: String? = null,
)

internal sealed interface ListenBrainzArtistTopParseResult {
    data class Success(val recordings: List<ListenBrainzArtistTopRecording>) :
        ListenBrainzArtistTopParseResult
    data object Invalid : ListenBrainzArtistTopParseResult
}

@Serializable
private data class ListenBrainzArtistTopRow(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("artist_mbids") val artistMbids: List<String>? = null,
    @SerialName("total_listen_count") val listens: Long? = null,
    @SerialName("total_user_count") val listeners: Long? = null,
    @SerialName("caa_release_mbid") val caaReleaseMbid: String? = null,
    @SerialName("caa_id") val caaId: Long? = null,
)

private val TOP_ARTIST_MBID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Exact artist MBID from the endpoint AND its artist_mbids is required.
 * Unknown/zero listening counts produce no rank. Entries scoped to another
 * artist do not leak into this artist's "Mais tocadas" section.
 */
internal fun parseListenBrainzArtistTopRecordings(
    response: String,
    expectedArtistMbid: String,
): ListenBrainzArtistTopParseResult {
    val artist = expectedArtistMbid.lowercase(Locale.ROOT)
    if (!TOP_ARTIST_MBID.matches(artist) || response.length > 256_000) {
        return ListenBrainzArtistTopParseResult.Invalid
    }
    val rows = try {
        Json { ignoreUnknownKeys = true }
            .decodeFromString<List<ListenBrainzArtistTopRow>>(response)
    } catch (_: Exception) {
        return ListenBrainzArtistTopParseResult.Invalid
    }
    if (rows.size > 100) return ListenBrainzArtistTopParseResult.Invalid
    val found = mutableSetOf<String>()
    val accepted = mutableListOf<ListenBrainzArtistTopRecording>()
    for (row in rows) {
        val ids = row.artistMbids.orEmpty().map { it.lowercase(Locale.ROOT) }
        if (artist !in ids) continue
        val recording = row.recordingMbid?.lowercase(Locale.ROOT)?.takeIf {
            TOP_ARTIST_MBID.matches(it)
        } ?: continue
        if (!found.add(recording)) return ListenBrainzArtistTopParseResult.Invalid
        if ((row.listens == null && row.listeners == null) ||
            (row.listens == 0L && row.listeners == 0L)
        ) continue
        val listens = row.listens ?: return ListenBrainzArtistTopParseResult.Invalid
        val listeners = row.listeners ?: return ListenBrainzArtistTopParseResult.Invalid
        if (listens < 0 || listeners < 0 || listeners > listens) {
            return ListenBrainzArtistTopParseResult.Invalid
        }
        if (listens > 0 && listeners > 0) {
            val coverRelease = row.caaReleaseMbid?.lowercase(Locale.ROOT)
                ?.takeIf(TOP_ARTIST_MBID::matches)
            val coverId = row.caaId?.takeIf { it > 0L }
            val artwork = if (coverRelease != null && coverId != null)
                "https://coverartarchive.org/release/$coverRelease/$coverId-250.jpg"
            else null
            accepted += ListenBrainzArtistTopRecording(
                recording, listens, listeners, artworkUri = artwork,
            )
        }
    }
    return ListenBrainzArtistTopParseResult.Success(accepted.sortedWith(
        compareByDescending<ListenBrainzArtistTopRecording> { it.listens }
            .thenByDescending { it.listeners }.thenBy { it.recordingMbid },
    ))
}
