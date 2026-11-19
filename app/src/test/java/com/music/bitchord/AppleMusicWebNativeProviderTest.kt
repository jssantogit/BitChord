package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebProtocolException
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTokenParser
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderCapabilityRegistry
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderPolicyGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AppleMusicWebNativeProviderTest {
    private class FakeTransport(
        private val response: suspend (ProviderCapability, String, Map<String, String>) -> String,
    ) : AppleMusicWebTransport {
        val calls = mutableListOf<Triple<ProviderCapability, String, Map<String, String>>>()
        override suspend fun get(
            capability: ProviderCapability, path: String, params: Map<String, String>,
        ): String {
            calls += Triple(capability, path, params)
            return response(capability, path, params)
        }
    }

    @Test fun searchFollowsAppleIssuedOffsetRatherThanPageTimesLimit() = runBlocking {
        val fake = FakeTransport { _, _, args ->
            if (args["offset"] == "9") {
                """{"results":{"songs":{"data":[
                    {"id":"3","type":"songs","attributes":{"name":"Third","artistName":"Artist","url":"https://music.apple.com/br/song/x/3"}},
                    {"id":"4","type":"songs","attributes":{"name":"Fourth"}}
                ]}}}"""
            } else {
                """{"results":{"songs":{"data":[
                    {"id":"1","type":"songs","attributes":{"name":"First"}},
                    {"id":"2","type":"songs","attributes":{"name":"Second"}}
                ],"next":"/v1/catalog/br/search?term=Rock&types=songs&offset=9"}}}"""
            }
        }
        val page = AppleMusicWebCatalog(fake).search(
            AppleMusicWebKind.SONG, "Rock", "br", page = 2, limit = 2,
        )
        assertEquals(listOf("3", "4"), page.items.map { it.id })
        assertEquals("apple_music_web:br:song:3", page.items.first().scopedId)
        assertFalse(page.items.first().playable)
        assertNull(page.items.first().musicBrainzId)
        assertEquals(2, fake.calls.size)
        assertEquals("9", fake.calls[1].third["offset"])
        assertTrue(fake.calls.all { it.first == ProviderCapability.CATALOG_SEARCH })
    }

    @Test fun searchFailsClosedOnForeignOrCrossStorefrontNextCursor() = runBlocking {
        for (cursor in listOf(
            "https://evil.example/v1/catalog/br/search?offset=9",
            "/v1/catalog/us/search?offset=9",
            "/v1/catalog/br/albums/123?offset=9",
        )) {
            val transport = FakeTransport { _, _, _ ->
                """{"results":{"songs":{"data":[{"id":"1","type":"songs","attributes":{"name":"A"}}],"next":"$cursor"}}}"""
            }
            try {
                AppleMusicWebCatalog(transport).search(
                    AppleMusicWebKind.SONG, "Rock", "br", page = 2, limit = 2,
                )
                fail("Untrusted cursor should be rejected: $cursor")
            } catch (_: AppleMusicWebProtocolException) {
                assertEquals(1, transport.calls.size)
            }
        }
    }

    @Test fun artistOverviewPreservesAppleShelvesAndStorefront() = runBlocking {
        val fake = FakeTransport { _, _, _ ->
            """{"data":[{"id":"55","type":"artists","attributes":{"name":"Real Artist"},
                "views":{"top-songs":{"data":[{"id":"1","type":"songs","attributes":{"name":"Hit"}}]},
                "full-albums":{"data":[{"id":"100","type":"albums","attributes":{"name":"Album"}}]},
                "singles":{"data":[{"id":"101","type":"albums","attributes":{"name":"Single"}}]},
                "similar-artists":{"data":[{"id":"56","type":"artists","attributes":{"name":"Similar"}}]}
            }}]}"""
        }
        val artist = AppleMusicWebCatalog(fake).artist("55", "br")
        assertEquals("apple_music_web:br:artist:55", artist.identity.scopedId)
        assertEquals(listOf("1"), artist.topSongs.map { it.id })
        assertEquals(listOf("100"), artist.albums.map { it.id })
        assertEquals(listOf("101"), artist.singlesAndEps.map { it.id })
        assertEquals(listOf("56"), artist.similarArtists.map { it.id })
        assertEquals(ProviderCapability.EDITORIAL_DISCOGRAPHY, fake.calls.single().first)
    }

    @Test fun resourceDetailDoesNotAcceptAnotherAppleIdentity() = runBlocking {
        val fake = FakeTransport { _, _, _ ->
            """{"data":[{"id":"999","type":"albums","attributes":{"name":"Wrong"}}]}"""
        }
        try {
            AppleMusicWebCatalog(fake).album("100", "br")
            fail("Other album ID must not be mapped onto the requested album")
        } catch (_: AppleMusicWebProtocolException) {
            // Fail closed.
        }
    }

    @Test fun tokenParserChoosesOnlyLiveAmpWebPlayJwt() {
        fun token(issuer: String, expiry: Long): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"ES256"}""".toByteArray())
            val claims = encoder.encodeToString(
                """{"iss":"$issuer","exp":$expiry}""".toByteArray(),
            )
            return "$header.$claims." + "x".repeat(45)
        }
        val wrong = token("someone-else", 9_000_000_000)
        val expired = token("AMPWebPlay", 10)
        val correct = token("AMPWebPlay", 9_000_000_000)
        val selected = AppleMusicWebTokenParser.pick(
            "bundle $wrong unrelated $expired live $correct", nowEpochSeconds = 1_900_000_000,
        )
        assertEquals(correct, selected?.value)
        assertEquals(9_000_000_000L, selected?.expiresAtEpochSeconds)
        assertNull(AppleMusicWebTokenParser.pick(wrong, nowEpochSeconds = 1_900_000_000))
    }

    @Test fun providerIsRegisteredButNeverImplicitlyEnabled() {
        val inventory = ProviderCapabilityRegistry.inventory()
        val registered = inventory.filter { it.key == BuiltInProviderKeys.APPLE_MUSIC_WEB }
        assertEquals(
            setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY),
            registered.map { it.capability }.toSet(),
        )
        val descriptors = ProviderCapabilityRegistry.descriptorsFrom(inventory)
        val empty = ProviderPolicyGate(ProviderCapabilityPolicy(descriptors))
        assertNull(empty.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        val withChoice = ProviderCapabilityPolicy(
            descriptors, choices = mapOf(
                ProviderCapability.CATALOG_SEARCH to listOf(
                    ProviderChoice(BuiltInProviderKeys.APPLE_MUSIC_WEB, enabled = true, order = 0),
                ),
            ),
        )
        empty.replace(withChoice)
        assertNull(empty.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        // A standalone explicitly READY descriptor models a future validated
        // opt-in, without unblocking the production inventory prematurely.
        val ready = descriptors.map { descriptor ->
            if (descriptor.key == BuiltInProviderKeys.APPLE_MUSIC_WEB) {
                descriptor.copy(
                    capabilityReadiness = descriptor.capabilityReadiness.mapValues {
                        com.music.bitchord.data.providers.ProviderReadiness.READY
                    },
                )
            } else descriptor
        }
        empty.replace(ProviderCapabilityPolicy(ready, choices = mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(BuiltInProviderKeys.APPLE_MUSIC_WEB, enabled = true, order = 0),
            ),
        )))
        assertNotNull(empty.open(ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.APPLE_MUSIC_WEB))
        assertNull(empty.open(ProviderCapability.EDITORIAL_DISCOGRAPHY, BuiltInProviderKeys.APPLE_MUSIC_WEB))
    }
}
