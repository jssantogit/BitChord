package com.music.bitchord.acquisition.a0

/** Pure interpretation of route-level counters. No peer identities or
 * network settings are inferred from these observations.
 */
internal object A0SearchReachabilityDiagnosis {
    enum class Stage {
        NO_PEER_ROUTES_OBSERVED,
        INDIRECT_OFFERS_ONLY,
        PEER_CONNECTION_WITHOUT_VALID_REPLY,
        VALID_REPLY_NO_RETAINED_CHOICES,
        RETAINED_CHOICES,
    }

    fun classify(
        directAccepted:Int,
        indirectOffers:Int,
        indirectConnected:Int,
        validReplies:Int,
        retainedChoices:Int,
    ):Stage=when {
        retainedChoices>0 -> Stage.RETAINED_CHOICES
        validReplies>0 -> Stage.VALID_REPLY_NO_RETAINED_CHOICES
        directAccepted>0 || indirectConnected>0 ->
            Stage.PEER_CONNECTION_WITHOUT_VALID_REPLY
        indirectOffers>0 -> Stage.INDIRECT_OFFERS_ONLY
        else -> Stage.NO_PEER_ROUTES_OBSERVED
    }
}
