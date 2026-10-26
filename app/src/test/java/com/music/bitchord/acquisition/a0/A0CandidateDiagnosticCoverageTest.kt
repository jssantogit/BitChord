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
        assertEquals("preflight-v5",values["policyVersion"])
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


    @Test fun hundredsOfPeerFailuresKeepSummaryWithoutFloodingTraces() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(
            sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "issue-177-peer-timeout-storm" }
        )
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        repeat(200) { trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECT_STARTED) }
        repeat(140) { trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECTED) }
        repeat(100) { trace.note(A0SmokeTrace.Stage.PIERCE_SENT) }
        repeat(25) { trace.note(A0SmokeTrace.Stage.INDIRECT_REPLY_ACCEPTED) }
        repeat(300) { trace.note(A0SmokeTrace.Stage.INDIRECT_READ_TIMEOUT,
            com.music.bitchord.observability.TraceOutcome.DEGRADED,
            A0SmokeTrace.Reason.READ_TIMEOUT) }
        repeat(80) { trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECT_FAILED,
            com.music.bitchord.observability.TraceOutcome.DEGRADED,
            A0SmokeTrace.Reason.CONNECT_FAILED) }
        repeat(40) { trace.note(A0SmokeTrace.Stage.INDIRECT_REPLY_REJECTED,
            com.music.bitchord.observability.TraceOutcome.DEGRADED,
            A0SmokeTrace.Reason.PEER_IO_FAILURE) }
        trace.finish(com.music.bitchord.observability.TraceOutcome.DEGRADED)
        for(stage in listOf(
            "indirect_read_timeout", "indirect_connect_failed",
            "indirect_reply_rejected", "indirect_connect_started",
            "indirect_connected", "pierce_sent", "indirect_reply_accepted")) {
            assertTrue(stage,points.count { it.stage==stage }<=2)
        }
        val end=points.single {
            it.kind==com.music.bitchord.observability.TracePointKind.TRACE_END &&
                it.operation=="a0_soulseek_one_shot"
        }
        assertEquals("300",end.attributes["indirectReadTimeouts"])
        assertEquals("80",end.attributes["indirectConnectFailures"])
        assertEquals("40",end.attributes["indirectReplyFailures"])
        assertEquals("200",end.attributes["indirectAttempts"])
        assertEquals("140",end.attributes["indirectConnected"])
        assertEquals("100",end.attributes["indirectPierceSent"])
        assertEquals("25",end.attributes["indirectRepliesAccepted"])
        assertFalse(end.attributes.toString().contains("peerUsername"))
    }

    @Test fun perPeerFairnessRejectionsRemainDistinctFromWorkerBackpressure() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "peer-fairness-budget" })
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        trace.peerSearchBackpressure(dropped=31,perPeerDropped=7)
        val event=points.single { it.stage=="peer_search_backpressure" }
        assertEquals("31",event.attributes["droppedOffers"])
        assertEquals("7",event.attributes["perPeerDropped"])
    }

    @Test fun comparisonOfSameQueryLogsOnlyOutcomeAndCounts() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "repeat-search-privacy" })
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        val comparison=A0SearchComparison()
        comparison.observe("ILLIT - Magnetic","SUPER REAL ME","HIGH_QUALITY",203,5)
        trace.searchComparison(comparison.observe(
            "ILLIT - Magnetic","SUPER REAL ME","HIGH_QUALITY",0,0))
        val event=points.single { it.stage=="search_comparison" }
        assertEquals("repeat_lost",event.attributes["kind"])
        assertEquals("203",event.attributes["previousCandidates"])
        assertEquals("0",event.attributes["currentPeerReplies"])
        assertFalse(event.attributes.toString().contains("ILLIT"))
        assertFalse(event.attributes.toString().contains("SUPER REAL ME"))
    }

    @Test fun albumCoverageDiagnosticsContainOnlyBoundedCounts() {
        val points=mutableListOf<StructuredTracePoint>()
        val tracer=StructuredTracer(sink=StructuredTraceSink { points.add(it) },
            traceIdFactory={ "release-shadow-metrics" })
        val trace=A0SmokeTrace.start(tracer,observedTransfer=true)
        val report=A0ReleaseDiscoveryPlanner.preview(
            listOf(A0ReleaseDiscoveryPlanner.Target("song",180)),
            emptyList())
        trace.releaseDiscoveryCoverage(report)
        val event=points.single { it.stage=="release_discovery_shadow" }
        assertEquals("0",event.attributes["autoReadyTracks"])
        assertEquals("1",event.attributes["manualReviewTracks"])
        assertEquals("0",event.attributes["bestFolderCoverage"])
        assertFalse(event.attributes.toString().contains("song"))
    }
}
