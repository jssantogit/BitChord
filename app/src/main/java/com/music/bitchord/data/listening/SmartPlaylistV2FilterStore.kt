package com.music.bitchord.data.listening

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saved *rule fragments*, not smart playlist definitions. No sort, limit or
 * snapshot of tracks is persisted. Applying a filter copies the immutable
 * typed rule tree into an editor draft; source edits do not mutate old drafts.
 */
internal data class SavedSmartPlaylistV2Filter(
    val id: String,
    val name: String,
    val rules: SmartPlaylistRule,
    val schemaVersion: Int = 1,
)

@Entity(tableName = "smart_playlist_v2_filter")
internal data class SmartPlaylistV2FilterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val rulesJson: String,
)

@Dao
internal interface SmartPlaylistV2FilterDao {
    @Upsert
    suspend fun upsert(value: SmartPlaylistV2FilterEntity)

    @Query("SELECT * FROM smart_playlist_v2_filter ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun all(): List<SmartPlaylistV2FilterEntity>

    @Query("SELECT * FROM smart_playlist_v2_filter ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observe(): Flow<List<SmartPlaylistV2FilterEntity>>

    @Query("DELETE FROM smart_playlist_v2_filter WHERE id = :id")
    suspend fun remove(id: String)
}

internal class SmartPlaylistV2FilterRepository(private val dao: SmartPlaylistV2FilterDao) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "kind"
    }

    internal companion object {
        fun validateFilterName(name: String): String = name.trim().also {
            require(it.isNotEmpty() && it.length <= 80) { "Filter name must be 1..80 characters" }
        }
    }

    suspend fun save(value: SavedSmartPlaylistV2Filter) {
        require(value.schemaVersion == 1) { "Unsupported filter schema" }
        require(value.id.isNotBlank() && value.id.length <= 100 &&
            value.id == value.id.trim()) { "Invalid filter ID" }
        val name = validateFilterName(value.name)
        // Reuse the engine's AST bounds and field/operator validation; no media
        // queries are executed by storing a filter.
        SmartPlaylistV2Query(rules = value.rules)
        val payload = json.encodeToString<SmartPlaylistRule>(value.rules)
        require(payload.length <= 32_768) { "Saved filter is too large" }
        dao.upsert(SmartPlaylistV2FilterEntity(value.id, name, 1, payload))
    }

    suspend fun list(): List<SavedSmartPlaylistV2Filter> = dao.all().map { it.toDomain() }

    fun observe(): Flow<List<SavedSmartPlaylistV2Filter>> =
        dao.observe().map { rows -> rows.map { it.toDomain() } }

    suspend fun remove(id: String) {
        require(id.isNotBlank() && id.length <= 100)
        dao.remove(id)
    }

    private fun SmartPlaylistV2FilterEntity.toDomain(): SavedSmartPlaylistV2Filter {
        require(schemaVersion == 1 && id.isNotBlank() &&
            name.isNotBlank() && rulesJson.length <= 32_768) {
            "Unsupported or corrupt saved filter"
        }
        val rules = json.decodeFromString<SmartPlaylistRule>(rulesJson)
        SmartPlaylistV2Query(rules = rules)
        return SavedSmartPlaylistV2Filter(id, name, rules)
    }
}

/** Explicit snapshot copy: no implicit dynamic link or hidden 100-song cap. */
internal fun SmartPlaylistRule.andSavedFilter(
    filter: SavedSmartPlaylistV2Filter,
): SmartPlaylistRule = SmartPlaylistRule.Group(
    SmartPlaylistRuleJoin.ALL, listOf(this, filter.rules),
).also { SmartPlaylistV2Query(it) }

/** Export to an editable smart playlist, never rewriting the saved filter. */
internal fun SavedSmartPlaylistV2Filter.toPlaylistCopy(
    playlistId: String,
    playlistName: String = name,
): SavedSmartPlaylistV2Definition = SavedSmartPlaylistV2Definition(
    id = playlistId,
    name = playlistName,
    query = SmartPlaylistV2Query(rules = rules),
)
