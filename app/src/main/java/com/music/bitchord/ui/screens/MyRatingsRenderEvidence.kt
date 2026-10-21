package com.music.bitchord.ui.screens

/**
 * L18 diagnostics projection. Counts describe UI data truth without persisting
 * canonical or provider identifiers, titles, individual values or credentials.
 * A Composable callback is NOT pixel-visibility evidence (D13 owns that).
 */
internal data class MyRatingsRenderEvidence private constructor(
    private val localState: String,
    private val remoteState: String,
    private val localCount: Int?,
    private val remoteEvidenceCount: Int?,
    private val differentRatingRecordingCount: Int?,
    private val unmatchedRemoteCount: Int?,
) {
    fun metadata(): Map<String, String> = buildMap {
        put("localState", localState)
        put("remoteState", remoteState)
        put("visibleProven", "unknown")
        localCount?.let { put("localCount", it.coerceIn(0, 10000).toString()) }
        remoteEvidenceCount?.let { put("remoteEvidenceCount", it.coerceIn(0, 10000).toString()) }
        differentRatingRecordingCount?.let {
            put("differentRatingRecordingCount", it.coerceIn(0, 10000).toString())
        }
        unmatchedRemoteCount?.let {
            put("unmatchedRemoteCount", it.coerceIn(0, 10000).toString())
        }
    }

    companion object {
        val ALLOWED_KEYS: Set<String> = setOf(
            "localState", "remoteState", "visibleProven",
            "localCount", "remoteEvidenceCount",
            "differentRatingRecordingCount", "unmatchedRemoteCount",
        )

        fun from(
            local: MyRatingsUiState,
            remote: RemoteRatingsUiState,
        ): MyRatingsRenderEvidence {
            val localStatus = when (local) {
                MyRatingsUiState.Loading -> "loading"
                MyRatingsUiState.Empty -> "empty"
                MyRatingsUiState.Error -> "error"
                is MyRatingsUiState.Content -> "content"
            }
            val remoteStatus = when (remote) {
                RemoteRatingsUiState.Disabled -> "disabled"
                RemoteRatingsUiState.Loading -> "loading"
                RemoteRatingsUiState.Unavailable -> "unavailable"
                RemoteRatingsUiState.Error -> "error"
                is RemoteRatingsUiState.Ready -> "ready"
            }
            val localRecords = (local as? MyRatingsUiState.Content)?.items
            val remoteRows = (remote as? RemoteRatingsUiState.Ready)?.rows
            val remoteById = remoteRows?.associateBy { it.recordingId }
            val mismatches = if (localRecords != null && remoteById != null) {
                localRecords.count { row ->
                    remoteById[row.recordingId]?.evidence
                        ?.mapNotNull { it.numericValue }
                        ?.any { it.toDouble() != row.value } == true
                }
            } else null
            return MyRatingsRenderEvidence(
                localState = localStatus,
                remoteState = remoteStatus,
                localCount = when (local) {
                    MyRatingsUiState.Empty -> 0
                    is MyRatingsUiState.Content -> local.items.size
                    else -> null
                },
                remoteEvidenceCount = remoteRows?.size,
                differentRatingRecordingCount = mismatches,
                unmatchedRemoteCount = (remote as? RemoteRatingsUiState.Ready)?.coverage?.unmatched,
            )
        }
    }
}
