package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseLibrary
import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.playback.CanonicalPlayableAsset
import com.music.bitchord.playback.CanonicalQueueIdentity
import com.music.bitchord.playback.CanonicalQueueResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzCatalogPlaybackGateTest {
    private val mbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val fakeResolver = object : CanonicalQueueResolver {
        override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> =
            songs.map { null }
        override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>): List<CanonicalPlayableAsset?> =
            identities.map { id -> id?.let { CanonicalPlayableAsset(it, "content://local/1") } }
    }

    private fun library(vararg assetIds: String) = CanonicalBrowseLibrary(listOf(
        CanonicalBrowseTrack(
            recordingId = "recording",
            title = "Magnetic",
            artistDisplay = "ILLIT",
            durationMs = 160000L,
            artworkUri = null,
            availableAssets = assetIds.map {
                CanonicalBrowseMediaAsset(it, "local-mediastore")
            },
        ),
    ))

    @Test
    fun metadataOnlyCannotProduceAPlaybackOrQueuePlan() = runBlocking {
        val gate = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { "recording" },
            browse = { CanonicalBrowseLibrary.EMPTY },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
        )
        assertNull(gate.plan(mbid, CanonicalCollectionIntent.PLAY_NOW))
        assertNull(gate.plan(mbid, CanonicalCollectionIntent.ADD_TO_QUEUE))
    }

    @Test
    fun missingAndMalformedMbidsCannotEscapeTheCatalog() = runBlocking {
        val gate = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { null },
            browse = { library("local-1") },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
        )
        assertNull(gate.plan(mbid, CanonicalCollectionIntent.PLAY_NOW))
        assertNull(gate.plan("unsafe-title-as-id", CanonicalCollectionIntent.PLAY_NOW))
    }

    @Test
    fun knownRecordingWithOneLocalAssetPermitsExplicitIntentsOnly() = runBlocking {
        val gate = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { "recording" },
            browse = { library("local-1") },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
        )
        val play = gate.plan(mbid, CanonicalCollectionIntent.PLAY_NOW)
        val queue = gate.plan(mbid, CanonicalCollectionIntent.ADD_TO_QUEUE)
        assertEquals(CanonicalCollectionIntent.PLAY_NOW, play?.intent)
        assertEquals(CanonicalCollectionIntent.ADD_TO_QUEUE, queue?.intent)
        assertEquals("recording", play?.selections?.single()?.recordingId)
        assertEquals("local-1", queue?.selections?.single()?.identity?.mediaAssetId)
        assertTrue(play!!.rejected.isEmpty())
    }

    @Test
    fun ambiguousLocalRenditionsFailClosedWithoutGuessing() = runBlocking {
        val gate = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { "recording" },
            browse = { library("local-1", "local-2") },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
        )
        assertNull(gate.plan(mbid, CanonicalCollectionIntent.PLAY_NOW))
    }

    @Test
    fun unlinkedMusicBrainzRecordingProducesPrivacySafeDiagnosticReason() = runBlocking {
        val outcomes = mutableListOf<String>()
        val gate = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { null },
            browse = { library("local-1") },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
            traceOutcome = outcomes::add,
        )
        assertNull(gate.plan(mbid, CanonicalCollectionIntent.PLAY_NOW))
        assertEquals(listOf("unlinked_recording"), outcomes)
        assertTrue(outcomes.none { it.contains(mbid) || it.contains("Magnetic") })
    }

    @Test
    fun ambiguousAssetAndUniqueLocalAssetProduceDistinctReasons() = runBlocking {
        val outcomes = mutableListOf<String>()
        val gate = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { "recording" },
            browse = { library("local-1", "local-2") },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
            traceOutcome = outcomes::add,
        )
        assertNull(gate.plan(mbid, CanonicalCollectionIntent.PLAY_NOW))
        assertEquals(listOf("ambiguous_asset"), outcomes)
        val allowed = MusicBrainzCatalogPlaybackGate(
            trustedRecordingId = { "recording" },
            browse = { library("local-1") },
            planner = CanonicalCollectionIntentPlanner(fakeResolver),
            traceOutcome = outcomes::add,
        )
        assertTrue(allowed.plan(mbid, CanonicalCollectionIntent.PLAY_NOW) != null)
        assertEquals("ready", outcomes.last())
    }
}
