package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.parseMusicBrainzTrackArtistAppearances
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearancesClient
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistAppearance
import com.music.bitchord.ui.screens.nativeArtistIncludedInCoverTargets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzArtistAppearancesTest {
    private val target = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val releaseGroup = "33333333-3333-4333-8333-333333333333"

    @Test fun albumGuestAppearancePublishesOnlyTheCreditedRecordingNotWholeAlbum() {
        val wantedRecording = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val unrelatedRecording = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val body = """{"releases":[{
            "id":"55555555-5555-4555-8555-555555555555","status":"Official",
            "barcode":"0196588123456",
            "artist-credit":[{"artist":{"id":"$other","name":"Jon Batiste"}}],
            "release-group":{"id":"$releaseGroup",
                "title":"World Music Radio","primary-type":"Album"},
            "media":[{"tracks":[
                {"title":"Unrelated album track","recording":{
                    "id":"$unrelatedRecording","title":"Unrelated album track",
                    "artist-credit":[{"artist":{"id":"$other"}}]}},
                {"title":"Guest-feature song","recording":{
                    "id":"$wantedRecording","title":"Guest-feature song",
                    "artist-credit":[{"artist":{"id":"$other"}},
                        {"artist":{"id":"$target","name":"NewJeans"}}]}}
            ]}
        ]}]}"""
        val appearances = parseMusicBrainzTrackArtistAppearances(body, target)
        val visible = com.music.bitchord.ui.screens.nativeArtistEditorialGuestTracks(appearances)
        assertEquals(1, visible.size)
        assertEquals("Guest-feature song", visible.single().trackTitle)
        assertEquals(wantedRecording, visible.single().recordingMbid)
        assertEquals("World Music Radio", visible.single().title)
    }

    @Test fun fakeOfficialStatusWithoutCommercialProofCannotEnterGuestShelf() {
        val recording = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val raw = """{"releases":[{
            "id":"55555555-5555-4555-8555-555555555555","status":"Official",
            "artist-credit":[{"artist":{"id":"$other","name":"2AT"}}],
            "release-group":{"id":"$releaseGroup",
                "title":"SUPER ARRUNSHAI","primary-type":"Single"},
            "media":[{"tracks":[{"recording":{"id":"$recording",
                "title":"SUPER ARRUNSHAI",
                "artist-credit":[{"artist":{"id":"$target"}}]}}]}]
        }]}"""
        assertTrue(com.music.bitchord.ui.screens.nativeArtistEditorialGuestTracks(
            parseMusicBrainzTrackArtistAppearances(raw, target)).isEmpty())
        assertEquals(1, com.music.bitchord.ui.screens.nativeArtistEditorialGuestTracks(
            parseMusicBrainzTrackArtistAppearances(
                raw.replace("\"status\":\"Official\",",
                    "\"status\":\"Official\",\"barcode\":\"1234567890123\","),
                target,
            )).size)
    }

    @Test fun genuineDigitalGuestCanBeCorroboratedByRecordingIsrcWithoutBarcode() {
        val recording = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val raw = """{"releases":[{
            "id":"55555555-5555-4555-8555-555555555555","status":"Official",
            "artist-credit":[{"artist":{"id":"$other","name":"Official artist"}}],
            "release-group":{"id":"$releaseGroup","title":"Digital single","primary-type":"Single"},
            "media":[{"tracks":[{"recording":{
                "id":"$recording","title":"Featured recording",
                "isrcs":["USAAA2600123"],
                "artist-credit":[{"artist":{"id":"$target"}}]}}]}]
        }]}"""
        assertEquals(listOf(recording),
            com.music.bitchord.ui.screens.nativeArtistEditorialGuestTracks(
                parseMusicBrainzTrackArtistAppearances(raw, target),
            ).map { it.recordingMbid })
    }

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
        assertEquals("55555555-5555-4555-8555-555555555555", rows.single().releaseMbid)
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
    @Test fun officialStatusDoesNotAdmitRemixCompilationsOrMixtapes() {
        val body = """{"releases":[
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Other"}}],
           "release-group":{"id":"$releaseGroup","title":"User remixes","primary-type":"Album",
             "secondary-types":["Compilation","Remix"]},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target"}}]}]}]},
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Other"}}],
           "release-group":{"id":"44444444-4444-4444-8444-444444444444",
             "title":"Genuine studio feature","primary-type":"Album"},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target"}}]}]}]}
        ]}"""
        assertEquals(listOf("Genuine studio feature"),
            parseMusicBrainzTrackArtistAppearances(body, target).map { it.title })
    }

    @Test fun scrolledArtistAppearanceShelfUsesCachedProofAndExpires() = runBlocking {
        var now = 1_000L
        var requests = 0
        val valid = """{"releases":[
            {"id":"55555555-5555-4555-8555-555555555555","status":"Official","barcode":"0123456789012",
             "artist-credit":[{"artist":{"id":"$other","name":"Other artist"}}],
             "release-group":{"id":"$releaseGroup","title":"Real guest credit","primary-type":"Album"},
             "media":[{"tracks":[{"recording":{
                 "id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                 "title":"Featured song",
                 "artist-credit":[{"artist":{"id":"$target","name":"Guest"}}]}}]}]}
        ]}"""
        val client = MusicBrainzArtistAppearancesClient(
            nowMillis = { now },
            fetch = { requests++; valid },
        )
        assertEquals(1, client.browse(target).size)
        assertEquals(1, client.browse(target).size)
        now += 60_000L
        assertEquals(1, client.browse(target).size)
        assertEquals(1, requests)
        now += 6L * 60L * 60L * 1_000L + 1L
        assertEquals(1, client.browse(target).size)
        assertEquals(2, requests)
    }

    @Test fun failedGuestLookupIsThrottledButMayRecoverWithoutRestart() = runBlocking {
        var now = 10_000L
        var calls = 0
        val client = MusicBrainzArtistAppearancesClient(
            nowMillis = { now },
            fetch = { calls++; if (calls == 1) throw java.io.IOException("offline") else
                """{"releases":[]}"""
            },
        )
        assertTrue(client.browse(target).isEmpty())
        now += 30_000
        assertTrue(client.browse(target).isEmpty())
        assertEquals(1, calls)
        now += 91_000
        assertTrue(client.browse(target).isEmpty())
        assertEquals(2, calls)
    }

    @Test fun userEditsAndAnonymousVariousArtistsMustNotBecomeEditorialGuestCredits() {
        val body = """{"releases":[
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Remixer"}}],
           "release-group":{"id":"$releaseGroup",
             "title":"Magnetic (Judah UKG Edit)","primary-type":"Single"},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target"}}]}]}]},
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Various Artists"}}],
           "release-group":{"id":"44444444-4444-4444-8444-444444444444",
             "title":"TV anime compilation","primary-type":"Album"},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target"}}]}]}]},
          {"status":"Official","artist-credit":[{"artist":{"id":"$other","name":"Label artist"}}],
           "release-group":{"id":"66666666-6666-4666-8666-666666666666",
             "title":"Verified original collaboration","primary-type":"Single"},
           "media":[{"tracks":[{"artist-credit":[{"artist":{"id":"$target"}}]}]}]}
        ]}"""
        assertEquals(listOf("Verified original collaboration"),
            parseMusicBrainzTrackArtistAppearances(body, target).map { it.title })
    }


    @Test fun includedInCoversCanUseExactVerifiedGroupWhenEditionMissing() {
        val entries = (1..14).map { number ->
            MusicBrainzArtistAppearance(
                releaseGroupMbid = number.toString(16).padStart(8, '0') +
                    "-3333-4333-8333-333333333333",
                title = "Official feature $number",
                artistCredit = "Other artist",
                releaseDate = null,
                primaryType = "Single",
                releaseMbid = if (number == 1) null else
                    number.toString(16).padStart(8, '0') + "-5555-4555-8555-555555555555",
            )
        }
        val targets = nativeArtistIncludedInCoverTargets(entries)
        assertEquals(10, targets.size)
        assertEquals(null, targets.first().first)
        assertEquals(entries.first().releaseGroupMbid, targets.first().second)
        assertEquals(entries[1].releaseMbid, targets[1].first)
    }


    @Test fun weakFanReleaseOnFirstPageCannotPreventLaterCommercialFeature() = runBlocking {
        val offsets = mutableListOf<Int>()
        val fan = (1..25).joinToString(",") { i ->
            val group = i.toString(16).padStart(8, '0') + "-3333-4333-8333-333333333333"
            """{"status":"Official",
                "artist-credit":[{"artist":{"id":"$other","name":"2AT"}}],
                "release-group":{"id":"$group","title":"Fan song $i","primary-type":"Single"},
                "media":[{"tracks":[{"recording":{
                    "id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    "title":"Fan song $i",
                    "artist-credit":[{"artist":{"id":"$target"}}]}}]}]}"""
        }
        val official = """{"id":"55555555-5555-4555-8555-555555555555",
            "status":"Official","barcode":"0196588123456",
            "artist-credit":[{"artist":{"id":"$other","name":"Jon Batiste"}}],
            "release-group":{"id":"$releaseGroup","title":"World Music Radio",
                "primary-type":"Album"},
            "media":[{"tracks":[{"recording":{
                "id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "title":"Genuine Guest Track",
                "artist-credit":[{"artist":{"id":"$target"}}]}}]}]}"""
        val client = MusicBrainzArtistAppearancesClient(
            pagedFetch = { _, offset ->
                offsets += offset
                if (offset == 0) """{"release-count":26,"releases":[$fan]}"""
                else """{"release-count":26,"releases":[$official]}"""
            },
        )
        val found = client.browse(target)
        assertEquals(listOf("Genuine Guest Track"), found.map { it.trackTitle })
        assertEquals(listOf(0, 25), offsets)
    }

    @Test fun popularArtistsScanNextOfficialPageWhenFirstIsOnlyUnrelatedEditions() = runBlocking {
        val requestedOffsets = mutableListOf<Int>()
        val rejected = (1..25).joinToString(",") { n ->
            """{"id":"${n.toString(16).padStart(8, '0')}-3333-4333-8333-333333333333",
                "status":"Bootleg"}"""
        }
        val valid = """{"id":"55555555-5555-4555-8555-555555555555",
            "status":"Official","barcode":"0123456789012",
            "artist-credit":[{"artist":{"id":"$other","name":"Other artist"}}],
            "release-group":{"id":"$releaseGroup","title":"Real guest track","primary-type":"Single"},
            "media":[{"tracks":[{"recording":{
                "id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "title":"Real guest track",
                "artist-credit":[{"artist":{"id":"$target"}}]}}]}]}"""
        val client = MusicBrainzArtistAppearancesClient(
            pagedFetch = { _, offset ->
                requestedOffsets += offset
                if (offset == 0) """{"release-count":26,"releases":[$rejected]}"""
                else """{"release-count":26,"releases":[$valid]}"""
            },
        )
        assertEquals(listOf("Real guest track"), client.browse(target).map { it.title })
        assertEquals(listOf(0, 25), requestedOffsets)
    }


    @Test fun linkedRecordingArtistCreditCanProveGuestWhenTrackCreditIsAbsent() {
        val body = """{"releases":[
          {"id":"55555555-5555-4555-8555-555555555555",
           "status":"Official",
           "artist-credit":[{"artist":{"id":"$other","name":"Collaborator"}}],
           "release-group":{"id":"$releaseGroup","title":"Official collaboration",
               "primary-type":"Single"},
           "media":[{"tracks":[{"recording":{
             "id":"88888888-8888-4888-8888-888888888888",
             "artist-credit":[{"artist":{"id":"$target","name":"BTS"}}]}}]}]}
        ]}"""
        assertEquals(listOf("Official collaboration"),
            parseMusicBrainzTrackArtistAppearances(body, target).map { it.title })
        // No Recording MBID means there is no proven ReleaseTrack ->
        // Recording relationship; unscoped embedded credits cannot be used.
        assertTrue(parseMusicBrainzTrackArtistAppearances(
            body.replace(
                """"id":"88888888-8888-4888-8888-888888888888",""",
                """"id":"invalid",""",
            ), target,
        ).isEmpty())
    }


    @Test fun realGuestRecordingCreditSurvivesTrackCreditWithOnlyLeadArtist() {
        val body = """{"releases":[{
          "id":"55555555-5555-4555-8555-555555555555",
          "status":"Official",
          "artist-credit":[{"artist":{"id":"$other","name":"Other artist"}}],
          "release-group":{"id":"$releaseGroup","title":"Label collaboration",
              "primary-type":"Single"},
          "media":[{"tracks":[{
             "artist-credit":[{"artist":{"id":"$other","name":"Other artist"}}],
             "recording":{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
               "artist-credit":[{"artist":{"id":"$other"}},
                                 {"artist":{"id":"$target"}}]}
          }]}]
        }]}"""
        assertEquals(listOf("Label collaboration"),
            parseMusicBrainzTrackArtistAppearances(body, target).map { it.title })
    }

}
