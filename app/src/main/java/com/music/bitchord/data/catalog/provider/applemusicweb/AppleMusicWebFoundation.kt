package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.ProviderCapability
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Distinguishes valid complete, truncated/partial and unavailable results.
 * A page may legitimately be empty; that is not synonymous with unavailable.
 */
internal enum class AppleMusicWebCompleteness {
    PAGE_LIMIT,
    UPSTREAM_INTERRUPTED,
    MISSING_TRACKLIST,
    TRACK_COUNT_MISMATCH,
}

internal sealed interface AppleMusicWebOutcome<out T> {
    data class Complete<T>(val value: T) : AppleMusicWebOutcome<T>
    data class Partial<T>(
        val value: T,
        val reason: AppleMusicWebCompleteness,
    ) : AppleMusicWebOutcome<T>
    data class Unavailable(val reason: String) : AppleMusicWebOutcome<Nothing>
}

internal sealed interface AppleMusicWebBatchResult<out T> {
    val input: String
    data class Success<T>(override val input: String, val value: T) : AppleMusicWebBatchResult<T>
    data class Failure(
        override val input: String,
        val failure: AppleMusicWebBatchFailure,
        val httpStatus: Int? = null,
    ) : AppleMusicWebBatchResult<Nothing>
}

internal enum class AppleMusicWebBatchFailure {
    HTTP_ERROR,
    NETWORK_ERROR,
    INVALID_INPUT,
    INVALID_RESPONSE,
}

/**
 * One suspending implementation, bounded concurrency, ordered output.
 * No catch-all: cancellation and programming faults cancel the parent.
 * The caller's provider admission/revocation still owns every network request.
 */
internal class AppleMusicWebBatch(maxConcurrency: Int = 4) {
    private val semaphore = Semaphore(maxConcurrency.also {
        require(it in 1..16) { "Invalid Apple web batch concurrency" }
    })

    suspend fun <T> fetch(
        inputs: List<String>,
        fetchOne: suspend (String) -> T,
    ): List<AppleMusicWebBatchResult<T>> = coroutineScope {
        require(inputs.size <= 500) { "Unbounded Apple web batch" }
        inputs.map { input ->
            async {
                semaphore.withPermit {
                    try {
                        AppleMusicWebBatchResult.Success(input, fetchOne(input))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: AppleMusicWebHttpException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.HTTP_ERROR, failure.status,
                        )
                    } catch (_: AppleMusicWebProtocolException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.INVALID_RESPONSE,
                        )
                    } catch (_: IllegalArgumentException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.INVALID_INPUT,
                        )
                    } catch (_: IOException) {
                        AppleMusicWebBatchResult.Failure(
                            input, AppleMusicWebBatchFailure.NETWORK_ERROR,
                        )
                    }
                }
            }
        }.awaitAll()
    }
}

/**
 * Contract for the existing provider cache — not a second storage engine.
 * No token, cookie, account URL, personal data or non-Apple request may
 * become a persisted cache entry. Disabled providers still need admission
 * before a cached response is ever served.
 */
internal object AppleMusicWebCacheContract {
    private val storefront = Regex("[a-z]{2}")
    private val locale = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}")
    private val pathFormat = Regex("^/v1/catalog/[a-z]{2}/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")
    private val forbidden = listOf("auth", "token", "cookie", "session", "secret", "password", "key")

    fun key(
        capability: ProviderCapability,
        market: String,
        language: String,
        path: String,
        parameters: Map<String, String>,
    ): String? {
        val country = market.lowercase(Locale.ROOT)
        if (!storefront.matches(country) || !locale.matches(language) ||
            !pathFormat.matches(path) || !path.startsWith("/v1/catalog/$country/") ||
            capability !in setOf(ProviderCapability.CATALOG_SEARCH, ProviderCapability.EDITORIAL_DISCOGRAPHY) ||
            parameters.size > 30
        ) return null
        if (parameters.any { (name, value) ->
                val normalized = name.lowercase(Locale.ROOT)
                forbidden.any(normalized::contains) ||
                    name.length !in 1..100 || value.length > 4096 ||
                    value.any(Char::isISOControl)
            }
        ) return null
        val record = buildString {
            append("apple_music_web:v1\n")
            append(capability.name).append('\n')
            append(country).append('\n')
            append(language).append('\n')
            append(path).append('\n')
            for ((name, value) in parameters.toSortedMap()) {
                append(name.length).append(':').append(name)
                append(value.length).append(':').append(value).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(record.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
