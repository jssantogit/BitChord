package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.isCuratedMusicBrainzRelease
import com.music.bitchord.ui.canonicalCachedOfficialGroupTracklist
import com.music.bitchord.ui.canonicalPreferredEditions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalPreferredVideoEditionTest {
    private val group = "23d1cd75-8dc5-436d-a0c6-cab894dd5f64"
    private val video = "11111111-1111-4111-8111-111111111111"
    private val audio = "22222222-2222-4222-8222-222222222222"

    private fun edition(
        mbid: String, videoOnly: Boolean?,
    ) = MusicBrainzReleaseHit(
        musicBrainzId = mbid, title = "OMG", country = "KR",
        releaseDate = "2023-01-02", artistDisplay = "NewJeans",
        status = "Official", releaseGroupMbid = group,
        isVideoOnlyMedia = videoOnly,
    )

    @Test fun officialVideoOnlyEditionNeverDisplacesAnAudioSingle() {
        val videoEdition = edition(video, true)
        val audioEdition = edition(audio, false)
        assertFalse(isCuratedMusicBrainzRelease(videoEdition))
        assertTrue(isCuratedMusicBrainzRelease(audioEdition))
        assertEquals(listOf(audio), canonicalPreferredEditions(
            listOf(videoEdition, audioEdition), "OMG",
        ).map { it.musicBrainzId })
        assertEquals(emptyList<MusicBrainzReleaseHit>(), canonicalPreferredEditions(
            listOf(videoEdition), "OMG",
        ))
        // Unknown format is not equivalent to a proven video release.
        assertEquals(listOf(audio), canonicalPreferredEditions(
            listOf(edition(audio, null)), "OMG",
        ).map { it.musicBrainzId })
    }

    @Test fun confirmedVideoOnlyCachedTrackGraphCannotBecomeDefaultAudio() = runBlocking {
        val stubTrack = MusicBrainzCatalogTrack(
            trackMbid = "33333333-3333-4333-8333-333333333333",
            recordingMbid = "44444444-4444-4444-8444-444444444444",
            title = "OMG", mediumNumber = 1, trackNumber = 1,
            durationMs = 200000,
        )
        fun detail(mbid: String, videoOnly: Boolean) =
            MusicBrainzCatalogReleaseDetail(
                musicBrainzId = mbid, groupMbid = group, title = "OMG",
                artistDisplay = "NewJeans", country = "KR",
                releaseDate = "2023-01-02", tracks = listOf(stubTrack),
                isVideoOnlyContent = videoOnly,
            )
        val visited = mutableListOf<String>()
        val selected = canonicalCachedOfficialGroupTracklist(
            group, "OMG", listOf(edition(video, null), edition(audio, null)),
        ) { mbid ->
            visited += mbid
            if (mbid == video) detail(video, true) else detail(audio, false)
        }
        assertEquals(audio, selected?.musicBrainzId)
        assertTrue(video in visited)
        assertTrue(audio in visited)
        assertNull(canonicalCachedOfficialGroupTracklist(
            group, "OMG", listOf(edition(video, null)),
        ) { detail(it, true) })
    }
}
