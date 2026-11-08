package com.music.bitchord

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.bitchord.ui.CanonicalSitewideDiscoveryState
import com.music.bitchord.ui.CanonicalPersonalRecommendationState
import com.music.bitchord.ui.canonicalSitewideDiscoveryIsFresh
import com.music.bitchord.ui.canonicalSitewideDiscoveryExpiresAtMs
import com.music.bitchord.ui.canonicalPersonalRecommendationIsFresh
import com.music.bitchord.ui.canonicalPersonalRecommendationExpiresAtMs
import kotlinx.coroutines.delay

/** Preserve the same expiry-triggered refresh but keep Compose state machines
 * outside the giant BitChordApp renderer (JVM methods have a 64-KiB limit).
 * Expired data is never promoted to a current chart or personal shelf. */
@Composable
internal fun rememberCatalogSitewideFreshness(
    active: Boolean,
    selectedTab: Int,
    snapshot: CanonicalSitewideDiscoveryState,
    onExpiry: () -> Unit,
): Long {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(selectedTab, snapshot.fetchedAtMs, snapshot.upstreamUpdatedAtSeconds) {
        if (!active) return@LaunchedEffect
        val now = System.currentTimeMillis()
        nowMs = now
        if (!canonicalSitewideDiscoveryIsFresh(snapshot, now)) return@LaunchedEffect
        val expires = canonicalSitewideDiscoveryExpiresAtMs(snapshot) ?: return@LaunchedEffect
        val delayMs = expires - now
        if (delayMs > 0L) delay(delayMs + 1L)
        nowMs = System.currentTimeMillis()
        onExpiry()
    }
    return nowMs
}

/** No ListenBrainz token or private score enters a public recommendation shelf. */
@Composable
internal fun rememberCatalogPersonalFreshness(
    active: Boolean,
    selectedTab: Int,
    snapshot: CanonicalPersonalRecommendationState,
    accountEnabled: Boolean,
    accountToken: String,
    onExpiry: () -> Unit,
): Long {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(
        selectedTab, snapshot.fetchedAtMs, snapshot.upstreamUpdatedSeconds,
        accountEnabled, accountToken,
    ) {
        if (!active) return@LaunchedEffect
        val now = System.currentTimeMillis()
        nowMs = now
        if (!canonicalPersonalRecommendationIsFresh(snapshot, now)) return@LaunchedEffect
        val expires = canonicalPersonalRecommendationExpiresAtMs(snapshot) ?: return@LaunchedEffect
        val delayMs = expires - now
        if (delayMs > 0L) delay(delayMs + 1L)
        nowMs = System.currentTimeMillis()
        onExpiry()
    }
    return nowMs
}
