package com.music.bitchord.data.catalog.provider.applemusicweb

import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.RateLimitedProviderTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Browser-web public catalog transport, NOT the documented iTunes API.
 * In particular, a web bundle JWT does not authorize a music-user session,
 * playback, private recommendations, personal library, or lyric retrieval.
 */
internal enum class AppleMusicWebHost(val baseUrl: String) {
    MUSIC("https://music.apple.com"),
    API("https://amp-api.music.apple.com"),
}

internal data class AppleMusicWebHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** HTTP exchange is injected so tests run with no network or captured secrets. */
internal fun interface AppleMusicWebExchange {
    suspend fun get(
        host: AppleMusicWebHost,
        path: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
        maxBytes: Int,
    ): AppleMusicWebHttpResponse
}

internal class AppleMusicWebHttpException(val status: Int) :
    IOException("Apple Music Web HTTP $status")

internal class KtorAppleMusicWebExchange(
    private val client: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    },
) : AppleMusicWebExchange {
    override suspend fun get(
        host: AppleMusicWebHost, path: String,
        parameters: Map<String, String>, headers: Map<String, String>, maxBytes: Int,
    ): AppleMusicWebHttpResponse {
        require(path.startsWith("/") && !path.startsWith("//") &&
            !path.contains("..") && !path.contains("?") && !path.contains("#") &&
            path.length <= 1024)
        require(maxBytes in 1..8_000_000)
        val response = client.get(host.baseUrl + path) {
            parameters.forEach { (name, value) -> parameter(name, value) }
            headers.forEach { (name, value) -> header(name, value) }
        }
        val channel = response.bodyAsChannel()
        val size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (size != null && size > maxBytes) {
            channel.cancel(IOException("Oversized Apple Music Web response"))
            throw IOException("Oversized Apple Music Web response")
        }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(chunk, 0, minOf(chunk.size, maxBytes - out.size() + 1))
            if (count == -1) break
            if (count == 0) continue
            if (out.size() + count > maxBytes) {
                channel.cancel(IOException("Oversized Apple Music Web response"))
                throw IOException("Oversized Apple Music Web response")
            }
            out.write(chunk, 0, count)
        }
        return AppleMusicWebHttpResponse(
            response.status.value, out.toString(Charsets.UTF_8.name()),
            response.headers[HttpHeaders.RetryAfter]?.let {
                mapOf("Retry-After" to it.take(64))
            } ?: emptyMap(),
        )
    }
}

internal fun interface AppleMusicWebTokenSource {
    suspend fun token(forceRefresh: Boolean): AppleMusicWebToken
}

/**
 * A public Apple web bundle currently embeds a short-lived developer token.
 * No hardcoded JWT, no user credentials and no persistence; fail closed on
 * layout/token changes. Fetch only AFTER a provider admission ticket opens.
 */
internal class AppleMusicWebBundleTokenSource(
    private val exchange: AppleMusicWebExchange,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : AppleMusicWebTokenSource {
    private val lock = Mutex()
    private var cached: AppleMusicWebToken? = null
    private val bundleLink = Regex("""src=["'](/assets/index~[A-Za-z0-9_.~-]+\.js)["']""")

    override suspend fun token(forceRefresh: Boolean): AppleMusicWebToken =
        lock.withLock {
            val now = clockSeconds()
            cached?.takeIf { !forceRefresh && it.expiresAtEpochSeconds > now + 86400L }
                ?.let { return@withLock it }
            val page = exchange.get(
                AppleMusicWebHost.MUSIC, "/us/404", emptyMap(),
                mapOf("Accept" to "text/html"), 1_000_000,
            )
            if (page.status !in 200..499)
                throw AppleMusicWebHttpException(page.status)
            val asset = bundleLink.find(page.body)?.groupValues?.get(1)
                ?: throw AppleMusicWebProtocolException("Apple web bundle unavailable")
            val script = exchange.get(
                AppleMusicWebHost.MUSIC, asset, emptyMap(),
                mapOf("Accept" to "*/*"), 8_000_000,
            )
            if (script.status != 200) throw AppleMusicWebHttpException(script.status)
            val chosen = AppleMusicWebTokenParser.pick(script.body, now)
                ?: throw AppleMusicWebProtocolException("Apple web token unavailable")
            cached = chosen
            chosen
        }
}

/**
 * Each HTTP exchange, including token discovery, is capability-gated through
 * the same ProviderPolicyGate as iTunes. Disabling one capability cancels only
 * its in-flight work and suppresses stale publication.
 */
internal class AppleMusicWebPolicyTransport(
    private val exchange: AppleMusicWebExchange = KtorAppleMusicWebExchange(),
    private val tokens: AppleMusicWebTokenSource = AppleMusicWebBundleTokenSource(exchange),
    private val admission: RateLimitedProviderTransport = RateLimitedProviderTransport(),
    private val limiter: AppleMusicWebRateLimiter = AppleMusicWebRequestBudget.limiter,
    private val retryPolicy: AppleMusicWebRetryPolicy = AppleMusicWebRetryPolicy(),
    private val retrySleep: suspend (Long) -> Unit = { delay(it) },
) : AppleMusicWebTransport {

    override suspend fun get(
        capability: ProviderCapability, path: String, params: Map<String, String>,
    ): String {
        require(capability == ProviderCapability.CATALOG_SEARCH ||
            capability == ProviderCapability.EDITORIAL_DISCOGRAPHY) {
            "Unsupported Apple web provider capability"
        }
        require(AppleMusicWebOperationRegistry.allows(capability, path)) {
            "Apple Web operation is not authorized for capability"
        }
        require(params.size <= 30 && params.all { (name, value) ->
            name.length in 1..100 && value.length <= 4096 &&
                name.none { it.isISOControl() } && value.none { it.isISOControl() }
        })
        return admission.execute(
            capability = capability,
            provider = BuiltInProviderKeys.APPLE_MUSIC_WEB,
            scheduleLimited = { operation -> operation() },
            networkCall = {
                suspend fun request(token: AppleMusicWebToken): AppleMusicWebHttpResponse =
                    limiter.run {
                        exchange.get(
                            AppleMusicWebHost.API, path, params,
                            mapOf(
                                "Accept" to "application/json",
                                "Accept-Language" to "en-US,en;q=0.9",
                                "Origin" to "https://music.apple.com",
                                "Referer" to "https://music.apple.com/",
                                "Authorization" to "Bearer ${token.value}",
                            ), 2_000_000,
                        )
                    }
                var response = request(tokens.token(forceRefresh = false))
                if (response.status == 401) {
                    // One refresh maximum for expired anonymous credentials.
                    response = request(tokens.token(forceRefresh = true))
                }
                var attempt = 1
                while (response.status != 200) {
                    val rawRetryAfter = response.headers["Retry-After"]
                    // Invalid or HTTP-date backoff is not permission to retry
                    // immediately; fail closed instead of ignoring it.
                    val retryAfter = rawRetryAfter?.toLongOrNull()
                    if (rawRetryAfter != null && retryAfter == null)
                        throw AppleMusicWebHttpException(response.status)
                    val waitMs = retryPolicy.delayFor(response.status, attempt, retryAfter)
                        ?: throw AppleMusicWebHttpException(response.status)
                    if (waitMs > 0) retrySleep(waitMs)
                    attempt++
                    response = request(tokens.token(forceRefresh = false))
                }
                response.body
            },
        )
    }
}
