package com.music.bitchord.data.listening

/**
 * Reorders adjacent or arbitrary siblings without flattening groups or changing
 * saved smart-playlist references. Structural editing stays separate from the UI.
 */
internal fun SmartPlaylistRule.Group.moveChild(
    fromIndex: Int,
    toIndex: Int,
): SmartPlaylistRule.Group {
    require(fromIndex in children.indices && toIndex in children.indices) {
        "Rule position is outside this group"
    }
    if (fromIndex == toIndex) return this
    val reordered = children.toMutableList()
    val moved = reordered.removeAt(fromIndex)
    reordered.add(toIndex, moved)
    return copy(children = reordered)
}

/** The UI must append the selected definition, never the first available one. */
internal fun SmartPlaylistRule.Group.appendPlaylistReference(
    playlistId: String,
): SmartPlaylistRule.Group {
    require(playlistId.isNotBlank() && playlistId.length <= 100 &&
        playlistId == playlistId.trim()) { "Invalid saved playlist reference" }
    return copy(children = children + SmartPlaylistRule.Reference(playlistId))
}
