package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.metadataEndpointLabel
import com.music.bitchord.data.catalog.metadata.metadataHttpStatusLabel
import com.music.bitchord.data.catalog.metadata.metadataHttpOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataNetworkDiagnosticsTest {
    @Test fun endpointLabelsNeverExposeUserQueriesOrRecordingIds() {
        assertEquals("recording", metadataEndpointLabel("recording/725ac47a-74c8-4f48-8294-9ab980cf5f92"))
        assertEquals("artist", metadataEndpointLabel("artist/UUID?query=personal"))
        assertEquals("release_group", metadataEndpointLabel("release-group/secret"))
        assertEquals("other", metadataEndpointLabel("sensitive-user-input"))
        assertEquals("other", metadataEndpointLabel(""))
    }

    @Test fun outcomesAreBoundedNonIdentifyingCategories() {
        assertEquals("ok", metadataHttpOutcome(200))
        assertEquals("empty", metadataHttpOutcome(204))
        assertEquals("rate_limited", metadataHttpOutcome(429))
        assertEquals("not_found", metadataHttpOutcome(404))
        assertEquals("server_error", metadataHttpOutcome(503))
        assertEquals("request_error", metadataHttpOutcome(400))
        assertEquals("network_error", metadataHttpOutcome(null))
    }
    @Test fun httpCodesRevealActionablePublicApiFailuresWithoutUrls() {
        assertEquals("400", metadataHttpStatusLabel(400))
        assertEquals("401", metadataHttpStatusLabel(401))
        assertEquals("403", metadataHttpStatusLabel(403))
        assertEquals("429", metadataHttpStatusLabel(429))
        assertEquals("503", metadataHttpStatusLabel(503))
        assertEquals("unknown", metadataHttpStatusLabel(null))
        assertEquals("unknown", metadataHttpStatusLabel(0))
        assertEquals("unknown", metadataHttpStatusLabel(999))
    }
}
