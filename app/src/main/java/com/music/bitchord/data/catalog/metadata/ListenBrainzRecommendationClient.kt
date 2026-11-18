package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest

internal sealed interface ListenBrainzRecommendationFetchResult {
    data class Success(
        val items: List<ListenBrainzRecordingRecommendation>,
        val fetchedAtMs: Long,
        val upstreamUpdatedSeconds: Long,
        val fromCache: Boolean,
    ) : ListenBrainzRecommendationFetchResult

    data object NoRecommendations : ListenBrainzRecommendationFetchResult
    data object InvalidRequest : ListenBrainzRecommendationFetchResult
    data object NotAuthenticated : ListenBrainzRecommendationFetchResult
    data object Unavailable : ListenBrainzRecommendationFetchResult
    data object Malformed : ListenBrainzRecommendationFetchResult
    data object Stale : ListenBrainzRecommendationFetchResult
}

/**
 * Experimental collaborative filtering. Must be called only after opt-in and
 * followed by independent MusicBrainz Recording verification before display.
 * Nothing here creates playable assets or assigns a score to local tracks.
 */
internal class ListenBrainzRecommendationClient(
    private val transport: ListenBrainzRecommendationTransport =
        KtorListenBrainzRecommendationTransport(),
    private val limiter: ListenBrainzRateLimiter = ListenBrainzRequestBudget.limiter,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val gateProvider: () -> ProviderPolicyGate? = {
        ProviderRuntime.gateForCacheReadOrNull()
    },
) {
    private data class Cached(
        val accountDigest: String,
        val count: Int,
        val success: ListenBrainzRecommendationFetchResult.Success,
    )
    private data class NegativeCache(
        val accountDigest: String,
        val count: Int,
        val fetchedAtMs: Long,
    )
    private val mutex = Mutex()
    private var cached: Cached? = null
    private var noRecommendations: NegativeCache? = null

    suspend fun recordings(token: String, count: Int = 16): ListenBrainzRecommendationFetchResult {
        if (token.isBlank()) return ListenBrainzRecommendationFetchResult.NotAuthenticated
        if (count !in 1..50) return ListenBrainzRecommendationFetchResult.InvalidRequest
        // Account-specific recommendations have an independent user opt-out:
        // never borrow authorization from scrobbling or public charts. The
        // executor cancels both HTTP legs when this capability is revoked.
        val gate = gateProvider() ?: return ListenBrainzRecommendationFetchResult.Unavailable
        return when (val outcome = ProviderQueryExecutor(gate).execute(
            ProviderCapability.PERSONAL_RECOMMENDATIONS,
            BuiltInProviderKeys.LISTENBRAINZ,
        ) { recordingsAuthorized(token, count) }) {
            is ProviderCallResult.Success -> outcome.value
            ProviderCallResult.Denied,
            ProviderCallResult.Stale,
            is ProviderCallResult.Failed -> ListenBrainzRecommendationFetchResult.Unavailable
        }
    }

    private suspend fun recordingsAuthorized(
        token: String, count: Int,
    ): ListenBrainzRecommendationFetchResult {
        val account = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        mutex.lock()
        try {
            val now = nowMillis()
            cached?.takeIf {
                it.accountDigest == account && it.count == count &&
                    now >= it.success.fetchedAtMs &&
                    now - it.success.fetchedAtMs < 60L * 60L * 1_000L
            }?.let { return it.success.copy(fromCache = true) }

            // A 204 means the CF model is not ready for this account.
            // Avoid repeating token validation + the same 204 on every visit.
            noRecommendations?.takeIf {
                it.accountDigest == account && it.count == count &&
                    now >= it.fetchedAtMs &&
                    now - it.fetchedAtMs < 15L * 60L * 1_000L
            }?.let { return ListenBrainzRecommendationFetchResult.NoRecommendations }

            val validated = try {
                getWithRetry { transport.validateToken(token) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ListenBrainzRecommendationFetchResult.Unavailable
            }
            if (validated.status != 200) return ListenBrainzRecommendationFetchResult.Unavailable
            val user = parseListenBrainzValidatedAccount(validated.body)
                ?: return ListenBrainzRecommendationFetchResult.NotAuthenticated
            val response = try {
                getWithRetry { transport.recommendations(user, count) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ListenBrainzRecommendationFetchResult.Unavailable
            }
            if (response.status == 204) {
                noRecommendations = NegativeCache(account, count, nowMillis())
                return ListenBrainzRecommendationFetchResult.NoRecommendations
            }
            if (response.status != 200) return ListenBrainzRecommendationFetchResult.Unavailable
            val parsed = parseListenBrainzRecommendationFeed(
                response.body, expectedUser = user, nowSeconds = nowMillis() / 1_000L,
            ) as? ListenBrainzRecommendationParseResult.Success
                ?: return ListenBrainzRecommendationFetchResult.Malformed
            if (parsed.items.isEmpty()) {
                noRecommendations = NegativeCache(account, count, nowMillis())
                return ListenBrainzRecommendationFetchResult.NoRecommendations
            }
            // A historical model is not a current personalized suggestion.
            if (parsed.lastUpdatedSeconds < nowMillis() / 1_000L - 30L * 24L * 60L * 60L) {
                return ListenBrainzRecommendationFetchResult.Stale
            }
            val success = ListenBrainzRecommendationFetchResult.Success(
                items = parsed.items,
                fetchedAtMs = nowMillis(),
                upstreamUpdatedSeconds = parsed.lastUpdatedSeconds,
                fromCache = false,
            )
            noRecommendations = null
            cached = Cached(account, count, success)
            return success
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun getWithRetry(
        request: suspend () -> ListenBrainzRecommendationHttpResponse,
    ): ListenBrainzRecommendationHttpResponse {
        var response = limiter.run { request() }
        limiter.observeRateLimit(response.rateLimitRemaining, response.rateLimitResetInSeconds)
        if (response.status == 429 || response.status == 503) {
            retryDelay((response.retryAfterSeconds ?: 1L).coerceIn(1L, 30L) * 1_000L)
            response = limiter.run { request() }
            limiter.observeRateLimit(response.rateLimitRemaining, response.rateLimitResetInSeconds)
        }
        return response
    }
}
