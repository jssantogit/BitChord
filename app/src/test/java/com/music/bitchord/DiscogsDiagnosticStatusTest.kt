package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsDiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscogsDiagnosticStatusTest {
    @Test fun outcomesAreBoundedCodesNotTitlesIdsOrApiPayloads() {
        val cases = listOf(
            DiscogsEditionEnrichment.Disabled to "disabled",
            DiscogsEditionEnrichment.NoLink to "no_link",
            DiscogsEditionEnrichment.Ambiguous to "ambiguous",
            DiscogsEditionEnrichment.Candidate to "candidate",
            DiscogsEditionEnrichment.Conflict to "conflict",
            DiscogsEditionEnrichment.Invalid to "invalid",
            DiscogsEditionEnrichment.Unavailable to "unavailable",
        )
        for ((result, code) in cases) {
            assertEquals(code, DiscogsDiagnosticStatus.of(result))
        }
    }

    @Test fun requestStatusDistinguishesRateLimitAndAuthButNotBodyOrId() {
        assertEquals("ok", DiscogsDiagnosticStatus.forHttp(200))
        assertEquals("auth_required", DiscogsDiagnosticStatus.forHttp(401))
        assertEquals("auth_required", DiscogsDiagnosticStatus.forHttp(403))
        assertEquals("missing", DiscogsDiagnosticStatus.forHttp(404))
        assertEquals("throttled", DiscogsDiagnosticStatus.forHttp(429))
        assertEquals("server_error", DiscogsDiagnosticStatus.forHttp(503))
        assertEquals("other_http", DiscogsDiagnosticStatus.forHttp(418))
    }
}
