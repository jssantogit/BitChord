package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.catalog.metadata.discogs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleArtistTitleHintsTest {
    private val mbid = "f2eaa98b-fef5-4327-9e7f-7d6500fdb973"

    private fun mb(title: String, artist: String = "ILLIT",
        kind: String = "EP") = MusicBrainzReleaseGroupHit(
        musicBrainzId = mbid, title = title, primaryType = kind,
        firstReleaseDate = "2024-03-25", artistDisplay = artist,
    )

    private fun discogs(title: String, artist: String = "ILLIT") =
        DiscogsShadowRelease(
            key = "discogs:release:123", discogsReleaseId = 123L,
            title = title, artist = artist, year = 2024,
            kind = DiscogsEditorialKind.EP, shelf = DiscogsEditorialShelf.EPS,
            attributionUrl = "https://www.discogs.com/release/123",
        )

    @Test fun onlyExactCreditedArtistTitlesBecomeCandidatesNotAppleProducts() {
        val hints = AppleArtistTitleHintPolicy.select(
            artistName = "ILLIT",
            mbGroups = listOf(
                mb("SUPER REAL ME"),
                mb("SUPER REAL ME"),
                mb("Wrong Artist's Record", artist = "Other"),
                mb("Live Concert", kind = "Broadcast"),
                mb("Dance Practice"), // Noise excluded by MB canonical policy.
                mb("I'LL LIKE YOU"),
            ),
            discogsEditions = listOf(discogs("I'll Like You"), discogs("Foreign", "Other")),
            existingAppleTitles = listOf("SUPER REAL ME - EP"),
        )
        assertTrue("I'LL LIKE YOU" in hints)
        assertTrue("SUPER REAL ME" !in hints)
        assertTrue("Wrong Artist's Record" !in hints)
        assertTrue("Dance Practice" !in hints)
        assertTrue("Foreign" !in hints)
        assertEquals(1, hints.size) // typography/case duplicates deduplicated
    }

    @Test fun discogsOnlyContributesVerifiedArtistScopedEvidence() {
        val actual = AppleArtistTitleHintPolicy.select(
            artistName = "ILLIT",
            mbGroups = emptyList(),
            discogsEditions = listOf(discogs("Lucky Girl Syndrome")),
            existingAppleTitles = emptyList(),
        )
        assertEquals(listOf("Lucky Girl Syndrome"), actual)
    }

    @Test fun selectorNeverInfersMediaIdentityOrReturnsMoreThanBudget() {
        val hints = AppleArtistTitleHintPolicy.select(
            artistName = "ILLIT",
            mbGroups = (1..120).map { mb("Album $it") },
            discogsEditions = emptyList(),
            existingAppleTitles = emptyList(),
        )
        assertTrue(hints.size <= 24)
        assertTrue(hints.all { it.startsWith("Album ") })
    }

    @Test fun verifiedDiscogsHintsAreNotStarvedByTwentyFourMusicBrainzGroups() {
        val suggestions = AppleArtistTitleHintPolicy.select(
            artistName = "ILLIT",
            mbGroups = (1..80).map { mb("MB Album $it") },
            discogsEditions = listOf(
                discogs("Discogs Exclusive Edition"),
                discogs("Another Discogs Edition").copy(
                    key = "discogs:release:124", discogsReleaseId = 124L,
                    attributionUrl = "https://www.discogs.com/release/124",
                ),
            ),
            existingAppleTitles = emptyList(),
        )
        assertEquals(24, suggestions.size)
        assertTrue("Discogs Exclusive Edition" in suggestions)
        assertTrue("Another Discogs Edition" in suggestions)
        assertEquals(24, suggestions.distinct().size)
    }

    @Test fun oneCharacterStageNameCanStillFindAppleVerifiedReleaseCandidates() {
        val titles = AppleArtistTitleHintPolicy.select(
            artistName = "V",
            mbGroups = listOf(mb("Layover", artist = "V")),
            discogsEditions = emptyList(),
            existingAppleTitles = emptyList(),
        )
        assertEquals(listOf("Layover"), titles)
    }

    @Test fun MBFallbackWhenNoVerifiedMBArtistLinkMustNotProduceDiscogsByName() {
        val selected = AppleArtistTitleHintPolicy.select(
            artistName = "ILLIT",
            mbGroups = listOf(mb("SUPER REAL ME")),
            discogsEditions = emptyList(),
            existingAppleTitles = emptyList(),
        )
        assertEquals(listOf("SUPER REAL ME"), selected)
    }
}
