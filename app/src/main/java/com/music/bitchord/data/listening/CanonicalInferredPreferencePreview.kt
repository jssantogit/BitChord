package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only existing canonical IDs, never metadata names or provider-native identity. */
internal enum class CanonicalPreferenceTargetType { RELEASE, ARTIST }
internal enum class CanonicalPreferenceConfidence { INSUFFICIENT, LOW, MEDIUM, HIGH }

/** An inference is evidence, not an artist/release explicit rating or persisted score. */
internal data class CanonicalInferredPreferenceSummary(
    val targetType: CanonicalPreferenceTargetType,
    val targetId: String,
    val inferredRating: Double?,
    val ratedRecordingCount: Int,
    val totalRecordingCount: Int,
    val coverage: Double,
    val confidence: CanonicalPreferenceConfidence,
    val evidenceRecordingIds: List<String>,
)

/**
 * Pure projection: absence of an explicit rating is UNKNOWN, not zero/neutral.
 * No category-level explicit preference is created and upstream rankers are untouched.
 */
internal class CanonicalInferredPreferencePlanner {
    fun project(
        targetType: CanonicalPreferenceTargetType,
        targetId: String,
        memberRecordingIds: List<String>,
        explicitRatings: List<RecordingRating>,
    ): CanonicalInferredPreferenceSummary {
        require(targetId.isNotBlank())
        val members = memberRecordingIds.filter(String::isNotBlank).distinct().sorted()
        val memberSet = members.toHashSet()
        val ratingById = explicitRatings
            .asSequence()
            .filter { it.recordingId in memberSet && it.value.isFinite() && it.value in 1.0..5.0 }
            .sortedWith(compareByDescending<RecordingRating> { it.ratedAtEpochMs }.thenBy { it.recordingId })
            .distinctBy { it.recordingId }
            .associateBy { it.recordingId }
        val evidence = ratingById.keys.sorted()
        val rating = if (evidence.isEmpty()) null else evidence.sumOf { ratingById.getValue(it).value } / evidence.size
        val coverage = if (members.isEmpty()) 0.0 else evidence.size.toDouble() / members.size
        val confidence = when {
            evidence.isEmpty() -> CanonicalPreferenceConfidence.INSUFFICIENT
            evidence.size >= 3 && coverage >= 0.5 -> CanonicalPreferenceConfidence.HIGH
            evidence.size >= 2 && coverage >= 0.25 -> CanonicalPreferenceConfidence.MEDIUM
            else -> CanonicalPreferenceConfidence.LOW
        }
        return CanonicalInferredPreferenceSummary(
            targetType = targetType,
            targetId = targetId,
            inferredRating = rating,
            ratedRecordingCount = evidence.size,
            totalRecordingCount = members.size,
            coverage = coverage,
            confidence = confidence,
            evidenceRecordingIds = evidence,
        )
    }
}

internal interface CanonicalPreferenceMembershipReader {
    /** Null when canonical target is unknown or over the non-truncating safety bound. */
    suspend fun recordingIds(kind: CanonicalPreferenceTargetType, canonicalId: String): List<String>?
}

/**
 * Reads existing canonical relations; no Catalog DAO/schema mutation or credit promotion.
 * An unknown target or more than [maximumMembers] linked Recordings fails closed rather
 * than producing a misleading coverage fraction from a silently truncated population.
 */
internal class RoomCanonicalPreferenceMembershipReader(
    private val catalog: CanonicalCatalogDatabase,
    private val maximumMembers: Int = 1_000,
) : CanonicalPreferenceMembershipReader {
    init { require(maximumMembers in 1..10_000) }

    override suspend fun recordingIds(
        kind: CanonicalPreferenceTargetType,
        canonicalId: String,
    ): List<String>? = withContext(Dispatchers.IO) {
        if (canonicalId.isBlank()) return@withContext null
        val exists = when (kind) {
            CanonicalPreferenceTargetType.RELEASE -> catalog.catalogDao().releaseById(canonicalId) != null
            CanonicalPreferenceTargetType.ARTIST -> catalog.catalogDao().artistById(canonicalId) != null
        }
        if (!exists) return@withContext null
        val sql = when (kind) {
            CanonicalPreferenceTargetType.RELEASE ->
                "SELECT DISTINCT recordingId FROM catalog_release_track WHERE releaseId = ? ORDER BY recordingId LIMIT ?"
            CanonicalPreferenceTargetType.ARTIST ->
                "SELECT DISTINCT recordingId FROM catalog_recording_artist_credit WHERE artistId = ? ORDER BY recordingId LIMIT ?"
        }
        val ids = mutableListOf<String>()
        catalog.openHelper.readableDatabase.query(sql, arrayOf<Any>(canonicalId, maximumMembers + 1))
            .use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getString(0)
            }
        if (ids.size > maximumMembers) null else ids
    }
}

/** Existing explicit Recording store is read only. No write APIs reachable here. */
internal class CanonicalInferredPreferencePreview(
    private val membership: CanonicalPreferenceMembershipReader,
    private val ratings: ExplicitRecordingRatingReader,
    private val planner: CanonicalInferredPreferencePlanner = CanonicalInferredPreferencePlanner(),
) {
    suspend fun preview(
        targetType: CanonicalPreferenceTargetType,
        targetId: String,
    ): CanonicalInferredPreferenceSummary? {
        val memberIds = membership.recordingIds(targetType, targetId) ?: return null
        val rated = memberIds.mapNotNull { ratings.currentRating(it) }
        return planner.project(targetType, targetId, memberIds, rated)
    }
}
