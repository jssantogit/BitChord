package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class CanonicalQueuePersistenceV2Test {
    @Test fun `canonical snapshot omits transport and source identity`() {
        val source = QueueSource("Mixed queue", PlaybackSourceType.BROWSE, "mixed")
        val song = Song(videoId="legacy-video", title="Track", artist="Artist", thumbnailUrl=null,
            localUri="content://secret/42", localPath="/secret/Track.flac", queueTier=QueueTier.CONTEXT,
            queueEntryId="entry", playbackSource=source.title, playbackSourceType=source.type, playbackSourceId=source.id)
        val identity = CanonicalQueueIdentity("recording-1", "asset-1", RoomCanonicalQueueResolver.LOCAL_PROVIDER)
        val state = BoundedContextPersistence.State(
            ContextSnapshot("context-token", "${source.type.name}:${source.id}", source.id,
                listOf(song.copy(queueEntryId=null)), 1L, null, ContinuationState.EXHAUSTED),
            source, QueueWindow(1).startAt(0).snapshot(),
            listOf(BoundedContextPersistence.Entry(song,0,0L,identity)), 0, 12345L, listOf(identity))
        val raw = requireNotNull(BoundedContextPersistence.encode(state))
        assertTrue(raw.contains("\"schemaVersion\":2"))
        listOf("sourceKey","legacy-video","content://secret/42","/secret/Track.flac","sessionId").forEach { assertFalse(raw.contains(it)) }
        assertTrue(raw.contains("recording-1")); assertTrue(raw.contains("asset-1")); assertTrue(raw.contains("local-mediastore"))
        val decoded = requireNotNull(BoundedContextPersistence.decode(raw))
        assertEquals(12345L, decoded.positionMs); assertEquals(0, decoded.currentIndex)
        assertEquals(identity, decoded.entries.single().canonicalIdentity)
    }

    @Test fun `remote canonical snapshot restores only logical uri without account access`() {
        val request = CanonicalRemotePlaybackRequest("recording-r","asset-r","opensubsonic:account")
        val logical = CanonicalRemotePlaybackUri.encode(request)
        val source = QueueSource("Remote", PlaybackSourceType.BROWSE, "remote")
        val song = Song(videoId="recording-r", title="Remote", artist="Artist", thumbnailUrl=null,
            localUri=logical, queueTier=QueueTier.CONTEXT, queueEntryId="remote-entry",
            playbackSource=source.title, playbackSourceType=source.type, playbackSourceId=source.id)
        val identity = CanonicalQueueIdentity(request.recordingId, request.mediaAssetId, request.providerKey)
        val state = BoundedContextPersistence.State(
            ContextSnapshot("remote-token","${source.type.name}:${source.id}",source.id,listOf(song.copy(queueEntryId=null)),0L,null,ContinuationState.EXHAUSTED),
            source, QueueWindow(1).startAt(0).snapshot(), listOf(BoundedContextPersistence.Entry(song,0,0L,identity)),0,900L,listOf(identity))
        val raw=requireNotNull(BoundedContextPersistence.encode(state))
        assertFalse(raw.contains(logical)); assertFalse(raw.contains("videoId")); assertFalse(raw.contains("sourceKey"))
        val decoded=requireNotNull(BoundedContextPersistence.decode(raw))
        val resolver=object: CanonicalQueueResolver {
            override suspend fun identifyAll(songs: List<Song>) = error("restore must not identify")
            override suspend fun resolveAll(ids: List<CanonicalQueueIdentity?>) = ids.map { id -> id?.let {
                val req=CanonicalRemotePlaybackRequest(it.recordingId,requireNotNull(it.mediaAssetId),requireNotNull(it.providerKey))
                CanonicalPlayableAsset(it,CanonicalRemotePlaybackUri.encode(req))
            }}
        }
        val restored=requireNotNull(kotlinx.coroutines.runBlocking { CanonicalQueueMigration.rehydrate(decoded,resolver) })
        assertEquals(logical,restored.entries.single().song.localUri)
        assertTrue(CanonicalRemotePlaybackUri.isCanonical(requireNotNull(restored.entries.single().song.localUri)))
    }
}
