package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.AppleArtistTitleHintSource
import com.music.bitchord.data.catalog.metadata.MusicBrainzAppleArtistUrlResolver
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistUrlLookup
import com.music.bitchord.data.catalog.metadata.VerifiedAppleArtistTitleHintSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleMusicBrainzArtistUrlResolverTest {
    private val artistId = 136975L
    private val storeUrl = "https://music.apple.com/br/artist/the-beatles/136975?uo=4"
    private val mbid = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
    private val other = "f2eaa98b-fef5-4327-9e7f-7d6500fdb973"
    private val appleMusicRelation = "64785d6c-2eeb-4f86-9418-b6c2d6c53c13"

    private fun relation(id: String, type: String = appleMusicRelation) =
        """{"target-type":"artist","type-id":"$type",
            "artist":{"id":"$id","name":"The Beatles"}}"""

    private fun response(url: String = storeUrl, vararg relations: String) =
        """{"id":"0b513786-47c9-4e03-8b05-1fd1b09077b2",
            "resource":"$url","relations":[${relations.joinToString(",")}]}"""

    private class Fake(var raw: String) : MusicBrainzArtistUrlLookup {
        val calls = mutableListOf<String>()
        override suspend fun lookup(url: String): String {
            calls += url
            return raw
        }
    }

    @Test fun exactAppleMusicRelationBindsTheAppleArtistIdToTheMbArtistId() = runBlocking {
        val fake = Fake(response(storeUrl, relation(mbid)))
        val result = MusicBrainzAppleArtistUrlResolver(fake).resolve(artistId, "br", storeUrl)
        assertEquals(mbid, result)
        assertEquals(listOf(storeUrl), fake.calls)
    }

    @Test fun wrongArtistIdOrStorefrontNeverQueriesMusicBrainz() = runBlocking {
        val fake = Fake(response(storeUrl, relation(mbid)))
        val resolver = MusicBrainzAppleArtistUrlResolver(fake)
        assertNull(resolver.resolve(136976L, "br", storeUrl))
        assertNull(resolver.resolve(artistId, "us", storeUrl))
        assertNull(resolver.resolve(artistId, "br", "https://music.apple.com.evil.org/br/artist/name/136975"))
        assertNull(resolver.resolve(artistId, "br", "https://music.apple.com/br/album/name/136975"))
        assertNull(resolver.resolve(artistId, "br", "http://music.apple.com/br/artist/name/136975"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun sourceCannotClaimADifferentAppleArtistEvenIfMbArtistNameMatches() = runBlocking {
        val fake = Fake(response("https://music.apple.com/br/artist/the-beatles/123",
            relation(mbid)))
        assertNull(MusicBrainzAppleArtistUrlResolver(fake).resolve(artistId, "br", storeUrl))
    }

    @Test fun nonAppleMusicRelationOrMissingArtistTargetIsNotProof() = runBlocking {
        val fake = Fake(response(storeUrl, relation(mbid,
            "63cc5d1f-f096-4c94-a43f-ecb32ea94161")))
        val resolver = MusicBrainzAppleArtistUrlResolver(fake)
        assertNull(resolver.resolve(artistId, "br", storeUrl))
        fake.raw = """{"resource":"$storeUrl","relations":[
            {"target-type":"release-group","type-id":"$appleMusicRelation",
             "release-group":{"id":"$mbid"}}]}"""
        assertNull(resolver.resolve(artistId, "br", storeUrl))
    }

    @Test fun conflictingMusicBrainzArtistLinksFailClosed() = runBlocking {
        val fake = Fake(response(storeUrl, relation(mbid), relation(other)))
        assertNull(MusicBrainzAppleArtistUrlResolver(fake).resolve(artistId, "br", storeUrl))
        fake.raw = response(storeUrl, relation(mbid), relation(mbid))
        assertEquals(mbid, MusicBrainzAppleArtistUrlResolver(fake)
            .resolve(artistId, "br", storeUrl))
    }

    @Test fun unverifiedOrMalformedResourceDoesNotProduceArtistLink() = runBlocking {
        val fake = Fake("""{"relations":[${relation(mbid)}]}""")
        val resolver = MusicBrainzAppleArtistUrlResolver(fake)
        assertNull(resolver.resolve(artistId, "br", storeUrl))
        fake.raw = "not json"
        assertNull(resolver.resolve(artistId, "br", storeUrl))
    }

    @Test fun hintSourceReceivesMbidOnlyForVerifiedAppleArtistAndFallsBackOnFailure() =
        runBlocking {
            val fake = Fake(response(storeUrl, relation(mbid)))
            val resolver = MusicBrainzAppleArtistUrlResolver(fake)
            val received = mutableListOf<String?>()
            val base = AppleArtistTitleHintSource { _, id ->
                received += id
                listOf("Abbey Road")
            }
            val source = VerifiedAppleArtistTitleHintSource(
                artistId, "br", storeUrl, resolver, base,
            )
            assertEquals(listOf("Abbey Road"), source.suggest("The Beatles", null))
            assertEquals(listOf(mbid), received)

            fake.raw = response(storeUrl, relation(mbid), relation(other))
            assertEquals(listOf("Abbey Road"), source.suggest("The Beatles", null))
            assertEquals(listOf(mbid, null), received)
        }
}
