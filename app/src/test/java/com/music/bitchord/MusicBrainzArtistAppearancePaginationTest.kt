package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearancesClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Release pagination must count distinct works, not alternate editions of a recording. */
class MusicBrainzArtistAppearancePaginationTest {
    private val guest = "11111111-1111-4111-8111-111111111111"
    private val host = "22222222-2222-4222-8222-222222222222"
    private val groupA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val groupB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun official(index: Int, group: String): String {
        val id = index.toString(16).padStart(8, '0')
        return """{"id":"$id-5555-4555-8555-555555555555",
            "status":"Official","barcode":"0196588123456",
            "artist-credit":[{"artist":{"id":"$host","name":"Host"}}],
            "release-group":{"id":"$group","title":"Guest work $group","primary-type":"Single"},
            "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$guest"}}],
                "recording":{"id":"11111111-1111-4111-8111-111111111112",
                             "title":"Featured track"}}]}]}"""
    }

    private fun bootlegs(count: Int): String =
        (1..count).joinToString(",") { """{"status":"Bootleg"}""" }

    @Test fun firstVerifiedGuestDoesNotHideGuestOnNextFullBrowsePage() = runBlocking {
        val seen = mutableListOf<Int>()
        val client = MusicBrainzArtistAppearancesClient(
            pagedFetch = { _, offset ->
                seen += offset
                when (offset) {
                    0 -> """{"release-count":50,"releases":[${official(1, groupA)},${bootlegs(24)}]}"""
                    25 -> """{"release-count":50,"releases":[${official(2, groupB)},${bootlegs(24)}]}"""
                    else -> error("No third request when two distinct guest works are found")
                }
            },
        )
        assertEquals(listOf(groupA, groupB), client.browse(guest).map { it.releaseGroupMbid })
        assertEquals(listOf(0, 25), seen)
        client.browse(guest)
        assertEquals(listOf(0, 25), seen) // cache avoids redundant queries
    }

    @Test fun repeatedEditionsAreNotMistakenForMultipleGuestWorks() = runBlocking {
        val seen = mutableListOf<Int>()
        val client = MusicBrainzArtistAppearancesClient(
            pagedFetch = { _, offset ->
                seen += offset
                val editions = when (offset) {
                    0, 25 -> (offset + 1..offset + 25)
                        .joinToString(",") { official(it, groupA) }
                    50 -> official(51, groupB)
                    else -> error("Exceeded existing three-page limit")
                }
                """{"release-count":51,"releases":[$editions]}"""
            },
        )
        assertEquals(listOf(groupA, groupB),
            client.browse(guest).map { it.releaseGroupMbid })
        assertEquals(listOf(0, 25, 50), seen)
    }

    @Test fun twoDistinctWorksOnFirstPageMustAvoidMoreRequests() = runBlocking {
        val seen = mutableListOf<Int>()
        val client = MusicBrainzArtistAppearancesClient(
            pagedFetch = { _, offset ->
                seen += offset
                if (offset != 0) error("Two verified works already cover the browse budget")
                """{"release-count":75,"releases":[${official(1, groupA)},${official(2, groupB)},${bootlegs(23)}]}"""
            },
        )
        assertEquals(listOf(groupA, groupB),
            client.browse(guest).map { it.releaseGroupMbid })
        assertEquals(listOf(0), seen)
    }
}
