package com.music.bitchord.data.scrobbling

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A playback occurrence, not a track identity. No access token or user identifier
 * is persisted. All metadata is kept in an app-private, no-backup directory.
 */
@Serializable
internal data class PendingListenBrainzListen(
    val occurrenceId: String,
    val accountKey: String,
    val startMs: Long,
    val endMs: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val artist: String,
    val title: String,
    val album: String,
) {
    fun isQualified(): Boolean =
        occurrenceId.isNotBlank() && accountKey.isNotBlank() &&
            startMs / 1_000 >= 1_033_430_400L && endMs > startMs &&
            title.isNotBlank() && artist.isNotBlank() &&
            title.length <= 1_024 && artist.length <= 1_024 && album.length <= 1_024 &&
            listenBrainzQualifiesAsListen(listenedMs, durationMs)
}

/**
 * Crash-safe outbox. A successful atomic replace means the record survives a
 * normal process death; a failed write never pretends that a listen was saved.
 *
 * Every operation uses one process-wide lock even when a worker reopens the
 * file while the media service is writing it. No destructive recovery on JSON
 * decode errors: preserve the original file for explicit diagnosis.
 */
internal class ListenBrainzOutboxStore(private val file: File) {
    @Serializable
    private data class Envelope(
        val version: Int = 1,
        val listens: List<PendingListenBrainzListen> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun enqueue(entry: PendingListenBrainzListen): Boolean = synchronized(fileLock) {
        if (!entry.isQualified()) return@synchronized false
        val current = load()
        if (current.any { it.occurrenceId == entry.occurrenceId }) return@synchronized false
        if (current.size >= MAX_PENDING) return@synchronized false
        write(current + entry)
        true
    }

    fun pending(accountKey: String): List<PendingListenBrainzListen> = synchronized(fileLock) {
        load().filter { it.accountKey == accountKey }
    }

    fun remove(occurrenceId: String, accountKey: String): Boolean = synchronized(fileLock) {
        val current = load()
        val remaining = current.filterNot { it.occurrenceId == occurrenceId && it.accountKey == accountKey }
        if (remaining.size == current.size) return@synchronized false
        write(remaining)
        true
    }

    /** Privacy: do not silently replay an old account's listens using a new token. */
    fun keepOnlyAccount(accountKey: String) = synchronized(fileLock) {
        val current = load()
        val kept = current.filter { it.accountKey == accountKey }
        if (current.size != kept.size) write(kept)
    }

    fun clear() = synchronized(fileLock) {
        write(emptyList())
    }

    private fun load(): List<PendingListenBrainzListen> {
        if (!file.exists()) return emptyList()
        val state = json.decodeFromString<Envelope>(file.readText(Charsets.UTF_8))
        require(state.version == 1) { "Unsupported ListenBrainz outbox version" }
        return state.listens
    }

    private fun write(listens: List<PendingListenBrainzListen>) {
        val folder = file.parentFile ?: error("ListenBrainz outbox requires a private directory")
        check(folder.isDirectory || folder.mkdirs()) { "Failed to create ListenBrainz outbox directory" }
        val temp = File(folder, file.name + ".new")
        val content = json.encodeToString(Envelope(listens = listens)).toByteArray(Charsets.UTF_8)
        // MAX_PENDING and the per-field limits cap this file to a modest size.
        FileOutputStream(temp).use { out ->
            out.write(content)
            out.fd.sync()
        }
        // Internal app storage supports atomic rename. If it does not, fail
        // closed rather than risking a truncated original during replacement.
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        const val MAX_PENDING = 512
        val fileLock = Any()
    }
}
