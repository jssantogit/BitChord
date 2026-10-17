package com.music.bitchord.data.catalog

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale
import java.util.UUID

internal object CanonicalLocalLibrary {
    private const val LOCAL_MEDIASTORE_PROVIDER = "local-mediastore"
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
        return database.withTransaction {
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
                    ),
                )

                dao.deleteReleaseTracksForRecording(recordingId)
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
            dateAddedSeconds = song.localDateAddedSeconds,
            dateModifiedSeconds = song.localDateModifiedSeconds,
            trackNumber = song.trackNumber,
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
)

internal data class MediaStoreSourceLookup(
    val collectionUri: String,
    val mediaId: Long,
)
