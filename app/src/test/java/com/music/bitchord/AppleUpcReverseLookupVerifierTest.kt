package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Public Apple UPC lookup proves a candidate barcode belongs to THIS collection. */
class AppleUpcReverseLookupVerifierTest {
    private val upc = "8801234567890"
    private val album = ItunesEditorialCatalog.assemble("br", listOf(
        ItunesCatalogItem(
            type = ItunesCatalogItemType.ALBUM, id = 101,
            storefront = "br", title = "SUPER REAL ME",
            artistName = "ILLIT", artistId = 99, collectionId = 101,
            storeUrl = null, releaseDate = "2024-03-25",
            genre = "K-Pop", durationMs = null,
            trackNumber = null, discNumber = null,
            trackCount = 2, upc = null,
        ),
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK, id = 201, storefront = "br",
            title = "Magnetic", artistName = "ILLIT", artistId = 99,
            collectionId = 101, storeUrl = null, releaseDate = null,
            genre = null, durationMs = 120000, trackNumber = 1, discNumber = 1,
        ),
        ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK, id = 202, storefront = "br",
            title = "Lucky Girl Syndrome", artistName = "ILLIT", artistId = 99,
            collectionId = 101, storeUrl = null, releaseDate = null,
            genre = null, durationMs = 118000, trackNumber = 2, discNumber = 1,
        ),
    )).single()

    private fun returnedAlbum(
        id: Long = 101, artistId: Long = 99, title: String = "SUPER REAL ME",
        returnedUpc: String? = null,
    ) = """{"wrapperType":"collection","collectionType":"Album",
        "collectionId":$id,"artistId":$artistId,"artistName":"ILLIT",
        "collectionName":"$title","trackCount":2
        ${returnedUpc?.let { ""","upc":"$it" """ } ?: ""}}"""

    private fun track(
        id: Long, title: String, position: Int,
        collectionId: Long = 101,
    ) = """{"wrapperType":"track","kind":"song",
        "trackId":$id,"collectionId":$collectionId,
        "trackName":"$title","artistName":"ILLIT",
        "artistId":99,"discNumber":1,"trackNumber":$position}"""

    private suspend fun lookup(rows: List<String>, count: Int = rows.size,
        barcode: String = upc,
    ): Pair<Boolean, Map<String,String>> {
        var captured = emptyMap<String,String>()
        val client = ItunesCatalogClient(object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String,String>): String {
                assertEquals("lookup", path)
                captured = parameters
                return """{"resultCount":$count,"results":[${rows.joinToString(",")}]}"""
            }
        })
        return AppleUpcReverseLookupVerifier(client).verifies(album, barcode) to captured
    }

    @Test fun exactAppleCollectionArtistAndCompleteTrackIdentityProveCandidateUpc() =
        runBlocking {
            val (verified, params) = lookup(listOf(
                returnedAlbum(), track(201, "Magnetic", 1),
                track(202, "Lucky Girl Syndrome", 2),
            ))
            assertTrue(verified)
            assertEquals(upc, params["upc"])
            assertEquals("br", params["country"])
            assertEquals("song", params["entity"])
            assertEquals("200", params["limit"])
        }

    @Test fun reusedUpcWrongStoreProductAndArtistNeverProveIdentity() = runBlocking {
        val correctTracks = listOf(
            track(201, "Magnetic", 1), track(202, "Lucky Girl Syndrome", 2),
        )
        assertFalse(lookup(listOf(returnedAlbum(id = 400)) + correctTracks).first)
        assertFalse(lookup(listOf(returnedAlbum(artistId = 100)) + correctTracks).first)
        assertFalse(lookup(listOf(returnedAlbum(title = "Other Version")) +
            correctTracks).first)
        assertFalse(lookup(listOf(returnedAlbum(returnedUpc = "1111111111111")) +
            correctTracks).first)
        assertFalse(lookup(listOf(returnedAlbum(), returnedAlbum(id = 400)) +
            correctTracks).first)
    }

    @Test fun incompleteAndContradictoryTrackEvidenceFailsClosed() = runBlocking {
        assertFalse(lookup(listOf(returnedAlbum(), track(201, "Magnetic", 1))).first)
        assertFalse(lookup(listOf(returnedAlbum(),
            track(201, "Magnetic", 1),
            track(202, "Different Recording", 2))).first)
        assertFalse(lookup(listOf(returnedAlbum(),
            track(201, "Magnetic", 2),
            track(202, "Lucky Girl Syndrome", 1))).first)
        assertFalse(lookup(listOf(returnedAlbum(),
            track(201, "Magnetic", 1),
            track(202, "Lucky Girl Syndrome", 2, collectionId = 222))).first)
        // Complete response guarantee: count > parsed count must not be certified.
        assertFalse(lookup(listOf(returnedAlbum(),
            track(201, "Magnetic", 1),
            track(202, "Lucky Girl Syndrome", 2)), count = 4).first)
    }

    @Test fun invalidBarcodeNeverReachesApple() = runBlocking {
        var called = false
        val client = ItunesCatalogClient(object : ItunesCatalogTransport {
            override suspend fun get(path: String, parameters: Map<String,String>): String {
                called = true
                error("Unsafe lookup")
            }
        })
        assertFalse(AppleUpcReverseLookupVerifier(client).verifies(album, "123 OR foo:*"))
        assertFalse(called)
    }
}
