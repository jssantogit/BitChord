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
 * V2 definitions coexist with legacy rows. The migration never deletes or
 * rewrites V1 data: the old editor remains operational until explicitly replaced.
 */
internal data class SavedSmartPlaylistV2Definition(
    val id: String,
    val name: String,
    val query: SmartPlaylistV2Query,
    val schemaVersion: Int = 2,
)

@Entity(tableName = "smart_playlist_v2_definition")
internal data class SmartPlaylistV2DefinitionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val queryJson: String,
)

@Dao
internal interface SmartPlaylistV2DefinitionDao {
    @Upsert
    suspend fun upsert(definition: SmartPlaylistV2DefinitionEntity)

    @Query("SELECT * FROM smart_playlist_v2_definition ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun all(): List<SmartPlaylistV2DefinitionEntity>

    @Query("SELECT * FROM smart_playlist_v2_definition ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observe(): Flow<List<SmartPlaylistV2DefinitionEntity>>

    @Query("DELETE FROM smart_playlist_v2_definition WHERE id = :id")
    suspend fun remove(id: String)
}

/** Typed, bounded, strict JSON. No provider credentials or media URLs enter this store. */
internal class SmartPlaylistV2DefinitionRepository(
    private val dao: SmartPlaylistV2DefinitionDao,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        classDiscriminator = "kind"
    }

    suspend fun save(definition: SavedSmartPlaylistV2Definition) {
        require(definition.schemaVersion == 2) { "Unsupported V2 definition schema" }
        require(definition.id.isNotBlank() && definition.id.length <= 100)
        val name = definition.name.trim()
        require(name.isNotEmpty() && name.length <= 80)
        val payload = json.encodeToString(definition.query)
        require(payload.length <= 32_768) { "Playlist rule graph is too large" }
        dao.upsert(SmartPlaylistV2DefinitionEntity(
            id = definition.id,
            name = name,
            schemaVersion = 2,
            queryJson = payload,
        ))
    }

    suspend fun list(): List<SavedSmartPlaylistV2Definition> = dao.all().map { it.toDomain() }

    fun observe(): Flow<List<SavedSmartPlaylistV2Definition>> =
        dao.observe().map { rows -> rows.map { it.toDomain() } }

    suspend fun remove(id: String) {
        require(id.isNotBlank())
        dao.remove(id)
    }

    private fun SmartPlaylistV2DefinitionEntity.toDomain(): SavedSmartPlaylistV2Definition {
        require(schemaVersion == 2 && id.isNotBlank() && name.isNotBlank() &&
            queryJson.length <= 32_768) { "Unsupported or corrupt V2 definition" }
        // A bad or future schema fails closed. It must never silently become an
        // empty/match-all filter or remove the user's saved definition.
        val query = json.decodeFromString<SmartPlaylistV2Query>(queryJson)
        return SavedSmartPlaylistV2Definition(id, name, query)
    }
}

/** A lossless V1->V2 translation; nothing is written by this conversion. */
internal fun RuleBasedSmartPlaylistQuery.toV2(): SmartPlaylistV2Query {
    val rules = mutableListOf<SmartPlaylistRule>()
    fun minimum(field: SmartPlaylistRuleField, threshold: Double?) {
        if (threshold != null) {
            rules += SmartPlaylistRule.Comparison(
                field, SmartPlaylistRuleOperator.GREATER_OR_EQUAL, threshold,
            )
        }
    }
    minimum(SmartPlaylistRuleField.EXPLICIT_RATING, minimumExplicitRating)
    minimum(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, minimumCompletions?.toDouble())
    minimum(SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE, minimumDaysSinceAudible?.toDouble())
    if (maximumDaysSinceAudible != null) {
        rules += SmartPlaylistRule.Comparison(
            SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE,
            SmartPlaylistRuleOperator.LESS_OR_EQUAL, maximumDaysSinceAudible.toDouble(),
        )
    }
    minimum(SmartPlaylistRuleField.DURATION_MS, minimumDurationMs?.toDouble())
    return SmartPlaylistV2Query(
        rules = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, rules),
        sort = SmartPlaylistV2Sort.RATING_DESC,
        limit = limit,
    )
}
