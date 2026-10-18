package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

internal object CanonicalQueueMigration {
    suspend fun canonicalize(
        state: BoundedContextPersistence.State,
        resolver: CanonicalQueueResolver,
    ): BoundedContextPersistence.State {
        val contextSongs = state.context.songs
        val existingContext = state.contextIdentities.takeIf { it.size == contextSongs.size }
        val entriesNeedingIdentification = state.entries.map { entry ->
            if (entry.canonicalIdentity != null || entry.canonicalIndex?.let { existingContext?.getOrNull(it) } != null) {
                null
            } else {
                entry.song
            }
        }
        val identificationInput = buildList {
            if (existingContext == null) addAll(contextSongs)
            entriesNeedingIdentification.filterNotNull().forEach(::add)
        }
        val detected = resolver.identifyAll(identificationInput).iterator()
        val contextIdentities = if (existingContext != null) {
            existingContext
        } else {
            List(contextSongs.size) { if (detected.hasNext()) detected.next() else null }
        }
        val entryIdentities = state.entries.indices.map { index ->
            val entry = state.entries[index]
            entry.canonicalIdentity
                ?: entry.canonicalIndex?.let(contextIdentities::getOrNull)
                ?: if (entriesNeedingIdentification[index] != null && detected.hasNext()) detected.next() else null
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
        val contextIdentities = state.contextIdentities.takeIf { it.size == state.context.songs.size }
        val entryIdentities = state.entries.map { entry ->
            entry.canonicalIdentity
                ?: entry.canonicalIndex?.let { index -> contextIdentities?.getOrNull(index) }
        }
        if (contextIdentities == null && entryIdentities.none { it != null }) return state
        if (contextIdentities == null || contextIdentities.any { it == null } || entryIdentities.any { it == null }) {
            return null
        }

        val all = contextIdentities + entryIdentities
        val resolved = resolver.resolveAll(all)
        if (resolved.size != all.size || resolved.any { it == null }) return null
        val contextAssets = resolved.take(contextIdentities.size)
        val entryAssets = resolved.drop(contextIdentities.size)
        val restoredContextSongs = state.context.songs.mapIndexed { index, song ->
            song.withCanonicalAsset(requireNotNull(contextAssets[index]))
        }
        val restoredEntries = state.entries.mapIndexed { index, entry ->
            entry.copy(
                song = entry.song.withCanonicalAsset(requireNotNull(entryAssets[index])),
                canonicalIdentity = entryIdentities[index],
            )
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
        queueTier = queueTier.takeIf { it == QueueTier.CONTEXT || it == QueueTier.USER_QUEUE || it == QueueTier.AUTOPLAY }
            ?: QueueTier.CONTEXT,
    )
}
