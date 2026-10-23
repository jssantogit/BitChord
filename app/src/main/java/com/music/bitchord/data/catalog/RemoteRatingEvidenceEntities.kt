package com.music.bitchord.data.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Only provider-owned, canonical-reconciled read-only evidence. Never Listening state. */
@Entity(
    tableName = "catalog_remote_rating_evidence",
    primaryKeys = ["providerKey", "scopeId", "mediaAssetId"],
    indices = [Index("recordingId")],
)
internal data class RemoteRatingEvidenceEntity(
    val providerKey: String,
    val scopeId: String,
    val mediaAssetId: String,
    val recordingId: String,
    val numericKind: String,
    val numericValue: Int?,
    val starredKind: String,
    val generation: Long,
) {
    override fun toString(): String = "RemoteRatingEvidenceEntity(<redacted>)"
}

/** One complete provider enumeration. Counts only: no source IDs or titles in diagnostics. */
@Entity(tableName = "catalog_remote_rating_coverage")
internal data class RemoteRatingCoverageEntity(
    @PrimaryKey val providerKey: String,
    val scopeId: String,
    val generation: Long,
    val enumerated: Int,
    val matched: Int,
    val unmatched: Int,
    val absentNumeric: Int,
    val clearedNumeric: Int,
    val valuedNumeric: Int,
    val invalidNumeric: Int,
    val starred: Int,
    val invalidStarred: Int,
) {
    override fun toString(): String =
        "RemoteRatingCoverage(enumerated=$enumerated, matched=$matched, unmatched=$unmatched, " +
            "absentNumeric=$absentNumeric, clearedNumeric=$clearedNumeric, " +
            "valuedNumeric=$valuedNumeric, invalidNumeric=$invalidNumeric, " +
            "starred=$starred, invalidStarred=$invalidStarred)"
}
