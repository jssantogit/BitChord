package com.music.bitchord.data.catalog

import android.content.Context
import android.net.Uri
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.DiagnosticLevel
import androidx.room.withTransaction
import java.util.Locale
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

internal data class LocalTagScanStats(
    val attempted: Int,
    val updated: Int,
    val unsupported: Int,
    val accessErrors: Int,
    val superseded: Int,
)

/**
 * Incrementally supplements MediaStore's indexed metadata from local headers.
 * At most one coroutine per app process, with batched Room commits; no file
 * descriptor is opened inside a transaction. An interrupted run resumes at the
 * next local scan, using a versioned source fingerprint for idempotence.
 */
internal object LocalEmbeddedTagEnricher {
    private const val TAG = "BitChordLocalTags"
    private const val BATCH_SIZE = 32
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    @Synchronized
    fun schedule(context: Context, db: CanonicalCatalogDatabase) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val startedAt = System.nanoTime()
            val stats = processPending(db) { rawUri ->
                readLocalTags(context, rawUri)
            }
            // Bounded privacy-safe evidence exported in the app's Diagnostics.
            // No file paths, URIs, raw tag values, or credentials ever leave here.
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "local_tag_enrichment_v1",
                level = if (stats.accessErrors > 0) DiagnosticLevel.WARN else DiagnosticLevel.INFO,
                metadata = mapOf(
                    "attempted" to stats.attempted.toString(),
                    "updated" to stats.updated.toString(),
                    "unsupported" to stats.unsupported.toString(),
                    "accessErrors" to stats.accessErrors.toString(),
                    "superseded" to stats.superseded.toString(),
                ),
                durationNanos = System.nanoTime() - startedAt,
            )
        }
    }

    /**
     * A seekable descriptor is needed for MP4, where moov often follows mdat.
     * POSitional reads avoid fetching or decoding the audio payload. Other
     * formats keep the existing bounded streaming parser, including virtual
     * providers that expose only InputStream.
     */
    private fun readLocalTags(context: Context, rawUri: String): LocalEmbeddedTags? {
        val uri = Uri.parse(rawUri)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("content", "file")) {
            "Local tags require a local content/file URI"
        }
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
        if (afd == null) {
            return context.contentResolver.openInputStream(uri)?.use(
                LocalEmbeddedTagParser::readStream,
            )
        }
        return afd.use { asset ->
            asset.createInputStream().use { input ->
                val first = ByteArray(8)
                var count = 0
                while (count < first.size) {
                    val got = input.read(first, count, first.size - count)
                    if (got <= 0) break
                    count += got
                }
                if (count < first.size) return@use null
                val isMp4 = first.copyOfRange(4, 8).contentEquals(
                    "ftyp".toByteArray(Charsets.US_ASCII),
                )
                if (!isMp4) {
                    return@use LocalEmbeddedTagParser.readStream(
                        SequenceInputStream(ByteArrayInputStream(first), input),
                    )
                }
                // File-backed content providers generally expose random access.
                // Pipe-backed virtual files stay UNKNOWN instead of scanning
                // hundreds of MB to find a tail moov.
                val channel = FileInputStream(asset.fileDescriptor).channel
                val size = if (asset.length >= 0L) asset.length
                    else channel.size() - asset.startOffset
                LocalMp4TagParser.parse(size) { offset, length ->
                    val buffer = ByteBuffer.allocate(length)
                    var n = 0
                    while (n < length) {
                        val got = channel.read(buffer, asset.startOffset + offset + n)
                        if (got <= 0) return@parse null
                        n += got
                    }
                    buffer.array()
                }
            }
        }
    }

    /**
     * Real Room-backed test seam; [read] may read a content URI or return null
     * for an unsupported/malformed file (UNKNOWN). Throws on inaccessible files,
     * which are NOT marked as completed so they can be retried.
     */
    suspend fun processPending(
        db: CanonicalCatalogDatabase,
        read: suspend (String) -> LocalEmbeddedTags?,
    ): LocalTagScanStats {
        val dao = db.catalogDao()
        val pending = dao.pendingLocalEmbeddedTags(CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER)
            .filter { it.sourceFingerprint != it.parsedFingerprint }
        var attempted = 0
        var updated = 0
        var unsupported = 0
        var errors = 0
        var superseded = 0
        for (chunk in pending.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()
            val results = chunk.map { row ->
                attempted++
                val parsed = try {
                    ReadResult(row, read(row.uri), canCommit = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    errors++
                    ReadResult(row, null, canCommit = false)
                }
                if (parsed.canCommit && parsed.value == null) unsupported++
                parsed
            }
            db.withTransaction {
                for (result in results) {
                    if (!result.canCommit) continue
                    val row = result.row
                    val currentAsset = dao.mediaAssetById(row.mediaAssetId)
                    val current = dao.mediaAssetTagsById(row.mediaAssetId)
                    if (currentAsset?.provider != CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER ||
                        currentAsset?.uri != row.uri ||
                        current == null ||
                        current.localSourceFingerprint != row.sourceFingerprint ||
                        current.localParsedFingerprint == row.sourceFingerprint
                    ) {
                        superseded++
                        continue
                    }
                    val parsed = result.value
                    dao.upsertMediaAssetTags(current.copy(
                        genresJson = parsed?.genres?.let { mergeText(current.genresJson, it) }
                            ?: current.genresJson,
                        moodsJson = parsed?.moods?.let { mergeText(current.moodsJson, it) }
                            ?: current.moodsJson,
                        bpmsJson = parsed?.bpms?.let { mergeBpms(current.bpmsJson, it) }
                            ?: current.bpmsJson,
                        stylesJson = parsed?.styles?.let { mergeText(current.stylesJson, it) }
                            ?: current.stylesJson,
                        localParsedFingerprint = row.sourceFingerprint,
                    ))
                    updated++
                }
            }
            // Allows cancellation and UI/catalog work to interleave at 50k scale.
            yield()
        }
        return LocalTagScanStats(attempted, updated, unsupported, errors, superseded)
    }

    private data class ReadResult(
        val row: CanonicalLocalTagScanRow,
        val value: LocalEmbeddedTags?,
        val canCommit: Boolean,
    )

    private fun mergeText(existingJson: String?, incoming: List<String>): String {
        val known = existingJson?.let {
            runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull()
        }.orEmpty()
        return Json.encodeToString((known + incoming).distinct())
    }

    private fun mergeBpms(existingJson: String?, incoming: List<Double>): String {
        val known = existingJson?.let {
            runCatching { Json.decodeFromString<List<Double>>(it) }.getOrNull()
        }.orEmpty()
        return Json.encodeToString((known + incoming).distinct())
    }
}
