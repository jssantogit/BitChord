package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.parseMusicBrainzTrackArtistAppearances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzArtistAppearancesTest {
    private val target = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val releaseGroup = "33333333-3333-4333-8333-333333333333"

    @Test fun onlyVerifiedOfficialGuestAppearancesInOtherArtistsReleases() {
        val body = """{"release-count":4,"releases":[
           {"id":"55555555-5555-4555-8555-555555555555","status":"Official",
            "title":"Featured song edition","date":"2026-05-03",
            "artist-credit":[{"artist":{"id":"$other","name":"Other artist"},"name":"Other artist"}],
            "release-group":{"id":"$releaseGroup","title":"Featured collection","primary-type":"Album","first-release-date":"2026-05-01"},
            "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target","name":"Selected artist"}}]}]}]},
           {"id":"66666666-6666-4666-8666-666666666666","status":"Official",
            "artist-credit":[{"artist":{"id":"$other","name":"Other artist"}}],
            "release-group":{"id":"$releaseGroup","title":"Featured collection","primary-type":"Album","first-release-date":"2026-05-01"},
            "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target","name":"Selected artist"}}]}]}]},
           {"id":"77777777-7777-4777-8777-777777777777","status":"Official",
            "artist-credit":[{"artist":{"id":"$target","name":"Selected artist"}}],
            "release-group":{"id":"99999999-9999-4999-8999-999999999999","title":"Own album","primary-type":"Album"}},
           {"id":"88888888-8888-4888-8888-888888888888","status":"Bootleg",
            "artist-credit":[{"artist":{"id":"$other","name":"Other artist"}}],
            "release-group":{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","title":"Bootleg"}}]}"""
        val rows = parseMusicBrainzTrackArtistAppearances(body, target)
        assertEquals(1, rows.size)
        assertEquals(releaseGroup, rows.single().releaseGroupMbid)
        assertEquals("Featured collection", rows.single().title)
        assertEquals("Other artist", rows.single().artistCredit)
    }

    @Test fun incompleteOrUnverifiedBrowseResponsesDoNotInventAppearances() {
        assertTrue(parseMusicBrainzTrackArtistAppearances("""{"releases":[]}""", target).isEmpty())
        assertTrue(parseMusicBrainzTrackArtistAppearances(
            """{"releases":[{"status":"Official","release-group":{"id":"$releaseGroup","title":"No credit"}}]}""",
            target,
        ).isEmpty())
        assertTrue(parseMusicBrainzTrackArtistAppearances(
            """{"releases":[{"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Other"}}],
                "release-group":{"id":"../../invalid","title":"Wrong"}}]}""",
            target,
        ).isEmpty())
    }

    @Test fun doesNotConfuseOtherArtistsCoversWithRealTrackCredits() {
        val body = """{"releases":[
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Other"}}],
           "release-group":{"id":"$releaseGroup","title":"NewJeans Karaoke Remix","primary-type":"Single"},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$other","name":"Other"}}]}]}]},
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Other"}}],
           "release-group":{"id":"44444444-4444-4444-8444-444444444444","title":"Verified featuring","primary-type":"Album"},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target","name":"Selected"}}]}]}]}
        ]}"""
        val rows = parseMusicBrainzTrackArtistAppearances(body, target)
        assertEquals(listOf("Verified featuring"), rows.map { it.title })
        assertTrue(parseMusicBrainzTrackArtistAppearances(
           body.replace("\"media\"", "\"unsupported-media\""), target,
        ).isEmpty())
    }
}
