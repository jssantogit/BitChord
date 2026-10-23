package com.music.bitchord.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRatingEvidenceD12Test {
    @Test fun absentUnsupportedAndUnknownRemainDifferent() {
        assertEquals(RemoteRatingFieldTruth.ABSENT, RemoteRatingFieldTruth.classify(false, null))
        assertEquals(RemoteRatingFieldTruth.UNSUPPORTED, RemoteRatingFieldTruth.classify(null, null, false))
        assertEquals(RemoteRatingFieldTruth.UNKNOWN, RemoteRatingFieldTruth.classify(null, null))
        assertEquals(RemoteRatingFieldTruth.ZERO, RemoteRatingFieldTruth.classify(true, 0))
        assertEquals(RemoteRatingFieldTruth.RATED, RemoteRatingFieldTruth.classify(true, 5))
        assertEquals(RemoteRatingFieldTruth.INVALID, RemoteRatingFieldTruth.classify(true, 6))
    }

    @Test fun aggregateKeepsStarredSeparateAndNeverInfersFiveStars() {
        val tally = RemoteRatingEvidenceTally()
        tally.add(RemoteRatingFieldTruth.ABSENT, starred = true)
        tally.add(RemoteRatingFieldTruth.ZERO, starred = false)
        tally.add(RemoteRatingFieldTruth.RATED, starred = null)
        val summary = tally.snapshot()
        assertEquals(3, summary.total)
        assertEquals(1, summary.starred)
        assertEquals(1, summary.rated)
        assertEquals(1, summary.cleared)
        assertEquals(1, summary.absent)
        assertEquals(1, summary.unknownStarred)
    }

    @Test fun countsAreBoundedAndNoRemoteRatingsAreExported() {
        val tally = RemoteRatingEvidenceTally(limit = 2)
        repeat(40) { tally.add(RemoteRatingFieldTruth.RATED, starred = true) }
        val summary = tally.snapshot()
        assertEquals(2, summary.total)
        assertEquals(2, summary.rated)
        assertTrue(summary.truncated)
        assertFalse(summary.toString().contains("5.0"))
    }

    @Test fun canonicalMatchCannotPretendAnUnprovenIdentityWasMapped() {
        assertEquals(CanonicalRatingMatchTruth.UNKNOWN,
            CanonicalRatingMatchTruth.fromEvidence(identityProven = null, matches = 0))
        assertEquals(CanonicalRatingMatchTruth.UNMATCHED,
            CanonicalRatingMatchTruth.fromEvidence(identityProven = true, matches = 0))
        assertEquals(CanonicalRatingMatchTruth.AMBIGUOUS,
            CanonicalRatingMatchTruth.fromEvidence(identityProven = true, matches = 2))
        assertEquals(CanonicalRatingMatchTruth.MATCHED,
            CanonicalRatingMatchTruth.fromEvidence(identityProven = true, matches = 1))
    }
}
