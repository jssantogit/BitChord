package com.music.bitchord.ui

import java.util.Locale

/** MusicBrainz editorial metadata, not the number of tracks or a title heuristic. */
internal enum class CanonicalEditorialShelf { ALBUMS, SINGLES_EPS, UNCLASSIFIED }

internal data class CanonicalEditorialDecision(
    val shelf: CanonicalEditorialShelf,
    val format: String?,
    val secondaryTypes: List<String>,
    val reason: String,
) {
    val isPublishable: Boolean get() = shelf != CanonicalEditorialShelf.UNCLASSIFIED
}

/**
 * ReleaseGroup's primary type decides the recording's published *format*.
 * Secondary types annotate it; a Compilation/Live/Remix/Soundtrack cannot
 * create an album from an unknown or Other primary type.
 * The pipe-delimited representation is a constrained Room-friendly evidence
 * value; it is not a user-facing description and is not an identity key.
 */
internal object CanonicalEditorialDiscography {
    private val secondaryLabels = mapOf(
        "compilation" to "Compilation", "live" to "Live",
        "soundtrack" to "Soundtrack", "remix" to "Remix",
        "demo" to "Demo", "dj-mix" to "DJ-mix", "mixtape/street" to "Mixtape/Street",
        "spokenword" to "Spokenword", "audiobook" to "Audiobook",
        "audio drama" to "Audio drama", "interview" to "Interview",
        "field recording" to "Field recording",
    )

    fun classify(primaryType: String?, secondaryTypes: String?): CanonicalEditorialDecision {
        val normalized = primaryType?.trim()?.lowercase(Locale.ROOT)
        val tags = secondaryTypes.orEmpty().split('|').mapNotNull {
            secondaryLabels[it.trim().lowercase(Locale.ROOT)]
        }.distinct()
        val shelf = when (normalized) {
            "album" -> CanonicalEditorialShelf.ALBUMS
            "ep", "single" -> CanonicalEditorialShelf.SINGLES_EPS
            else -> CanonicalEditorialShelf.UNCLASSIFIED
        }
        val format = when (normalized) {
            "album" -> "Album"
            "ep" -> "EP"
            "single" -> "Single"
            else -> null
        }
        return CanonicalEditorialDecision(
            shelf, format, tags,
            if (format != null) "musicbrainz-primary-type" else "unclassified-primary-type",
        )
    }
}
