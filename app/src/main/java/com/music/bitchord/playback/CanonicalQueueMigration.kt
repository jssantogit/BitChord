package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

internal object CanonicalQueueMigration {
    suspend fun canonicalize(
        state: BoundedContextPersistence.State,
        resolver: CanonicalQueueResolver,
    ): BoundedContextPersistence.State {
        val contextSongs = state.context.songs
        val contextIdentities = state.contextIdentities
            .takeIf { it.size == contextSongs.size }
            ?.toMutableList()
            ?: MutableList(contextSongs.size) { null }

        // An anchored physical row that already crossed the canonical boundary is
        // stronger evidence than its compatibility Song envelope. Reuse it instead
        // of trying to rediscover identity from videoId/URI during persistence.
        state.entries.forEach { entry ->
            val index = entry.canonicalIndex ?: return@forEach
            val identity = entry.canonicalIdentity ?: return@forEach
            if (index in contextIdentities.indices && contextIdentities[index] == null) {
                contextIdentities[index] = identity
            }
        }

        val contextPending = contextSongs.indices.filter { contextIdentities[it] == null }
        val entryIdentities = state.entries.map { entry ->
            entry.canonicalIdentity
                ?: entry.canonicalIndex?.let(contextIdentities::getOrNull)
        }.toMutableList()
        val entryPending = state.entries.indices.filter { index -> entryIdentities[index] == null }

        val identificationInput = buildList {
            contextPending.forEach { add(contextSongs[it]) }
            entryPending.forEach { add(state.entries[it].song) }
        }
        if (identificationInput.isNotEmpty()) {
            val detected = resolver.identifyAll(identificationInput)
            if (detected.size == identificationInput.size) {
                contextPending.forEachIndexed { ordinal, index ->
                    contextIdentities[index] = detected[ordinal]
                }
                val entryOffset = contextPending.size
                entryPending.forEachIndexed { ordinal, index ->
                    val anchored = state.entries[index].canonicalIndex
                        ?.let(contextIdentities::getOrNull)
                    entryIdentities[index] = anchored ?: detected[entryOffset + ordinal]
                }
            }
        }

        state.entries.indices.forEach { index ->
            if (entryIdentities[index] == null) {
                entryIdentities[index] = state.entries[index].canonicalIndex
                    ?.let(contextIdentities::getOrNull)
            }
        }

        return state.copy(
            contextIdentities = contextIdentities,
            entries = state.entries.mapIndexed { index, entry ->
                entry.copy(canonicalIdentity = entryIdentities[index])
            },
        )
    }

    suspend fun rehydrate(
        state: BoundedContextPersistence.State,
        resolver: CanonicalQueueResolver,
    ): BoundedContextPersistence.State? {
        val contextIdentities = state.contextIdentities
            .takeIf { it.size == state.context.songs.size }
            ?: if (state.entries.none { it.canonicalIdentity != null }) return state else return null
        val entryIdentities = state.entries.map { entry ->
            entry.canonicalIdentity
                ?: entry.canonicalIndex?.let(contextIdentities::getOrNull)
        }
        val identitiesToResolve = (contextIdentities + entryIdentities)
            .filterNotNull()
            .distinct()
        if (identitiesToResolve.isEmpty()) return state

        val resolved = resolver.resolveAll(identitiesToResolve)
        if (resolved.size != identitiesToResolve.size) return null
        val byIdentity = identitiesToResolve.indices.associate { index ->
            identitiesToResolve[index] to resolved[index]
        }
        if (byIdentity.values.any { it == null }) return null

        fun asset(identity: CanonicalQueueIdentity?): CanonicalPlayableAsset? =
            identity?.let(byIdentity::get)

        val restoredContextSongs = state.context.songs.mapIndexed { index, song ->
            val identity = contextIdentities[index]
            if (identity == null) song else song.withCanonicalAsset(asset(identity) ?: return null)
        }
        val restoredEntries = state.entries.mapIndexed { index, entry ->
            val identity = entryIdentities[index]
            if (identity == null) {
                entry
            } else {
                entry.copy(
                    song = entry.song.withCanonicalAsset(asset(identity) ?: return null),
                    canonicalIdentity = identity,
                )
            }
        }
        return state.copy(
            context = state.context.copy(songs = restoredContextSongs),
            entries = restoredEntries,
            contextIdentities = contextIdentities,
        )
    }

    private fun Song.withCanonicalAsset(asset: CanonicalPlayableAsset): Song = copy(
        videoId = asset.uri,
        localUri = asset.uri,
        localPath = asset.path,
        queueTier = queueTier.takeIf {
            it == QueueTier.CONTEXT || it == QueueTier.USER_QUEUE || it == QueueTier.AUTOPLAY
        } ?: QueueTier.CONTEXT,
    )
}
