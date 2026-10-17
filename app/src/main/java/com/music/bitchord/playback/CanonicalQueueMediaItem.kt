package com.music.bitchord.playback

import androidx.media3.common.MediaItem

/**
 * Playback-owned sidecar for canonical identity on a live Media3 queue item.
 *
 * MediaItem.mediaId deliberately remains the compatibility/transport id. The
 * canonical recording id is looked up through the immutable queue-entry id,
 * which already survives MediaSession bundling and queue reordering.
 */
internal val MediaItem.canonicalQueueIdentity: CanonicalQueueIdentity?
    get() = queueEntryId?.let(CanonicalQueueIdentityHints::identityForEntry)

/**
 * Process-local bridge from a restored durable canonical snapshot to Media3.
 *
 * The map is replaced atomically for each bounded restore. Keys are queue-entry
 * ids, never provider ids or rendition URIs, so changing the playable MediaAsset
 * does not change the identity lookup. Canonical-context keys mirror the ids the
 * service creates while rebuilding QueueShuffle's logical context.
 */
internal object CanonicalQueueIdentityHints {
    @Volatile
    private var byEntryId: Map<String, CanonicalQueueIdentity> = emptyMap()

    fun replace(
        contextToken: String,
        contextIdentities: List<CanonicalQueueIdentity?>,
        entries: List<BoundedContextPersistence.Entry>,
    ) {
        val next = LinkedHashMap<String, CanonicalQueueIdentity>(
            contextIdentities.size + entries.size,
        )
        contextIdentities.forEachIndexed { index, identity ->
            if (identity != null) {
                next["context:$contextToken:$index"] = identity
            }
        }
        entries.forEach { entry ->
            val identity = entry.canonicalIdentity ?: return@forEach
            val entryId = entry.song.queueEntryId?.takeIf(String::isNotBlank) ?: return@forEach
            next[entryId] = identity
        }
        byEntryId = next
    }

    fun identityForEntry(entryId: String): CanonicalQueueIdentity? = byEntryId[entryId]

    fun clear() {
        byEntryId = emptyMap()
    }
}
