package com.music.bitchord.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalRatingAssetRow
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRemoteRatingReader
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.playback.CanonicalRemotePlaybackRequest
import com.music.bitchord.playback.CanonicalRemotePlaybackUri
import com.music.bitchord.ui.components.CanonicalRatingDisplaySnapshot
import com.music.bitchord.ui.components.RatingDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Never transport a provider-native key, raw source URI or credentials to UI. */
internal data class CanonicalRemoteRatingInput(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val scopeId: String,
    val numericValue: Int?,
    val starred: Boolean,
)

/**
 * Pure, linear-time projection for both album/playlist rows and player chrome.
 * RecordingId joins the local note; MediaAssetId selects the exact remote note.
 * No title/artist heuristics, cross-account preference copies or N+1 IO.
 */
internal fun buildCanonicalRatingPresentation(
    assets: List<CanonicalRatingAssetRow>,
    localValues: Map<String, Double>,
    remote: List<CanonicalRemoteRatingInput>,
    activeProvider: String?,
    activeScope: String?,
): CanonicalRatingDisplaySnapshot {
    val remoteByAsset = if (activeProvider != null && !activeScope.isNullOrBlank()) {
        remote.asSequence().filter {
            it.providerKey == activeProvider &&
                it.scopeId == activeScope &&
                it.numericValue in 1..5
        }.associateBy { it.mediaAssetId }
    } else emptyMap()

    val byAsset = LinkedHashMap<String, RatingDisplay>()
    val bySong = LinkedHashMap<String, RatingDisplay>()
    assets.forEach { asset ->
        val local = localValues[asset.recordingId]
            ?.takeIf { it.isFinite() && it in 1.0..5.0 }
        // Scope AND complete canonical identity must agree. A favorite alone,
        // zero/cleared, invalid value or another account cannot become 5 stars.
        val remoteNumeric = remoteByAsset[asset.mediaAssetId]
            ?.takeIf {
                it.recordingId == asset.recordingId &&
                    it.providerKey == asset.providerKey
            }?.numericValue
        if (local == null && remoteNumeric == null) return@forEach

        val display = RatingDisplay(localValue = local, remoteValue = remoteNumeric)
        byAsset[asset.mediaAssetId] = display

        val key = when {
            asset.providerKey == "local-mediastore" -> asset.uri ?: asset.path
            asset.providerKey == activeProvider -> CanonicalRemotePlaybackUri.encode(
                CanonicalRemotePlaybackRequest(
                    recordingId = asset.recordingId,
                    mediaAssetId = asset.mediaAssetId,
                    providerKey = asset.providerKey,
                ),
            )
            else -> null
        }
        key?.takeIf(String::isNotBlank)?.let { bySong[it] = display }
        // Local paths and SAF/MediaStore URIs are distinct legitimate queue keys.
        if (asset.providerKey == "local-mediastore") {
            asset.uri?.takeIf(String::isNotBlank)?.let { bySong[it] = display }
            asset.path?.takeIf(String::isNotBlank)?.let { bySong[it] = display }
        }
    }
    return CanonicalRatingDisplaySnapshot(
        bySongKey = bySong,
        byMediaAssetId = byAsset,
    )
}

private data class ScopedRatingPresentation(
    val account: OpenSubsonicAccountRecord?,
    val snapshot: CanonicalRatingDisplaySnapshot,
)

/**
 * Exactly one Room-local Flow, one Room remote cache-revision Flow, and one
 * account preference Flow. Reads are batched on IO and never triggered by
 * scrolling, song-row recomposition, Now Playing or a menu open.
 */
@Composable
internal fun rememberCanonicalRatingDisplay(): CanonicalRatingDisplaySnapshot {
    val context = LocalContext.current.applicationContext
    val catalog = remember(context) { CanonicalCatalogDatabase.get(context) }
    val listening = remember(context) { ListeningEventDatabase.get(context) }
    val persistence = remember(context) { SharedPrefsOpenSubsonicAccountPersistence(context) }
    val reader = remember(catalog, persistence) {
        OpenSubsonicRemoteRatingReader(catalog) { persistence.read() }
    }
    val accountFlow = remember(persistence) { persistence.observe() }
    val account by accountFlow.collectAsStateWithLifecycle(initialValue = persistence.read())
    val localFlow = remember(listening) { listening.recordingRatingDao().observeAllCurrentRatings() }
    val localRows by localFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val revisionFlow = remember(catalog) { catalog.catalogDao().observeRemoteRatingCoverage() }
    val revisions by revisionFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val result by produceState(
        initialValue = ScopedRatingPresentation(null, CanonicalRatingDisplaySnapshot()),
        key1 = account,
        key2 = localRows,
        key3 = revisions,
    ) {
        value = try {
            withContext(Dispatchers.IO) {
                val liveAccount = persistence.read()
                if (liveAccount != account) {
                    ScopedRatingPresentation(null, CanonicalRatingDisplaySnapshot())
                } else {
                    val activeProvider = account?.let { "opensubsonic:${it.accountId}" }
                    val activeScope = account?.ratingScopeId
                    val snapshot = reader.read()
                    val remote = if (snapshot.availability == RemoteRatingAvailability.CACHED_COMPLETE &&
                        !activeScope.isNullOrBlank() &&
                        snapshot.items.all {
                            it.providerKey == activeProvider && it.scopeId == activeScope
                        }
                    ) {
                        snapshot.items.map { item ->
                            CanonicalRemoteRatingInput(
                                recordingId = item.recordingId,
                                mediaAssetId = item.mediaAssetId,
                                providerKey = item.providerKey,
                                scopeId = item.scopeId,
                                numericValue = item.numeric.value
                                    ?.takeIf { item.numeric.kind == RemoteNumericRatingKind.VALUE },
                                starred = item.starred.name == "STARRED",
                            )
                        }
                    } else emptyList()
                    val assets = catalog.catalogDao().ratingPresentationAssets(
                        localProvider = "local-mediastore",
                        remoteProvider = activeProvider ?: "__no_active_opensubsonic_account__",
                    )
                    val assembled = buildCanonicalRatingPresentation(
                        assets = assets,
                        localValues = localRows.associate { it.recordingId to it.ratingValue },
                        remote = remote,
                        activeProvider = activeProvider,
                        activeScope = activeScope,
                    )
                    // A logout/switch mid-query cannot paint the previous user's stars.
                    if (persistence.read() != account) {
                        ScopedRatingPresentation(null, CanonicalRatingDisplaySnapshot())
                    } else {
                        ScopedRatingPresentation(account, assembled)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ScopedRatingPresentation(null, CanonicalRatingDisplaySnapshot())
        }
    }
    return if (result.account == account) result.snapshot else CanonicalRatingDisplaySnapshot()
}
