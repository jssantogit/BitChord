package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.providers.MusicBrainzCapabilitySearch
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzCapabilitySearchTest {
    private val mb = ProviderKey("musicbrainz")

    private fun policy(enabled: Boolean) = ProviderCapabilityPolicy(
        descriptors = listOf(ProviderDescriptor(mb,
            setOf(ProviderCapability.CATALOG_SEARCH),
            readiness = ProviderReadiness.READY)),
        choices = mapOf(ProviderCapability.CATALOG_SEARCH to listOf(
            ProviderChoice(mb, enabled, 0),
        )),
    )

    @Test fun genericCatalogEntryProtectsEveryMusicBrainzQueryWithoutAnExtraNetworkAttempt(): Unit = runBlocking {
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(ProviderPolicyGate(policy(false))),
        )
        var queried = false
        val result = adapter.executeCatalog {
            queried = true
            MusicBrainzCatalogSearchResult.Success(
                MusicBrainzCatalogPage(emptyList<MusicBrainzArtistHit>(), 0, 0),
            )
        }
        assertEquals(ProviderCallResult.Denied, result)
        assertFalse(queried)
    }

    @Test fun genericCatalogEntryAllowsEnabledExistingSourceResults(): Unit = runBlocking {
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(ProviderPolicyGate(policy(true))),
        )
        val result = adapter.executeCatalog {
            MusicBrainzCatalogSearchResult.Success(
                MusicBrainzCatalogPage(listOf(MusicBrainzArtistHit(
                    "11111111-2222-4333-8444-555555555555", "NewJeans", null, "KR",
                )), 1, 0),
            )
        }
        assertTrue(result is ProviderCallResult.Success)
    }

    @Test fun disabledMusicBrainzNeverStartsExistingCatalogClient() = runBlocking {
        var contacted = false
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(ProviderPolicyGate(policy(false))),
            lookupArtists = { _, _, _ ->
                contacted = true
                error("should not request MusicBrainz")
            },
        )
        assertEquals(ProviderCallResult.Denied, adapter.searchArtists("NewJeans"))
        assertFalse(contacted)
    }

    @Test fun enabledAdapterRetainsMusicBrainzScopedResultWithoutInventingPlayback() = runBlocking {
        val artist = MusicBrainzArtistHit(
            "11111111-2222-4333-8444-555555555555", "NewJeans", null, "KR",
        )
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(ProviderPolicyGate(policy(true))),
            lookupArtists = { query, limit, offset ->
                assertEquals("NewJeans", query)
                assertEquals(20, limit)
                assertEquals(0, offset)
                MusicBrainzCatalogSearchResult.Success(
                    MusicBrainzCatalogPage(listOf(artist), total = 1, offset = 0),
                )
            },
        )
        val result = adapter.searchArtists("NewJeans", limit = 20)
        assertTrue(result is ProviderCallResult.Success)
        val sourceResult = (result as ProviderCallResult.Success).value
        assertTrue(sourceResult is MusicBrainzCatalogSearchResult.Success)
        assertEquals(artist, (sourceResult as MusicBrainzCatalogSearchResult.Success).page.items.single())
    }

    @Test fun disablingWhileMusicBrainzLookupIsInFlightDropsTheResult() = runBlocking {
        val gate = ProviderPolicyGate(policy(true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(gate),
            lookupArtists = { _, _, _ ->
                entered.complete(Unit)
                release.await()
                MusicBrainzCatalogSearchResult.Success(MusicBrainzCatalogPage(
                    emptyList<MusicBrainzArtistHit>(), total = 0, offset = 0,
                ))
            },
        )
        val response = async { adapter.searchArtists("NewJeans") }
        entered.await()
        gate.replace(policy(false))
        release.complete(Unit)
        assertEquals(ProviderCallResult.Stale, response.await())
    }
}
