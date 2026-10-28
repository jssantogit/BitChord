package com.music.bitchord.data.listening

/**
 * Canonical saved-V2 reference graph. We resolve an ordered DAG before reading
 * any candidates; a missing, corrupt or cyclic target is an ERROR (not an empty
 * playlist, and not a FALSE condition that NOT can invert).
 */
internal fun SmartPlaylistRule.referencedPlaylistIds(): Set<String> = when (this) {
    is SmartPlaylistRule.Reference -> setOf(playlistId)
    is SmartPlaylistRule.Comparison -> emptySet()
    is SmartPlaylistRule.Group -> children.flatMap { it.referencedPlaylistIds() }.toSet()
    is SmartPlaylistRule.Not -> child.referencedPlaylistIds()
}

/**
 * Postorder dependencies: a referenced definition is evaluated exactly once,
 * using the same account/availability snapshot as the requesting query.
 * The limit is a graph bound, independent of the existing AST depth/size bounds.
 */
internal fun resolveSmartPlaylistReferences(
    root: SmartPlaylistV2Query,
    saved: List<SavedSmartPlaylistV2Definition>,
    rootId: String? = null,
): List<SavedSmartPlaylistV2Definition> {
    require(rootId == null || (rootId.isNotBlank() && rootId.length <= 100))
    val ids = saved.map { it.id }
    require(ids.all { it.isNotBlank() && it.length <= 100 } && ids.size == ids.distinct().size) {
        "Ambiguous saved playlist identities"
    }
    val definitions = saved.associateBy { it.id }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val order = mutableListOf<SavedSmartPlaylistV2Definition>()
    fun visit(id: String, depth: Int) {
        require(depth <= 8 && visited.size + visiting.size < 64) {
            "Saved playlist reference graph exceeds safety bounds"
        }
        require(id != rootId) { "A saved playlist cannot reference itself" }
        require(id !in visiting) { "Cyclic saved smart playlist reference" }
        if (id in visited) return
        val target = definitions[id]
            ?: throw IllegalArgumentException("Referenced smart playlist is not available")
        visiting += id
        target.query.rules.referencedPlaylistIds().sorted().forEach { visit(it, depth + 1) }
        visiting -= id
        visited += id
        order += target
    }
    root.rules.referencedPlaylistIds().sorted().forEach { visit(it, 1) }
    return order
}

/**
 * The *result* of each saved query (including its sort/limit) is the referenced
 * membership, not a shallow copy of the child's predicates. Every stage uses
 * the same canonical candidate evidence and fails closed for absent memberships.
 */
internal fun previewSmartPlaylistReferences(
    candidates: List<RuleBasedRecordingCandidate>,
    root: SmartPlaylistV2Query,
    graph: List<SavedSmartPlaylistV2Definition>,
    engine: SmartPlaylistV2RuleEngine,
): List<SmartPlaylistV2Match> {
    val memberships = mutableMapOf<String, Set<String>>()
    graph.forEach { definition ->
        memberships[definition.id] = engine.preview(candidates, definition.query, memberships)
            .mapTo(linkedSetOf()) { it.recordingId }
    }
    return engine.preview(candidates, root, memberships)
}

/** Validate the *projected* set before saving; old V1 rows are never touched. */
internal fun validateSmartPlaylistReferencesOnSave(
    proposed: SavedSmartPlaylistV2Definition,
    saved: List<SavedSmartPlaylistV2Definition>,
) {
    val projected = saved.filterNot { it.id == proposed.id } + proposed
    projected.forEach { definition ->
        resolveSmartPlaylistReferences(definition.query, projected, definition.id)
    }
}

internal fun validateSmartPlaylistReferenceDeletion(
    id: String,
    saved: List<SavedSmartPlaylistV2Definition>,
) {
    require(saved.none {
        it.id != id && id in it.query.rules.referencedPlaylistIds()
    }) { "A saved playlist is referenced by another saved playlist" }
}
