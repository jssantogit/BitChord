package com.music.bitchord.desktop

import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Compatibility boundary for historical browse IDs, without the retired YouTube network client. */
object DesktopSearchClient {

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.SONGS,
    ): Result<List<SearchResult>> {
        if (query.isBlank()) return Result.failure(IllegalArgumentException("Search query cannot be empty"))
        return DesktopSourceRegistry.search(query.trim(), filter)
    }

    private fun <T> retired(): Result<T> = Result.failure(
        UnsupportedOperationException("Retired YouTube pages have no verified provider-backed asset"),
    )

    // These are archival YouTube-specific page and account entry points. Their
    // serialized IDs may still appear in historical rows, but opening one must
    // never contact the removed browse, session or stream backend.
    suspend fun home(): Result<HomeFeed> = retired()
    suspend fun moreHome(@Suppress("UNUSED_PARAMETER") token: String): Result<HomeFeed> = retired()
    suspend fun homeSupplement(@Suppress("UNUSED_PARAMETER") browseId: String): Result<List<HomeShelf>> = retired()
    val HOME_SUPPLEMENT_BROWSE_IDS: List<String> = emptyList()
    suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = retired()
    suspend fun moodGenreShelves(
        @Suppress("UNUSED_PARAMETER") browseId: String,
        @Suppress("UNUSED_PARAMETER") params: String?,
    ): Result<List<HomeShelf>> = retired()
    suspend fun moodGenreArtwork(
        @Suppress("UNUSED_PARAMETER") browseId: String,
        @Suppress("UNUSED_PARAMETER") params: String?,
    ): Result<String?> = retired()

    suspend fun browse(
        @Suppress("UNUSED_PARAMETER") browseId: String,
        @Suppress("UNUSED_PARAMETER") fallback: BrowseItem? = null,
    ): Result<DesktopCollection> = retired()

    suspend fun moreCollectionSongs(
        @Suppress("UNUSED_PARAMETER") token: String,
        @Suppress("UNUSED_PARAMETER") artistFallback: String? = null,
    ): Result<Pair<List<Song>, String?>> = retired()

    suspend fun history(): Result<List<Song>> = retired()
    suspend fun radio(@Suppress("UNUSED_PARAMETER") videoId: String): Result<List<Song>> = retired()
    suspend fun trackLinks(@Suppress("UNUSED_PARAMETER") videoId: String): Result<Song> = retired()
    suspend fun library(): Result<LibraryPage> = retired()

    suspend fun syncLikedIds(
        @Suppress("UNUSED_PARAMETER") firstToken: String,
        @Suppress("UNUSED_PARAMETER") onIds: suspend (Set<String>) -> Unit,
    ) = Unit

    suspend fun rate(
        @Suppress("UNUSED_PARAMETER") videoId: String,
        @Suppress("UNUSED_PARAMETER") status: LikeStatus,
    ): Result<Unit> = retired()
    suspend fun artistPage(@Suppress("UNUSED_PARAMETER") browseId: String): Result<ArtistPage> = retired()
    suspend fun setSubscribed(
        @Suppress("UNUSED_PARAMETER") channelId: String,
        @Suppress("UNUSED_PARAMETER") subscribed: Boolean,
    ): Result<Unit> = retired()
    suspend fun createPlaylist(
        @Suppress("UNUSED_PARAMETER") title: String,
        @Suppress("UNUSED_PARAMETER") privacy: PlaylistPrivacy,
        @Suppress("UNUSED_PARAMETER") videoIds: List<String> = emptyList(),
    ): Result<String> = retired()
    suspend fun deletePlaylist(@Suppress("UNUSED_PARAMETER") playlistId: String): Result<Unit> = retired()
    suspend fun renamePlaylist(
        @Suppress("UNUSED_PARAMETER") playlistId: String,
        @Suppress("UNUSED_PARAMETER") title: String,
    ): Result<Unit> = retired()
    suspend fun addToPlaylist(
        @Suppress("UNUSED_PARAMETER") playlistId: String,
        @Suppress("UNUSED_PARAMETER") videoIds: List<String>,
    ): Result<Map<String, String>> = retired()
    suspend fun removeFromPlaylist(
        @Suppress("UNUSED_PARAMETER") playlistId: String,
        @Suppress("UNUSED_PARAMETER") entries: List<Pair<String, String>>,
    ): Result<Unit> = retired()
    suspend fun playlistEntries(@Suppress("UNUSED_PARAMETER") browseId: String): Result<List<Song>> = retired()
    suspend fun reorderPlaylist(
        @Suppress("UNUSED_PARAMETER") playlistId: String,
        @Suppress("UNUSED_PARAMETER") current: List<String>,
        @Suppress("UNUSED_PARAMETER") target: List<String>,
    ): Result<Unit> = retired()

    // The old typeahead hit YouTube on every keystroke. Provider-owned search
    // is explicit; local recent queries stay available without network calls.
    suspend fun searchTypeahead(@Suppress("UNUSED_PARAMETER") input: String): Result<List<SearchResult>> =
        Result.success(emptyList())

    suspend fun searchSuggestions(@Suppress("UNUSED_PARAMETER") input: String): Result<List<String>> =
        Result.success(emptyList())

    suspend fun accountMenu(): Result<JsonObject> = retired()

    suspend fun accountsList(): Result<JsonObject> = retired()

    /** The account's own playlists among a library shelf's items — the ones an edit can target. */
    internal fun userPlaylists(
        @Suppress("UNUSED_PARAMETER") items: List<com.music.bitchord.data.model.ShelfItem>,
    ): List<UserPlaylist> = emptyList()

    internal fun isVideoId(id: String): Boolean = VIDEO_ID.matches(id)

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    /** Every object named [name] anywhere under [root], depth first. */
    internal fun renderers(root: JsonElement, name: String): List<JsonObject> = buildList {
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == name && value is JsonObject) add(value)
                    walk(value)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
    }
}

data class DesktopCollection(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
    val songs: List<Song>,
    /** The token for the rows after these, or null once there are none. */
    val continuation: String? = null,
    /**
     * Whether this is a playlist the account made, rather than one it saved — null when the page
     * does not say, or when it is not a playlist at all.
     */
    val owned: Boolean? = null,
    /**
     * A page opened before its tracks have arrived: the id and whatever the card that opened it
     * knew, drawn as a loader until the browse answers.
     */
    val loading: Boolean = false,
) {
    /** The raw id an edit is addressed to; the browse id carries a `VL` prefix. */
    val playlistId: String get() = browseId.removePrefix("VL")
}

internal fun browseTypeOf(browseId: String): BrowseType = when {
    browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_") -> BrowseType.ALBUM
    browseId.startsWith("VL") -> BrowseType.PLAYLIST
    browseId.startsWith("UC") -> BrowseType.ARTIST
    else -> BrowseType.OTHER
}
