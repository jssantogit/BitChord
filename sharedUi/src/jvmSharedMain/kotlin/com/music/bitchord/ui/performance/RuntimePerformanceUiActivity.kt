package com.music.bitchord.ui.performance

/** Fixed, low-cardinality UI activity signals used by debug runtime diagnostics. */
enum class RuntimePerformanceUiSignal(val metadataKey: String) {
    NOW_PLAYING_RECOMPOSITION("nowPlayingRecompositions"),
    ARTWORK_BACKDROP_RECOMPOSITION("artworkBackdropRecompositions"),
    ARTWORK_MESH_UPDATE("artworkMeshUpdates"),
    CANVAS_FRAME_MESH_UPDATE("canvasFrameMeshUpdates"),
}

/** Fixed UI state dimensions attached to bounded activity windows. */
enum class RuntimePerformanceUiStateKey(
    val metadataKey: String,
    val changesMetadataKey: String,
) {
    CANVAS_MODE("canvasMode", "canvasModeChanges"),
    DYNAMIC_BLUR("dynamicBlur", "dynamicBlurChanges"),
}

/** Fixed values for UI state dimensions. */
enum class RuntimePerformanceUiStateValue(val metadataValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    ENABLED("enabled"),
    REDUCED("reduced"),
}
