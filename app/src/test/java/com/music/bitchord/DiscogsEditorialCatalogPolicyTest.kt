package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.*
import org.junit.Assert.*
import org.junit.Test

/** Contract tests first: user-visible editorial corrections must never fabricate MB identities. */
class DiscogsEditorialCatalogPolicyTest {
    private val mb = CanonicalEditionEvidence(
        musicBrainzReleaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
        title = "SUPER REAL ME",
        creditedArtist = "ILLIT",
        status = "Official",
        barcodes = listOf("8809704427821"),
        trackTitles = listOf("My World", "Magnetic", "Midnight Fiction", "Lucky Girl Syndrome"),
        country = "KR",
        releaseYear = 2024,
        creditedArtistNames = listOf("ILLIT"),
    )

    private fun edition(
        id: Long,
        barcode: String = "8809704427821",
        tracks: List<String> = mb.trackTitles,
        formats: List<String> = listOf("CD", "EP"),
        artist: String = "ILLIT",
        master: Long? = 987L,
        year: Int = 2024,
        unofficial: Boolean = false,
    ) = DiscogsRecord(
        link = DiscogsLink(DiscogsKind.RELEASE, id),
        title = "SUPER REAL ME",
        artistNames = listOf(artist),
        country = "South Korea",
        year = year,
        labels = listOf("Belift Lab" to "CMDC12000"),
        barcodes = listOf(barcode),
        formats = formats,
        tracklist = tracks.mapIndexed { index, title -> "${index + 1}" to title },
        unofficial = unofficial,
        masterId = master,
    )

    @Test fun unlinkedEditionRequiresMoreThanTitleArtistAndMatchingTracks() {
        assertEquals(DiscogsEditionVerdict.VERIFIED,
            DiscogsEditionMatcher.assessUnlinked(mb, edition(101)))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assessUnlinked(mb.copy(barcodes = emptyList()), edition(101)))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assessUnlinked(mb, edition(101, year = 2025)))
        assertEquals(DiscogsEditionVerdict.CANDIDATE,
            DiscogsEditionMatcher.assessUnlinked(mb, edition(101, tracks = mb.trackTitles.dropLast(1))))
        assertEquals(DiscogsEditionVerdict.CONFLICT,
            DiscogsEditionMatcher.assessUnlinked(mb, edition(101, artist = "Other Artist")))
    }

    @Test fun duplicateStrongCandidatesMustRemainAmbiguous() {
        assertTrue(DiscogsUnlinkedMatch.select(mb, listOf(edition(101), edition(102)))
            is DiscogsUnlinkedMatch.Ambiguous)
        assertEquals(101L,
            (DiscogsUnlinkedMatch.select(mb, listOf(edition(101))) as DiscogsUnlinkedMatch.Verified)
                .record.link.id)
        assertTrue(DiscogsUnlinkedMatch.select(mb, listOf(edition(101, artist = "Other Artist")))
            is DiscogsUnlinkedMatch.NoMatch)
    }

    @Test fun missingTypeCanBeFilledButContradictorySingleEditionCannotOverwriteMusicBrainz() {
        val one = listOf(DiscogsGroupTypeProof(101L, DiscogsEditorialKind.EP, verified = true))
        val filled = DiscogsTypeConsensus.resolve("Other", one)
        assertEquals(DiscogsEditorialKind.EP, (filled as DiscogsTypeResolution.Decided).kind)
        assertEquals(DiscogsTypeSource.DISCOGS_FILL, filled.source)

        val disputed = DiscogsTypeConsensus.resolve("Album", one)
        assertTrue(disputed is DiscogsTypeResolution.Disputed)
        assertEquals(DiscogsEditorialKind.ALBUM, (disputed as DiscogsTypeResolution.Disputed).musicBrainzKind)
    }

    @Test fun twoIndependentVerifiedEditionsCanCorrectDisplayTypeWithoutChangingCanonicalType() {
        val two = listOf(
            DiscogsGroupTypeProof(101L, DiscogsEditorialKind.EP, verified = true),
            DiscogsGroupTypeProof(102L, DiscogsEditorialKind.EP, verified = true),
        )
        val fixed = DiscogsTypeConsensus.resolve("Album", two)
        assertEquals(DiscogsEditorialKind.EP, (fixed as DiscogsTypeResolution.Decided).kind)
        assertEquals(DiscogsTypeSource.DISCOGS_CORRECTION, fixed.source)
        assertEquals(setOf(101L, 102L), fixed.releaseIds.toSet())
        assertTrue(DiscogsTypeConsensus.resolve("Album", two +
            DiscogsGroupTypeProof(103L, DiscogsEditorialKind.ALBUM, verified = true))
            is DiscogsTypeResolution.Disputed)
        assertTrue(DiscogsTypeConsensus.resolve("Other", listOf(
            DiscogsGroupTypeProof(101L, DiscogsEditorialKind.EP, verified = false),
        )) is DiscogsTypeResolution.Unknown)
    }

    @Test fun twoEditionsFromSameDiscogsMasterAreOnlyOneEditorialTypeVote() {
        val variants = listOf(
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(212, DiscogsEditorialKind.EP, true, masterId = 500),
        )
        assertTrue(DiscogsTypeConsensus.resolve("Album", variants)
            is DiscogsTypeResolution.Disputed)
        assertEquals(DiscogsTypeSource.DISCOGS_FILL,
            (DiscogsTypeConsensus.resolve("Other", variants)
                as DiscogsTypeResolution.Decided).source)
    }

    @Test fun differentVerifiedMastersCanCorroborateTypeWithoutCountingVariantsTwice() {
        val evidence = listOf(
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(212, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(301, DiscogsEditorialKind.EP, true, masterId = 600),
        )
        val changed = DiscogsTypeConsensus.resolve("Album", evidence)
            as DiscogsTypeResolution.Decided
        assertEquals(DiscogsTypeSource.DISCOGS_CORRECTION, changed.source)
        assertEquals(DiscogsEditorialKind.EP, changed.kind)
        assertEquals(listOf(211L, 212L, 301L), changed.releaseIds)
    }

    @Test fun conflictingKindsWithinOneMasterMustStayDisputedEvenWithOtherVotes() {
        val evidence = listOf(
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(212, DiscogsEditorialKind.ALBUM, true, masterId = 500),
            DiscogsGroupTypeProof(301, DiscogsEditorialKind.EP, true, masterId = 600),
        )
        assertTrue(DiscogsTypeConsensus.resolve("Album", evidence)
            is DiscogsTypeResolution.Disputed)
    }

    @Test fun contradictoryKindsForOneVerifiedDiscogsReleaseCannotCreateTypeCorrection() {
        // Duplicated observations of the SAME Release are not independent
        // editorial evidence. Never keep whichever kind arrived first.
        val contradictory = listOf(
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.ALBUM, true, masterId = 500),
            DiscogsGroupTypeProof(301, DiscogsEditorialKind.EP, true, masterId = 600),
        )
        assertTrue(DiscogsTypeConsensus.resolve("Album", contradictory)
            is DiscogsTypeResolution.Disputed)
        assertTrue(DiscogsTypeConsensus.resolve("Other", contradictory)
            is DiscogsTypeResolution.Disputed)
        assertTrue(DiscogsTypeConsensus.resolve("Album", contradictory.reversed())
            is DiscogsTypeResolution.Disputed)
    }

    @Test fun contradictoryMasterAssignmentForSameReleaseCannotInflateIndependentVotes() {
        // One Release is claimed by two different Masters. Choosing the first
        // record can turn one editorial Master into two apparent independent votes.
        val contradictory = listOf(
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 600),
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(301, DiscogsEditorialKind.EP, true, masterId = 500),
        )
        assertTrue(DiscogsTypeConsensus.resolve("Album", contradictory)
            is DiscogsTypeResolution.Disputed)
        assertTrue(DiscogsTypeConsensus.resolve("Album", contradictory.reversed())
            is DiscogsTypeResolution.Disputed)
    }

    @Test fun knownMasterMustWinOverAnUnknownMasterOnDuplicatedRelease() {
        // The same Release is first observed without a Master and later with
        // the real Master; both it and another edition belong to Master 500.
        // Counting release:211 + master:500 would fabricate a second vote.
        val incomplete = listOf(
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true),
            DiscogsGroupTypeProof(211, DiscogsEditorialKind.EP, true, masterId = 500),
            DiscogsGroupTypeProof(301, DiscogsEditorialKind.EP, true, masterId = 500),
        )
        assertTrue(DiscogsTypeConsensus.resolve("Album", incomplete)
            is DiscogsTypeResolution.Disputed)
        assertTrue(DiscogsTypeConsensus.resolve("Album", incomplete.reversed())
            is DiscogsTypeResolution.Disputed)
        assertEquals(DiscogsTypeSource.DISCOGS_FILL,
            (DiscogsTypeConsensus.resolve("Other", incomplete)
                as DiscogsTypeResolution.Decided).source)
    }

    @Test fun recoveredDiscogsReleaseHasTypedIndependentIdentityAndNoPlayback() {
        val observations = listOf(
            DiscogsPublicEditionSnapshot(edition(101, master = 987), fetchedAtMillis = 1_000),
            DiscogsPublicEditionSnapshot(edition(102, master = 987), fetchedAtMillis = 1_000),
        )
        assertTrue(DiscogsShadowCatalog.select(observations, expectedArtist = "ILLIT",
            mbCatalogChecked = false, nowMillis = 1_200).isEmpty())

        val recovered = DiscogsShadowCatalog.select(observations, expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200)
        assertEquals(1, recovered.size)
        assertEquals("discogs:master:987", recovered.single().key)
        assertEquals(DiscogsEditorialShelf.EPS, recovered.single().shelf)
        assertFalse(recovered.single().canPlay)
        assertNull(recovered.single().musicBrainzId)
        assertTrue(recovered.single().attributionUrl.startsWith("https://www.discogs.com/release/"))
    }

    @Test fun publicIdentityUsesRelatedProviderIdNotDisambiguatedArtistName() {
        val source = DiscogsPublicEditionSnapshot(
            edition(180L, artist = "Ive (16)", master = null).copy(
                title = "Unlisted EP", artistIds = listOf(98765L),
            ), 1_000,
        )
        val valid = DiscogsShadowCatalog.select(
            listOf(source), expectedArtist = "IVE", mbCatalogChecked = true,
            nowMillis = 1_200, expectedDiscogsArtistId = 98765L,
        )
        assertEquals(1, valid.size)
        assertEquals("IVE", valid.single().artist)
        assertNull(valid.single().musicBrainzId)
        assertFalse(valid.single().canPlay)
        assertTrue(DiscogsShadowCatalog.select(
            listOf(source), expectedArtist = "IVE", mbCatalogChecked = true,
            nowMillis = 1_200, expectedDiscogsArtistId = 98766L,
        ).isEmpty())
        assertTrue(DiscogsShadowCatalog.select(
            listOf(source), expectedArtist = "IVE", mbCatalogChecked = true,
            nowMillis = 1_200,
        ).isEmpty())
        assertTrue(DiscogsShadowCatalog.select(
            listOf(source.copy(record = source.record.copy(artistIds = emptyList()))),
            expectedArtist = "IVE", mbCatalogChecked = true,
            nowMillis = 1_200, expectedDiscogsArtistId = 98765L,
        ).isEmpty())
    }

    @Test fun discogsOnlyShelvesNeverDuplicateKnownMusicBrainzGroups() {
        val observations = listOf(
            DiscogsPublicEditionSnapshot(edition(101, master = 987), 1_000),
        )
        val shadow = DiscogsShadowCatalog.select(
            observations, expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200,
            knownMusicBrainzTitles = setOf("SUPER REAL ME"),
        )
        assertTrue(shadow.isEmpty())
        assertTrue(DiscogsShadowCatalog.select(
            observations, expectedArtist = "ILLIT",
            mbCatalogChecked = false, nowMillis = 1_200,
            knownMusicBrainzTitles = emptySet(),
        ).isEmpty())
    }

    @Test fun knownMusicBrainzGroupIsNotReintroducedByDiscogsArtistTitlePrefix() {
        val snapshot = DiscogsPublicEditionSnapshot(
            edition(109, master = null).copy(title = "ILLIT - SUPER REAL ME"), 1_000,
        )
        val found = DiscogsShadowCatalog.select(
            listOf(snapshot), expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200,
            knownMusicBrainzTitles = setOf("SUPER REAL ME"),
        )
        assertTrue(found.isEmpty())
    }

    @Test fun knownMusicBrainzGroupIsNotReintroducedByUnicodeOrSafeCaseVariation() {
        val snapshot = DiscogsPublicEditionSnapshot(
            edition(110, master = null).copy(title = "ＳＵＰＥＲ  ＲＥＡＬ　ＭＥ"), 1_000,
        )
        assertTrue(DiscogsShadowCatalog.select(
            listOf(snapshot), expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200,
            knownMusicBrainzTitles = setOf("SUPER REAL ME"),
        ).isEmpty())
    }

    @Test fun physicalEditionVersionOfKnownGroupIsNotDeclaredDiscogsOnly() {
        val variants = listOf(
            "Super Real Me (SUPER ME ver.)",
            "ILLIT - Super Real Me (REAL ME version)",
            "Super Real Me [Limited Edition]",
        )
        variants.forEachIndexed { i, title ->
            val snapshot = DiscogsPublicEditionSnapshot(
                edition(130 + i.toLong(), master = null).copy(title = title), 1_000,
            )
            assertTrue("Known group must cover physical edition: $title",
                DiscogsShadowCatalog.select(
                    listOf(snapshot), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = setOf("SUPER REAL ME"),
                ).isEmpty())
        }
    }

    @Test fun realPublicIndexPunctuationAndExplicitVariantsAreNotFalseDiscogsOnly() {
        val knownVariants = listOf(
            "Romance: Untold" to "ROMANCE : UNTOLD",
            "I’LL LIKE YOU" to "I'll Like You",
            "ICONIC BY MISTAKE (Explicit)" to "ICONIC BY MISTAKE",
        )
        knownVariants.forEachIndexed { index, (discogsTitle, musicBrainzTitle) ->
            val snapshot = DiscogsPublicEditionSnapshot(
                edition(160L + index, master = null).copy(title = discogsTitle), 1_000,
            )
            assertTrue("Known MusicBrainz group is not missing: $discogsTitle",
                DiscogsShadowCatalog.select(
                    listOf(snapshot), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = setOf(musicBrainzTitle),
                ).isEmpty())
        }
        val distinct = DiscogsPublicEditionSnapshot(
            edition(170L, master = null).copy(title = "Easy-Crazy-Hot"), 1_000,
        )
        assertEquals(1, DiscogsShadowCatalog.select(
            listOf(distinct), expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200,
            knownMusicBrainzTitles = setOf("CRAZY", "HOT"),
        ).size)
    }

    @Test fun knownSingleDigitalBilingualSlashCannotBeInventedAsDiscogsOnly() {
        // Real public controls B-03/B-04: the primary source and MB already
        // identify the single, while the Discogs one-track File title and its
        // track are printed in two scripts. This protects the *absence* gate;
        // it does NOT identify an exact MusicBrainz Release or recording.
        val examples = listOf(
            Triple(37253067L, "Fashion / ファッション", "Fashion"),
            Triple(34121731L, "Dare Me / デアミー", "DARE ME"),
        )
        for ((discogsId, bilingual, knownGroup) in examples) {
            val source = DiscogsPublicEditionSnapshot(
                edition(discogsId, artist = "Ive (16)", master = null).copy(
                    title = bilingual, artistIds = listOf(7122521L),
                    formats = listOf("File", "Single"),
                    labels = listOf("Sony Music Labels" to ""),
                    tracklist = listOf("1" to bilingual),
                    year = 2026,
                ), 1_000,
            )
            fun select(item: DiscogsPublicEditionSnapshot, known: Set<String>) =
                DiscogsShadowCatalog.select(
                    listOf(item), expectedArtist = "IVE",
                    expectedDiscogsArtistId = 7122521L,
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = known,
                )
            assertEquals("A real source lead remains when no known group is supplied: $bilingual",
                1, select(source, emptySet()).size)
            assertTrue("Known single must not be called absent by alternate script: $bilingual",
                select(source, setOf(knownGroup)).isEmpty())
            assertEquals("Two-track releases are not proven to be the same single",
                1, select(source.copy(record = source.record.copy(tracklist =
                    listOf("1" to bilingual, "2" to "Other song"))),
                    setOf(knownGroup)).size)
            assertEquals("Physical CD may have edition-specific identity",
                1, select(source.copy(record = source.record.copy(formats =
                    listOf("CD", "Single"))), setOf(knownGroup)).size)
            assertEquals("Unknown MusicBrainz titles are not suppressed",
                1, select(source, setOf("Something Else")).size)
        }
    }

    @Test fun observedDiscogsPunctuationIsNotASecondMusicBrainzReleaseGroup() {
        val pairs = listOf(
            "Easy-Crazy-Hot" to "EASY CRAZY HOT",
            "'Pureflow' Pt. 1" to "‘PUREFLOW’ pt.1",
            "ROMANCE: UNTOLD -Daydream-" to "ROMANCE : UNTOLD Daydream",
        )
        pairs.forEachIndexed { i, (discogs, musicBrainz) ->
            val snapshot = DiscogsPublicEditionSnapshot(
                edition(200L + i, master = null).copy(title = discogs), 1_000,
            )
            assertTrue("Punctuation is not new music: $discogs",
                DiscogsShadowCatalog.select(
                    listOf(snapshot), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = setOf(musicBrainz),
                ).isEmpty())
        }
        val genuinelyDifferent = listOf(
            "EASY CRAZY HOT (Live)" to "EASY CRAZY HOT",
            "Love-Me" to "Love Me",
            "Fashion / ファッション" to "Fashion",
        )
        genuinelyDifferent.forEachIndexed { i, (discogs, musicBrainz) ->
            val snapshot = DiscogsPublicEditionSnapshot(
                edition(210L + i, master = null).copy(title = discogs), 1_000,
            )
            assertEquals("Do not infer a translation or collapse variants: $discogs",
                1, DiscogsShadowCatalog.select(
                    listOf(snapshot), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = setOf(musicBrainz),
                ).size)
        }
    }

    @Test fun explicitBilingualMusicBrainzGroupAliasesPreventFalseSourceOnly() {
        val examples = listOf(
            "HOP" to "合 (HOP)",
            "Jewelry" to "ジュエリー (Jewelry)",
        )
        examples.forEachIndexed { index, (discogs, musicBrainz) ->
            val record = DiscogsPublicEditionSnapshot(
                edition(275L + index, master = null).copy(title = discogs), 1_000,
            )
            assertTrue("Exact MB bilingual alias is already a known group",
                DiscogsShadowCatalog.select(
                    listOf(record), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = setOf(musicBrainz),
                ).isEmpty())
            assertTrue(DiscogsShadowCatalog.knownTitlePredicate(
                "ILLIT", setOf(musicBrainz),
            )(discogs))
        }
        val unsafe = listOf(
            "HOP (Remix)" to "合 (HOP)",
            "Love" to "Love (Live)",
            "Remix" to "합창 (Remix)",
            "HOP" to "합창 (Hope)",
            "Fashion / ファッション" to "Fashion",
        )
        unsafe.forEachIndexed { index, (discogs, mb) ->
            val record = DiscogsPublicEditionSnapshot(
                edition(280L + index, master = null).copy(title = discogs), 1_000,
            )
            assertEquals("Do not infer arbitrary translation or remix equivalence",
                1, DiscogsShadowCatalog.select(
                    listOf(record), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                    knownMusicBrainzTitles = setOf(mb),
                ).size)
        }
    }

    @Test fun exactSourceAssertedBilingualPairsAcceptParenthesesAndEqualsOnly() {
        val known = DiscogsShadowCatalog.knownTitlePredicate(
            "TOMORROW X TOGETHER", setOf("誓い (CHIKAI)"),
        )
        assertTrue(known("誓い = Chikai"))
        assertTrue(known("TOMORROW X TOGETHER - 誓い = Chikai"))
        assertFalse(known("誓い = Other"))
        assertFalse(known("誓い = Chikai (Remix)"))
        assertFalse(known("誓い = Chikai / Bonus"))
        assertFalse(DiscogsShadowCatalog.knownTitlePredicate(
            "TOMORROW X TOGETHER", setOf("Hello (Chikai)"),
        )("Hello = Chikai"))
    }

    @Test fun videoOnlyAlbumOrSingleLabelsCannotCreateAudioArtistShelves() {
        val videoCases = listOf(
            listOf("DVD", "DVD-Video", "Album"),
            listOf("Blu-ray", "Album"),
            listOf("DVD", "Single"),
        )
        videoCases.forEachIndexed { i, formats ->
            val snapshot = DiscogsPublicEditionSnapshot(
                edition(230L + i, formats = formats, master = null), 1_000,
            )
            assertTrue("Video-only must not become a music shelf: $formats",
                DiscogsShadowCatalog.select(
                    listOf(snapshot), expectedArtist = "ILLIT",
                    mbCatalogChecked = true, nowMillis = 1_200,
                ).isEmpty())
        }
        val mixed = DiscogsPublicEditionSnapshot(
            edition(240L, formats = listOf("CD", "DVD", "Album"), master = null),
            1_000,
        )
        assertEquals(1, DiscogsShadowCatalog.select(
            listOf(mixed), expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200,
        ).size)
    }

    @Test fun distinctRemixOrLiveReleaseIsNotSilentlyCollapsedIntoOriginalGroup() {
        val titles = listOf("Super Real Me (remix)", "Super Real Me (live)")
        titles.forEachIndexed { i, title ->
            val snapshot = DiscogsPublicEditionSnapshot(
                edition(140 + i.toLong(), master = null).copy(title = title), 1_000,
            )
            assertEquals(title, DiscogsShadowCatalog.select(
                listOf(snapshot), expectedArtist = "ILLIT",
                mbCatalogChecked = true, nowMillis = 1_200,
                knownMusicBrainzTitles = setOf("SUPER REAL ME"),
            ).single().title)
        }
    }

    @Test fun differentArtistPrefixMustNotBeStrippedAsAnIdentityShortcut() {
        val snapshot = DiscogsPublicEditionSnapshot(
            edition(111, master = null).copy(title = "OTHER - SUPER REAL ME"), 1_000,
        )
        val result = DiscogsShadowCatalog.select(
            listOf(snapshot), expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 1_200,
            knownMusicBrainzTitles = setOf("SUPER REAL ME"),
        )
        assertEquals(1, result.size)
        assertEquals("OTHER - SUPER REAL ME", result.single().title)
    }

    @Test fun recoveredCatalogRejectsUnofficialStaleAndAlreadyLinkedEditions() {
        val inputs = listOf(
            DiscogsPublicEditionSnapshot(edition(101, unofficial = true), 1_000),
            DiscogsPublicEditionSnapshot(edition(102, formats = listOf("Vinyl", "LP")), 1_000),
            DiscogsPublicEditionSnapshot(edition(103), 0),
        )
        assertTrue(DiscogsShadowCatalog.select(inputs, expectedArtist = "ILLIT",
            mbCatalogChecked = true, nowMillis = 5L * 60 * 60 * 1_000 + 1).isEmpty())
        assertTrue(DiscogsShadowCatalog.select(listOf(
            DiscogsPublicEditionSnapshot(edition(104), 1_000),
        ), expectedArtist = "ILLIT", mbCatalogChecked = true,
            linkedReleaseIds = setOf(104), nowMillis = 1_200).isEmpty())
    }
}
