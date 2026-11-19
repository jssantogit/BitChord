package com.music.bitchord

import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebCatalog
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebEditorialGateway
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebKind
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebOutcome
import com.music.bitchord.data.catalog.provider.applemusicweb.AppleMusicWebTransport
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Safe boundary between public Apple Web catalog and canonical editorial callers. */
class AppleMusicWebEditorialGatewayTest {
    private val provider = BuiltInProviderKeys.APPLE_MUSIC_WEB
    private val search = ProviderCapability.CATALOG_SEARCH
    private val discography = ProviderCapability.EDITORIAL_DISCOGRAPHY

    private fun gate(vararg enabled: ProviderCapability): ProviderPolicyGate {
        val policy = ProviderCapabilityPolicy(
            descriptors = listOf(ProviderDescriptor(
                key = provider, capabilities = setOf(search, discography),
                readiness = ProviderReadiness.READY,
            )),
            choices = enabled.associateWith {
                listOf(ProviderChoice(provider, enabled = true, order = 0))
            },
        )
        return ProviderPolicyGate(policy)
    }

    @Test fun searchDeniedBeforeTransportAndNeverFallsBackToItunes() = runBlocking {
        var requests = 0
        val gateway = AppleMusicWebEditorialGateway(AppleMusicWebCatalog(
            AppleMusicWebTransport { _, _, _ -> requests++; fail("Network not authorized"); "{}" },
        ), gate())
        val outcome = gateway.search(AppleMusicWebKind.ARTIST, "BTS", "jp")
        assertEquals(ProviderCallResult.Denied, outcome)
        assertEquals(0, requests)
    }

    @Test fun searchCarriesAppleIdentityAndHonestPageCursorWithoutMbidsOrStreams() = runBlocking {
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { cap, path, _ ->
                assertEquals(search, cap)
                assertEquals("/v1/catalog/br/search", path)
                """{"results":{"artists":{"data":[{"id":"121","type":"artists",
                    "attributes":{"name":"Artista"}}],"next":"/v1/catalog/br/search?offset=19"}}}"""
            }), gate(search),
        )
        val result = gateway.search(AppleMusicWebKind.ARTIST, "Artista", "BR")
        assertTrue(result is ProviderCallResult.Success)
        val value = (result as ProviderCallResult.Success).value
        assertEquals(provider, value.provider)
        assertEquals(search, value.capability)
        assertEquals("br", value.storefront)
        assertTrue(value.outcome is AppleMusicWebOutcome.Complete)
        val page = (value.outcome as AppleMusicWebOutcome.Complete).value
        assertTrue(page.hasMore)
        assertEquals("apple_music_web:br:artist:121", page.items.single().scopedId)
        assertNull(page.items.single().musicBrainzId)
        assertFalse(page.items.single().playable)
        assertNotEquals(BuiltInProviderKeys.ITUNES, value.provider)
    }

    @Test fun wrongSearchResourceTypeFailsRatherThanCrossContaminatingCatalog() = runBlocking {
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                """{"results":{"songs":{"data":[{"id":"121","type":"artists",
                "attributes":{"name":"Not a song"}}]}}}"""
            }), gate(search),
        )
        val result = gateway.search(AppleMusicWebKind.SONG, "Song", "us")
        assertTrue(result is ProviderCallResult.Failed)
    }

    @Test fun duplicatedAppleResourceIdsOnOneSearchPageFailClosed() = runBlocking {
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                """{"results":{"artists":{"data":[
                    {"id":"121","type":"artists","attributes":{"name":"A"}},
                    {"id":"121","type":"artists","attributes":{"name":"B"}}
                ]}}}"""
            }), gate(search),
        )
        assertTrue(gateway.search(AppleMusicWebKind.ARTIST, "test", "us") is ProviderCallResult.Failed)
    }

    @Test fun discographyPermissionCannotBeBorrowedFromSearch() = runBlocking {
        var requests = 0
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ -> requests++; "{}" }),
            gate(search),
        )
        assertEquals(ProviderCallResult.Denied, gateway.artist("77", "br"))
        assertEquals(0, requests)
    }

    @Test fun revocationDuringSuspendedCatalogQueryNeverPublishesResult() = runBlocking {
        val active = gate(search)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                entered.complete(Unit)
                release.await()
                """{"results":{"artists":{"data":[{"id":"77","type":"artists",
                    "attributes":{"name":"No stale publishing"}}]}}}"""
            }), active,
        )
        val request = async { gateway.search(AppleMusicWebKind.ARTIST, "BTS", "jp") }
        entered.await()
        active.replace(ProviderCapabilityPolicy(listOf(ProviderDescriptor(
            key = provider, capabilities = setOf(search, discography),
            readiness = ProviderReadiness.READY,
        ))))
        release.complete(Unit)
        assertEquals(ProviderCallResult.Stale, request.await())
    }

    @Test fun albumPaginationFailureRemainsExplicitPartialWithSourceProvenance() = runBlocking {
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, path, _ ->
                if (path.endsWith("relationships/tracks"))
                    """{"data":[],"next":"/v1/catalog/br/albums/10/relationships/tracks?offset=3"}"""
                else
                    """{"data":[{"id":"10","type":"albums","attributes":{"name":"Album"},
                        "relationships":{"tracks":{"data":[{"id":"1","type":"songs",
                            "attributes":{"name":"One"}}],
                        "next":"/v1/catalog/br/albums/10/relationships/tracks?offset=2"}}}]}"""
            }), gate(discography),
        )
        val result = gateway.album("10", "br")
        assertTrue(result is ProviderCallResult.Success)
        val value = (result as ProviderCallResult.Success).value
        assertEquals(provider, value.provider)
        assertEquals(discography, value.capability)
        assertTrue(value.outcome is AppleMusicWebOutcome.Partial)
        val partial = value.outcome as AppleMusicWebOutcome.Partial
        assertTrue(partial.value.possiblyTruncated)
        assertEquals(listOf("1"), partial.value.tracks.map { it.id })
    }

    @Test fun absentTracksRelationshipCannotBePublishedAsACompleteAlbum() = runBlocking {
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                """{"data":[{"id":"10","type":"albums",
                    "attributes":{"name":"Pre-release"}}]}"""
            }), gate(discography),
        )
        val result = gateway.album("10", "br")
        assertTrue(result is ProviderCallResult.Success)
        val album = (result as ProviderCallResult.Success).value.outcome
        assertTrue(album is AppleMusicWebOutcome.Partial)
        val partial = album as AppleMusicWebOutcome.Partial
        assertTrue(partial.value.possiblyTruncated)
    }

    @Test fun declaredTrackCountCannotBeSilentlyIgnored() = runBlocking {
        val gateway = AppleMusicWebEditorialGateway(
            AppleMusicWebCatalog(AppleMusicWebTransport { _, _, _ ->
                """{"data":[{"id":"10","type":"albums",
                    "attributes":{"name":"Two tracks","trackCount":2},
                    "relationships":{"tracks":{"data":[{"id":"1","type":"songs",
                        "attributes":{"name":"Only one returned"}}]}}}]}"""
            }), gate(discography),
        )
        val result = gateway.album("10", "br")
        assertTrue(result is ProviderCallResult.Success)
        val outcome = (result as ProviderCallResult.Success).value.outcome
        assertTrue("Observed 1 of 2 tracks must stay partial", outcome is AppleMusicWebOutcome.Partial)
    }
}
