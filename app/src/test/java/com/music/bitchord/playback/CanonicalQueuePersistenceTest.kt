package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalQueuePersistenceTest {

    @Test
    fun `bounded local queue persists recording identity and re-resolves rendition`() = runBlocking {
        val source = QueueSource("Local album", PlaybackSourceType.BROWSE, "local-album")
        val providerAlias = "youtube-provider-alias"
        val originalUri = "content://media/external/audio/media/42"
        val replacementUri = "content://media/external/audio/media/84"
        val identity = CanonicalQueueIdentity("recording-uuid")
        val song = Song(
            videoId = providerAlias,
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = originalUri,
            localPath = "/storage/emulated/0/Music/Track.flac",
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "queue-entry",
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
        val context = ContextSnapshot(
            contextToken = "context-token",
            sourceKey = "${source.type.name}:${source.id}",
            browseId = source.id,
            songs = listOf(song.copy(queueEntryId = null)),
            version = 1L,
            continuationToken = null,
            continuationState = ContinuationState.EXHAUSTED,
        )
        val state = BoundedContextPersistence.State(
            context = context,
            source = source,
            window = QueueWindow(1).startAt(0).snapshot(),
            entries = listOf(
                BoundedContextPersistence.Entry(
                    song = song,
                    canonicalIndex = 0,
                    cycleId = 0L,
                ),
            ),
            currentIndex = 0,
            positionMs = 12_345L,
        )
        val savingResolver = resolver(
            PlayableMediaAsset(
                recordingId = identity.recordingId,
                mediaAssetId = "asset-old",
                uri = originalUri,
                path = song.localPath,
            ),
        )

        val canonicalized = BoundedContextPersistence.canonicalize(state, savingResolver)

        assertEquals(listOf(identity), canonicalized.contextIdentities)
        assertEquals(identity, canonicalized.entries.single().canonicalIdentity)
        assertEquals(originalUri, canonicalized.context.songs.single().videoId)
        assertEquals(originalUri, canonicalized.entries.single().song.videoId)
        assertNotEquals(providerAlias, canonicalized.entries.single().song.videoId)

        val raw = BoundedContextPersistence.encode(canonicalized)
        assertNotNull(raw)
        val decoded = requireNotNull(BoundedContextPersistence.decode(requireNotNull(raw)))
        val restoreResolver = resolver(
            PlayableMediaAsset(
                recordingId = identity.recordingId,
                mediaAssetId = "asset-new",
                uri = replacementUri,
                path = "/storage/emulated/0/Music/Moved Track.flac",
            ),
        )
        val restored = requireNotNull(BoundedContextPersistence.rehydrate(decoded, restoreResolver))
        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(restored))

        assertEquals(listOf(identity), restored.contextIdentities)
        assertEquals(identity, projected.entries.single().canonicalIdentity)
        assertEquals(replacementUri, projected.entries.single().song.videoId)
        assertEquals(replacementUri, projected.entries.single().song.localUri)
        assertEquals("/storage/emulated/0/Music/Moved Track.flac", projected.entries.single().song.localPath)
        assertEquals(12_345L, projected.positionMs)
    }

    @Test
    fun `canonical restore is rejected when recording no longer has a playable asset`() = runBlocking {
        val source = QueueSource("Local album", PlaybackSourceType.BROWSE, "local-album")
        val identity = CanonicalQueueIdentity("recording-uuid")
        val staleUri = "content://media/external/audio/media/42"
        val song = Song(
            videoId = staleUri,
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = staleUri,
            localPath = "/storage/emulated/0/Music/Removed Track.flac",
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "queue-entry",
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
        val state = BoundedContextPersistence.State(
            context = ContextSnapshot(
                contextToken = "context-token",
                sourceKey = "${source.type.name}:${source.id}",
                browseId = source.id,
                songs = listOf(song.copy(queueEntryId = null)),
                version = 0L,
                continuationToken = null,
                continuationState = ContinuationState.EXHAUSTED,
            ),
            source = source,
            window = QueueWindow(1).startAt(0).snapshot(),
            entries = listOf(
                BoundedContextPersistence.Entry(
                    song = song,
                    canonicalIndex = 0,
                    cycleId = 0L,
                    canonicalIdentity = identity,
                ),
            ),
            currentIndex = 0,
            positionMs = 12_345L,
            contextIdentities = listOf(identity),
        )

        assertNull(
            BoundedContextPersistence.rehydrate(
                state,
                CanonicalPlaybackResolver(PlaybackCatalog { emptyList() }),
            ),
        )
    }

    @Test
    fun `restored media item carries recording identity without replacing transport media id`() {
        val source = QueueSource("Local album", PlaybackSourceType.BROWSE, "local-album")
        val identity = CanonicalQueueIdentity("recording-uuid")
        val assetUri = "content://media/external/audio/media/84"
        val song = Song(
            videoId = assetUri,
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = assetUri,
            localPath = "/storage/emulated/0/Music/Track.flac",
            queueTier = QueueTier.CONTEXT,
            queueEntryId = "queue-entry",
            playbackSource = source.title,
            playbackSourceType = source.type,
            playbackSourceId = source.id,
        )
        val state = BoundedContextPersistence.State(
            context = ContextSnapshot(
                contextToken = "context-token",
                sourceKey = "${source.type.name}:${source.id}",
                browseId = source.id,
                songs = listOf(song.copy(queueEntryId = null)),
                version = 0L,
                continuationToken = null,
                continuationState = ContinuationState.EXHAUSTED,
            ),
            source = source,
            window = QueueWindow(1).startAt(0).snapshot(),
            entries = listOf(
                BoundedContextPersistence.Entry(
                    song = song,
                    canonicalIndex = 0,
                    cycleId = 0L,
                    canonicalIdentity = identity,
                ),
            ),
            currentIndex = 0,
            positionMs = 0L,
            contextIdentities = listOf(identity),
        )
        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(state))
        val restoredItem = QueueShuffle.withQueueMetadata(
            projected.entries.single().song.toMediaItem(),
            newCanonicalIndex = 0,
            newTier = QueueTier.CONTEXT,
        )

        assertEquals(assetUri, restoredItem.mediaId)
        assertEquals(identity, restoredItem.canonicalQueueIdentity)
        assertNotEquals(identity.recordingId, restoredItem.mediaId)
    }

    private fun resolver(asset: PlayableMediaAsset): PlaybackResolver =
        CanonicalPlaybackResolver(PlaybackCatalog { listOf(asset) })
}
