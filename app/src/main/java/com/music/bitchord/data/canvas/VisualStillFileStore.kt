package com.music.bitchord.data.canvas

import android.content.Context
import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Saved stills are separate from Coil's evictable HTTP cache. Shared by every
 * artwork provider and every screen; a verified identity, never URL order,
 * owns the stored file. Keep in noBackupFilesDir to avoid cloud-backed images.
 */
internal class VisualStillFileStore(
    private val directory: File,
    private val maxBytes: () -> Long = { MAX_STILL_CACHE_BYTES },
    private val downloader: suspend (String, File) -> Boolean,
) {
    private val keys = ConcurrentHashMap<String, Mutex>()
    private val parallel = Semaphore(3)
    private val invalidation = AtomicLong(0L)

    fun cached(surface: VisualSurface, identity: String): File? {
        if (identity.isBlank()) return null
        val image = File(directory, visualMediaCacheKey(surface, identity) + ".image")
        return image.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun ensure(surface: VisualSurface, identity: String, url: String): File? {
        if (identity.isBlank() || !isSafeVisualMediaUrl(url)) return null
        cached(surface, identity)?.let { return it }
        val id = visualMediaCacheKey(surface, identity)
        val mutex = keys.getOrPut(id) { Mutex() }
        return mutex.withLock {
            cached(surface, identity)?.let { return@withLock it }
            parallel.withPermit {
                withContext(Dispatchers.IO) {
                    val generation = invalidation.get()
                    if (!directory.exists() && !directory.mkdirs()) return@withContext null
                    val temporary = File(directory, "$id.part")
                    val target = File(directory, "$id.image")
                    try {
                        if (!downloader(url, temporary) || temporary.length() == 0L ||
                            temporary.length() > MAX_STILL_BYTES ||
                            generation != invalidation.get()
                        ) return@withContext null
                        if (!temporary.renameTo(target)) return@withContext null
                        prune()
                        target
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }

    /** Clearing invalidates in-flight commits; a network response cannot
     * repopulate files after the user has explicitly cleared visual storage. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        invalidation.incrementAndGet()
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    fun trimToQuota() { prune() }

    fun bytesOnDisk(): Long = directory.listFiles().orEmpty()
        .filter { it.isFile }.sumOf { it.length() }

    /** No unbounded build-up when a user browses a huge public catalog. */
    private fun prune() {
        val files = directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".image") }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes().coerceIn(0L, MAX_SAVED_STILLS_BYTES)) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        const val MAX_STILL_BYTES = 12L * 1024 * 1024
        const val MAX_STILL_CACHE_BYTES = 256L * 1024 * 1024
    }
}

/**
 * Facade for all current/future metadata and artwork providers.
 * Screens can request a still immediately from disk and warm the URL in the
 * background. Never downloads without a real, safe HTTPS URL.
 */
object VisualStillMedia {
    @Volatile private var store: VisualStillFileStore? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Respect a newly lowered quota without blocking the settings UI. */
    fun requestTrim() {
        cleanupScope.launch { store?.trimToQuota() }
    }

    fun init(context: Context) {
        val root = File(context.noBackupFilesDir, "visual_offline_stills_v1")
        store = VisualStillFileStore(
            directory = root,
            maxBytes = { com.music.bitchord.data.settings.AppSettings.visualStillCacheLimitBytes.value },
        ) { url, output ->
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).get().build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful ||
                        !isSafeVisualMediaUrl(response.request.url.toString())
                    ) return@use false
                    val body = response.body ?: return@use false
                    if (body.contentLength() > VisualStillFileStore.MAX_STILL_BYTES)
                        return@use false
                    // Some image CDNs omit Content-Type; reject known non-image
                    // responses but let Coil decode exact bytes later.
                    val mime = body.contentType()?.toString()
                    if (mime != null && !mime.startsWith("image/")) return@use false
                    body.byteStream().use { input ->
                        output.outputStream().buffered().use { sink ->
                            val buffer = ByteArray(16 * 1024)
                            var received = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                received += read
                                if (received > VisualStillFileStore.MAX_STILL_BYTES)
                                    throw IOException("Visual still too large")
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    output.length() > 0L
                }
            }
        }
    }

    /** A File model is supported by Coil; no file:// network URL is synthesized. */
    fun cached(surface: VisualSurface, identity: String): File? =
        store?.cached(surface, identity)

    suspend fun save(surface: VisualSurface, identity: String, url: String): File? {
        val settings = com.music.bitchord.data.settings.AppSettings
        if (!settings.saveVisualStillImages.value ||
            surface !in settings.visualStillSurfaces.value
        ) return cached(surface, identity)
        return store?.ensure(surface, identity, url)
    }

    suspend fun clear() { store?.clear() }
    fun bytesOnDisk() = store?.bytesOnDisk() ?: 0L
}
