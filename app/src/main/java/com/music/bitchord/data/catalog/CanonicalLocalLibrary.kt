package com.music.bitchord.data.catalog

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.diagnostics.DiagnosticCategory
import com.music.bitchord.diagnostics.Diagnostics
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object CanonicalLocalLibrary {
    internal const val LOCAL_MEDIASTORE_PROVIDER = "local-mediastore"
    private const val LOCAL_TAGS_PROVIDER = "local-tags"
    private const val YOUTUBE_PROVIDER = "youtube"
    private const val RECORDING_ENTITY_TYPE = "recording"
    private const val RELEASE_ENTITY_TYPE = "release"

    /**
     * Reconciles the latest phone MediaStore scan into canonical rows and then
     * reads Local Music back from Room. The existing UI/player sees only the
     * legacy adapter output; Room is the source of truth after this boundary.
     */
    suspend fun sync(context: Context, scannedSongs: List<Song>): List<Song> {
        val database = CanonicalCatalogDatabase.get(context)
        val result = syncWithDatabase(context, database, scannedSongs)
        // Header probes are incremental and asynchronous: no file decoding or
        // tag I/O while Room holds the local-library transaction.
        LocalEmbeddedTagEnricher.schedule(context.applicationContext, database)
        return result
    }

    /** Real Room test seam; retains the exact production transaction and prune guard. */
    internal suspend fun syncWithDatabase(
        context: Context,
        database: CanonicalCatalogDatabase,
        scannedSongs: List<Song>,
    ): List<Song> = database.withTransaction {
            val dao = database.catalogDao()
            val candidates = scannedSongs.mapNotNull(::candidateFromLegacySong)
            val seenSourceKeys = ArrayList<String>(candidates.size)

            for (candidate in candidates) {
                seenSourceKeys += candidate.sourceKey

                val existingAsset = dao.mediaAsset(LOCAL_MEDIASTORE_PROVIDER, candidate.sourceKey)
                val rememberedRecordingId = dao.entityIdForExternalIdentity(
                    LOCAL_MEDIASTORE_PROVIDER,
                    RECORDING_ENTITY_TYPE,
                    candidate.sourceKey,
                )
                val recordingId = existingAsset?.recordingId
                    ?: rememberedRecordingId
                    ?: UUID.randomUUID().toString()
                val mediaAssetId = existingAsset?.id ?: UUID.randomUUID().toString()

                dao.upsertRecording(
                    RecordingEntity(
                        id = recordingId,
                        title = candidate.title,
                        artistDisplay = candidate.artistDisplay,
                        durationMs = candidate.durationMs,
                    ),
                )
                dao.upsertExternalIdentity(
                    ExternalIdentityEntity(
                        provider = LOCAL_MEDIASTORE_PROVIDER,
                        entityType = RECORDING_ENTITY_TYPE,
                        externalId = candidate.sourceKey,
                        entityId = recordingId,
                    ),
                )
                dao.upsertMediaAsset(
                    MediaAssetEntity(
                        id = mediaAssetId,
                        recordingId = recordingId,
                        provider = LOCAL_MEDIASTORE_PROVIDER,
                        sourceKey = candidate.sourceKey,
                        uri = candidate.uri,
                        path = candidate.path,
                        dateAddedSeconds = candidate.dateAddedSeconds,
                        dateModifiedSeconds = candidate.dateModifiedSeconds,
                        artworkUri = candidate.artworkUri,
                        localGenre = candidate.localGenre,
                        localBitrateBps = candidate.localBitrateBps,
                        audioContentType = candidate.localMimeType,
                        audioBitrateBps = candidate.localBitrateBps,
                    ),
                )
                // Preserve completed embedded-tag evidence only if the source
                // timestamp+size and extractor schema revision still match.
                // A changed file immediately invalidates old positive tags.
                val previousTags = dao.mediaAssetTagsById(mediaAssetId)
                val fingerprint = candidate.sourceFingerprint
                val reuse = fingerprint != null &&
                    previousTags?.localParsedFingerprint == fingerprint
                dao.upsertMediaAssetTags(
                    MediaAssetTagsEntity(
                        mediaAssetId = mediaAssetId,
                        genresJson = mergedLocalTagJson(
                            candidate.genres, previousTags?.genresJson.takeIf { reuse },
                        ),
                        moodsJson = mergedLocalTagJson(
                            candidate.moods, previousTags?.moodsJson.takeIf { reuse },
                        ),
                        bpmsJson = mergedLocalBpmJson(
                            candidate.bpms, previousTags?.bpmsJson.takeIf { reuse },
                        ),
                        stylesJson = if (reuse) previousTags?.stylesJson else null,
                        composersJson = mergedLocalTagJson(
                            candidate.composers, previousTags?.composersJson.takeIf { reuse },
                        ),
                        localSourceFingerprint = fingerprint,
                        localParsedFingerprint = if (reuse) fingerprint else null,
                    ),
                )

                // Clear only this scan's ownership and prune edges exclusively
                // owned by local tags. MediaAssets/identities from others stay.
                dao.deleteReleaseTrackSourcesForRecording(recordingId, LOCAL_TAGS_PROVIDER)
                dao.deleteLocalOnlyReleaseTracksForRecording(recordingId, LOCAL_TAGS_PROVIDER)
                candidate.albumTitle?.let { albumTitle ->
                    val releaseEvidenceKey = localReleaseEvidenceKey(
                        albumTitle = albumTitle,
                        artistDisplay = candidate.artistDisplay,
                    )
                    val releaseId = dao.entityIdForExternalIdentity(
                        LOCAL_TAGS_PROVIDER,
                        RELEASE_ENTITY_TYPE,
                        releaseEvidenceKey,
                    ) ?: UUID.randomUUID().toString()

                    dao.upsertRelease(
                        ReleaseEntity(
                            id = releaseId,
                            title = albumTitle,
                            artistDisplay = candidate.artistDisplay,
                            artworkUri = candidate.artworkUri,
                        ),
                    )
                    dao.upsertExternalIdentity(
                        ExternalIdentityEntity(
                            provider = LOCAL_TAGS_PROVIDER,
                            entityType = RELEASE_ENTITY_TYPE,
                            externalId = releaseEvidenceKey,
                            entityId = releaseId,
                        ),
                    )
                    dao.upsertReleaseTrack(
                        ReleaseTrackEntity(
                            releaseId = releaseId,
                            recordingId = recordingId,
                            trackNumber = candidate.trackNumber,
                        ),
                    )
                    dao.upsertCatalogReleaseTrackSource(
                        CatalogReleaseTrackSourceEntity(releaseId, recordingId, LOCAL_TAGS_PROVIDER),
                    )
                }

                candidate.youtubeVideoId?.let { youtubeVideoId ->
                    dao.upsertExternalIdentity(
                        ExternalIdentityEntity(
                            provider = YOUTUBE_PROVIDER,
                            entityType = RECORDING_ENTITY_TYPE,
                            externalId = youtubeVideoId,
                            entityId = recordingId,
                        ),
                    )
                }
            }

            val existingSourceKeys = dao.mediaAssetSourceKeys(LOCAL_MEDIASTORE_PROVIDER)
            val seenSourceKeySet = seenSourceKeys.toHashSet()
            val scanComplete = existingSourceKeys
                .asSequence()
                .filterNot(seenSourceKeySet::contains)
                .all { sourceKey ->
                    canConfirmSourceIsAbsentOrExcluded(context, sourceKey)
                }

            if (scanComplete && seenSourceKeys.isEmpty()) {
                dao.deleteAllMediaAssets(LOCAL_MEDIASTORE_PROVIDER)
            } else {
                val staleSourceKeys = staleSourceKeys(
                    existingSourceKeys = existingSourceKeys,
                    seenSourceKeys = seenSourceKeys,
                    scanComplete = scanComplete,
                )
                for (sourceKey in staleSourceKeys) {
                    dao.deleteMediaAsset(LOCAL_MEDIASTORE_PROVIDER, sourceKey)
                }
            }

            orderLocalRows(
                dao.localRows(
                    localProvider = LOCAL_MEDIASTORE_PROVIDER,
                    youtubeProvider = YOUTUBE_PROVIDER,
                    recordingEntityType = RECORDING_ENTITY_TYPE,
                ),
            ).map(CanonicalLocalRow::toLegacySong)
        }

    internal fun candidateFromLegacySong(song: Song): LocalMediaCandidate? {
        val uri = song.localUri?.takeIf(String::isNotBlank) ?: return null
        return LocalMediaCandidate(
            sourceKey = uri,
            title = song.title,
            artistDisplay = song.artist,
            durationMs = parseDurationMillis(song.durationText),
            albumTitle = song.albumName.cleanOptionalTag(),
            artworkUri = song.thumbnailUrl,
            uri = uri,
            path = song.localPath,
            localGenre = song.localGenre,
            localBitrateBps = song.localBitrateBps,
            localMimeType = song.localMimeType,
            dateAddedSeconds = song.localDateAddedSeconds,
            dateModifiedSeconds = song.localDateModifiedSeconds,
            trackNumber = song.trackNumber,
            genres = normalizedLocalTagList(song.localGenres),
            moods = normalizedLocalTagList(song.localMoods),
            bpms = normalizedLocalBpmList(song.localBpms),
            composers = normalizedLocalTagList(song.localComposers),
            sourceFingerprint = song.localDateModifiedSeconds?.takeIf { it > 0L }
                ?.let { modified -> "v3:$modified:${song.localSizeBytes ?: -1L}" },
            youtubeVideoId = song.videoId.takeIf { id ->
                id != uri &&
                    !id.startsWith("content://", ignoreCase = true) &&
                    !id.startsWith("file://", ignoreCase = true)
            },
        )
    }

    internal fun localReleaseEvidenceKey(albumTitle: String, artistDisplay: String): String =
        "${normalizeEvidence(albumTitle)}|${normalizeEvidence(artistDisplay)}"

    internal fun parseDurationMillis(durationText: String?): Long? {
        val rawParts = durationText?.trim()?.split(':') ?: return null
        if (rawParts.size !in 2..3) return null

        val parts = rawParts.map { part ->
            part.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        if (parts.last() !in 0L..59L) return null
        if (parts.size == 3 && parts[1] !in 0L..59L) return null

        val totalSeconds = when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3_600L + parts[1] * 60L + parts[2]
            else -> return null
        }
        return totalSeconds.takeIf { it >= 0L }?.times(1_000L)
    }

    internal fun orderLocalRows(rows: List<CanonicalLocalRow>): List<CanonicalLocalRow> =
        rows.sortedWith(
            compareBy<CanonicalLocalRow, String>(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    internal fun staleSourceKeys(
        existingSourceKeys: List<String>,
        seenSourceKeys: Collection<String>,
        scanComplete: Boolean,
    ): List<String> {
        if (!scanComplete) return emptyList()
        val seen = seenSourceKeys.toHashSet()
        return existingSourceKeys.filterNot(seen::contains)
    }

    internal fun mediaStoreLookupForSourceKey(sourceKey: String): MediaStoreSourceLookup? {
        val parsed = runCatching { java.net.URI(sourceKey) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("content", ignoreCase = true)) return null
        if (!parsed.authority.equals("media", ignoreCase = true)) return null

        val segments = parsed.path
            ?.trim('/')
            ?.split('/')
            ?.filter(String::isNotBlank)
            ?: return null
        if (segments.size < 4) return null
        if (!segments[segments.lastIndex - 2].equals("audio", ignoreCase = true)) return null
        if (!segments[segments.lastIndex - 1].equals("media", ignoreCase = true)) return null

        val mediaId = segments.last().toLongOrNull() ?: return null
        val collectionPath = segments.dropLast(1).joinToString("/")
        return MediaStoreSourceLookup(
            collectionUri = "content://${parsed.authority}/$collectionPath",
            mediaId = mediaId,
        )
    }

    /**
     * An unseen persisted asset is only safe to prune when MediaStore can
     * authoritatively show that its source row is gone or no longer belongs in
     * the current Local Music view. A null/failed query is deliberately treated
     * as unknown so a transient scanner failure cannot wipe the canonical DB.
     *
     * Query the collection by `_ID` rather than the stale item URI itself. Some
     * MediaStore implementations return null/fail for a deleted item URI, while
     * the collection query can authoritatively return an empty cursor.
     */
    private fun canConfirmSourceIsAbsentOrExcluded(context: Context, sourceKey: String): Boolean {
        val lookup = mediaStoreLookupForSourceKey(sourceKey) ?: return false
        val collectionUri = runCatching { Uri.parse(lookup.collectionUri) }.getOrNull() ?: return false

        val projection = buildList {
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_ALARM)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.IS_PODCAST)
            }
        }.toTypedArray()

        return runCatching {
            context.contentResolver.query(
                collectionUri,
                projection,
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(lookup.mediaId.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use true

                val displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME),
                ).orEmpty()
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val durationMs = if (cursor.isNull(durationColumn)) 0L else cursor.getLong(durationColumn)
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                val isMusic = cursor.getInt(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC),
                ) != 0

                if (!isMusic) return@use true

                if (AppSettings.filterNonMusicAudio.value) {
                    val isAlarm = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM),
                    ) != 0
                    val isNotification = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION),
                    ) != 0
                    val isRingtone = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE),
                    ) != 0
                    val isPodcast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST)) != 0
                    } else {
                        false
                    }

                    if (isAlarm || isNotification || isRingtone || isPodcast) return@use true
                    if (!LocalMediaRepository.isEligibleLocalMusic(durationMs, displayName, path)) {
                        return@use true
                    }
                }

                !LocalMediaRepository.isInSelectedFolder(
                    path,
                    AppSettings.localMusicFolderUri.value,
                )
            } ?: false
        }.getOrDefault(false)
    }

    private fun normalizeEvidence(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun String?.cleanOptionalTag(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }

    /** Never turn a failed/blank tag read into a verified empty field. */
    /** Preserve trusted embedded values during an unchanged MediaStore rescan. */
    private fun mergedLocalTagJson(
        fresh: List<String>?, previousJson: String?,
    ): String? {
        val old = previousJson?.let {
            runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull()
        }
        val values = when {
            fresh != null && old != null -> (fresh + old).distinct()
            fresh != null -> fresh
            else -> old
        }
        return values?.let { Json.encodeToString(it) }
    }

    private fun mergedLocalBpmJson(
        fresh: List<Double>?, previousJson: String?,
    ): String? {
        val old = previousJson?.let {
            runCatching { Json.decodeFromString<List<Double>>(it) }.getOrNull()
        }
        val values = when {
            fresh != null && old != null -> (fresh + old).distinct()
            fresh != null -> fresh
            else -> old
        }
        return values?.let { Json.encodeToString(it) }
    }

    private fun normalizedLocalBpmList(values: List<Double>?): List<Double>? =
        values?.takeIf { it.size <= 16 && it.all { v -> v.isFinite() && v > 0.0 && v <= 999.0 } }
            ?.distinct()

    private fun normalizedLocalTagList(values: List<String>?): List<String>? =
        values?.takeIf { it.size <= 64 }?.map { it.trim() }
            ?.takeIf { tags ->
                tags.all { it.isNotEmpty() && it.length <= 256 &&
                    !it.equals("<unknown>", ignoreCase = true) }
            }?.distinct()
}

internal data class LocalMediaCandidate(
    val sourceKey: String,
    val title: String,
    val artistDisplay: String,
    val durationMs: Long?,
    val albumTitle: String?,
    val artworkUri: String?,
    val uri: String,
    val path: String?,
    val dateAddedSeconds: Long?,
    val dateModifiedSeconds: Long?,
    val trackNumber: Int?,
    val youtubeVideoId: String?,
    val genres: List<String>? = null,
    val moods: List<String>? = null,
    val bpms: List<Double>? = null,
    val composers: List<String>? = null,
    val sourceFingerprint: String? = null,
    val localGenre: String? = null,
    val localBitrateBps: Long? = null,
    val localMimeType: String? = null,
)

internal data class MediaStoreSourceLookup(
    val collectionUri: String,
    val mediaId: Long,
)

/**
 * Bring the existing MediaStore library into the canonical multi-source view
 * once per process, rather than rescanning on every category or Compose entry.
 * The Library's explicit pull-to-refresh invalidates this gate; permission
 * changes also retry because an unsuccessful scan is never marked complete.
 * All playback identities still come from [CanonicalLocalLibrary.sync].
 */
internal object UnifiedLocalCatalogIndex {
    private val mutex = Mutex()
    @Volatile private var complete = false

    private fun trace(
        phase: String,
        scannedCount: Int = 0,
        indexedCount: Int = 0,
        elapsedNanos: Long? = null,
    ) {
        // Privacy: counts and stages only; never log paths, file names or IDs.
        runCatching {
            Diagnostics.event(
                category = DiagnosticCategory.CATALOG,
                name = "unified_library_local_index_v1",
                metadata = mapOf(
                    "phase" to phase,
                    "scannedCount" to scannedCount.toString(),
                    "indexedCount" to indexedCount.toString(),
                ),
                durationNanos = elapsedNanos,
            )
        }
    }

    suspend fun ensure(context: Context) {
        if (complete) return
        if (!LocalMediaRepository.hasStoragePermission(context)) {
            trace("permission_missing")
            return
        }
        mutex.withLock {
            if (complete || !LocalMediaRepository.hasStoragePermission(context)) return@withLock
            val started = SystemClock.elapsedRealtimeNanos()
            val app = context.applicationContext
            var scannedCount = 0
            try {
                val scanned = LocalMediaRepository.getLocalMusic(app)
                scannedCount = scanned.size
                trace("scanned", scannedCount)
                val indexed = CanonicalLocalLibrary.sync(app, scanned)
                complete = true
                trace("ready", scannedCount, indexed.size,
                    SystemClock.elapsedRealtimeNanos() - started)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                trace("cancelled", scannedCount)
                throw cancelled
            } catch (failure: Exception) {
                trace("failed", scannedCount, elapsedNanos =
                    SystemClock.elapsedRealtimeNanos() - started)
                throw failure
            }
        }
    }

    fun invalidate() {
        complete = false
        trace("invalidated")
    }
}
