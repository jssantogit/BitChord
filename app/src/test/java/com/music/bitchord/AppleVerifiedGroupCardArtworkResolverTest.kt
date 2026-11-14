package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppleVerifiedGroupCardArtworkResolverTest {
    private val group = "529f87a1-1081-48a4-8ee1-3c08de229858"
    private val otherGroup = "e514e432-ca77-41c1-82c1-4e3a24ebbb0c"
    private val releaseId = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val alternate = "271b31f8-8ab6-483c-97d9-8f0fabb2d4f4"
    private val cover = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/100x100bb.jpg"
    private val store = "https://music.apple.com/br/album/101"

    private val verified = AppleVerifiedEditionArtwork(
        musicBrainzReleaseId = releaseId,
        musicBrainzReleaseGroupId = group,
        appleScopedId = "itunes:br:album:101",
        artworkUrl = cover,
        storeUrl = store,
    )

    private fun edition(
        id: String = releaseId,
        groupId: String = group,
        status: String? = "Official",
    ) = MusicBrainzReleaseHit(
        musicBrainzId = id, title = "SUPER REAL ME", country = "KR",
        releaseDate = "2024-03-25", artistDisplay = "ILLIT",
        status = status, releaseGroupMbid = groupId,
    )

    private fun page(vararg hits: MusicBrainzReleaseHit,
                     total: Int = hits.size, offset: Int = 0,
                     consumed: Int = hits.size) =
        MusicBrainzCatalogPage(hits.toList(), total, offset, consumed)

    @Test fun onlyCompleteSingleOfficialEditionIsEligible() {
        assertEquals(releaseId, uniqueAppleGroupCardEdition(
            group, page(edition()),
        ))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition(), edition(alternate))))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition(), total = 2)))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition(), consumed = 2)))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition(), offset = 1)))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition(status = "Bootleg"))))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition(groupId = otherGroup))))
        assertNull(uniqueAppleGroupCardEdition(group,
            page(edition().copy(isVideoOnlyMedia = true))))
    }

    @Test fun oneOfficialEditionAmongCompleteBootlegVariantsIsSafe() {
        val mixed = page(
            edition(status = "Bootleg"),
            edition(alternate, status = "Official"),
            edition("bc36ebc6-d875-46ea-88c7-b644e93c151e", status = "Promotion"),
        )
        assertEquals(alternate, uniqueAppleGroupCardEdition(group, mixed))
        assertNull(uniqueAppleGroupCardEdition(group, page(
            edition(), edition(alternate), total = 3,
        )))
        assertNull(uniqueAppleGroupCardEdition(group, page(
            edition(), edition(alternate),
        )))
    }

    @Test fun oneExactTitleOfficialAmidExplicitAlternateOfficialPackagingIsEligible() {
        val deluxe = edition(alternate).copy(title = "SUPER REAL ME (Deluxe Version)")
        assertNull(uniqueAppleGroupCardEdition(group, page(edition(), deluxe)))
        assertEquals(releaseId, uniqueAppleGroupCardEdition(
            group, page(edition(), deluxe), "SUPER REAL ME",
        ))
        assertNull(uniqueAppleGroupCardEdition(
            group, page(edition(), edition(alternate)), "SUPER REAL ME",
        ))
        assertNull(uniqueAppleGroupCardEdition(
            group, page(edition(), edition(alternate).copy(title = "SUPER REAL ME (Japan)")),
            "SUPER REAL ME",
        ))
        assertNull(uniqueAppleGroupCardEdition(
            group, page(edition(), deluxe, total = 3), "SUPER REAL ME",
        ))
    }

    @Test fun selectedCleanOfficialEditionResolvesItsOwnAppleProof() = runBlocking {
        val resolver = AppleVerifiedGroupCardArtworkResolver(
            groups = AppleGroupCardEditionSource {
                MusicBrainzCatalogGraphResult.Success(page(
                    edition(),
                    edition(alternate).copy(title = "SUPER REAL ME (Deluxe Version)"),
                ))
            },
            editions = AppleGroupCardArtworkSource { id, _ ->
                assertEquals(releaseId, id)
                verified
            },
        )
        assertEquals(verified, resolver.resolve(group, "br", "SUPER REAL ME"))
        assertNull(resolver.resolve(group, "br"))
    }

    @Test fun verifiedOfficialEditionIsSelectedFromCompleteMixedStatusGroup() = runBlocking {
        var checkedId: String? = null
        val resolver = AppleVerifiedGroupCardArtworkResolver(
            groups = AppleGroupCardEditionSource {
                MusicBrainzCatalogGraphResult.Success(page(
                    edition(status = "Bootleg"),
                    edition(alternate, status = "Official"),
                ))
            },
            editions = AppleGroupCardArtworkSource { id, _ ->
                checkedId = id
                verified.copy(musicBrainzReleaseId = alternate)
            },
        )
        assertEquals(alternate,
            resolver.resolve(group, "br")?.musicBrainzReleaseId)
        assertEquals(alternate, checkedId)
    }

    @Test fun multipleVisitsReuseGroupEditionSelectionWithoutRebrowsing() = runBlocking {
        var browses = 0
        var editionReads = 0
        val resolver = AppleVerifiedGroupCardArtworkResolver(
            groups = AppleGroupCardEditionSource {
                browses++
                MusicBrainzCatalogGraphResult.Success(page(edition()))
            },
            editions = AppleGroupCardArtworkSource { _, _ ->
                editionReads++
                verified
            },
        )
        assertEquals(verified, resolver.resolve(group, "br", "SUPER REAL ME"))
        assertEquals(verified, resolver.resolve(group, "br", "SUPER REAL ME"))
        assertEquals(1, browses)
        assertEquals(1, editionReads)
    }

    @Test fun negativeGroupSelectionRetriesAfterShortTtl() = runBlocking {
        var now = 1_000L
        var browses = 0
        val resolver = AppleVerifiedGroupCardArtworkResolver(
            groups = AppleGroupCardEditionSource {
                browses++
                MusicBrainzCatalogGraphResult.Success(page(
                    edition(), edition(alternate),
                ))
            },
            editions = AppleGroupCardArtworkSource { _, _ ->
                error("ambiguous official group cannot initiate UPC lookup")
            },
            clockMs = { now },
        )
        assertNull(resolver.resolve(group, "br", "SUPER REAL ME"))
        assertNull(resolver.resolve(group, "br", "SUPER REAL ME"))
        assertEquals(1, browses)
        now += 16 * 60_000L
        assertNull(resolver.resolve(group, "br", "SUPER REAL ME"))
        assertEquals(2, browses)
    }

    @Test fun eligibleCardReceivesOnlyTheExactAppleProductWithAttribution() = runBlocking {
        var lookups = 0
        val resolver = AppleVerifiedGroupCardArtworkResolver(
            groups = AppleGroupCardEditionSource {
                MusicBrainzCatalogGraphResult.Success(page(edition()))
            },
            editions = AppleGroupCardArtworkSource { id, country ->
                assertEquals(releaseId, id)
                assertEquals("br", country)
                lookups++
                verified
            },
        )
        assertEquals(verified, resolver.resolve(group, "br"))
        assertEquals(1, lookups)
    }

    @Test fun conflictingOrIncompleteGroupNeverStartsAppleLookup() = runBlocking {
        for (sourcePage in listOf(
            page(edition(), edition(alternate)),
            page(edition(), total = 2),
            page(edition(status = "Bootleg")),
            page(edition(groupId = otherGroup)),
        )) {
            var called = false
            val resolver = AppleVerifiedGroupCardArtworkResolver(
                groups = AppleGroupCardEditionSource {
                    MusicBrainzCatalogGraphResult.Success(sourcePage)
                },
                editions = AppleGroupCardArtworkSource { _, _ ->
                    called = true
                    verified
                },
            )
            assertNull(resolver.resolve(group, "br"))
            assertFalse(called)
        }
    }

    @Test fun forgedAppleEditionCannotEnterGroupShelf() = runBlocking {
        for (claim in listOf(
            verified.copy(musicBrainzReleaseId = alternate),
            verified.copy(musicBrainzReleaseGroupId = otherGroup),
            verified.copy(storeUrl = "https://music.apple.com.attacker.test/101"),
            verified.copy(artworkUrl = "https://attacker.test/101.jpg"),
        )) {
            val resolver = AppleVerifiedGroupCardArtworkResolver(
                groups = AppleGroupCardEditionSource {
                    MusicBrainzCatalogGraphResult.Success(page(edition()))
                },
                editions = AppleGroupCardArtworkSource { _, _ -> claim },
            )
            assertNull(resolver.resolve(group, "br"))
        }
    }

    @Test fun cancellationPropagatesInsteadOfNegativeCaching() = runBlocking {
        val resolver = AppleVerifiedGroupCardArtworkResolver(
            groups = AppleGroupCardEditionSource {
                throw CancellationException("user navigated away")
            },
            editions = AppleGroupCardArtworkSource { _, _ -> verified },
        )
        try {
            resolver.resolve(group, "br")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // The consumer cancels its own work; never emit a stale cover.
        }
    }
}
