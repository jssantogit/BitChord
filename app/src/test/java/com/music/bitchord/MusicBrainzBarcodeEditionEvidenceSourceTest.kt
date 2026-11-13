package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicBrainzBarcodeEditionEvidenceSourceTest {
    private val editionId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val groupId = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val artistMbid = "11111111-1111-4111-8111-111111111111"
    private val trackMbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val recordingMbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"

    private val album = ItunesEditorialCatalog.assemble("br", listOf(
        ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = 101, storefront = "br",
            title = "SUPER REAL ME", artistName = "ILLIT", artistId = 99,
            collectionId = 101, storeUrl = null, releaseDate = "2024-03-25",
            genre = null, durationMs = null, trackNumber = null, discNumber = null,
            upc = "8801234567890", trackCount = 1,
        ),
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK, id = 201, storefront = "br",
            title = "Magnetic", artistName = "ILLIT", artistId = 99,
            collectionId = 101, storeUrl = null, releaseDate = null,
            genre = null, durationMs = null, trackNumber = 1, discNumber = 1,
        ),
    )).single()

    @Test fun exactBarcodeAndCompleteEditionGraphSupplyOnlyReleaseGroupFacts() =
        runBlocking {
            val calls = mutableListOf<String>()
            val transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    calls += path
                    return when (path) {
                        "release" -> {
                            assertEquals("barcode:8801234567890", parameters["query"])
                            assertEquals("false", parameters["dismax"])
                            """{"count":1,"offset":0,"releases":[{
                                "id":"$editionId","title":"SUPER REAL ME","status":"Official",
                                "artist-credit":[{"name":"ILLIT",
                                  "artist":{"id":"$artistMbid"}}],
                                "release-group":{"id":"$groupId","primary-type":"EP"}}]}"""
                        }
                        "release/$editionId" -> """{
                            "id":"$editionId","title":"SUPER REAL ME",
                            "date":"2024-03-25","status":"Official","barcode":"8801234567890",
                            "release-group":{"id":"$groupId"},
                            "artist-credit":[{"name":"ILLIT",
                              "artist":{"id":"$artistMbid"}}],
                            "media":[{"position":1,"track-count":1,
                                "tracks":[{"id":"$trackMbid","position":1,
                                   "title":"Magnetic","recording":{
                                       "id":"$recordingMbid","title":"Magnetic"}}]}]}"""
                        "release-group/$groupId" -> """{
                            "id":"$groupId","title":"SUPER REAL ME",
                            "primary-type":"EP",
                            "artist-credit":[{"name":"ILLIT"}]}"""
                        else -> error("Unexpected MusicBrainz path: $path")
                    }
                }
            }
            val client = MusicBrainzClient(
                transport = transport,
                rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
            )
            val source = MusicBrainzBarcodeEditionEvidenceSource(
                MusicBrainzCatalogSearch(client), MusicBrainzCatalogGraph(client),
            )
            val result = source.discover(album)
            assertNotNull(result)
            assertEquals(listOf(editionId), result?.editions?.map { it.musicBrainzId })
            assertEquals(listOf("EP"), result?.groups?.map { it.primaryType })
            assertEquals(listOf("release", "release/$editionId",
                "release-group/$groupId"), calls)
        }

    @Test fun cappedBarcodeSearchMustNotPickFirstMatchingEdition() = runBlocking {
        val calls = mutableListOf<String>()
        val sourceClient = MusicBrainzClient(
            transport = object : MusicBrainzTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>, userAgent: String,
                ): String {
                    calls += path
                    return """{"count":30,"offset":0,"releases":[{
                        "id":"$editionId","title":"SUPER REAL ME","status":"Official",
                        "artist-credit":[{"name":"ILLIT",
                            "artist":{"id":"$artistMbid"}}]}]}"""
                }
            },
            rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
        )
        val source = MusicBrainzBarcodeEditionEvidenceSource(
            MusicBrainzCatalogSearch(sourceClient),
            MusicBrainzCatalogGraph(sourceClient),
        )
        assertNull(source.discover(album))
        assertEquals(listOf("release"), calls)
    }

    @Test fun incompleteArtistCreditInBarcodeSearchFailsBeforeGraphLookup() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client = MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(path: String, parameters: Map<String, String>,
                        userAgent: String): String {
                        calls += path
                        return """{"count":1,"offset":0,"releases":[{
                            "id":"$editionId","title":"SUPER REAL ME","status":"Official",
                            "artist-credit":[{"name":"ILLIT"}]}]}"""
                    }
                }, rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
            )
            assertNull(MusicBrainzBarcodeEditionEvidenceSource(
                MusicBrainzCatalogSearch(client), MusicBrainzCatalogGraph(client),
            ).discover(album))
            assertEquals(listOf("release"), calls)
        }
}
