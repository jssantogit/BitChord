package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseHit
import com.music.bitchord.data.catalog.metadata.discogs.discogsCorroborationEditionIds
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscogsGroupCorroborationTest {
    private fun edition(id: Int, status: String?) = MusicBrainzReleaseHit(
        musicBrainzId = "00000000-0000-4000-8000-%012d".format(id),
        title = "Same release", country = "XW", releaseDate = "2026-01-01",
        artistDisplay = "Artist", status = status,
    )
    @Test fun selectAtMostTwoDistinctOfficialAlternativeEditions() {
        val selected = edition(1, "Official")
        val results = discogsCorroborationEditionIds(
            selected.musicBrainzId,
            listOf(selected, edition(2, "Official"), edition(2, "Official"),
                edition(3, "Promotion"), edition(4, null),
                edition(5, "Official"), edition(6, "Official")),
        )
        assertEquals(listOf(edition(2, "Official").musicBrainzId,
            edition(5, "Official").musicBrainzId), results)
    }

    @Test fun rejectsInvalidProviderIdentifiersAndNeverTreatsUnofficialEditionsAsProof() {
        val selected = edition(1, "Official")
        val bad = edition(2, "Official").copy(musicBrainzId = "local:2")
        assertEquals(emptyList<String>(), discogsCorroborationEditionIds(
            selected.musicBrainzId,
            listOf(bad, edition(3, "Pseudo-release"), edition(4, "Bootleg")),
        ))
        assertEquals(emptyList<String>(), discogsCorroborationEditionIds(
            "local:1", listOf(edition(2, "Official")),
        ))
    }
}
