package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.MediaAssetEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun interface OpenSubsonicArtworkAssetStore {
    suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity?
}

internal enum class OpenSubsonicArtworkAccessFailure {
    MEDIA_ASSET_NOT_FOUND,
    ARTWORK_NOT_AVAILABLE,
    INVALID_ARTWORK_REFERENCE,
    UNSUPPORTED_PROVIDER,
    ACCOUNT_NOT_CONFIGURED,
    ACCOUNT_MISMATCH,
}

internal sealed interface OpenSubsonicArtworkAccessResult {
    class Ready(val request: OpenSubsonicArtworkRequest) : OpenSubsonicArtworkAccessResult
    data class Failure(val reason: OpenSubsonicArtworkAccessFailure) : OpenSubsonicArtworkAccessResult
}

/**
 * Resolves only credential-free canonical/provider state. Reading the secure
 * credential store is deliberately deferred to [OpenSubsonicArtworkRequest.fetch].
 */
internal class OpenSubsonicArtworkAccess(
    private val assets: OpenSubsonicArtworkAssetStore,
    private val accounts: OpenSubsonicAccountRepository,
    private val saltFactory: () -> String = ::defaultOpenSubsonicSalt,
) {
    suspend fun resolve(mediaAssetId: String): OpenSubsonicArtworkAccessResult {
        val asset = assets.mediaAsset(mediaAssetId)
            ?: return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.MEDIA_ASSET_NOT_FOUND,
            )
        val providerAccountId = accountIdFromProvider(asset.provider)
            ?: return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.UNSUPPORTED_PROVIDER,
            )
        val artworkReference = asset.artworkUri
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.ARTWORK_NOT_AVAILABLE,
            )
        val locator = parseLogicalArtworkReference(artworkReference)
            ?: return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.INVALID_ARTWORK_REFERENCE,
            )
        if (locator.accountId != providerAccountId) {
            return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.ACCOUNT_MISMATCH,
            )
        }
        val account = accounts.current()
            ?: return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.ACCOUNT_NOT_CONFIGURED,
            )
        if (account.accountId != providerAccountId) {
            return OpenSubsonicArtworkAccessResult.Failure(
                OpenSubsonicArtworkAccessFailure.ACCOUNT_MISMATCH,
            )
        }

        return OpenSubsonicArtworkAccessResult.Ready(
            OpenSubsonicArtworkRequest(
                recordingId = asset.recordingId,
                mediaAssetId = asset.id,
                providerKey = asset.provider,
                cacheKey = artworkCacheKey(asset.id, artworkReference),
                accountId = providerAccountId,
                coverArtId = locator.coverArtId,
                accounts = accounts,
                saltFactory = saltFactory,
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

/** Safe descriptor: no password, token, salt, authenticated URL or raw cover locator. */
internal class OpenSubsonicArtworkRequest internal constructor(
    val recordingId: String,
    val mediaAssetId: String,
    val providerKey: String,
    val cacheKey: String,
    private val accountId: String,
    private val coverArtId: String,
    private val accounts: OpenSubsonicAccountRepository,
    private val saltFactory: () -> String,
) {
    suspend fun fetch(transport: OpenSubsonicArtworkTransport): OpenSubsonicArtworkFetchResult {
        // First and only secure-store read in the artwork flow.
        val config = accounts.savedConfig()
            ?: return OpenSubsonicArtworkFetchResult.CredentialUnavailable
        if (config.accountId != accountId) {
            return OpenSubsonicArtworkFetchResult.AccountMismatch
        }

        val endpointUrl = "${config.normalizedServerUrl}/rest/getCoverArt.view"
        val parameters = linkedMapOf(
            "v" to OPEN_SUBSONIC_API_VERSION,
            "c" to OPEN_SUBSONIC_CLIENT_NAME,
            "id" to coverArtId,
            "size" to ARTWORK_SIZE_PX.toString(),
        ).apply {
            putAll(openSubsonicAuthenticationParameters(config.auth, saltFactory))
        }

        return try {
            when (val result = transport.fetch(endpointUrl, parameters)) {
                is OpenSubsonicArtworkTransportResult.Success ->
                    OpenSubsonicArtworkFetchResult.Ready(
                        bytes = result.bytes,
                        contentType = result.contentType,
                    )
                OpenSubsonicArtworkTransportResult.NetworkUnavailable ->
                    OpenSubsonicArtworkFetchResult.NetworkUnavailable
                OpenSubsonicArtworkTransportResult.AuthenticationRejected ->
                    OpenSubsonicArtworkFetchResult.AuthenticationRejected
                OpenSubsonicArtworkTransportResult.RequestRejected ->
                    OpenSubsonicArtworkFetchResult.RequestRejected
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Transport exceptions may embed the full authenticated URL.
            OpenSubsonicArtworkFetchResult.NetworkUnavailable
        }
    }

    override fun toString(): String =
        "OpenSubsonicArtworkRequest(recordingId=$recordingId, mediaAssetId=$mediaAssetId, " +
            "providerKey=$providerKey, cacheKey=$cacheKey, locator=<redacted>, auth=<runtime>)"
}

internal sealed interface OpenSubsonicArtworkFetchResult {
    class Ready(
        val bytes: ByteArray,
        val contentType: String,
    ) : OpenSubsonicArtworkFetchResult {
        override fun toString(): String =
            "Ready(bytes=${bytes.size}, contentType=$contentType)"
    }

    data object CredentialUnavailable : OpenSubsonicArtworkFetchResult
    data object AccountMismatch : OpenSubsonicArtworkFetchResult
    data object NetworkUnavailable : OpenSubsonicArtworkFetchResult
    data object AuthenticationRejected : OpenSubsonicArtworkFetchResult
    data object RequestRejected : OpenSubsonicArtworkFetchResult
}

internal fun interface OpenSubsonicArtworkTransport {
    suspend fun fetch(
        endpointUrl: String,
        parameters: Map<String, String>,
    ): OpenSubsonicArtworkTransportResult
}

internal sealed interface OpenSubsonicArtworkTransportResult {
    class Success(
        val bytes: ByteArray,
        val contentType: String,
    ) : OpenSubsonicArtworkTransportResult

    data object NetworkUnavailable : OpenSubsonicArtworkTransportResult
    data object AuthenticationRejected : OpenSubsonicArtworkTransportResult
    data object RequestRejected : OpenSubsonicArtworkTransportResult
}

/** Provider-owned cache. Keys are explicitly credential-free. */
internal interface OpenSubsonicArtworkCache {
    suspend fun read(cacheKey: String): ByteArray?
    suspend fun write(cacheKey: String, bytes: ByteArray)
}

internal sealed interface OpenSubsonicArtworkLoadResult {
    class Ready(
        val bytes: ByteArray,
        val fromCache: Boolean,
    ) : OpenSubsonicArtworkLoadResult

    data class Placeholder(val reason: String) : OpenSubsonicArtworkLoadResult
}

internal class OpenSubsonicArtworkLoader(
    private val access: OpenSubsonicArtworkAccess,
    private val transport: OpenSubsonicArtworkTransport,
    private val cache: OpenSubsonicArtworkCache,
) {
    suspend fun load(
        mediaAssetId: String,
        expectedRecordingId: String? = null,
        expectedProviderKey: String? = null,
    ): OpenSubsonicArtworkLoadResult {
        val resolved = access.resolve(mediaAssetId)
        if (resolved is OpenSubsonicArtworkAccessResult.Failure) {
            return OpenSubsonicArtworkLoadResult.Placeholder(resolved.reason.name)
        }
        val request = (resolved as OpenSubsonicArtworkAccessResult.Ready).request
        if ((expectedRecordingId != null && request.recordingId != expectedRecordingId) ||
            (expectedProviderKey != null && request.providerKey != expectedProviderKey)
        ) {
            // No cached/remote artwork for a stale or forged playback selection.
            return OpenSubsonicArtworkLoadResult.Placeholder("SELECTION_MISMATCH")
        }

        runCatching { cache.read(request.cacheKey) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { cached ->
                return OpenSubsonicArtworkLoadResult.Ready(cached, fromCache = true)
            }

        return when (val fetched = request.fetch(transport)) {
            is OpenSubsonicArtworkFetchResult.Ready -> {
                if (fetched.bytes.isEmpty()) {
                    OpenSubsonicArtworkLoadResult.Placeholder("EMPTY_ARTWORK")
                } else {
                    runCatching { cache.write(request.cacheKey, fetched.bytes) }
                    OpenSubsonicArtworkLoadResult.Ready(fetched.bytes, fromCache = false)
                }
            }
            OpenSubsonicArtworkFetchResult.CredentialUnavailable ->
                OpenSubsonicArtworkLoadResult.Placeholder("CREDENTIAL_UNAVAILABLE")
            OpenSubsonicArtworkFetchResult.AccountMismatch ->
                OpenSubsonicArtworkLoadResult.Placeholder("ACCOUNT_MISMATCH")
            OpenSubsonicArtworkFetchResult.NetworkUnavailable ->
                OpenSubsonicArtworkLoadResult.Placeholder("NETWORK_UNAVAILABLE")
            OpenSubsonicArtworkFetchResult.AuthenticationRejected ->
                OpenSubsonicArtworkLoadResult.Placeholder("AUTHENTICATION_REJECTED")
            OpenSubsonicArtworkFetchResult.RequestRejected ->
                OpenSubsonicArtworkLoadResult.Placeholder("REQUEST_REJECTED")
        }
    }
}

/**
 * Reuses shared connection infrastructure but never global interceptors.
 * That prevents credentials owned by another provider (for example WebDAV)
 * or future request loggers from observing an authenticated artwork query.
 */
internal fun isolatedOpenSubsonicArtworkHttpClient(
    sharedClient: OkHttpClient,
): OkHttpClient {
    val builder = sharedClient.newBuilder()
    builder.interceptors().clear()
    builder.networkInterceptors().clear()
    return builder
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** Only rate limits or server-side unavailability merit an automatic retry. */
internal fun isTransientArtworkHttpStatus(status: Int): Boolean =
    status == 429 || status in 500..599

internal class OkHttpOpenSubsonicArtworkTransport(
    sharedClient: OkHttpClient = Http.client,
    private val maxBytes: Int = MAX_ARTWORK_BYTES,
) : OpenSubsonicArtworkTransport {
    private val client = isolatedOpenSubsonicArtworkHttpClient(sharedClient)

    override suspend fun fetch(
        endpointUrl: String,
        parameters: Map<String, String>,
    ): OpenSubsonicArtworkTransportResult = withContext(Dispatchers.IO) {
        try {
            val url = endpointUrl.toHttpUrl().newBuilder().apply {
                parameters.forEach { (name, value) -> addQueryParameter(name, value) }
            }.build()
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> OpenSubsonicArtworkTransportResult.AuthenticationRejected
                    in 200..299 -> {
                        val body = response.body
                        val contentType = body.contentType()?.toString().orEmpty()
                        if (!contentType.startsWith("image/", ignoreCase = true)) {
                            return@use OpenSubsonicArtworkTransportResult.RequestRejected
                        }
                        if (body.contentLength() > maxBytes) {
                            return@use OpenSubsonicArtworkTransportResult.RequestRejected
                        }
                        val bytes = readBounded(body.byteStream(), maxBytes)
                            ?: return@use OpenSubsonicArtworkTransportResult.RequestRejected
                        OpenSubsonicArtworkTransportResult.Success(bytes, contentType)
                    }
                    else -> if (isTransientArtworkHttpStatus(response.code)) {
                        OpenSubsonicArtworkTransportResult.NetworkUnavailable
                    } else {
                        OpenSubsonicArtworkTransportResult.RequestRejected
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            OpenSubsonicArtworkTransportResult.NetworkUnavailable
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray? {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

internal class FileOpenSubsonicArtworkCache(
    context: Context,
) : OpenSubsonicArtworkCache {
    private val directory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)

    override suspend fun read(cacheKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = cacheFile(cacheKey)
        if (!file.isFile) return@withContext null
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    override suspend fun write(cacheKey: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext
        directory.mkdirs()
        val destination = cacheFile(cacheKey)
        val temporary = File(directory, "${destination.name}.tmp-${System.nanoTime()}")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(destination)) {
                destination.writeBytes(bytes)
            }
            destination.setLastModified(System.currentTimeMillis())
            trimCache()
        } finally {
            temporary.delete()
        }
    }

    private fun cacheFile(cacheKey: String): File =
        File(directory, sha256(cacheKey))

    private fun trimCache() {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
            .sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        files.forEach { file ->
            retainedBytes += file.length()
            if (retainedBytes > MAX_CACHE_BYTES) file.delete()
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "opensubsonic-artwork-v1"
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    }
}

internal class RoomOpenSubsonicArtworkAssetStore(
    context: Context,
) : OpenSubsonicArtworkAssetStore {
    private val dao = CanonicalCatalogDatabase.get(context.applicationContext).catalogDao()

    override suspend fun mediaAsset(mediaAssetId: String): MediaAssetEntity? =
        dao.mediaAssetById(mediaAssetId)
}

internal fun androidOpenSubsonicArtworkLoader(context: Context): OpenSubsonicArtworkLoader {
    val appContext = context.applicationContext
    val accounts = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(appContext),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(appContext),
    )
    return OpenSubsonicArtworkLoader(
        access = OpenSubsonicArtworkAccess(
            assets = RoomOpenSubsonicArtworkAssetStore(appContext),
            accounts = accounts,
        ),
        transport = OkHttpOpenSubsonicArtworkTransport(),
        cache = FileOpenSubsonicArtworkCache(appContext),
    )
}

private data class LogicalArtworkLocator(
    val accountId: String,
    val coverArtId: String,
)

private fun parseLogicalArtworkReference(value: String): LogicalArtworkLocator? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("opensubsonic", ignoreCase = true)) return null
    if (!uri.host.equals("artwork", ignoreCase = true)) return null
    if (uri.query != null || uri.fragment != null || uri.userInfo != null) return null
    val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
    if (segments.size != 2) return null
    val accountId = decodeLogicalId(segments[0]) ?: return null
    val coverArtId = decodeLogicalId(segments[1]) ?: return null
    if (!accountId.matches(Regex("[A-Za-z0-9._-]+"))) return null
    if (coverArtId.isBlank()) return null
    return LogicalArtworkLocator(accountId, coverArtId)
}

private fun decodeLogicalId(encoded: String): String? = runCatching {
    String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
}.getOrNull()?.takeIf(String::isNotBlank)

private fun artworkCacheKey(mediaAssetId: String, artworkReference: String): String =
    "opensubsonic-artwork:v1:$mediaAssetId:${sha256(artworkReference)}:$ARTWORK_SIZE_PX"

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private const val ARTWORK_SIZE_PX = 256
private const val MAX_ARTWORK_BYTES = 20 * 1024 * 1024
