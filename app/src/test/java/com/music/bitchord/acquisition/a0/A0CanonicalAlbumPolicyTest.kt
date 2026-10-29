package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

/** Album/edition decisions are made from *untrusted* metadata, not file audio. */
class A0CanonicalAlbumPolicyTest {
    private val policy=A0CanonicalAlbumPolicy

    @Test fun conflictingAdvertisedAlbumCannotBePromotedByExcellentEncoding() {
        val mismatch=policy.assess(
            target=A0CanonicalAlbumPolicy.Target("Blue Sky"),
            claim=A0CanonicalAlbumPolicy.Claim("Greatest Hits")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,mismatch.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.ALBUM_CLAIM_CONFLICT,mismatch.reason)
    }

    @Test fun exactlyMatchingAlbumNameIsOnlyAnUnverifiedClaimNotVerifiedMaster() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Blue Sky")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,result.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.MATCHING_NAME_IS_UNVERIFIED,result.reason)
    }

    @Test fun missingAlbumEvidencePreservesManualCandidateWithoutPromotingIt() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim(null)
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,result.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.ALBUM_NOT_ADVERTISED,result.reason)
    }

    @Test fun exactReleaseIdCannotBeProvedFromMatchingPeerAlbumText() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target(
                requestedAlbum="Blue Sky",
                exactReleaseMbid="11111111-1111-4111-8111-111111111111"
            ),
            A0CanonicalAlbumPolicy.Claim("Blue Sky")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,result.status)
        assertEquals(A0CanonicalAlbumPolicy.Reason.EXACT_RELEASE_NOT_PROVEN,result.reason)
    }

    @Test fun anUnconstrainedAlbumNeverFabricatesAlbumEvidence() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target(null),
            A0CanonicalAlbumPolicy.Claim("Another Album")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.UNCONSTRAINED,result.status)
    }

    @Test fun releaseSuffixMustNotPassAnExactAlbumNameMatch() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Blue Sky Remastered 2021")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,result.status)
    }

    @Test fun caseAccentAndPunctuationDifferencesNormalizeWithoutFuzzySubstrings() {
        val good=policy.assess(
            A0CanonicalAlbumPolicy.Target("Coração, A"),
            A0CanonicalAlbumPolicy.Claim(" coracao a ")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,good.status)
        val bad=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Sky")
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,bad.status)
    }

    @Test fun albumClaimIsNeverTransferAuthorization() {
        val data=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky"),
            A0CanonicalAlbumPolicy.Claim("Blue Sky")
        )
        assertFalse(data.authorizesTransfer)
    }

    @Test fun artistOrGenericDownloadFoldersAreNotWrongAlbumEvidence() {
        val target=A0CanonicalAlbumPolicy.Target("Blue Sky",expectedArtist="Mira")
        for(folder in listOf("Mira","Music","Downloads","General",
            "Unknown Album","Other","FLAC","Lossless",
            "AAC","Opus","Ogg","Ogg Vorbis","Vorbis")) {
            val result=policy.assess(target,A0CanonicalAlbumPolicy.Claim(folder,
                A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
            assertEquals("Folder: "+folder,
                A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,result.status)
            assertEquals(A0CanonicalAlbumPolicy.Reason.ALBUM_HINT_GENERIC,
                result.reason)
        }
    }

    @Test fun distinctiveWrongAlbumFolderRemainsAConflictingUntrustedHint() {
        val result=policy.assess(
            A0CanonicalAlbumPolicy.Target("Blue Sky",expectedArtist="Mira"),
            A0CanonicalAlbumPolicy.Claim("Greatest Hits",
                A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT)
        )
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,result.status)
        assertFalse(result.authorizesTransfer)
    }

    @Test fun formattingControlsCannotMasqueradeAsMatchingAlbumName() {
        val target=A0CanonicalAlbumPolicy.Target("Blue Sky")
        for(name in listOf("Blue\\u202E Sky","Blue\\u200B Sky")) {
            val display=name.replace("\\u202E","\u202E").replace("\\u200B","\u200B")
            val result=policy.assess(target,A0CanonicalAlbumPolicy.Claim(display))
            assertEquals(A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,result.status)
            assertEquals(A0CanonicalAlbumPolicy.Reason.UNTRUSTED_FORMAT_CONTROL,result.reason)
        }
    }
    @Test fun discAndVinylPartFoldersAreNotDistinctAlbumProof() {
        val target=A0CanonicalAlbumPolicy.Target("Blue Sky",expectedArtist="Mira")
        for(name in listOf("Disc 1","Disc 02","CD1","CD 2","Disk 3","Vinyl A","01")) {
            val answer=A0CanonicalAlbumPolicy.assess(target,A0CanonicalAlbumPolicy.Claim(
                name,A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
            assertEquals("Generic: "+name,A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,
                answer.status)
        }
    }

    @Test fun peerSingleSuffixDoesNotBecomeWrongAlbumOrProofOfEdition() {
        val target=A0CanonicalAlbumPolicy.Target("Looks Can Kill",expectedArtist="IVE")
        val answer=A0CanonicalAlbumPolicy.assess(
            target,
            A0CanonicalAlbumPolicy.Claim("Looks Can Kill - Single",
                A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
        assertEquals(A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,answer.status)
        assertFalse(answer.authorizesTransfer)
    }


    @Test fun compositePeerFolderContainingFullAlbumIsReviewNotConflictOrMatch() {
        val target=A0CanonicalAlbumPolicy.Target("BREAK EVEN",expectedArtist="ILLIT")
        for(folder in listOf(
            "ILLIT - BREAK EVEN [FLAC]",
            "BREAK EVEN (2026) 24bit",
            "ILLIT - BREAK EVEN",
            "Music - BREAK EVEN (Deluxe)"
        )) {
            val decision=policy.assess(target,A0CanonicalAlbumPolicy.Claim(
                folder,A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
            assertEquals(folder,A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL,decision.status)
            assertEquals(A0CanonicalAlbumPolicy.Reason.ALBUM_HINT_COMPOSITE,decision.reason)
            assertFalse(decision.authorizesTransfer)
        }
        val wrong=policy.assess(target,A0CanonicalAlbumPolicy.Claim(
            "ILLIT - Another Album [FLAC]",
            A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,wrong.status)
    }

    @Test fun compositeNamesNeverOverrideExplicitConflictOrAlternateVersion() {
        val target=A0CanonicalAlbumPolicy.Target("BREAK EVEN",expectedArtist="ILLIT")
        for(label in listOf("ILLIT - BREAK EVEN [FLAC]","BREAK EVEN (Deluxe)")) {
            assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,
                policy.assess(target,A0CanonicalAlbumPolicy.Claim(label,
                    A0CanonicalAlbumPolicy.ClaimSource.EXPLICIT_ALBUM_FIELD)).status)
        }
        for(folder in listOf("BREAK EVEN (Live)","BREAK EVEN - Remix",
            "ILLIT - BREAK EVEN remastered 2023")) {
            assertEquals(folder,A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,
                policy.assess(target,A0CanonicalAlbumPolicy.Claim(folder,
                    A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT)).status)
        }
        val notSubstring=policy.assess(target,A0CanonicalAlbumPolicy.Claim(
            "BREAK EVENT (2026)",A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT))
        assertEquals(A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM,notSubstring.status)
    }
}
