package com.music.bitchord.acquisition.a0

/** Sanitized result classifier shared by direct and indirect P2P search. */
internal object A0CandidateOutcome {
    enum class Kind { SERVER_READER_FAILURE, NO_PEER_REPLY, NO_ELIGIBLE_CANDIDATE, CANDIDATES_READY }

    fun classify(
        report:A0ObservedCandidateHandoff.Screening,
        validatedPeerReply:Boolean,
        serverReaderFailed:Boolean=false
    ):Kind = when {
        serverReaderFailed -> Kind.SERVER_READER_FAILURE
        report.accepted>0 -> Kind.CANDIDATES_READY
        validatedPeerReply || report.responses>0 -> Kind.NO_ELIGIBLE_CANDIDATE
        else -> Kind.NO_PEER_REPLY
    }
}
