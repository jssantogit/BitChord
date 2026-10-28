package com.music.bitchord.data.listening

import com.music.bitchord.data.catalog.ArtistEntity
import com.music.bitchord.data.catalog.CanonicalCatalogDao
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.data.catalog.CanonicalLibraryBrowseRepository
import com.music.bitchord.data.catalog.RoomCanonicalLibraryBrowseStore
import com.music.bitchord.data.catalog.ReleaseEntity
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Album/Artist context must never be represented by a synthetic RecordingId.
 * These predicates work on their own canonical entity IDs, and only on source-
 * scoped lists supplied by the caller. No remote provider query is made here.
 *
 * More metadata (original date, credited album artist, composer, favorite date)
 * needs authoritative per-entity evidence first; these fields fail eligibility
 * rather than guessing from a Recording title or display credit.
 */
internal enum class CanonicalSmartFilterContext { SONGS, ALBUMS, ARTISTS }

private fun SmartPlaylistRule.supportedForContext(
    fields: Set<SmartPlaylistRuleField>,
): Boolean = when (this) {
    is SmartPlaylistRule.Comparison -> field in fields
    is SmartPlaylistRule.Group -> children.isNotEmpty() &&
        children.all { it.supportedForContext(fields) }
    is SmartPlaylistRule.Not -> child.supportedForContext(fields)
    is SmartPlaylistRule.Reference -> false
}

internal fun SavedSmartPlaylistV2Filter.isSupportedForCanonicalAlbumBrowse(): Boolean =
    rules.supportedForContext(setOf(
        SmartPlaylistRuleField.RELEASE_TITLE,
        SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
        SmartPlaylistRuleField.SOURCE_KIND,
    ))

internal fun SavedSmartPlaylistV2Filter.isSupportedForCanonicalArtistBrowse(): Boolean =
    rules.supportedForContext(setOf(
        SmartPlaylistRuleField.ARTIST_DISPLAY,
        SmartPlaylistRuleField.SOURCE_KIND,
    ))

/** Null = UNKNOWN: NOT of an unverified cover must not include an album. */
private fun SmartPlaylistRule.textTruth(
    field: SmartPlaylistRuleField,
    text: String?,
    artworkReference: String? = null,
    sourceKind: SmartPlaylistSourceKind? = null,
): Boolean? = when (this) {
    is SmartPlaylistRule.Comparison -> {
        if (this.field == SmartPlaylistRuleField.SOURCE_KIND) {
            sourceKind?.let { actual ->
                val expected = textValue?.trim()?.uppercase(Locale.ROOT)
                val equal = actual.name == expected
                when (operator) {
                    SmartPlaylistRuleOperator.EQUAL -> equal
                    SmartPlaylistRuleOperator.NOT_EQUAL -> !equal
                    else -> null
                }
            }
        } else if (this.field != field &&
            this.field != SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE) null
        else if (this.field == SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE) {
            if (operator == SmartPlaylistRuleOperator.IS_PRESENT &&
                !artworkReference.isNullOrBlank()) true else null
        } else {
            val actual = text?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            val wanted = textValue?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            // A blank title/name in the canonical projection has no negative
            // provenance. Neither IS_MISSING nor NOT(IS_PRESENT) can promote it
            // to a known absence. This is separate from a verified missing field.
            if (actual == null) null else when (operator) {
                SmartPlaylistRuleOperator.IS_PRESENT -> true
                SmartPlaylistRuleOperator.IS_MISSING -> false
                else -> {
                    if (wanted == null) null else when (operator) {
                        SmartPlaylistRuleOperator.EQUAL -> actual == wanted
                        SmartPlaylistRuleOperator.NOT_EQUAL -> actual != wanted
                        SmartPlaylistRuleOperator.CONTAINS -> wanted in actual
                        SmartPlaylistRuleOperator.DOES_NOT_CONTAIN -> wanted !in actual
                        SmartPlaylistRuleOperator.STARTS_WITH -> actual.startsWith(wanted)
                        SmartPlaylistRuleOperator.ENDS_WITH -> actual.endsWith(wanted)
                        else -> null
                    }
                }
            }
        }
    }
    is SmartPlaylistRule.Not ->
        child.textTruth(field, text, artworkReference, sourceKind)?.not()
    is SmartPlaylistRule.Reference -> null
    is SmartPlaylistRule.Group -> {
        val values = children.map { it.textTruth(field, text, artworkReference, sourceKind) }
        when (join) {
            SmartPlaylistRuleJoin.ALL -> when {
                false in values -> false
                null in values -> null
                else -> true
            }
            SmartPlaylistRuleJoin.ANY -> when {
                true in values -> true
                null in values -> null
                else -> false
            }
        }
    }
}

/** Caller supplies only already-visible canonical releases; no source expansion. */
internal fun filterCanonicalBrowseAlbums(
    releases: List<ReleaseEntity>,
    filter: SavedSmartPlaylistV2Filter,
    providerKey: String? = null,
): List<ReleaseEntity> {
    require(filter.isSupportedForCanonicalAlbumBrowse()) {
        "This saved filter needs unsupported Album evidence"
    }
    SmartPlaylistV2Query(filter.rules)
    require(releases.all { it.id.isNotBlank() } &&
        releases.map { it.id }.distinct().size == releases.size) {
        "Album source must contain unique canonical release IDs"
    }
    return releases.filter { release ->
        filter.rules.textTruth(
            SmartPlaylistRuleField.RELEASE_TITLE,
            release.title,
            release.artworkUri,
            verifiedContextualSourceKind(providerKey),
        ) == true
    }
}

/** Caller supplies only already-visible canonical artists, not display-name groups. */
internal fun filterCanonicalBrowseArtists(
    artists: List<ArtistEntity>,
    filter: SavedSmartPlaylistV2Filter,
    providerKey: String? = null,
): List<ArtistEntity> {
    require(filter.isSupportedForCanonicalArtistBrowse()) {
        "This saved filter needs unsupported Artist evidence"
    }
    SmartPlaylistV2Query(filter.rules)
    require(artists.all { it.id.isNotBlank() } &&
        artists.map { it.id }.distinct().size == artists.size) {
        "Artist source must contain unique canonical artist IDs"
    }
    return artists.filter { artist ->
        filter.rules.textTruth(
            SmartPlaylistRuleField.ARTIST_DISPLAY,
            artist.name,
            sourceKind = verifiedContextualSourceKind(providerKey),
        ) == true
    }
}

/**
 * Provider-scoped read-only seam for future Albums/Artists library surfaces and
 * recommendation constraints. Exact account keys stay in the query parameters,
 * not in saved filter JSON or external metadata identities.
 */
internal class CanonicalContextualFilterLibrary(
    private val catalog: CanonicalCatalogDao,
) {
    /**
     * On-demand account-scoped Songs read for non-Compose consumers such as a
     * future recommendation planner. No hidden global snapshot, MediaStore
     * scan, native provider IDs or fabricated Playback evidence.
     */
    suspend fun songs(
        providerKey: String,
        filter: SavedSmartPlaylistV2Filter,
    ): List<CanonicalBrowseTrack> {
        require(providerKey.isNotBlank() && providerKey == providerKey.trim()) {
            "A configured account/provider is required"
        }
        require(filter.isSupportedForCanonicalSongBrowse()) {
            "Saved Song filter requires unavailable evidence"
        }
        val snapshot = CanonicalLibraryBrowseRepository(
            RoomCanonicalLibraryBrowseStore(catalog),
        ).observe(providerKey).first()
        return filterCanonicalBrowseSongs(snapshot.tracks, filter, providerKey)
    }

    suspend fun albums(
        providerKey: String,
        filter: SavedSmartPlaylistV2Filter,
    ): List<ReleaseEntity> {
        require(providerKey.isNotBlank()) { "A configured account/provider is required" }
        require(filter.isSupportedForCanonicalAlbumBrowse())
        return filterCanonicalBrowseAlbums(
            catalog.providerScopedReleases(providerKey), filter, providerKey,
        )
    }

    suspend fun artists(
        providerKey: String,
        filter: SavedSmartPlaylistV2Filter,
    ): List<ArtistEntity> {
        require(providerKey.isNotBlank()) { "A configured account/provider is required" }
        require(filter.isSupportedForCanonicalArtistBrowse())
        return filterCanonicalBrowseArtists(
            catalog.providerScopedArtists(providerKey), filter, providerKey,
        )
    }
}
