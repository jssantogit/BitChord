package com.music.bitchord.acquisition.a0

/**
 * A network/transfer gate independent of Android Settings initialization.
 * User intention to download is NOT consent to unattended peer selection.
 */
internal object A0AutoConsentPolicy {
    fun allows(
        userRequested:Boolean,
        enabledByUser:Boolean,
        devPilot:Boolean,
        downloadsAllowed:Boolean,
        destinationReady:Boolean,
    ):Boolean = userRequested && enabledByUser && devPilot &&
        downloadsAllowed && destinationReady
}
