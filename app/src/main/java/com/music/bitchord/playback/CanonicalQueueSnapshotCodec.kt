package com.music.bitchord.playback

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Canonical queue schema v2.
 *
 * A migrated row stores canonical identity plus safe UI metadata only. Rows for
 * which no canonical mapping is proven may remain explicitly legacy, so their
 * compatibility envelope cannot force already-migrated rows back to schema v1.
 * The v2 context itself never stores provider sourceKey, auth, or final endpoints.
 */
internal object CanonicalQueueSnapshotCodec {
    private const val SCHEMA_VERSION = 2
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun canEncode(state: BoundedContextPersistence.State): Boolean =
        state.contextIdentities.any { it != null } ||
            state.entries.any { entry ->
                entry.canonicalIdentity != null ||
                    entry.canonicalIndex?.let(state.contextIdentities::getOrNull) != null
            }

    fun encode(state: BoundedContextPersistence.State): String? {
        if (!canEncode(state)) return null
        return runCatching { json.encodeToString(StoredState.from(state)) }.getOrNull()
    }

    fun decode(raw: String): BoundedContextPersistence.State? {
        if (!raw.contains("\"schemaVersion\":$SCHEMA_VERSION")) return null
        return runCatching { json.decodeFromString<StoredState>(raw).toState() }.getOrNull()
    }

    @Serializable
    private data class StoredIdentity(
        val recordingId: String,
        val mediaAssetId: String? = null,
        val providerKey: String? = null,
    ) {
        fun toIdentity(): CanonicalQueueIdentity? = runCatching {
            CanonicalQueueIdentity(recordingId, mediaAssetId, providerKey)
        }.getOrNull()

        companion object {
            fun from(value: CanonicalQueueIdentity) = StoredIdentity(
                recordingId = value.recordingId,
                mediaAssetId = value.mediaAssetId,
                providerKey = value.providerKey,
            )
        }
    }

    /** Safe metadata for canonical rows. No playable locator or native entity id lives here. */
    @Serializable
    private data class StoredMetadata(
        val title: String,
        val artist: String,
        val durationText: String? = null,
        val albumName: String? = null,
        val isVideo: Boolean = false,
        val isVideoOrigin: Boolean = false,
        val queueTier: String = QueueTier.CONTEXT.name,
        val queueEntryId: String? = null,
        val radioName: String? = null,
        val sourceQuality: String? = null,
        val isExplicit: Boolean? = null,
        val playbackSource: String? = null,
        val playbackSourceType: String? = null,
        val playbackSourceId: String? = null,
    ) {
        fun toSong(compatibilityId: String): Song? {
            val tier = runCatching { QueueTier.valueOf(queueTier) }.getOrNull() ?: return null
            val sourceType = playbackSourceType?.let {
                runCatching { PlaybackSourceType.valueOf(it) }.getOrNull()
            }
            if (playbackSourceType != null && sourceType == null) return null
            return Song(
                videoId = compatibilityId,
                title = title,
                artist = artist,
                thumbnailUrl = null,
                durationText = durationText,
                albumName = albumName,
                isVideo = isVideo,
                isVideoOrigin = isVideoOrigin,
                queueTier = tier,
                queueEntryId = queueEntryId,
                radioName = radioName,
                sourceQuality = sourceQuality,
                isExplicit = isExplicit,
                playbackSource = playbackSource,
                playbackSourceType = sourceType,
                playbackSourceId = playbackSourceId,
            )
        }

        companion object {
            fun from(song: Song) = StoredMetadata(
                title = song.title,
                artist = song.artist,
                durationText = song.durationText,
                albumName = song.albumName,
                isVideo = song.isVideo,
                isVideoOrigin = song.isVideoOrigin,
                queueTier = song.queueTier.name,
                queueEntryId = song.queueEntryId,
                radioName = song.radioName,
                sourceQuality = song.sourceQuality,
                isExplicit = song.isExplicit,
                playbackSource = song.playbackSource,
                playbackSourceType = song.playbackSourceType?.name,
                playbackSourceId = song.playbackSourceId,
            )
        }
    }

    /**
     * Compatibility payload used only for a row with no proven canonical mapping.
     * It is never emitted for a migrated row.
     */
    @Serializable
    private data class StoredLegacySong(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
        val durationText: String? = null,
        val artistId: String? = null,
        val artists: List<StoredArtist> = emptyList(),
        val albumId: String? = null,
        val albumName: String? = null,
        val isVideo: Boolean = false,
        val isVideoOrigin: Boolean = false,
        val setVideoId: String? = null,
        val queueTier: String = QueueTier.CONTEXT.name,
        val queueEntryId: String? = null,
        val radioName: String? = null,
        val localUri: String? = null,
        val downloadFormat: String? = null,
        val localPath: String? = null,
        val localDateAddedSeconds: Long? = null,
        val localDateModifiedSeconds: Long? = null,
        val sourceQuality: String? = null,
        val isExplicit: Boolean? = null,
        val playbackSource: String? = null,
        val playbackSourceType: String? = null,
        val playbackSourceId: String? = null,
    ) {
        fun toSong(): Song? {
            if (videoId.isBlank()) return null
            val tier = runCatching { QueueTier.valueOf(queueTier) }.getOrNull() ?: return null
            val sourceType = playbackSourceType?.let {
                runCatching { PlaybackSourceType.valueOf(it) }.getOrNull()
            }
            if (playbackSourceType != null && sourceType == null) return null
            return Song(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                durationText = durationText,
                artistId = artistId,
                artists = artists.map { ArtistRef(it.name, it.browseId) },
                albumId = albumId,
                albumName = albumName,
                isVideo = isVideo,
                isVideoOrigin = isVideoOrigin,
                setVideoId = setVideoId,
                queueTier = tier,
                queueEntryId = queueEntryId,
                radioName = radioName,
                localUri = localUri,
                downloadFormat = downloadFormat,
                localPath = localPath,
                localDateAddedSeconds = localDateAddedSeconds,
                localDateModifiedSeconds = localDateModifiedSeconds,
                sourceQuality = sourceQuality,
                isExplicit = isExplicit,
                playbackSource = playbackSource,
                playbackSourceType = sourceType,
                playbackSourceId = playbackSourceId,
            )
        }

        companion object {
            fun from(song: Song) = StoredLegacySong(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                durationText = song.durationText,
                artistId = song.artistId,
                artists = song.artists.map { StoredArtist(it.name, it.browseId) },
                albumId = song.albumId,
                albumName = song.albumName,
                isVideo = song.isVideo,
                isVideoOrigin = song.isVideoOrigin,
                setVideoId = song.setVideoId,
                queueTier = song.queueTier.name,
                queueEntryId = song.queueEntryId,
                radioName = song.radioName,
                localUri = song.localUri,
                downloadFormat = song.downloadFormat,
                localPath = song.localPath,
                localDateAddedSeconds = song.localDateAddedSeconds,
                localDateModifiedSeconds = song.localDateModifiedSeconds,
                sourceQuality = song.sourceQuality,
                isExplicit = song.isExplicit,
                playbackSource = song.playbackSource,
                playbackSourceType = song.playbackSourceType?.name,
                playbackSourceId = song.playbackSourceId,
            )
        }
    }

    @Serializable
    private data class StoredArtist(val name: String, val browseId: String?)

    @Serializable
    private data class StoredSong(
        val identity: StoredIdentity? = null,
        val metadata: StoredMetadata,
        val legacy: StoredLegacySong? = null,
    ) {
        fun identityOrNull(): CanonicalQueueIdentity? = identity?.toIdentity()

        fun toSong(compatibilityId: String): Song? = when {
            identity != null && legacy == null -> {
                val canonical = identity.toIdentity() ?: return null
                metadata.toSong("$compatibilityId:${canonical.recordingId}")
            }
            identity == null && legacy != null -> legacy.toSong()
            else -> null
        }

        companion object {
            fun from(song: Song, identity: CanonicalQueueIdentity?): StoredSong =
                if (identity != null) {
                    StoredSong(
                        identity = StoredIdentity.from(identity),
                        metadata = StoredMetadata.from(song),
                    )
                } else {
                    StoredSong(
                        metadata = StoredMetadata.from(song),
                        legacy = StoredLegacySong.from(song),
                    )
                }
        }
    }

    @Serializable
    private data class StoredEntry(
        val song: StoredSong,
        val canonicalIndex: Int? = null,
        val cycleId: Long? = null,
    )

    @Serializable
    private data class StoredContext(
        val contextToken: String,
        val browseId: String? = null,
        val sourceTitle: String,
        val sourceType: String,
        val sourceId: String? = null,
        val songs: List<StoredSong>,
        val continuationToken: String? = null,
        val continuationState: String,
        val diagnostic: String? = null,
        val seenContinuationTokens: List<String> = emptyList(),
    )

    @Serializable
    private data class StoredCycle(val cycleId: Long, val order: List<Int>)

    @Serializable
    private data class StoredWindow(
        val canonicalSize: Int,
        val order: List<Int>,
        val cursor: Int,
        val currentCanonicalIndex: Int?,
        val shuffleEnabled: Boolean,
        val shuffleSeed: Long,
        val cycleId: Long,
        val consumedThisCycle: List<Int>,
        val lastCycleFinalCanonicalIndex: Int?,
        val plannedCycles: List<StoredCycle> = emptyList(),
    )

    @Serializable
    private data class StoredState(
        val schemaVersion: Int = SCHEMA_VERSION,
        val context: StoredContext,
        val window: StoredWindow,
        val entries: List<StoredEntry>,
        val currentIndex: Int,
        val positionMs: Long,
    ) {
        fun toState(): BoundedContextPersistence.State? {
            if (schemaVersion != SCHEMA_VERSION || entries.isEmpty() ||
                currentIndex !in entries.indices || positionMs < 0L
            ) return null
            val sourceType = runCatching { PlaybackSourceType.valueOf(context.sourceType) }
                .getOrNull() ?: return null
            val source = QueueSource(context.sourceTitle, sourceType, context.sourceId)
            val identities = context.songs.map { stored -> stored.identityOrNull() }
            val contextSongs = context.songs.mapIndexed { index, item ->
                item.toSong("canonical:context:$index")
                    ?.copy(queueTier = QueueTier.CONTEXT, queueEntryId = null)
                    ?: return null
            }
            val continuationState = runCatching {
                ContinuationState.valueOf(context.continuationState)
            }.getOrNull() ?: return null
            val diagnostic = context.diagnostic?.let {
                runCatching { ContinuationDiagnostic.valueOf(it) }.getOrNull() ?: return null
            }
            val snapshot = ContextSnapshot(
                contextToken = context.contextToken,
                sourceKey = "${source.type.name}:${source.id.orEmpty()}",
                browseId = context.browseId,
                songs = contextSongs,
                version = 0L,
                continuationToken = context.continuationToken,
                continuationState = continuationState,
                diagnostic = diagnostic,
                seenContinuationTokens = context.seenContinuationTokens,
            )
            val restoredEntries = entries.mapIndexed { index, stored ->
                val identity = stored.song.identityOrNull()
                val song = if (stored.canonicalIndex != null) {
                    val canonical = contextSongs.getOrNull(stored.canonicalIndex) ?: return null
                    val tier = runCatching {
                        QueueTier.valueOf(stored.song.metadata.queueTier)
                    }.getOrNull() ?: return null
                    canonical.copy(
                        queueTier = tier,
                        queueEntryId = stored.song.metadata.queueEntryId,
                    )
                } else {
                    stored.song.toSong("canonical:entry:$index") ?: return null
                }
                BoundedContextPersistence.Entry(
                    song = song,
                    canonicalIndex = stored.canonicalIndex,
                    cycleId = stored.cycleId,
                    canonicalIdentity = identity,
                )
            }
            val windowSnapshot = QueueWindowSnapshot(
                canonicalSize = window.canonicalSize,
                order = window.order,
                cursor = window.cursor,
                currentCanonicalIndex = window.currentCanonicalIndex,
                shuffleEnabled = window.shuffleEnabled,
                shuffleSeed = window.shuffleSeed,
                cycleId = window.cycleId,
                consumedThisCycle = window.consumedThisCycle,
                lastCycleFinalCanonicalIndex = window.lastCycleFinalCanonicalIndex,
                plannedCycles = window.plannedCycles.map {
                    QueueWindowCycleSnapshot(it.cycleId, it.order)
                },
            )
            return BoundedContextPersistence.State(
                context = snapshot,
                source = source,
                window = windowSnapshot,
                entries = restoredEntries,
                currentIndex = currentIndex,
                positionMs = positionMs,
                contextIdentities = identities,
            )
        }

        companion object {
            fun from(state: BoundedContextPersistence.State): StoredState {
                val contextIdentities = state.contextIdentities
                    .takeIf { it.size == state.context.songs.size }
                    ?: List(state.context.songs.size) { null }
                return StoredState(
                    context = StoredContext(
                        contextToken = state.context.contextToken,
                        browseId = state.context.browseId,
                        sourceTitle = state.source.title,
                        sourceType = state.source.type.name,
                        sourceId = state.source.id,
                        songs = state.context.songs.mapIndexed { index, song ->
                            StoredSong.from(
                                song.copy(queueEntryId = null),
                                contextIdentities[index],
                            )
                        },
                        continuationToken = state.context.continuationToken,
                        continuationState = state.context.continuationState.name,
                        diagnostic = state.context.diagnostic?.name,
                        seenContinuationTokens = state.context.seenContinuationTokens,
                    ),
                    window = StoredWindow(
                        canonicalSize = state.window.canonicalSize,
                        order = state.window.order,
                        cursor = state.window.cursor,
                        currentCanonicalIndex = state.window.currentCanonicalIndex,
                        shuffleEnabled = state.window.shuffleEnabled,
                        shuffleSeed = state.window.shuffleSeed,
                        cycleId = state.window.cycleId,
                        consumedThisCycle = state.window.consumedThisCycle,
                        lastCycleFinalCanonicalIndex = state.window.lastCycleFinalCanonicalIndex,
                        plannedCycles = state.window.plannedCycles.map {
                            StoredCycle(it.cycleId, it.order)
                        },
                    ),
                    entries = state.entries.map { entry ->
                        val identity = entry.canonicalIdentity
                            ?: entry.canonicalIndex?.let(contextIdentities::getOrNull)
                        StoredEntry(
                            song = StoredSong.from(entry.song, identity),
                            canonicalIndex = entry.canonicalIndex,
                            cycleId = entry.cycleId,
                        )
                    },
                    currentIndex = state.currentIndex,
                    positionMs = state.positionMs.coerceAtLeast(0L),
                )
            }
        }
    }
}
