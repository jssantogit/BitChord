package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearancesClient
import com.music.bitchord.data.catalog.metadata.parseMusicBrainzTrackArtistAppearances
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A page's own twelve-result cap must not discard a distinct credited work
 * before the browse collector can assess how many release groups it found.
 */
class MusicBrainzArtistAppearancePageDiversityTest {
    private val guest = "11111111-1111-4111-8111-111111111111"
    private val host = "22222222-2222-4222-8222-222222222222"
    private val album = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val single = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun guestTracks(count: Int, firstIndex: Int = 1): String =
        (firstIndex until firstIndex + count).joinToString(",") { number ->
            val recording = number.toString(16).padStart(8, '0')
            """{"artist-credit":[{"artist":{"id":"$guest"}}],
                "recording":{"id":"$recording-1111-4111-8111-111111111112",
                    "title":"Guest recording $number"}}"""
        }

    private fun edition(group: String, title: String, releaseId: String,
                        tracks: String, releaseDate: String): String = """
        {"id":"$releaseId","status":"Official","barcode":"0196588123456",
         "artist-credit":[{"artist":{"id":"$host","name":"Host artist"}}],
         "release-group":{"id":"$group","title":"$title",
             "primary-type":"Album","first-release-date":"$releaseDate"},
         "media":[{"tracks":[$tracks]}]}"""

    private fun mixedPage(): String = """
        {"release-count":2,"releases":[
            ${edition(album, "Host album", "55555555-5555-4555-8555-555555555555",
                guestTracks(13), "2026-04-01")},
            ${edition(single, "Host separate single", "66666666-6666-4666-8666-666666666666",
                guestTracks(1, 30), "2025-05-01")}
        ]}"""

    @Test fun parserReservesOneEditorialGuestPerReleaseGroupBeforeTwelveResultCap() {
        val raw = parseMusicBrainzTrackArtistAppearances(mixedPage(), guest, limit = 12)
        assertEquals(12, raw.size)
        assertEquals(setOf(album, single), raw.map { it.releaseGroupMbid }.toSet())
        assertEquals(11, raw.count { it.releaseGroupMbid == album })
        assertEquals(1, raw.count { it.releaseGroupMbid == single })
    }

    @Test fun onePageBrowsePreservesBothGuestWorksEvenWhenFirstHasThirteenSongs() = runBlocking {
        val offsets = mutableListOf<Int>()
        val client = MusicBrainzArtistAppearancesClient(
            pagedFetch = { _, offset ->
                offsets += offset
                if (offset != 0) error("One page already includes both independent works")
                mixedPage()
            },
        )
        assertEquals(setOf(album, single),
            client.browse(guest).map { it.releaseGroupMbid }.toSet())
        assertEquals(listOf(0), offsets)
    }
}
