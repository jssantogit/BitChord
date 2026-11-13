package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.metadata.ListenBrainzSimilarArtist
import androidx.compose.foundation.shape.CircleShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class NativeArtistSimilarArtworkTest {
    @Test fun portraitLookupIsBoundedAndDeduplicatedByVerifiedArtistMbid() {
        val artists = (1..12).map { n ->
            ListenBrainzSimilarArtist(
                n.toString(16).padStart(8, '0') + "-1111-4111-8111-111111111111",
                "Artist $n",
            )
        }
        val targets = nativeArtistSimilarPhotoTargets(
            listOf(artists.first(), artists.first()) + artists,
        )
        assertEquals(8, targets.size)
        assertEquals(8, targets.map { it.mbid }.distinct().size)
        assertEquals(artists.first().mbid, targets.first().mbid)
    }

    @Test fun similarPortraitUriRequiresActualAppleArtworkHost() {
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/test.jpg",
            nativeArtistSimilarPortraitUri(
                "https://is1-ssl.mzstatic.com/image/thumb/test.jpg",
            ))
        assertNull(nativeArtistSimilarPortraitUri("http://is1-ssl.mzstatic.com/image.png"))
        assertNull(nativeArtistSimilarPortraitUri("https://mzstatic.com.evil.example/image.png"))
        assertNull(nativeArtistSimilarPortraitUri("https://example.com/artist.png"))
    }

    @Test fun cachedArtistPhotoMayBeUsedOfflineOnlyWithExactTrustedDiskUri() {
        val local = "file:/data/user/0/com.dev.bitchord/no_backup/visual_offline_stills_v1/abc.image"
        assertNull(nativeArtistSimilarPortraitUri(local))
        assertEquals(local, nativeArtistSimilarPortraitUri(local, trustedSavedUri = local))
        assertNull(nativeArtistSimilarPortraitUri("file:/sdcard/Downloads/untrusted.jpg",
            trustedSavedUri = local))
        assertNull(nativeArtistSimilarPortraitUri("https://example.org/fake.jpg",
            trustedSavedUri = local))
    }

    @Test fun similarArtistPortraitUsesCircularShapeUnlikeReleaseSleeves() {
        assertEquals(CircleShape, nativeArtistSimilarArtworkShape)
    }


    @Test fun photoLookupsRunInBoundedParallelAndPublishTheFastResultFirst() = runBlocking {
        val a = ListenBrainzSimilarArtist("aaaaaaaa-1111-4111-8111-111111111111", "Artist A")
        val b = ListenBrainzSimilarArtist("bbbbbbbb-1111-4111-8111-111111111111", "Artist B")
        val c = ListenBrainzSimilarArtist("cccccccc-1111-4111-8111-111111111111", "Artist C")
        val entered = Channel<String>(Channel.UNLIMITED)
        val releases = mapOf(
            "Artist A" to CompletableDeferred<Unit>(),
            "Artist B" to CompletableDeferred<Unit>(),
            "Artist C" to CompletableDeferred<Unit>(),
        )
        val observed = mutableListOf<String>()
        val resolved = Channel<String>(Channel.UNLIMITED)
        val job = launch {
            resolveNativeSimilarPortraits(
                artists = listOf(a, b, c),
                lookup = { name ->
                    entered.send(name)
                    releases.getValue(name).await()
                    "https://is1-ssl.mzstatic.com/image/thumb/${name.last()}.jpg"
                },
                onResolved = { mbid, _ ->
                    observed += mbid
                    resolved.trySend(mbid)
                },
            )
        }
        // A slow first artist must not serially block the next portrait.
        assertEquals("Artist A", withTimeout(1_000) { entered.receive() })
        assertEquals("Artist B", withTimeout(1_000) { entered.receive() })
        releases.getValue("Artist B").complete(Unit)
        assertEquals("Artist C", withTimeout(1_000) { entered.receive() })
        assertEquals(b.mbid, withTimeout(1_000) { resolved.receive() })
        assertTrue(a.mbid !in observed)
        releases.getValue("Artist A").complete(Unit)
        releases.getValue("Artist C").complete(Unit)
        withTimeout(1_000) { job.join() }
        assertEquals(setOf(a.mbid, b.mbid, c.mbid), observed.toSet())
    }

}
