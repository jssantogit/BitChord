package com.music.bitchord.data.catalog.metadata

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class ListenBrainzRecommendationHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
    val rateLimitRemaining: Long? = null,
    val rateLimitResetInSeconds: Long? = null,
)

internal interface ListenBrainzRecommendationTransport {
    suspend fun validateToken(token: String): ListenBrainzRecommendationHttpResponse
    suspend fun recommendations(userName: String, count: Int): ListenBrainzRecommendationHttpResponse
}

/** Account tokens are sent only in an Authorization header, never in URLs or logs. */
internal class KtorListenBrainzRecommendationTransport(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
            socketTimeoutMillis = 15_000L
        }
    },
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
) : ListenBrainzRecommendationTransport {
    private suspend fun <T> authorized(block: suspend () -> T): T =
        admission.execute(
            capability = ProviderCapability.PERSONAL_RECOMMENDATIONS,
            provider = BuiltInProviderKeys.LISTENBRAINZ,
            scheduleLimited = { task -> task() },
            networkCall = block,
        )

    override suspend fun validateToken(token: String): ListenBrainzRecommendationHttpResponse =
        authorized { validateTokenUnchecked(token) }

    private suspend fun validateTokenUnchecked(token: String): ListenBrainzRecommendationHttpResponse =
        measureMetadataHttp(
            provider = "listenbrainz", endpoint = "user_validation",
            status = { r: ListenBrainzRecommendationHttpResponse -> r.status },
        ) {
            val response = client.get("https://api.listenbrainz.org/1/validate-token") {
                header(HttpHeaders.Authorization, "Token $token")
                header(HttpHeaders.UserAgent, "BitChord/" + BuildConfig.VERSION_NAME)
            }
            ListenBrainzRecommendationHttpResponse(
                status = response.status.value,
                body = if (response.status.value == 200) response.bodyAsText() else "",
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                rateLimitRemaining = response.headers["X-RateLimit-Remaining"]?.toLongOrNull(),
                rateLimitResetInSeconds = response.headers["X-RateLimit-Reset-In"]?.toLongOrNull(),
            )
        }

    override suspend fun recommendations(
        userName: String, count: Int,
    ): ListenBrainzRecommendationHttpResponse =
        authorized { recommendationsUnchecked(userName, count) }

    private suspend fun recommendationsUnchecked(
        userName: String, count: Int,
    ): ListenBrainzRecommendationHttpResponse = measureMetadataHttp(
        provider = "listenbrainz", endpoint = "recording_recommendations",
        status = { r: ListenBrainzRecommendationHttpResponse -> r.status },
    ) {
        val encoded = URLEncoder.encode(userName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val response = client.get(
            "https://api.listenbrainz.org/1/cf/recommendation/user/$encoded/recording",
        ) {
            header(HttpHeaders.UserAgent, "BitChord/" + BuildConfig.VERSION_NAME)
            parameter("count", count)
            parameter("offset", 0)
        }
        ListenBrainzRecommendationHttpResponse(
            status = response.status.value,
            body = if (response.status.value == 200) response.bodyAsText() else "",
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
            rateLimitRemaining = response.headers["X-RateLimit-Remaining"]?.toLongOrNull(),
            rateLimitResetInSeconds = response.headers["X-RateLimit-Reset-In"]?.toLongOrNull(),
        )
    }
}

internal fun parseListenBrainzValidatedAccount(raw: String): String? {
    if (raw.length > 4_096) return null
    val root = try { Json.parseToJsonElement(raw) as? JsonObject }
        catch (_: Exception) { null } ?: return null
    if ((root["valid"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val username = (root["user_name"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
    if (username.isBlank() || username.length > 128 ||
        username.any { it.isISOControl() }
    ) return null
    return username
}
