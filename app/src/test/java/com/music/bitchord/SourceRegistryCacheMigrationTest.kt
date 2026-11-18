package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRegistryCacheMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun known() = SourceConfig(
        id = "known", kind = SourceKind.ADDON,
        baseUrl = "https://source.example", enabled = false,
        playbackCacheEpoch = "persisted-epoch",
    )

    @Test fun anUnknownFutureSourceSurvivesCacheEpochMigrationVerbatim() {
        val raw = """[
          {"id":"known","kind":"ADDON","baseUrl":"https://source.example",
           "enabled":false,"futurePolicy":{"optOut":true}},
          {"id":"future","kind":"FUTURE_SERVER","baseUrl":"https://future.example",
           "futureOptions":{"privateConfig":"preserve-me"}}
        ]""".trimIndent()
        val output = requireNotNull(
            SourceRegistry.sourcesDocumentPreservingUnknown(raw, listOf(known())))
        val entries = json.parseToJsonElement(output).jsonArray
        assertEquals(2, entries.size)
        assertEquals("persisted-epoch",
            entries[0].jsonObject["playbackCacheEpoch"]?.jsonPrimitive?.content)
        assertTrue(entries[0].jsonObject.containsKey("futurePolicy"))
        assertEquals(json.parseToJsonElement(raw).jsonArray[1], entries[1])
        assertEquals("FUTURE_SERVER", entries[1].jsonObject["kind"]?.jsonPrimitive?.content)
    }

    @Test fun laterSourceChangesPreserveUnknownEntriesAndRetiredKnownSourcesAreRemoved() {
        val raw = """[
          {"id":"old","kind":"YOUTUBE","enabled":true},
          {"id":"future","kind":"NEXTGEN_SOURCE","opaque":"retain"}
        ]""".trimIndent()
        val output = requireNotNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            raw, listOf(known())))
        val entries = json.parseToJsonElement(output).jsonArray
        assertEquals(2, entries.size)
        assertEquals("future", entries[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("known", entries[1].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(json.parseToJsonElement(raw).jsonArray[1], entries[0])
    }

    @Test fun malformedSourceDocumentIsNeverReplacedWithEmptyConfig() {
        assertNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            """{"not":"an array"}""", emptyList()))
        assertNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            """not valid json""", listOf(known())))
    }

    @Test fun futureKindWithReusedIdRefusesAmbiguousRuntimeWrite() {
        val raw = """[{"id":"known","kind":"FUTURE_SERVER","settings":"future"}]"""
        assertNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            raw, listOf(known())))
        // Unknown values survive intact when there is no conflicting active
        // source: a later version can still decode the saved provider.
        val unchanged = requireNotNull(
            SourceRegistry.sourcesDocumentPreservingUnknown(raw, emptyList()))
        assertEquals(json.parseToJsonElement(raw),
            json.parseToJsonElement(unchanged))
    }
    @Test fun malformedFutureIdDoesNotCrashPreferenceMigration() {
        val raw = """[{"id":{"opaque":"future"},"kind":"FUTURE_SERVER",
          "settings":{"keep":"all"}}]"""
        val output = requireNotNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            raw, listOf(known())))
        val entries = json.parseToJsonElement(output).jsonArray
        assertEquals(2, entries.size)
        assertEquals(json.parseToJsonElement(raw).jsonArray[0], entries[0])
    }

    @Test fun duplicateKnownIdsRequireManualRepairInsteadOfSilentCollapse() {
        val raw = """[
          {"id":"duplicate","kind":"ADDON","baseUrl":"https://a.example"},
          {"id":"duplicate","kind":"ADDON","baseUrl":"https://b.example"}
        ]"""
        assertNull(SourceRegistry.sourcesDocumentPreservingUnknown(raw, emptyList()))
    }

    @Test fun sourceDocumentMustCommitBeforeAnyRuntimePublication() {
        var writeCalled = 0
        assertTrue(SourceRegistry.sourceDocumentCommitAllowed("valid") { written ->
            writeCalled++
            written == "valid"
        })
        assertEquals(1, writeCalled)
        assertTrue(!SourceRegistry.sourceDocumentCommitAllowed("valid") {
            writeCalled++
            false
        })
        assertTrue(!SourceRegistry.sourceDocumentCommitAllowed("valid") {
            writeCalled++
            throw IllegalStateException("disk full")
        })
        assertTrue(!SourceRegistry.sourceDocumentCommitAllowed(null) {
            writeCalled++
            true
        })
        assertEquals(3, writeCalled)
    }

    @Test fun migratedSourceDocumentKeepsExplicitOptOutAcrossRestart() {
        val disabled = known().copy(enabled = false)
        val before = """[{"id":"known","kind":"ADDON",
            "baseUrl":"https://source.example","enabled":false}]"""
        val next = requireNotNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            before, listOf(disabled),
        ))
        val first = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SourceConfig.serializer()), next)
        assertEquals(false, first.single().enabled)
        assertEquals(disabled.playbackCacheEpoch, first.single().playbackCacheEpoch)
        val second = requireNotNull(SourceRegistry.sourcesDocumentPreservingUnknown(
            next, first,
        ))
        assertEquals(json.parseToJsonElement(next), json.parseToJsonElement(second))
    }

}
