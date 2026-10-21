package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CanonicalPlayableAsset
import com.music.bitchord.playback.CanonicalQueueIdentity
import com.music.bitchord.playback.CanonicalQueueResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalSongFeedbackTargetTest {
    private fun song() = Song(
        videoId = "untrusted-native-id",
        title = "Same title",
        artist = "Same artist",
        thumbnailUrl = null,
    )

    private class StubResolver(
        private val identified: CanonicalQueueIdentity?,
        private val resolved: CanonicalPlayableAsset?,
    ) : CanonicalQueueResolver {
        var identifyCalls = 0
        var resolveCalls = 0

        override suspend fun identifyAll(songs: List<Song>): List<CanonicalQueueIdentity?> {
            identifyCalls++
            assertEquals(1, songs.size)
            return listOf(identified)
        }

        override suspend fun resolveAll(
            identities: List<CanonicalQueueIdentity?>,
        ): List<CanonicalPlayableAsset?> {
            resolveCalls++
            assertEquals(listOf(identified), identities)
            return listOf(resolved)
        }
    }

    @Test fun onlyPlaybackOwnedCanonicalIdentityOpensRatings() = runBlocking {
        val id = CanonicalQueueIdentity("recording-1", "asset-1", "local-mediastore")
        val resolver = StubResolver(id, CanonicalPlayableAsset(id, "content://local/1"))
        assertEquals("recording-1", CanonicalSongFeedbackTarget(resolver).recordingId(song()))
        assertEquals(1, resolver.identifyCalls)
        assertEquals(1, resolver.resolveCalls)
    }

    @Test fun opaqueProviderIdsAndTitlesDoNotBecomeRecordingIds() = runBlocking {
        val resolver = StubResolver(null, null)
        assertNull(CanonicalSongFeedbackTarget(resolver).recordingId(song()))
        assertEquals(0, resolver.resolveCalls)
    }

    @Test fun unresolvedAssetsAndIdentityOnlySnapshotsFailClosed() = runBlocking {
        val resolvedIdentity = CanonicalQueueIdentity("recording-1", "asset-1", "remote")
        val noAsset = StubResolver(resolvedIdentity, null)
        assertNull(CanonicalSongFeedbackTarget(noAsset).recordingId(song()))
        val identityOnly = CanonicalQueueIdentity("recording-1")
        val noRendition = StubResolver(identityOnly, null)
        assertNull(CanonicalSongFeedbackTarget(noRendition).recordingId(song()))
        assertEquals(0, noRendition.resolveCalls)
    }

    @Test fun anotherRecordingOrRenditionCannotBeSubstituted() = runBlocking {
        val identity = CanonicalQueueIdentity("recording-1", "asset-1", "remote")
        for (different in listOf(
            CanonicalQueueIdentity("recording-2", "asset-1", "remote"),
            CanonicalQueueIdentity("recording-1", "asset-2", "remote"),
            CanonicalQueueIdentity("recording-1", "asset-1", "another-provider"),
        )) {
            val resolver = StubResolver(identity, CanonicalPlayableAsset(different, "bitchord://canonical"))
            assertNull(CanonicalSongFeedbackTarget(resolver).recordingId(song()))
        }
    }
}
