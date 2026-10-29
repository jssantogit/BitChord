package com.music.bitchord.data.listening

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * A normal playlist is an ordered, user-authored list of canonical RecordingIds.
 * It is NOT a snapshot of provider URLs, YouTube videoIds or MediaAssetIds.
 * Playback revalidates the members against the active sources on every click.
 */
internal data class SavedCanonicalStaticPlaylist(
    val id: String,
    val name: String,
    val recordingIds: List<String>,
    val schemaVersion: Int = 1,
    /** Null means all available sources; otherwise an exact configured provider. */
    val sourceProviderKey: String? = null,
)

@Entity(tableName = "canonical_static_playlist")
internal data class CanonicalStaticPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val recordingIdsJson: String,
    val sourceProviderKey: String?,
)

@Dao
internal interface CanonicalStaticPlaylistDao {
    @Upsert
    suspend fun upsert(row: CanonicalStaticPlaylistEntity)

    @Query("SELECT * FROM canonical_static_playlist ORDER BY name COLLATE NOCASE, id")
    suspend fun all(): List<CanonicalStaticPlaylistEntity>

    @Query("SELECT * FROM canonical_static_playlist ORDER BY name COLLATE NOCASE, id")
    fun observe(): Flow<List<CanonicalStaticPlaylistEntity>>

    @Query("DELETE FROM canonical_static_playlist WHERE id = :id")
    suspend fun remove(id: String)
}

/**
 * Maintains separate identity from the legacy Song/videoId store.
 * An unavailable Recording remains in the saved list (never silently erased),
 * but the queue planner must prove an eligible current MediaAsset before playing.
 */
internal class CanonicalStaticPlaylistRepository(
    private val dao: CanonicalStaticPlaylistDao,
) {
    suspend fun save(value: SavedCanonicalStaticPlaylist) {
        validate(value)
        val json = JSONArray()
        value.recordingIds.forEach(json::put)
        dao.upsert(CanonicalStaticPlaylistEntity(
            id = value.id, name = value.name.trim(),
            schemaVersion = value.schemaVersion, recordingIdsJson = json.toString(),
            sourceProviderKey = value.sourceProviderKey,
        ))
    }

    suspend fun list(): List<SavedCanonicalStaticPlaylist> =
        dao.all().map { it.toDomain() }

    fun observe(): Flow<List<SavedCanonicalStaticPlaylist>> =
        dao.observe().map { rows -> rows.map { it.toDomain() } }

    suspend fun remove(id: String) {
        require(id.isNotBlank())
        dao.remove(id)
    }

    private fun CanonicalStaticPlaylistEntity.toDomain(): SavedCanonicalStaticPlaylist {
        require(schemaVersion == 1) { "Unsupported canonical static playlist schema" }
        val members = JSONArray(recordingIdsJson)
        val ids = (0 until members.length()).map { index ->
            require(members.opt(index) is String) { "Invalid canonical member" }
            members.getString(index)
        }
        return SavedCanonicalStaticPlaylist(id, name, ids, schemaVersion, sourceProviderKey)
            .also(::validate)
    }

    private fun validate(value: SavedCanonicalStaticPlaylist) {
        require(value.schemaVersion == 1) { "Unsupported canonical static playlist schema" }
        require(value.id.isNotBlank() && value.id.length <= 100 &&
            value.id == value.id.trim() && value.id.none { it.isISOControl() }) {
            "Invalid playlist id"
        }
        require(value.name.trim().length in 1..80) { "Invalid playlist name" }
        val provider = value.sourceProviderKey
        require(provider == null || provider == "local-mediastore" ||
            (provider.startsWith("opensubsonic:") && provider.length in 15..180 &&
                provider.substringAfter("opensubsonic:").isNotBlank() &&
                provider.none { it.isWhitespace() || it.isISOControl() || it == '/' })) {
            "Invalid canonical playlist source provider"
        }
        require(value.recordingIds.size <= 50_000) { "Too many playlist members" }
        require(value.recordingIds.size == value.recordingIds.distinct().size) {
            "Repeated RecordingIds are not supported by the canonical queue"
        }
        require(value.recordingIds.all { id ->
            id.isNotBlank() && id.length <= 256 && id == id.trim() &&
                id.none { it.isWhitespace() || it.isISOControl() } &&
                !id.startsWith("http://", ignoreCase = true) &&
                !id.startsWith("https://", ignoreCase = true) &&
                !id.startsWith("content://", ignoreCase = true) &&
                !id.startsWith("file://", ignoreCase = true)
        }) { "Invalid canonical RecordingId" }
    }
}
