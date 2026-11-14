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

    @Test fun absentAppleUpcIsRecoveredOnlyAfterExactPublicAppleBarcodeLookup() =
        runBlocking {
            val mbCalls = mutableListOf<String>()
            val appleCalls = mutableListOf<Map<String, String>>()
            val client = MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(path: String, parameters: Map<String, String>,
                        userAgent: String): String {
                        mbCalls += path
                        return when (path) {
                            "release" -> {
                                assertEquals("false", parameters["dismax"])
                                assertEquals(
                                    """release:"SUPER REAL ME" AND artist:"ILLIT" AND status:official""",
                                    parameters["query"],
                                )
                                """{"count":1,"offset":0,"releases":[{
                                  "id":"$editionId","title":"SUPER REAL ME","status":"Official",
                                  "artist-credit":[{"name":"ILLIT",
                                    "artist":{"id":"$artistMbid"}}],
                                  "release-group":{"id":"$groupId","primary-type":"EP"}}]}"""
                            }
                            "release/$editionId" -> """{
                               "id":"$editionId","title":"SUPER REAL ME",
                               "date":"2024-03-25","barcode":"8801234567890",
                               "status":"Official","release-group":{"id":"$groupId"},
                               "artist-credit":[{"name":"ILLIT",
                                   "artist":{"id":"$artistMbid"}}],
                               "media":[{"position":1,"track-count":1,
                                   "tracks":[{"id":"$trackMbid","title":"Magnetic",
                                       "position":1,"recording":{"id":"$recordingMbid",
                                           "title":"Magnetic"}}]}]}"""
                            "release-group/$groupId" -> """{"id":"$groupId",
                               "title":"SUPER REAL ME","primary-type":"EP",
                               "artist-credit":[{"name":"ILLIT"}]}"""
                            else -> error("Unexpected MusicBrainz request: $path")
                        }
                    }
                },
                rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
            )
            val appleClient = ItunesCatalogClient(object : ItunesCatalogTransport {
                override suspend fun get(path: String, parameters: Map<String, String>): String {
                    assertEquals("lookup", path)
                    appleCalls += parameters
                    return """{"resultCount":2,"results":[
                      {"wrapperType":"collection","collectionType":"Album",
                       "collectionId":101,"collectionName":"SUPER REAL ME",
                       "artistName":"ILLIT","artistId":99,"trackCount":1},
                      {"wrapperType":"track","kind":"song","trackId":201,
                       "collectionId":101,"trackName":"Magnetic",
                       "artistName":"ILLIT","discNumber":1,"trackNumber":1}
                    ]}"""
                }
            })
            val source = MusicBrainzBarcodeEditionEvidenceSource(
                MusicBrainzCatalogSearch(client), MusicBrainzCatalogGraph(client),
                AppleUpcReverseLookupVerifier(appleClient),
            )
            val withoutUpc = album.copy(upc = null)
            val discovered = source.discover(withoutUpc)
            assertEquals("8801234567890", discovered?.verifiedAppleUpc)
            assertEquals(listOf(editionId), discovered?.editions?.map { it.musicBrainzId })
            assertEquals(listOf("EP"), discovered?.groups?.map { it.primaryType })
            assertEquals(listOf("release", "release/$editionId",
                "release-group/$groupId"), mbCalls)
            assertEquals(1, appleCalls.size)
            assertEquals("8801234567890", appleCalls.single()["upc"])
            assertEquals("br", appleCalls.single()["country"])
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

    @Test fun barcodeCandidateOnSecondPageIsReadOnlyAfterCompleteMbSearch() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client = MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String {
                        calls += if (path == "release") {
                            "$path:${parameters["offset"]}"
                        } else path
                        return when (path) {
                            "release" -> {
                                assertEquals("barcode:8801234567890", parameters["query"])
                                assertEquals("12", parameters["limit"])
                                assertEquals("false", parameters["dismax"])
                                val offset = parameters["offset"]!!.toInt()
                                if (offset == 0) {
                                    val unrelated = (1..12).joinToString(",") { index ->
                                        val id = "00000000-0000-4000-8000-" +
                                            index.toString().padStart(12, '0')
                                        """{"id":"$id","title":"Unrelated edition $index",
                                           "status":"Official","artist-credit":[{
                                           "name":"ILLIT","artist":{"id":"$artistMbid"}}]}"""
                                    }
                                    """{"count":13,"offset":0,"releases":[$unrelated]}"""
                                } else {
                                    assertEquals(12, offset)
                                    """{"count":13,"offset":12,"releases":[{
                                      "id":"$editionId","title":"SUPER REAL ME",
                                      "status":"Official","artist-credit":[{"name":"ILLIT",
                                      "artist":{"id":"$artistMbid"}}],
                                      "release-group":{"id":"$groupId","primary-type":"EP"}}]}"""
                                }
                            }
                            "release/$editionId" -> """{
                              "id":"$editionId","title":"SUPER REAL ME",
                              "date":"2024-03-25","status":"Official",
                              "barcode":"8801234567890","release-group":{"id":"$groupId"},
                              "artist-credit":[{"name":"ILLIT",
                                "artist":{"id":"$artistMbid"}}],
                              "media":[{"position":1,"track-count":1,"tracks":[{
                                "id":"$trackMbid","position":1,"title":"Magnetic",
                                "recording":{"id":"$recordingMbid","title":"Magnetic"}}]}]}"""
                            "release-group/$groupId" -> """{
                              "id":"$groupId","title":"SUPER REAL ME",
                              "primary-type":"EP","artist-credit":[{"name":"ILLIT"}]}"""
                            else -> error("Unexpected graph query: $path")
                        }
                    }
                },
                rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
            )
            val source = MusicBrainzBarcodeEditionEvidenceSource(
                MusicBrainzCatalogSearch(client), MusicBrainzCatalogGraph(client),
            )
            val discovered = source.discover(album)
            assertEquals(listOf(editionId), discovered?.editions?.map { it.musicBrainzId })
            assertEquals(listOf("EP"), discovered?.groups?.map { it.primaryType })
            assertEquals(listOf(
                "release:0", "release:12", "release/$editionId",
                "release-group/$groupId",
            ), calls)
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
