package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
import kotlinx.coroutines.CancellationException

/** Read-only canonical catalog seam used by provider-owned runtime media access. */
internal fun interface OpenSubsonicMediaAssetStore {
    suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity?
}

internal enum class OpenSubsonicMediaAccessFailure {
    MEDIA_ASSET_NOT_FOUND,
    UNSUPPORTED_PROVIDER,
    ACCOUNT_NOT_CONFIGURED,
    ACCOUNT_MISMATCH,
    CREDENTIAL_UNAVAILABLE,
    INVALID_LOCATOR,
}

internal sealed interface OpenSubsonicMediaAccessResult {
    data class Ready(val request: OpenSubsonicRuntimeAudioRequest) : OpenSubsonicMediaAccessResult
    data class Failure(val reason: OpenSubsonicMediaAccessFailure) : OpenSubsonicMediaAccessResult
}

/**
 * Provider-owned bridge from a persisted canonical MediaAsset to ephemeral
 * authenticated OpenSubsonic access. It never writes catalog state.
 */
internal class OpenSubsonicMediaAccess(
    private val assets: OpenSubsonicMediaAssetStore,
    private val accounts: OpenSubsonicAccountRepository,
    private val saltFactory: () -> String = ::defaultOpenSubsonicSalt,
) {
    suspend fun resolve(mediaAssetId: String): OpenSubsonicMediaAccessResult {
        val asset = assets.mediaAsset(mediaAssetId)
            ?: return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.MEDIA_ASSET_NOT_FOUND,
            )

        val accountId = accountIdFromProvider(asset.provider)
            ?: return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.UNSUPPORTED_PROVIDER,
            )
        val account = accounts.current()
            ?: return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.ACCOUNT_NOT_CONFIGURED,
            )
        if (account.accountId != accountId) {
            return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH,
            )
        }

        val sourceKey = asset.sourceKey.trim()
        if (sourceKey.isEmpty()) {
            return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.INVALID_LOCATOR,
            )
        }

        // Capture before reading credentials so account edits during resolution
        // invalidate the entire attempt, including an A→B→A configuration race.
        val revision = OpenSubsonicAccountRevisionClock.current()
        // This is the first point at which credential storage is read. A
        // missing/invalidated Keystore value therefore makes historical assets
        // unavailable without mutating their canonical identity or availability.
        val config = accounts.savedConfig()
            ?: return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.CREDENTIAL_UNAVAILABLE,
            )
        if (config.accountId != accountId) {
            return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.ACCOUNT_MISMATCH,
            )
        }

        val lease = OpenSubsonicStreamLifecycleLease(
            providerKey = asset.provider,
            generation = revision,
            initialRevision = revision,
            revisionNow = OpenSubsonicAccountRevisionClock::current,
            savedConfig = accounts::savedConfig,
            configMatches = OpenSubsonicProvider(config)::matchesSavedConfig,
        )
        if (!lease.isCurrent()) {
            return OpenSubsonicMediaAccessResult.Failure(
                OpenSubsonicMediaAccessFailure.CREDENTIAL_UNAVAILABLE,
            )
        }

        return OpenSubsonicMediaAccessResult.Ready(
            OpenSubsonicRuntimeAudioRequest(
                recordingId = asset.recordingId,
                mediaAssetId = asset.id,
                providerKey = asset.provider,
                endpointUrl = "${config.normalizedServerUrl}/rest/stream.view",
                sourceKey = sourceKey,
                auth = config.auth,
                saltFactory = saltFactory,
                accountLease = lease,
            ),
        )
    }

    private fun accountIdFromProvider(providerKey: String): String? {
        if (!providerKey.startsWith(PROVIDER_PREFIX)) return null
        return providerKey.removePrefix(PROVIDER_PREFIX).takeIf(String::isNotBlank)
    }

    private companion object {
        const val PROVIDER_PREFIX = "opensubsonic:"
    }
}

/**
 * One-use runtime request description for original/direct OpenSubsonic audio.
 *
 * This is intentionally a regular, non-serializable class rather than a data
 * class/Parcelable. Secret-bearing auth material and provider source locator
 * stay private and are only materialized into HTTP parameters at request time.
 */
internal class OpenSubsonicRuntimeAudioRequest internal constructor(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val endpointUrl: String,
    private val sourceKey: String,
    private val auth: OpenSubsonicAuth,
    private val saltFactory: () -> String,
    private val accountLease: OpenSubsonicStreamLifecycleLease? = null,
) {
    val directOriginal: Boolean = true

    /** Private account freshness checks. Neither method exposes credentials. */
    internal fun accountIsCurrent(): Boolean = accountLease?.isCurrent() ?: true
    internal fun accountRevisionIsCurrent(): Boolean = accountLease?.revisionIsCurrent() ?: true

    internal fun materializeParameters(): Map<String, String> = linkedMapOf(
        "v" to OPEN_SUBSONIC_API_VERSION,
        "c" to OPEN_SUBSONIC_CLIENT_NAME,
        "id" to sourceKey,
        // OpenSubsonic defines raw as the explicit no-transcode stream format.
        "format" to "raw",
    ).apply {
        putAll(openSubsonicAuthenticationParameters(auth, saltFactory))
    }

    /** Provider-private POST query, never passed through logical Media3 DataSpec. */
    internal fun materializeTranscodeDecisionParameters(): Map<String, String> = linkedMapOf(
        "v" to OPEN_SUBSONIC_API_VERSION,
        "c" to OPEN_SUBSONIC_CLIENT_NAME,
        "f" to "json",
        "mediaId" to sourceKey,
        "mediaType" to "song",
    ).apply { putAll(openSubsonicAuthenticationParameters(auth, saltFactory)) }

    /** Provider-private GET query; opaque transcodeParams added without rewriting. */
    internal fun materializeTranscodeStreamParameters(): Map<String, String> = linkedMapOf(
        "v" to OPEN_SUBSONIC_API_VERSION,
        "c" to OPEN_SUBSONIC_CLIENT_NAME,
        "mediaId" to sourceKey,
        "mediaType" to "song",
    ).apply { putAll(openSubsonicAuthenticationParameters(auth, saltFactory)) }

    suspend fun probe(transport: OpenSubsonicRuntimeProbeTransport): OpenSubsonicRuntimeProbeResult = try {
        transport.probe(endpointUrl, materializeParameters())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // Never surface a transport exception: an HTTP client's exception may
        // include the full authenticated query string.
        OpenSubsonicRuntimeProbeResult.NETWORK_UNAVAILABLE
    }

    override fun toString(): String = buildString {
        append("OpenSubsonicRuntimeAudioRequest(")
        append("recordingId=").append(recordingId)
        append(", mediaAssetId=").append(mediaAssetId)
        append(", providerKey=").append(providerKey)
        append(", endpointUrl=").append(endpointUrl)
        append(", directOriginal=true")
        append(", locator=<redacted>, auth=<redacted>)")
    }
}

/**
 * Narrow provider-level probe seam. Production playback does not depend on it;
 * it exists so authenticated runtime access can be checked without crossing
 * into Media3/Queue and with typed, secret-free outcomes.
 */
internal fun interface OpenSubsonicRuntimeProbeTransport {
    suspend fun probe(
        endpointUrl: String,
        parameters: Map<String, String>,
    ): OpenSubsonicRuntimeProbeResult
}

internal enum class OpenSubsonicRuntimeProbeResult {
    AVAILABLE,
    NETWORK_UNAVAILABLE,
    AUTHENTICATION_REJECTED,
    REQUEST_REJECTED,
}

internal class RoomOpenSubsonicMediaAssetStore(
    context: Context,
) : OpenSubsonicMediaAssetStore {
    private val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()

    override suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity? =
        dao.mediaAssetById(mediaAssetId)
}

/** Android production composition root for the provider-owned seam. */
internal fun androidOpenSubsonicMediaAccess(context: Context): OpenSubsonicMediaAccess {
    val appContext = context.applicationContext
    return OpenSubsonicMediaAccess(
        assets = RoomOpenSubsonicMediaAssetStore(appContext),
        accounts = OpenSubsonicAccountRepository(
            persistence = SharedPrefsOpenSubsonicAccountPersistence(appContext),
            credentials = AndroidKeystoreOpenSubsonicCredentialStore(appContext),
        ),
    )
}
