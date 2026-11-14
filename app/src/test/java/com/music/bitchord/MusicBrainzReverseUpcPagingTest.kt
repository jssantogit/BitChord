package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicBrainzReverseUpcPagingTest {
    private val id = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val artist = "11111111-1111-4111-8111-111111111111"
    private val track = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val recording = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val album = ItunesEditorialCatalog.assemble("br", listOf(
        ItunesCatalogItem(
            ItunesCatalogItemType.ALBUM, 101, "br", "SUPER REAL ME", "ILLIT",
            99, 101, null, "2024-03-25", null, null, null, null, trackCount = 1,
        ),
        ItunesCatalogItem(
            ItunesCatalogItemType.TRACK, 201, "br", "Magnetic", "ILLIT",
            99, 101, null, null, null, null, 1, 1,
        ),
    )).single()

    @Test fun reverseUpcCanReachEditionOnThirdCompleteMbPageBeforeOneAppleLookup() =
        runBlocking {
            val calls = mutableListOf<String>()
            val sourceClient = MusicBrainzClient(
                transport = object : MusicBrainzTransport {
                    override suspend fun get(
                        path: String, parameters: Map<String, String>, userAgent: String,
                    ): String {
                        val offset = parameters["offset"]
                        calls += if (offset == null) path else "$path:$offset"
                        return when (path) {
                            "release" -> {
                                assertEquals(
                                    """release:"SUPER REAL ME" AND artist:"ILLIT" AND status:official""",
                                    parameters["query"],
                                )
                                val start = offset!!.toInt()
                                val sourceRows = (start until minOf(start + 12, 25))
                                    .joinToString(",") { idx ->
                                        val target = idx == 24
                                        val mbid = if (target) id else
                                            "00000000-0000-4000-8000-" +
                                                idx.toString().padStart(12, '0')
                                        """{"id":"$mbid",
                                          "title":"${if (target) "SUPER REAL ME" else "Other $idx"}",
                                          "status":"Official","artist-credit":[{"name":"ILLIT",
                                          "artist":{"id":"$artist"}}],
                                          "release-group":{"id":"$group","primary-type":"EP"}}"""
                                    }
                                """{"count":25,"offset":$start,"releases":[$sourceRows]}"""
                            }
                            "release/$id" -> """{
                                "id":"$id","title":"SUPER REAL ME","status":"Official",
                                "date":"2024-03-25","barcode":"8801234567890",
                                "release-group":{"id":"$group"},
                                "artist-credit":[{"name":"ILLIT",
                                   "artist":{"id":"$artist"}}],
                                "media":[{"position":1,"track-count":1,"tracks":[{
                                   "id":"$track","position":1,"title":"Magnetic",
                                   "recording":{"id":"$recording","title":"Magnetic"}}]}]}"""
                            "release-group/$group" -> """{
                                "id":"$group","title":"SUPER REAL ME","primary-type":"EP",
                                "artist-credit":[{"name":"ILLIT"}]}"""
                            else -> error("Unexpected MusicBrainz path: $path")
                        }
                    }
                }, rateLimiter = MusicBrainzRateLimiter(delayMillis = {}),
            )
            var appleCount = 0
            val apple = ItunesCatalogClient(object : ItunesCatalogTransport {
                override suspend fun get(
                    path: String, parameters: Map<String, String>,
                ): String {
                    appleCount++
                    assertEquals("lookup", path)
                    assertEquals("8801234567890", parameters["upc"])
                    return """{"resultCount":2,"results":[{
                        "wrapperType":"collection","collectionType":"Album",
                        "collectionId":101,"artistId":99,"artistName":"ILLIT",
                        "collectionName":"SUPER REAL ME","trackCount":1},{
                        "wrapperType":"track","kind":"song","trackId":201,
                        "collectionId":101,"trackName":"Magnetic",
                        "artistName":"ILLIT","discNumber":1,"trackNumber":1}]}"""
                }
            })
            val source = MusicBrainzBarcodeEditionEvidenceSource(
                MusicBrainzCatalogSearch(sourceClient),
                MusicBrainzCatalogGraph(sourceClient),
                AppleUpcReverseLookupVerifier(apple),
            )
            val evidence = source.discover(album)
            assertEquals("8801234567890", evidence?.verifiedAppleUpc)
            assertEquals(listOf(id), evidence?.editions?.map { it.musicBrainzId })
            assertEquals(1, appleCount)
            assertEquals(listOf(
                "release:0", "release:12", "release:24",
                "release/$id", "release-group/$group",
            ), calls)
        }
}
