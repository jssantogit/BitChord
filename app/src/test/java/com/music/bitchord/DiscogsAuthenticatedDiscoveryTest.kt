package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscogsAuthenticatedDiscoveryTest {
    private val token = "A".repeat(40)

    @Test fun noTokenMeansZeroRequestsAndDoesNotClaimNoResults() = runBlocking {
        val client = DiscogsDatabaseSearchClient(
            transport = object : DiscogsSearchTransport {
                override suspend fun search(
                    query: DiscogsSearchRequest, token: String, userAgent: String,
                ): DiscogsHttpResponse = error("Do not call Discogs without opt-in")
            },
            tokenProvider = { null }, enabled = true,
        )
        assertEquals(DiscogsSearchResult.AuthRequired, client.search(
            DiscogsSearchRequest(artist = "ILLIT", title = "SUPER REAL ME"),
        ))
    }

    @Test fun safeTypedSearchFiltersMalformedIdsAndNonReleaseEntities() = runBlocking {
        var calls = 0
        val client = DiscogsDatabaseSearchClient(
            transport = object : DiscogsSearchTransport {
                override suspend fun search(
                    query: DiscogsSearchRequest, token: String, userAgent: String,
                ): DiscogsHttpResponse {
                    calls++
                    assertEquals("ILLIT", query.artist)
                    assertEquals("SUPER REAL ME", query.title)
                    assertEquals(1, query.page)
                    assertTrue(token.isNotBlank())
                    return DiscogsHttpResponse(200, """{
                        "pagination":{"page":1,"pages":100},
                        "results":[
                            {"id":101,"type":"release","title":"ILLIT - SUPER REAL ME"},
                            {"id":102,"type":"master","title":"ILLIT - SUPER REAL ME"},
                            {"id":101,"type":"release"},
                            {"id":-7,"type":"release"},
                            {"id":103,"type":"artist"},
                            {"id":104,"type":"release"}]
                    }""")
                }
            },
            tokenProvider = { token }, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val result = client.search(DiscogsSearchRequest(
            artist = "ILLIT", title = "SUPER REAL ME",
        ))
        assertEquals(listOf(101L, 104L), (result as DiscogsSearchResult.Found).ids)
        assertEquals(1, calls)
    }

    @Test fun searchInputIsBoundedAndNetworkAndAuthFailuresAreTyped() = runBlocking {
        var requested = 0
        val client = DiscogsDatabaseSearchClient(
            transport = object : DiscogsSearchTransport {
                override suspend fun search(
                    query: DiscogsSearchRequest, token: String, userAgent: String,
                ): DiscogsHttpResponse {
                    requested++
                    return DiscogsHttpResponse(401, "")
                }
            }, tokenProvider = { token }, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0),
        )
        assertEquals(DiscogsSearchResult.InvalidQuery,
            client.search(DiscogsSearchRequest(artist = "ILLIT\u0000", title = "Album")))
        assertEquals(DiscogsSearchResult.InvalidQuery,
            client.search(DiscogsSearchRequest(artist = "ILLIT", page = 99)))
        assertEquals(0, requested)
        assertEquals(DiscogsSearchResult.AuthRequired,
            client.search(DiscogsSearchRequest(artist = "ILLIT", title = "Album")))
        assertEquals(1, requested)
    }

    @Test fun barcodeMissFallsBackToArtistAndReleaseTitleWithoutInventingIdentity() = runBlocking {
        val queries = mutableListOf<DiscogsSearchRequest>()
        val search = DiscogsDatabaseSearchClient(
            transport = object : DiscogsSearchTransport {
                override suspend fun search(
                    query: DiscogsSearchRequest, token: String, userAgent: String,
                ): DiscogsHttpResponse {
                    queries += query
                    return if (query.barcode != null)
                        DiscogsHttpResponse(200, """{"results":[]}""")
                    else DiscogsHttpResponse(200,
                        """{"results":[{"id":321,"type":"release"}]}""")
                }
            },
            tokenProvider = { token }, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val hydrated = mutableListOf<String>()
        val editions = DiscogsClient(
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    hydrated += path
                    return DiscogsHttpResponse(200, """{
                        "id":321, "title":"SUPER REAL ME",
                        "artists":[{"name":"ILLIT"}],
                        "year":2024, "country":"South Korea",
                        "labels":[{"name":"BELIFT LAB","catno":"CMDC12000"}],
                        "identifiers":[{"type":"Barcode","value":"8809704427821"}],
                        "formats":[{"name":"CD","descriptions":["EP"]}],
                        "tracklist":[{"position":"1","title":"Magnetic"}]
                    }""")
                }
            }, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val canonical = CanonicalEditionEvidence(
            musicBrainzReleaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
            title = "SUPER REAL ME", creditedArtist = "ILLIT",
            creditedArtistNames = listOf("ILLIT"), status = "Official",
            barcodes = listOf("8809704427821"),
            trackTitles = listOf("Magnetic"), releaseYear = 2024, country = "KR",
        )
        val result = DiscogsAuthenticatedDiscovery(search, editions).findForEdition(canonical)
        assertTrue(result is DiscogsAuthenticatedMatch.Verified)
        assertEquals(2, queries.size)
        assertEquals("8809704427821", queries.first().barcode)
        assertEquals("SUPER REAL ME", queries.last().title)
        assertEquals(listOf("releases/321"), hydrated)
    }

    @Test fun twoIndependentSearchesMustNotSilentlyChooseTheFirstVerifiedEdition() = runBlocking {
        val search = DiscogsDatabaseSearchClient(
            transport = object : DiscogsSearchTransport {
                override suspend fun search(
                    query: DiscogsSearchRequest, token: String, userAgent: String,
                ): DiscogsHttpResponse = if (query.barcode != null)
                    DiscogsHttpResponse(200, """{"results":[{"id":111,"type":"release"}]}""")
                else
                    DiscogsHttpResponse(200, """{"results":[{"id":112,"type":"release"}]}""")
            },
            tokenProvider = { token }, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val calls = mutableListOf<String>()
        val editions = DiscogsClient(
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    calls += path
                    return DiscogsHttpResponse(200, """{
                        "id":${path.substringAfter("/")},
                        "title":"SUPER REAL ME","artists":[{"name":"ILLIT"}],
                        "year":2024, "country":"South Korea",
                        "labels":[{"name":"BELIFT LAB","catno":"CMDC12000"}],
                        "identifiers":[{"type":"Barcode","value":"8809704427821"}],
                        "formats":[{"name":"CD","descriptions":["EP"]}],
                        "tracklist":[{"position":"1","title":"Magnetic"}]}""")
                }
            }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val canonical = CanonicalEditionEvidence(
            musicBrainzReleaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
            title = "SUPER REAL ME", creditedArtist = "ILLIT",
            creditedArtistNames = listOf("ILLIT"), status = "Official",
            barcodes = listOf("8809704427821"), trackTitles = listOf("Magnetic"),
            releaseYear = 2024, country = "KR",
        )
        assertTrue(DiscogsAuthenticatedDiscovery(search, editions)
            .findForEdition(canonical) is DiscogsAuthenticatedMatch.Ambiguous)
        assertEquals(listOf("releases/111", "releases/112"), calls)
    }

    @Test fun tokenNeverAppearsInSearchResultAndOnlyReturnedIdsAreHydrated() = runBlocking {
        val search = DiscogsDatabaseSearchClient(
            transport = object : DiscogsSearchTransport {
                override suspend fun search(
                    query: DiscogsSearchRequest, token: String, userAgent: String,
                ): DiscogsHttpResponse =
                    DiscogsHttpResponse(200, """{"results":[
                        {"id":111,"type":"release"},{"id":112,"type":"release"}]}""")
            },
            tokenProvider = { token }, enabled = true,
            limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val paths = mutableListOf<String>()
        val discogs = DiscogsClient(
            transport = object : DiscogsTransport {
                override suspend fun get(path: String, userAgent: String): DiscogsHttpResponse {
                    paths += path
                    return DiscogsHttpResponse(200, """{"id":${path.substringAfter("/")},
                        "title":"SUPER REAL ME","artists":[{"name":"ILLIT"}],
                        "year":2024,"country":"South Korea",
                        "labels":[{"name":"BELIFT LAB","catno":"CMDC12000"}],
                        "identifiers":[{"type":"Barcode","value":"8809704427821"}],
                        "formats":[{"name":"CD","descriptions":["EP"]}],
                        "tracklist":[{"position":"1","title":"Magnetic"}]}""")
                }
            }, enabled = true, limiter = DiscogsRateLimiter(minimumIntervalMs = 0L),
        )
        val canonical = CanonicalEditionEvidence(
            musicBrainzReleaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
            title = "SUPER REAL ME", creditedArtist = "ILLIT",
            creditedArtistNames = listOf("ILLIT"), status = "Official",
            barcodes = listOf("8809704427821"), trackTitles = listOf("Magnetic"),
            country = "KR", releaseYear = 2024,
        )
        val outcome = DiscogsAuthenticatedDiscovery(search, discogs).findForEdition(canonical)
        assertTrue(outcome is DiscogsAuthenticatedMatch.Ambiguous)
        assertEquals(listOf("releases/111", "releases/112"), paths)
        assertFalse(outcome.toString().contains(token))
    }
}
