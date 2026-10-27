package com.music.bitchord.acquisition.a0

import java.util.Collections
import java.util.Locale

/**
 * Immutable, read-only request snapshot for Android acquisition (#177).
 *
 * A manual title/artist/album are search constraints, never MusicBrainz IDs.
 * Only a separate, trusted catalogue integration may construct an intent with
 * canonical IDs. This snapshot does not rank, select or authorize a transfer.
 */
internal class A0CanonicalAcquisitionIntent private constructor(
    val origin: Origin,
    val title: String,
    artistCredits: List<String>,
    val requestedAlbum: String?,
    requestedVersion: Set<VersionTag>,
    val expectedDurationSeconds: Int?,
    val ids: CanonicalIds?,
    val strictExactRelease: Boolean,
    val quality: QualityProfileSnapshot,
) {
    enum class Origin { MANUAL_QUERY, CANONICAL_CATALOG }
    enum class VersionTag {
        ORIGINAL, REMASTER, LIVE, REMIX, EDIT, ACOUSTIC,
        INSTRUMENTAL, KARAOKE, SPED_UP, SLOWED_DOWN, COVER,
    }
    enum class QualityPreset {
        LEGACY_UNSPECIFIED, HI_RES, LOSSLESS, HIGH_QUALITY, HIGH_EFFICIENCY, CUSTOM,
    }
    /** Declared supported-audio families, not proof of actual audio or a transcoder. */
    enum class Codec { MP3, FLAC, PCM_WAV, AAC_ADTS }

    class QualityProfileSnapshot(
        val preset: QualityPreset = QualityPreset.LEGACY_UNSPECIFIED,
        allowedCodecs: Set<Codec> = emptySet(),
        val minBitrateKbps: Int? = null,
        val minSampleRateHz: Int? = null,
        val minBitDepth: Int? = null,
        val strict: Boolean = false,
    ) {
        val allowedCodecs: Set<Codec> =
            Collections.unmodifiableSet(allowedCodecs.toSet())

        init {
            require(minBitrateKbps == null || minBitrateKbps in 1..1536)
            require(minSampleRateHz == null || minSampleRateHz in 8000..384000)
            require(minBitDepth == null || minBitDepth in 8..32)
            require(preset == QualityPreset.CUSTOM || (
                allowedCodecs.isEmpty() && minBitrateKbps == null &&
                    minSampleRateHz == null && minBitDepth == null
            )) { "Only CUSTOM may specify explicit constraints" }
        }

        override fun toString(): String = "QualityProfileSnapshot(redacted)"
    }

    /**
     * MBIDs must come from the canonical provider, not parsing peer filenames.
     * Recording, Release Group and individual Release have distinct semantics.
     * IDs alone do not prove any bytes, master, or membership relationship.
     */
    class CanonicalIds(
        val recordingMbid: String? = null,
        val releaseMbid: String? = null,
        val releaseGroupMbid: String? = null,
    ) {
        init {
            require(listOf(recordingMbid, releaseMbid, releaseGroupMbid).any { it != null })
            for (id in listOfNotNull(recordingMbid, releaseMbid, releaseGroupMbid)) {
                require(MBID.matches(id.lowercase(Locale.ROOT))) { "Invalid canonical ID" }
            }
        }

        override fun toString(): String = "CanonicalIds(redacted)"
    }

    val artistCredits: List<String> =
        Collections.unmodifiableList(artistCredits.toList())
    val requestedVersion: Set<VersionTag> =
        Collections.unmodifiableSet(requestedVersion.toSet())

    init {
        require(title.isNotBlank())
        require(expectedDurationSeconds == null || expectedDurationSeconds in 15..7200)
        require(!strictExactRelease || (origin == Origin.CANONICAL_CATALOG &&
            ids?.releaseMbid != null)) {
            "Exact release must be anchored to a canonical Release MBID"
        }
        require(origin == Origin.CANONICAL_CATALOG || ids == null) {
            "Manual requests cannot assert canonical MBIDs"
        }
    }

    // A safe representation if an exception/diagnostic accidentally interpolates this object.
    override fun toString(): String = "A0CanonicalAcquisitionIntent(redacted)"

    companion object {
        private val MBID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        private fun label(value: String): String {
            require(value.none {
                it.isISOControl() || it in '\u202A'..'\u202E' ||
                    it in '\u2066'..'\u2069' || it in '\u200B'..'\u200F' ||
                    it == '\u2060' || it == '\uFEFF'
            }) { "Invalid display controls" }
            return value.trim().replace(Regex("\\s+"), " ")
        }

        private fun credits(values: List<String>): List<String> =
            values.map(::label).filter(String::isNotBlank).distinct().take(16)

        fun manual(
            title: String,
            artistCredits: List<String> = emptyList(),
            requestedAlbum: String? = null,
            requestedVersion: Set<VersionTag> = emptySet(),
            expectedDurationSeconds: Int? = null,
            quality: QualityProfileSnapshot = QualityProfileSnapshot(),
        ): A0CanonicalAcquisitionIntent = A0CanonicalAcquisitionIntent(
            origin = Origin.MANUAL_QUERY,
            title = label(title),
            artistCredits = credits(artistCredits),
            requestedAlbum = requestedAlbum?.let(::label)?.takeIf(String::isNotBlank),
            requestedVersion = requestedVersion,
            expectedDurationSeconds = expectedDurationSeconds,
            ids = null, strictExactRelease = false, quality = quality,
        )

        fun confirmedCatalog(
            title: String,
            ids: CanonicalIds,
            artistCredits: List<String> = emptyList(),
            requestedAlbum: String? = null,
            strictExactRelease: Boolean = false,
            requestedVersion: Set<VersionTag> = emptySet(),
            expectedDurationSeconds: Int? = null,
            quality: QualityProfileSnapshot = QualityProfileSnapshot(),
        ): A0CanonicalAcquisitionIntent = A0CanonicalAcquisitionIntent(
            origin = Origin.CANONICAL_CATALOG,
            title = label(title),
            artistCredits = credits(artistCredits),
            requestedAlbum = requestedAlbum?.let(::label)?.takeIf(String::isNotBlank),
            requestedVersion = requestedVersion,
            expectedDurationSeconds = expectedDurationSeconds,
            ids = ids, strictExactRelease = strictExactRelease, quality = quality,
        )
    }
}
