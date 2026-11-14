package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import org.junit.Assert.*
import org.junit.Test

class AppleEditorialProbeDiagnosticsTest {
    @Test fun aggregateAppleProbeEvidenceHasNoArtistTitleQueryOrIdentifier() {
        val report = AppleEditorialProbeReport(
            eligibleSeeds = 24, probesMade = 8, matchingProducts = 5,
            withoutMatchingProducts = 2, failedRequests = 1,
            incompleteResponses = 1, budgetLimited = true,
        )
        val metadata = appleEditorialProbeEventMetadata("home", report)
        assertEquals("home", metadata["surface"])
        assertEquals("24", metadata["candidateSeeds"])
        assertEquals("8", metadata["appleRequests"])
        assertEquals("5", metadata["confirmedProducts"])
        assertEquals("2", metadata["withoutMatch"])
        assertEquals("1", metadata["failed"])
        assertEquals("1", metadata["partial"])
        assertEquals("true", metadata["budgetLimited"])
        assertEquals(setOf("surface", "candidateSeeds", "appleRequests",
            "confirmedProducts", "withoutMatch", "failed", "partial",
            "budgetLimited"), metadata.keys)
        assertFalse(metadata.toString().contains("ILLIT"))
    }

    @Test fun unknownOrUserProvidedSurfaceIsReducedToNeutralLabel() {
        val report = AppleEditorialProbeReport(0,0,0,0,0,0,false)
        assertEquals("other",
            appleEditorialProbeEventMetadata("home://private-artist-name", report)["surface"])
        assertEquals("genre", appleEditorialProbeEventMetadata("genre", report)["surface"])
        assertEquals("0", appleEditorialProbeEventMetadata("home", report)["appleRequests"])
    }

    @Test fun defensiveBoundsExcludeInvalidOrHugeDiagnosticCounts() {
        val metadata = appleEditorialProbeEventMetadata("home",
            AppleEditorialProbeReport(-5, Int.MAX_VALUE, -3, 0, 0, 0, false))
        assertEquals("0", metadata["candidateSeeds"])
        assertEquals("10000", metadata["appleRequests"])
        assertEquals("0", metadata["confirmedProducts"])
    }
}
