package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource

/** Safe, bounded failures. No account names, native media IDs or HTTP errors. */
internal enum class OpenSubsonicStreamPreparationFailure {
    INVALID_IDENTITY,
    INVALID_ACCOUNT_GENERATION,
    INVALID_BITRATE,
    CAPABILITY_UNAVAILABLE,
}

internal sealed interface OpenSubsonicStreamPreparation {
    /**
     * Prepared is only a request to construct a private provider DataSource.
     * The per-asset decision, actual network and decoder result do not exist
     * until DataSource.open; even then audibility remains unobserved by Catalog.
     */
    class Ready internal constructor(
        val dataSource: DataSource,
        val requestedMode: OpenSubsonicStreamVariantMode,
    ) : OpenSubsonicStreamPreparation {
        override fun toString(): String =
            "OpenSubsonicStreamPreparation.Ready(mode=$requestedMode, negotiated=false, observed=false)"
    }

    data class Rejected(val reason: OpenSubsonicStreamPreparationFailure) :
        OpenSubsonicStreamPreparation
}

/**
 * Catalog-owned B13 consumer adapter. Playback supplies immutable canonical
 * RecordingId+MediaAssetId+providerKey, account generation and explicit quality
 * intent; Catalog owns capability gating, the actual decoder check, authenticated
 * POST/GET and the private Media3 HTTP boundary.
 *
 * The original route never demands a prior manifest refresh and remains raw.
 * MP3 merely prepares a request; the C11 engine still requires a positive,
 * matching per-MediaAsset getTranscodeDecision at DataSource.open. No implicit
 * fallback to ORIGINAL is attempted upon denial or failure.
 */
@UnstableApi
internal class OpenSubsonicPlaybackStreamAdapter(
    private val media3: OpenSubsonicMediaDataSourceFactory,
    private val capabilities: OpenSubsonicStreamPolicyGateway,
) {
    /** Side-effect-free snapshot. No network request during UI rendering. */
    fun previewCapabilities(
        accountGeneration: Long,
        providerKey: String,
    ): OpenSubsonicProviderStreamHandoff =
        capabilities.preview(accountGeneration, providerKey)

    /** Explicit discovery only; credential store and manifest transport are Catalog-private. */
    suspend fun refreshCapabilities(
        accountGeneration: Long,
        providerKey: String,
    ): OpenSubsonicProviderStreamHandoff =
        capabilities.refresh(accountGeneration, providerKey)

    fun prepareOriginal(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        accountGeneration: Long,
        onTruth: ((OpenSubsonicStreamTruthSnapshot) -> Unit)? = null,
    ): OpenSubsonicStreamPreparation =
        when (val planned = OpenSubsonicStreamVariantPlanner.original(
            recordingId, mediaAssetId, providerKey, accountGeneration,
        )) {
            is OpenSubsonicStreamVariantPlan.Accepted ->
                OpenSubsonicStreamPreparation.Ready(
                    media3.createWithVariant(planned.variant, onTruth),
                    OpenSubsonicStreamVariantMode.ORIGINAL,
                )
            is OpenSubsonicStreamVariantPlan.Rejected -> reject(planned.reason)
        }

    fun prepareCappedMp3(
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
        accountGeneration: Long,
        maxBitrateBps: Int,
        onTruth: ((OpenSubsonicStreamTruthSnapshot) -> Unit)? = null,
    ): OpenSubsonicStreamPreparation {
        // Playback B12 currently only offers LOW 96, MEDIUM 160 and HIGH 320
        // kbps. Never silently upscale or convert this into legacy best-effort.
        if (maxBitrateBps !in SUPPORTED_QUALITY_CEILINGS) {
            return OpenSubsonicStreamPreparation.Rejected(
                OpenSubsonicStreamPreparationFailure.INVALID_BITRATE,
            )
        }
        when (val original = OpenSubsonicStreamVariantPlanner.original(
            recordingId, mediaAssetId, providerKey, accountGeneration,
        )) {
            is OpenSubsonicStreamVariantPlan.Rejected -> return reject(original.reason)
            is OpenSubsonicStreamVariantPlan.Accepted -> Unit
        }
        val safe = capabilities.preview(accountGeneration, providerKey)
        if (safe.lifecycle != OpenSubsonicStreamLeaseStatus.CURRENT ||
            safe.transcoding !in setOf(
                OpenSubsonicStreamConfidence.ADVERTISED,
                OpenSubsonicStreamConfidence.VERIFIED,
            )
        ) {
            return OpenSubsonicStreamPreparation.Rejected(
                OpenSubsonicStreamPreparationFailure.CAPABILITY_UNAVAILABLE,
            )
        }
        val planned = OpenSubsonicStreamVariantPlanner.extensionRequested(
            recordingId, mediaAssetId, providerKey, accountGeneration,
            safe.transcoding, OpenSubsonicDecoderProfile.MP3_HTTP, maxBitrateBps,
        )
        return when (planned) {
            is OpenSubsonicStreamVariantPlan.Accepted ->
                OpenSubsonicStreamPreparation.Ready(
                    media3.createWithVariant(planned.variant, onTruth),
                    OpenSubsonicStreamVariantMode.EXTENSION_TRANSCODE_REQUESTED,
                )
            is OpenSubsonicStreamVariantPlan.Rejected -> reject(planned.reason)
        }
    }

    private fun reject(reason: OpenSubsonicStreamVariantRejection): OpenSubsonicStreamPreparation.Rejected =
        OpenSubsonicStreamPreparation.Rejected(
            when (reason) {
                OpenSubsonicStreamVariantRejection.INVALID_IDENTITY ->
                    OpenSubsonicStreamPreparationFailure.INVALID_IDENTITY
                OpenSubsonicStreamVariantRejection.INVALID_ACCOUNT_GENERATION ->
                    OpenSubsonicStreamPreparationFailure.INVALID_ACCOUNT_GENERATION
                OpenSubsonicStreamVariantRejection.INVALID_BITRATE ->
                    OpenSubsonicStreamPreparationFailure.INVALID_BITRATE
                OpenSubsonicStreamVariantRejection.CAPABILITY_NOT_ADVERTISED ->
                    OpenSubsonicStreamPreparationFailure.CAPABILITY_UNAVAILABLE
            },
        )

    private companion object {
        val SUPPORTED_QUALITY_CEILINGS = setOf(96_000, 160_000, 320_000)
    }
}

/** Android composition remains in Catalog; Playback gets no secret or URL. */
@UnstableApi
internal fun androidOpenSubsonicPlaybackStreamAdapter(
    context: Context,
): OpenSubsonicPlaybackStreamAdapter = OpenSubsonicPlaybackStreamAdapter(
    media3 = androidOpenSubsonicMediaDataSourceFactory(context),
    capabilities = androidOpenSubsonicStreamPolicyGateway(context),
)
