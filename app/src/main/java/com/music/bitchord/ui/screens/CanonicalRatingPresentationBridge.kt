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
                it.numericValue != null && it.numericValue in 1..5
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
    // Build an *identity* projection independent of whether a song is rated:
    // a local user can score a canonical recording for the first time without
    // asking Navidrome (or any network) for metadata. Only current-account
    // verified MediaAsset rows participate, never titles or external IDs.
    val recordingBySongKey = LinkedHashMap<String, String>()
    val ambiguousKeys = HashSet<String>()
    fun recordKey(key: String?, recordingId: String) {
        if (key.isNullOrBlank() || key in ambiguousKeys) return
        val existing = recordingBySongKey[key]
        if (existing != null && existing != recordingId) {
            recordingBySongKey.remove(key)
            ambiguousKeys.add(key)
        } else {
            recordingBySongKey[key] = recordingId
        }
    }
    assets.forEach { asset ->
        when {
            asset.providerKey == "local-mediastore" -> {
                recordKey(asset.uri, asset.recordingId)
                recordKey(asset.path, asset.recordingId)
            }
            asset.providerKey == activeProvider && !activeScope.isNullOrBlank() -> {
                recordKey(
                    CanonicalRemotePlaybackUri.encode(
                        CanonicalRemotePlaybackRequest(
                            recordingId = asset.recordingId,
                            mediaAssetId = asset.mediaAssetId,
                            providerKey = asset.providerKey,
                        ),
                    ),
                    asset.recordingId,
                )
            }
        }
    }
    // Identity ambiguity also invalidates the *visible badge*. A Song.videoId
    // or content URI shared by two distinct Recordings must not inherit the
    // last writer's local score merely because its MediaAsset was traversed
    // second. Exact MediaAssetId displays remain unambiguous.
    ambiguousKeys.forEach(bySong::remove)
    // Smart playlists and adaptive collections expose a RecordingId rather than
    // a chosen MediaAssetId. Do not guess if two assets disagree.
    val byRecording = assets.groupBy { it.recordingId }.mapNotNull { (id, rows) ->
        val distinct = rows.mapNotNull { byAsset[it.mediaAssetId] }.distinct()
        if (distinct.size == 1) id to distinct.single() else null
    }.toMap()
    return CanonicalRatingDisplaySnapshot(
        bySongKey = bySong,
        byMediaAssetId = byAsset,
        byRecordingId = byRecording,
        recordingIdBySongKey = recordingBySongKey,
    )
}

private data class ScopedRatingPresentation(
    val account: OpenSubsonicAccountRecord?,
    val snapshot: CanonicalRatingDisplaySnapshot,
)

/**
 * Local ratings, current account's canonical MediaAssets and remote cache
 * revision are independently observed. CPU projection and remote cached reads
 * run on IO; scrolling and Now Playing never execute N+1 Room queries.
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
    val activeProvider = account?.let { "opensubsonic:${it.accountId}" }
        ?: "__no_active_opensubsonic_account__"
    val assetFlow = remember(catalog, activeProvider) {
        catalog.catalogDao().observeRatingPresentationAssets(
            localProvider = "local-mediastore",
            remoteProvider = activeProvider,
        )
    }
    val assets by assetFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Either an asset change (import/deletion) or a remote rating cache update
    // refreshes the projection. No UI row owns a Room query.
    val catalogRevision = remember(assets, revisions) { assets to revisions }

    val result by produceState(
        initialValue = ScopedRatingPresentation(null, CanonicalRatingDisplaySnapshot()),
        key1 = account,
        key2 = localRows,
        key3 = catalogRevision,
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
