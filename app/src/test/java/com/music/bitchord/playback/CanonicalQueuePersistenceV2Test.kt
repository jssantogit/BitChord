package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalQueuePersistenceV2Test {

    @Test
    fun `canonical snapshot omits transport and source identity`() {
        val source = QueueSource("Mixed queue", PlaybackSourceType.BROWSE, "mixed")
        val song = song(
            id = "legacy-video",
            source = source,
            localUri = "content://secret/42",
            localPath = "/secret/Track.flac",
            entryId = "entry",
        )
        val identity = CanonicalQueueIdentity(
            "recording-1",
            "asset-1",
            RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val state = state(
            source = source,
            contextSongs = listOf(song.copy(queueEntryId = null)),
            entries = listOf(BoundedContextPersistence.Entry(song, 0, 0L, identity)),
            currentIndex = 0,
            positionMs = 12_345L,
            identities = listOf(identity),
        )

        val raw = requireNotNull(BoundedContextPersistence.encode(state))

        assertTrue(raw.contains("\"schemaVersion\":2"))
        listOf(
            "sourceKey",
            "legacy-video",
            "content://secret/42",
            "/secret/Track.flac",
            "sessionId",
        ).forEach { forbidden -> assertFalse(raw.contains(forbidden)) }
        assertTrue(raw.contains("recording-1"))
        assertTrue(raw.contains("asset-1"))
        assertTrue(raw.contains("local-mediastore"))

        val decoded = requireNotNull(BoundedContextPersistence.decode(raw))
        assertEquals(12_345L, decoded.positionMs)
        assertEquals(0, decoded.currentIndex)
        assertEquals(identity, decoded.entries.single().canonicalIdentity)
    }

    @Test
    fun `mixed canonical and legacy rows keep canonical rows off videoId`() {
        val source = QueueSource("Mixed", PlaybackSourceType.BROWSE, "mixed")
        val canonicalSong = song(
            id = "canonical-old-video",
            source = source,
            localUri = "content://canonical/secret",
            localPath = "/canonical/secret.flac",
            entryId = "canonical-entry",
        )
        val legacySong = song(
            id = "legacy-unmapped-video",
            source = source,
            tier = QueueTier.USER_QUEUE,
            entryId = "legacy-entry",
        )
        val identity = CanonicalQueueIdentity(
            "recording-c",
            "asset-c",
            RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val state = state(
            source = source,
            contextSongs = listOf(canonicalSong.copy(queueEntryId = null)),
            entries = listOf(
                BoundedContextPersistence.Entry(canonicalSong, 0, 0L, identity),
                BoundedContextPersistence.Entry(legacySong),
            ),
            currentIndex = 0,
            positionMs = 77L,
            identities = listOf(identity),
        )

        val raw = requireNotNull(BoundedContextPersistence.encode(state))
        val decoded = requireNotNull(BoundedContextPersistence.decode(raw))

        assertTrue(raw.contains("\"schemaVersion\":2"))
        assertFalse(raw.contains("canonical-old-video"))
        assertFalse(raw.contains("content://canonical/secret"))
        assertFalse(raw.contains("/canonical/secret.flac"))
        assertTrue(raw.contains("legacy-unmapped-video"))
        assertEquals(identity, decoded.entries[0].canonicalIdentity)
        assertNull(decoded.entries[1].canonicalIdentity)
        assertEquals("legacy-unmapped-video", decoded.entries[1].song.videoId)
        assertEquals(QueueTier.USER_QUEUE, decoded.entries[1].song.queueTier)
    }

    @Test
    fun `mixed local and remote queue restores order current item position and one batch`() = runBlocking {
        val source = QueueSource("Mixed providers", PlaybackSourceType.BROWSE, "providers")
        val localIdentity = CanonicalQueueIdentity(
            "recording-local",
            "asset-local",
            RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val remoteIdentity = CanonicalQueueIdentity(
            "recording-remote",
            "asset-remote",
            "opensubsonic:account",
        )
        val localSong = song(
            id = "old-local-video",
            source = source,
            localUri = "content://old/local",
            localPath = "/old/local.flac",
            entryId = "local-entry",
        )
        val remoteRequest = CanonicalRemotePlaybackRequest(
            remoteIdentity.recordingId,
            requireNotNull(remoteIdentity.mediaAssetId),
            requireNotNull(remoteIdentity.providerKey),
        )
        val remoteSong = song(
            id = "old-remote-video",
            source = source,
            localUri = CanonicalRemotePlaybackUri.encode(remoteRequest),
            entryId = "remote-entry",
        )
        val state = state(
            source = source,
            contextSongs = listOf(
                localSong.copy(queueEntryId = null),
                remoteSong.copy(queueEntryId = null),
            ),
            entries = listOf(
                BoundedContextPersistence.Entry(localSong, 0, 0L, localIdentity),
                BoundedContextPersistence.Entry(remoteSong, 1, 0L, remoteIdentity),
            ),
            currentIndex = 1,
            positionMs = 54_321L,
            identities = listOf(localIdentity, remoteIdentity),
            startIndex = 1,
        )
        val decoded = requireNotNull(
            BoundedContextPersistence.decode(requireNotNull(BoundedContextPersistence.encode(state))),
        )
        var resolveCalls = 0
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>) = error("restore must not identify")

            override suspend fun resolveAll(
                identities: List<CanonicalQueueIdentity?>,
            ): List<CanonicalPlayableAsset?> {
                resolveCalls += 1
                return identities.map { identity ->
                    when (identity) {
                        localIdentity -> CanonicalPlayableAsset(
                            localIdentity,
                            "content://current/local",
                            "/current/local.flac",
                        )
                        remoteIdentity -> CanonicalPlayableAsset(
                            remoteIdentity,
                            CanonicalRemotePlaybackUri.encode(remoteRequest),
                        )
                        else -> null
                    }
                }
            }
        }

        val restored = requireNotNull(CanonicalQueueMigration.rehydrate(decoded, resolver))
        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(restored))

        assertEquals(1, resolveCalls)
        assertEquals(listOf(localIdentity, remoteIdentity), projected.entries.map { it.canonicalIdentity })
        assertEquals(1, projected.currentIndex)
        assertEquals(54_321L, projected.positionMs)
        assertEquals("content://current/local", projected.entries[0].song.localUri)
        assertEquals(
            CanonicalRemotePlaybackUri.encode(remoteRequest),
            projected.entries[1].song.localUri,
        )
    }

    @Test
    fun `remote canonical restore stays logical and lazy when account state is unavailable`() = runBlocking {
        val request = CanonicalRemotePlaybackRequest(
            "recording-r",
            "asset-r",
            "opensubsonic:forgotten-account",
        )
        val logical = CanonicalRemotePlaybackUri.encode(request)
        val source = QueueSource("Remote", PlaybackSourceType.BROWSE, "remote")
        val remote = song(
            id = "recording-r",
            source = source,
            localUri = logical,
            entryId = "remote-entry",
        )
        val identity = CanonicalQueueIdentity(
            request.recordingId,
            request.mediaAssetId,
            request.providerKey,
        )
        val state = state(
            source = source,
            contextSongs = listOf(remote.copy(queueEntryId = null)),
            entries = listOf(BoundedContextPersistence.Entry(remote, 0, 0L, identity)),
            currentIndex = 0,
            positionMs = 900L,
            identities = listOf(identity),
        )
        val raw = requireNotNull(BoundedContextPersistence.encode(state))
        assertFalse(raw.contains(logical))
        assertFalse(raw.contains("videoId"))
        assertFalse(raw.contains("sourceKey"))

        val decoded = requireNotNull(BoundedContextPersistence.decode(raw))
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>) = error("restore must not identify")

            override suspend fun resolveAll(
                identities: List<CanonicalQueueIdentity?>,
            ): List<CanonicalPlayableAsset?> = identities.map { id ->
                id?.let {
                    val restoredRequest = CanonicalRemotePlaybackRequest(
                        it.recordingId,
                        requireNotNull(it.mediaAssetId),
                        requireNotNull(it.providerKey),
                    )
                    CanonicalPlayableAsset(it, CanonicalRemotePlaybackUri.encode(restoredRequest))
                }
            }
        }

        val restored = requireNotNull(CanonicalQueueMigration.rehydrate(decoded, resolver))
        assertEquals(logical, restored.entries.single().song.videoId)
        assertEquals(logical, restored.entries.single().song.localUri)
        assertEquals(identity, restored.entries.single().canonicalIdentity)
        assertTrue(CanonicalRemotePlaybackUri.isCanonical(requireNotNull(restored.entries.single().song.localUri)))
    }

    @Test
    fun `missing canonical local asset fails restore safely`() = runBlocking {
        val source = QueueSource("Local", PlaybackSourceType.BROWSE, "local")
        val identity = CanonicalQueueIdentity(
            "recording-missing",
            "asset-missing",
            RoomCanonicalQueueResolver.LOCAL_PROVIDER,
        )
        val local = song(
            id = "stale-video",
            source = source,
            localUri = "content://stale/local",
            localPath = "/stale/local.flac",
            entryId = "entry",
        )
        val decoded = requireNotNull(
            BoundedContextPersistence.decode(
                requireNotNull(
                    BoundedContextPersistence.encode(
                        state(
                            source = source,
                            contextSongs = listOf(local.copy(queueEntryId = null)),
                            entries = listOf(
                                BoundedContextPersistence.Entry(local, 0, 0L, identity),
                            ),
                            currentIndex = 0,
                            positionMs = 1L,
                            identities = listOf(identity),
                        ),
                    ),
                ),
            ),
        )
        val resolver = object : CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>) = error("restore must not identify")
            override suspend fun resolveAll(identities: List<CanonicalQueueIdentity?>) =
                identities.map { null }
        }

        assertNull(CanonicalQueueMigration.rehydrate(decoded, resolver))
    }

    @Test
fun `new unmapped legacy snapshot still writes schema two without sourceKey`() {
    val source = QueueSource("Legacy", PlaybackSourceType.BROWSE, "legacy")
    val legacy = song(id = "legacy-video-id", source = source, entryId = "legacy-entry")
    val state = state(
        source = source,
        contextSongs = listOf(legacy.copy(queueEntryId = null)),
        entries = listOf(BoundedContextPersistence.Entry(legacy, 0, 0L)),
        currentIndex = 0,
        positionMs = 222L,
        identities = emptyList(),
    )

    val raw = requireNotNull(BoundedContextPersistence.encode(state))
    val decoded = requireNotNull(BoundedContextPersistence.decode(raw))

    assertTrue(raw.contains("\"schemaVersion\":2"))
    assertFalse(raw.contains("sourceKey"))
    assertTrue(raw.contains("legacy-video-id"))
    assertEquals("legacy-video-id", decoded.entries.single().song.videoId)
    assertEquals(222L, decoded.positionMs)
}

@Test
fun `historical schema one snapshot remains readable`() {
    val source = QueueSource("Legacy", PlaybackSourceType.BROWSE, "legacy")
    val legacy = song(id = "legacy-video-id", source = source, entryId = "legacy-entry")
    val state = state(
        source = source,
        contextSongs = listOf(legacy.copy(queueEntryId = null)),
        entries = listOf(BoundedContextPersistence.Entry(legacy, 0, 0L)),
        currentIndex = 0,
        positionMs = 333L,
        identities = emptyList(),
    )

    val historical = requireNotNull(
        BoundedContextPersistence.encodeLegacySnapshotForTesting(state),
    )
    val decoded = requireNotNull(BoundedContextPersistence.decode(historical))

    assertTrue(historical.contains("\"schemaVersion\":1"))
    assertTrue(historical.contains("sourceKey"))
    assertEquals("legacy-video-id", decoded.entries.single().song.videoId)
    assertEquals(333L, decoded.positionMs)
}

    private fun state(
        source: QueueSource,
        contextSongs: List<Song>,
        entries: List<BoundedContextPersistence.Entry>,
        currentIndex: Int,
        positionMs: Long,
        identities: List<CanonicalQueueIdentity?>,
        startIndex: Int = 0,
    ) = BoundedContextPersistence.State(
        context = ContextSnapshot(
            contextToken = "context-token-${source.id}",
            sourceKey = "${source.type.name}:${source.id}",
            browseId = source.id,
            songs = contextSongs,
            version = 1L,
            continuationToken = null,
            continuationState = ContinuationState.EXHAUSTED,
        ),
        source = source,
        window = QueueWindow(contextSongs.size).startAt(startIndex).snapshot(),
        entries = entries,
        currentIndex = currentIndex,
        positionMs = positionMs,
        contextIdentities = identities,
    )

    private fun song(
        id: String,
        source: QueueSource,
        tier: QueueTier = QueueTier.CONTEXT,
        localUri: String? = null,
        localPath: String? = null,
        entryId: String? = null,
    ) = Song(
        videoId = id,
        title = "Track $id",
        artist = "Artist",
        thumbnailUrl = null,
        localUri = localUri,
        localPath = localPath,
        queueTier = tier,
        queueEntryId = entryId,
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
    )
}
