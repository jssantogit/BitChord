package com.music.bitchord.playback

/**
 * A local MediaStore rendition is not tied to the OpenSubsonic login.
 * Remote renditions must belong to a currently configured provider; that
 * provider and exact MediaAsset are checked again by B10 before playback.
 */
internal fun canonicalBatchProviderAllowed(
    provider: String,
    configuredRemoteProviders: Collection<String>,
): Boolean = provider == RoomCanonicalQueueResolver.LOCAL_PROVIDER ||
    (provider.startsWith("opensubsonic:") && provider in configuredRemoteProviders)
