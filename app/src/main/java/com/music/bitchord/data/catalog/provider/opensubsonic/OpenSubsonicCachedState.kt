package com.music.bitchord.data.catalog.provider.opensubsonic

/** Public Internet connectivity is deliberately distinct from LAN/server reachability. */
internal enum class OpenSubsonicPublicInternetState {
    UNKNOWN, CONNECTED, DISCONNECTED,
}

/**
 * Only an explicit authenticated protocol probe may report AUTHENTICATION_FAILED
 * or INVALID_RESPONSE. A failed ping persisted as a Boolean proves neither.
 */
internal enum class OpenSubsonicConnectionState {
    UNKNOWN, SERVER_REACHABLE, SERVER_UNREACHABLE, AUTHENTICATION_FAILED, INVALID_RESPONSE,
}

internal enum class OpenSubsonicSyncFreshness {
    UNKNOWN, FRESH, STALE,
}

/** A safe, local-only observation, not a playback/source-resolution verdict. */
internal data class OpenSubsonicCachedState(
    val server: OpenSubsonicConnectionState,
    val publicInternet: OpenSubsonicPublicInternetState,
    val cachedRecordingCount: Int,
    val cachedLibraryPresent: Boolean,
    val libraryEligible: Boolean,
    val freshness: OpenSubsonicSyncFreshness,
    val syncInProgress: Boolean,
    /** Never inferred from persisted MediaAsset presence or a successful ping. */
    val playableNowVerified: Boolean = false,
)

/**
 * Read projection: health may fail without deleting a Recording or removing its
 * cached eligibility. Never include server URL, query tokens or credentials.
 */
internal object OpenSubsonicCachedStateProjector {
    fun project(
        account: OpenSubsonicAccountRecord?,
        credentialPresent: Boolean,
        cachedRecordingCount: Int,
        syncInProgress: Boolean,
        nowEpochSeconds: Long,
        maxFreshAgeSeconds: Long,
        latestConnection: OpenSubsonicConnectionState? = null,
        publicInternet: OpenSubsonicPublicInternetState = OpenSubsonicPublicInternetState.UNKNOWN,
    ): OpenSubsonicCachedState {
        require(cachedRecordingCount >= 0) { "Cached recording count cannot be negative" }
        require(maxFreshAgeSeconds >= 0) { "Freshness horizon cannot be negative" }
        val server = if (account == null) OpenSubsonicConnectionState.UNKNOWN else
            latestConnection ?: when (account.lastHealthReachable) {
                true -> OpenSubsonicConnectionState.SERVER_REACHABLE
                false -> OpenSubsonicConnectionState.SERVER_UNREACHABLE
                null -> OpenSubsonicConnectionState.UNKNOWN
            }
        // The legacy timestamp means last ATTEMPT, not necessarily last good
        // snapshot. A failed last sync cannot be used to claim fresh cache.
        val lastVerifiedAttempt = account?.takeIf { it.lastSyncSucceeded == true }
            ?.lastSyncAtEpochSeconds
        val freshness = when {
            lastVerifiedAttempt == null -> OpenSubsonicSyncFreshness.UNKNOWN
            nowEpochSeconds < lastVerifiedAttempt -> OpenSubsonicSyncFreshness.UNKNOWN
            nowEpochSeconds - lastVerifiedAttempt <= maxFreshAgeSeconds ->
                OpenSubsonicSyncFreshness.FRESH
            else -> OpenSubsonicSyncFreshness.STALE
        }
        val cacheExists = cachedRecordingCount > 0
        return OpenSubsonicCachedState(
            server = server,
            publicInternet = publicInternet,
            cachedRecordingCount = cachedRecordingCount,
            cachedLibraryPresent = cacheExists,
            libraryEligible = account != null && credentialPresent && cacheExists,
            freshness = freshness,
            syncInProgress = syncInProgress,
        )
    }
}
