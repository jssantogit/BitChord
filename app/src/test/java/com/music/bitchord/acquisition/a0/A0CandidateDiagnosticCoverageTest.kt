package com.music.bitchord.acquisition.a0

import com.music.bitchord.observability.StructuredTracePoint
import com.music.bitchord.observability.StructuredTraceSink
import com.music.bitchord.observability.StructuredTracer
import org.junit.Assert.*
import org.junit.Test

/** Real structured tracing applies a 24-field attribute ceiling. */
class A0CandidateDiagnosticCoverageTest {
    @Test fun preflightPolicyClaimCountsAndAbstentionSurviveTraceBound() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(
            sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "issue-177-count-only" }
        )
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        trace.candidateScreening(A0ObservedCandidateHandoff.Screening(
            responses=2,publicFiles=7,lockedFiles=1,
            tooLarge=0,unsupportedFormat=0,unsafePath=0,noSlot=1,
            accepted=2,claimsDistinct=2,claimsRepeated=0,
            simulatedNoStrong=1,preflightStrong=0,preflightReview=2,
            preflightUnsafe=0
        ))
        val event=points.single { it.stage=="candidate_screening" }
        val values=event.attributes
        assertTrue(values.size<=24)
        assertEquals("preflight-v4",values["policyVersion"])
        assertEquals("2",values["selectable"])
        assertEquals("2",values["distinctClaims"])
        assertEquals("0",values["repeatedClaims"])
        assertEquals("1",values["simulationNoStrong"])
        assertEquals("2",values["preflightReview"])
        assertEquals("1",values["noSlot"])
        assertFalse(values.toString().contains("ILLIT"))
    }
    @Test fun canonicalAlbumAndCodecCountsUseSeparateBoundedRedactedEvent() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(
            sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "issue-177-canonical-counts" }
        )
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        val report=A0ObservedCandidateHandoff.Screening(
            responses=4,publicFiles=7,lockedFiles=0,tooLarge=0,
            unsupportedFormat=0,unsafePath=0,noSlot=0,accepted=3,
            albumMatches=1,albumUnknown=1,albumConflicts=1,
            qualityMismatches=2,manualPrimary=2,shadowStrong=1,
        )
        trace.canonicalCandidateScreening(report,"LOSSLESS")
        val event=points.single { it.stage=="canonical_candidate_screening" }
        assertTrue(event.attributes.size<=24)
        assertEquals("1",event.attributes["albumConflicts"])
        assertEquals("1",event.attributes["albumMatches"])
        assertEquals("2",event.attributes["qualityMismatches"])
        assertEquals("LOSSLESS",event.attributes["qualityPreset"])
        assertEquals("2",event.attributes["manualPrimary"])
        assertEquals("1",event.attributes["shadowStrong"])
        assertFalse(event.attributes.toString().contains("Greatest Hits"))
        assertFalse(event.attributes.toString().contains("soulseek_user"))
    }

    @Test fun rejectedPeerOffersAreAggregatedWithoutExhaustingTraceEvents() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(
            sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "issue-177-peer-burst" }
        )
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        repeat(180) { trace.serverOfferRejected() }
        repeat(45) { trace.serverOfferReceived() }
        trace.finish(com.music.bitchord.observability.TraceOutcome.SUCCEEDED)
        val rejected=points.count { it.stage=="server_offer_rejected" }
        val received=points.count { it.stage=="server_offer_received" }
        assertTrue(rejected<=2)
        assertTrue(received<=2)
        val complete=points.single {
            it.kind==com.music.bitchord.observability.TracePointKind.TRACE_END &&
                it.operation=="a0_soulseek_one_shot"
        }
        assertEquals("180",complete.attributes["rejectedServerOffers"])
        assertEquals("45",complete.attributes["receivedServerOffers"])
        assertFalse(complete.attributes.toString().contains("peerUsername"))
    }

}
