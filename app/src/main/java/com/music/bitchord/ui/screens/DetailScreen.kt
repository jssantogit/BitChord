package com.music.bitchord.ui.screens

import android.os.Build
import com.music.bitchord.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import com.music.bitchord.ui.components.liquidGlassRim
import com.music.bitchord.ui.components.longPressMenuClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import com.music.bitchord.data.model.LibraryState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.transformations
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.matchesSearch
import com.music.bitchord.data.sortedForLibrary
import com.music.bitchord.data.useTrackTags
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.SourceQuality
import com.music.bitchord.ui.components.SourceQualityBadge
import com.music.bitchord.ui.components.RatingDisplay
import com.music.bitchord.data.catalog.metadata.isCoverArchiveImageHost
import com.music.bitchord.data.catalog.metadata.appleApprovedBadgeForStoreUrl
import com.music.bitchord.data.canvas.AppleArtistArt
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.ArtistSignature
import com.music.bitchord.data.canvas.ArtistSignatureRepository
import com.music.bitchord.data.canvas.SignatureInkTransformation
import com.music.bitchord.data.canvas.keyColors
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.canvas.VisualStillMedia
import com.music.bitchord.data.canvas.VisualSurface
import com.music.bitchord.data.canvas.isSafeVisualMediaUrl
import com.music.bitchord.data.canvas.VisualArtworkCandidate
import com.music.bitchord.data.canvas.VisualArtworkPriority
import com.music.bitchord.data.canvas.VisualArtworkPrefetch
import com.music.bitchord.data.canvas.VisualRetentionMode
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.DownloadedBadge
import com.music.bitchord.ui.components.ExplicitSongTitle
import com.music.bitchord.ui.components.LIBRARY_GRID_SPACING
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.NUMBERED_ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.NativeDetailTrackNumber
import com.music.bitchord.ui.components.CanonicalSongRatingStars
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.libraryGrid
import com.music.bitchord.ui.components.lightweightLiquidGlass
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.detailSkeleton
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlin.math.roundToInt
import java.util.Locale

private const val MAX_ARTIST_SONGS = 20
private const val SONGS_PER_COLUMN = 4
private const val ARTIST_ROW_MAX_ITEMS = 5

/** The artist photo, very slightly taller than it is wide. */
private const val ARTIST_PHOTO_RATIO = 0.95f

/** The framed sleeve on a release page, as a fraction of the page width. */
private const val SLEEVE_FRACTION = 0.76f

/** ...and of its height, so a landscape window still shows the title under it. */
private const val SLEEVE_MAX_HEIGHT = 0.5f


/** Shuffle and Add to Library either side of the release page's Play pill. */

/** Past this the Play pill stops growing, so a tablet doesn't get a bar. */

/**
 * Where the search field sits once it is open — directly under the header,
 * which is item zero. The one place that has to know, so that opening the
 * search can carry the page up to it.
 */
private const val SEARCH_ITEM_INDEX = 1

/** The inset the header text and the action pills share. */

/** Extra breathing room for the editorial copy on album and artist pages. */

/** The artist bio and everything below it: the page edge every page shares. */
private val ARTIST_CONTENT_GUTTER = PAGE_GUTTER

/**
 * How far past the foot of the artist photo the name is allowed to hang.
 *
 * Sat flush to the bottom of the picture it lands wherever the picture happens
 * to be busy, and on a photo with anything going on down there the name reads
 * as part of it rather than as a caption to it. Dropped clear, it sits on the
 * blurred colour instead, which has nothing in it to compete.
 */

/** How far the artist name/logo and everything under it sit above the usual [HEADER_DROP]. */

/** The artist action row: a large Play flanked by two smaller glass circles. */

/** A larger share of a smaller circle than the release headers' 0.44. */

/** The title logo's widest share of the page, and tallest share of the photo. */

/** The signature closing the About section. */

/** A signature is drawn at this share of a title logo's size. */


/** Vector signatures decode at this width so small files stay crisp laid out large. */

private val CREDIT_SEPARATOR = Regex(""",\s|\s&\s|\s(?:x|and|feat\.?|ft\.?|with)\s""", RegexOption.IGNORE_CASE)

/** Whether this reads as several artists credited together rather than one name. */
private fun String.looksLikeCredit() = CREDIT_SEPARATOR.containsMatchIn(this)

/**
 * Album / artist / playlist page. Rendered inside the main content area
 * rather than as a sheet, so the tab bar and mini player stay visible.
 *
 * The page paints itself in the artwork's own colours — one solid tint behind
 * everything, secondary text in a lighter shade of the same hue, and an accent
 * taken off the sleeve for whatever is playing. See [rememberArtworkPalette]
 * for how those are derived and kept legible.
 *
 * A release is one layer: the list, sleeve and all, on a flat colour. An
 * artist page adds [PageBackground] under it — the photograph, dissolving into
 * the tint — since that picture runs edge to edge up under the bar. Nothing in
 * either is re-blurred while the page scrolls.
 */
@Composable
fun DetailScreen(
    page: DetailPage,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    activeShelf: HomeShelf? = null,
    onActiveShelfChange: (HomeShelf?) -> Unit = {},
    /**
     * Holding one of the album cards on an artist page — the same menu the
     * shelves on every other tab open, so a release can be queued from
     * wherever it is seen rather than only from its own page.
     */
    onSectionItemLongPress: ((ShelfItem) -> Unit)? = null,
    /**
     * Whether the filter box is open under the header. Owned by the caller
     * because the control that opens it sits in the top bar, beside the sort
     * and overflow buttons, not on this page.
     */
    searching: Boolean = false,
    onSearchingChange: (Boolean) -> Unit = {},
    /**
     * Saves this release to the account's library, or takes it out —
     * [DetailPage.library] says which way round. Null hides the control
     * entirely, which is the answer for a guest and for the pages YouTube never
     * offers to save; there is nothing to show a signed-out user here that
     * wouldn't just be refused.
     */
    onToggleLibrary: (() -> Unit)? = null,
    /**
     * Subscribes to this artist's channel, or unsubscribes —
     * [DetailPage.subscription] says which way round. The artist page's answer
     * to [onToggleLibrary], and null in the same circumstances: a guest, or a
     * page whose header never offered the button.
     */
    onToggleSubscription: (() -> Unit)? = null,
    /** Library state of releases shown as cards on this page, by browse id. */
    releaseLibrary: Map<String, LibraryState> = emptyMap(),
    onLoadReleaseLibrary: ((String) -> Unit)? = null,
    /** Null where saving isn't offered — a guest. */
    onToggleReleaseLibrary: ((String) -> Unit)? = null,
    /**
     * How the track list is ordered — the release's own running order by
     * default, or alphabetical. Owned by the caller rather than this page
     * because the control for it lives in the top bar, alongside the account
     * photo, not in this header.
     */
    songSort: SongSort = SongSort.DEFAULT,
    /**
     * Set when this page is a device folder — Downloads, Local Music and the
     * like. The header is the same as any playlist's; under it, instead of one
     * running order, come the folder's Songs / Artists / Albums tabs. See
     * [LocalFolder].
     */
    folder: LocalFolder? = null,
    /** Public tracklist metadata; never manufactured as a playable Song. */
    canonicalTracks: List<CanonicalCatalogSearchRow> = emptyList(),
    onCanonicalTrackPlay: ((String) -> Unit)? = null,
    /** Verified ListenBrainz order plus independent MusicBrainz recording credits. */
    canonicalPublicTopTracks: List<CanonicalCatalogSearchRow> = emptyList(),
    canonicalPublicTopSampled: Boolean = false,
    onCanonicalPublicTopOpen: ((CanonicalCatalogSearchRow) -> Unit)? = null,
    /** Verified current MusicBrainz ReleaseGroup, never an inferred local Song. */
    canonicalFeaturedRelease: CanonicalCatalogSearchRow? = null,
    onCanonicalFeaturedReleaseOpen: ((CanonicalCatalogSearchRow) -> Unit)? = null,
    /** MBID, title, creator and description only: no internal DTO in public API. */
    onCanonicalArtistPlaylistOpen: ((String, String, String, String?) -> Unit)? = null,
    /**
     * Only set for an independently certified MB Release→Apple product.
     * ReleaseGroups require the same exact edition as the visible tracklist.
     * Apple-provided artwork is promotional, so its official store badge
     * must be placed adjacent to the certified cover.
     */
    appleStorePromotionUrl: String? = null,
    /** Artist cards only: Group MBID -> exact promotional artwork and store URL. */
    appleGroupCardPromotions: Map<String, Pair<String, String>> = emptyMap(),
) {
    val applePromotionBadge = remember(page.browseId, appleStorePromotionUrl) {
        if (page.browseId.startsWith("catalog:RELEASE:") ||
            page.browseId.startsWith("catalog:RELEASE_GROUP:")
        ) appleApprovedBadgeForStoreUrl(appleStorePromotionUrl)
        else null
    }
    val externalStoreUriHandler = LocalUriHandler.current
    val rawSongs = (page.songs as? UiState.Success)?.data.orEmpty()
    // A folder keeps its own sort — the one its top bar menu sets — rather
    // than the per-page one a release takes.
    val folderSort = folder?.sort
    val songs = remember(rawSongs, songSort, folderSort) {
        if (folderSort != null) rawSongs.sortedForLibrary(folderSort) else rawSongs.sortedForDetail(songSort)
    }
    // What a numbered row shows regardless of [songSort] — an album's track
    // numbers are the sleeve's own and must not relabel themselves to match
    // wherever a sort put the row. An album grouped off a device's files
    // numbers from its tags where they say, since a release with a track
    // missing must not renumber the rest.
    val originalTrackNumbers = remember(rawSongs) {
        val byTag = rawSongs.useTrackTags()
        rawSongs.withIndex().associate { (i, s) -> s.videoId to ((if (byTag) s.trackNumber else null) ?: (i + 1)) }
    }
    val isArtist = page.type == BrowseType.ARTIST
    val isLocalArtist = isArtist && LocalGroups.isArtist(page.browseId)
    // Only the explicitly legacy artist browse feed carries provider-ranked
    // tracks today. MusicBrainz catalog results do not provide popularity;
    // locally available recordings never constitute ranking evidence.
    val legacyRankedArtistFeed = isArtist &&
        nativeArtistLegacyRankedFeed(page.browseId)
    val publicArtistMbid = page.browseId.takeIf {
        isArtist && it.startsWith("catalog:ARTIST:")
    }?.removePrefix("catalog:ARTIST:")
    val publicTopRows = if (publicArtistMbid != null &&
        onCanonicalPublicTopOpen != null
    ) canonicalPublicTopTracks.filter {
        it.kind == CanonicalSearchKind.RECORDING &&
            it.artistScopeMbid?.equals(publicArtistMbid, ignoreCase = true) == true
    } else emptyList()
    val artistVisibleSections = nativeArtistVisibleSections(
        NativeArtistSectionEvidence(
            context = if (isLocalArtist) NativeArtistContext.LIBRARY
                else NativeArtistContext.ONLINE_CATALOG,
            hasTracks = isArtist && (songs.isNotEmpty() || publicTopRows.isNotEmpty()),
            hasVerifiedPopularity = legacyRankedArtistFeed || publicTopRows.isNotEmpty(),
        ),
    )
    val catalogRatingTarget = remember(page.browseId) {
        canonicalCatalogEntityRatingTarget(page.browseId)
    }
    val catalogRating by rememberCanonicalCatalogEntityRating(catalogRatingTarget)
    var editingCatalogRating by remember(page.browseId) { mutableStateOf(false) }
    if (editingCatalogRating && catalogRatingTarget != null) {
        CanonicalCatalogEntityRatingDialog(
            target = catalogRatingTarget,
            title = if (isArtist) "Avaliar artista" else "Avaliar álbum",
            onDismiss = { editingCatalogRating = false },
        )
    }
    val localArtistContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val catalogPinRepo = remember(localArtistContext) {
        CatalogMetadataPinRepository.get(localArtistContext)
    }
    val catalogPins by catalogPinRepo.pins.collectAsStateWithLifecycle()
    val catalogPin = remember(page.browseId, page.title, page.subtitle, page.thumbnailUrl) {
        CatalogMetadataPin(
            browseId = page.browseId,
            title = page.title.trim().take(240),
            subtitle = page.subtitle.orEmpty().take(240),
            artworkUrl = trustedCatalogMetadataPinArtwork(page.thumbnailUrl),
        )
    }
    val canPinCatalogRelease = catalogRatingTarget?.entityType in setOf("ALBUM", "RELEASE_GROUP") &&
        validCatalogMetadataPin(catalogPin)
    // Prefetch one hero and the first visible cards from any metadata
    // provider. The same Coil memory/disk cache serves their eventual native
    // AsyncImage nodes, so this never creates another image pipeline.
    val warmupTargets = remember(page.browseId, page.thumbnailUrl,
        publicTopRows, canonicalTracks, page.sections,
    ) {
        buildList {
            add(VisualArtworkCandidate(page.thumbnailUrl,
                VisualArtworkPriority.HERO, HEADER_ART_PX))
            publicTopRows.take(4).forEach { row ->
                add(VisualArtworkCandidate(row.artworkUri,
                    VisualArtworkPriority.VISIBLE, ROW_ART_PX))
            }
            canonicalTracks.take(5).forEach { row ->
                add(VisualArtworkCandidate(row.artworkUri,
                    VisualArtworkPriority.VISIBLE, ROW_ART_PX))
            }
            page.sections.take(3).forEach { section ->
                section.items.take(3).forEach { item ->
                    add(VisualArtworkCandidate(item.thumbnailUrl,
                        VisualArtworkPriority.NEARBY, CARD_ART_PX))
                }
            }
        }
    }
    LaunchedEffect(warmupTargets) {
        VisualArtworkPrefetch.warm(localArtistContext, warmupTargets)
    }

    var localArtistRecent by remember(page.browseId) { mutableStateOf<ShelfItem?>(null) }
    LaunchedEffect(page.browseId, rawSongs, page.sections) {
        localArtistRecent = if (isLocalArtist && page.sections.isNotEmpty()) {
            try {
                mostRecentVerifiedLocalArtistAlbum(
                    localArtistContext, rawSongs, page.sections,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else null
    }
    // Whether the top release card's album is already saved isn't on the shelf
    // item; it is read off the album once, as soon as the card is known.
    val topReleaseId = if (isArtist && !isLocalArtist)
        page.sections.topRelease()?.browseId else null
    LaunchedEffect(topReleaseId) { topReleaseId?.let { onLoadReleaseLibrary?.invoke(it) } }
    // Apple Music's hero photo and title logo for this artist, found by name.
    // Null until (and unless) it arrives; the YouTube header stands in meanwhile.
    var appleArt by remember(page.browseId) {
        mutableStateOf(if (isArtist) AppleArtistArtRepository.cached(page.title) else null)
    }
    // Secondary provider: handwritten signature from the user's own
    // artist-signature API. Drawn only when Apple has no title logo.
    val signatureEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureBaseUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val signatureUpdates by ArtistSignatureRepository.updates.collectAsStateWithLifecycle()
    var signature by remember(page.browseId) { mutableStateOf<ArtistSignature?>(null) }
    // Opened from a track, the title is the whole credit ("A, B & C") until the
    // page loads and swaps in the one artist's name. Searching a credit finds
    // nobody, so a title that reads as several artists waits for that swap; any
    // other name starts the moment the page opens.
    val nameSettled = !page.title.looksLikeCredit() || page.songs !is UiState.Loading
    LaunchedEffect(page.browseId, page.title, nameSettled) {
        if (isArtist && nameSettled) appleArt = AppleArtistArtRepository.artFor(page.title) ?: appleArt
    }
    LaunchedEffect(page.browseId, page.title, nameSettled, signatureEnabled, signatureBaseUrl, signatureUpdates) {
        if (!isArtist || !nameSettled) return@LaunchedEffect
        val base = ArtistSignatureRepository.effectiveBaseUrl(signatureEnabled, signatureBaseUrl)
        if (base == null) {
            signature = null
            return@LaunchedEffect
        }
        // Bounded retries with backoff: a sleeping self-hosted API needs
        // ~30-60s to wake (Render free tier), and a single miss here would
        // otherwise stand until the page is reopened. Genuine misses aren't
        // cached, so retrying is cheap. Covers roughly a full wake cycle.
        val backoff = listOf(0L, 5_000L, 15_000L, 30_000L)
        for (wait in backoff) {
            if (wait > 0) delay(wait)
            signature = ArtistSignatureRepository.cached(page.title, base)
                ?: ArtistSignatureRepository.signatureFor(page.title, base)
                ?: signature
            if (signature != null) return@LaunchedEffect
        }
    }
    val palette = rememberArtworkPalette(
        imageUrl = appleArt?.heroUrl ?: page.thumbnailUrl,
        keyColors = appleArt?.keyColors(),
    )

    // Narrowing the running order in place — the release equivalent of the
    // filter box on the Local Music tab, and the one thing a long track list
    // needs that scrolling can't give it. [searching] says whether it is open;
    // the text itself is the page's, and goes when the box does: a filter left
    // on an album that was closed and reopened would be a page that appears to
    // have lost most of its tracks.
    var query by rememberSaveable(page.browseId) { mutableStateOf("") }
    var editingMetadataRecording by remember(page.browseId) { mutableStateOf<String?>(null) }
    var addingMetadataRecording by remember(page.browseId) {
        mutableStateOf<Triple<String, String, String>?>(null)
    }
    addingMetadataRecording?.let { (recording, title, artist) ->
        CanonicalMetadataRecordingPlaylistPicker(
            recordingId = recording, title = title, artist = artist,
            onDismiss = { addingMetadataRecording = null },
        )
    }
    editingMetadataRecording?.let { recording ->
        CanonicalMetadataRecordingRatingDialog(
            recordingId = recording, onDismiss = { editingMetadataRecording = null },
        )
    }
    val canonicalVisibleTrackIndices = remember(canonicalTracks, query, songSort) {
        canonicalMetadataAlbumVisibleIndices(
            canonicalTracks.map { it.title to it.subtitle }, query, songSort,
        )
    }
    // Whether the field still owes the keyboard an appearance. Held here rather
    // than in the field, which is a row in a lazy list: scrolled out of sight it
    // is disposed, and a field that asks for focus every time it is composed
    // would throw the keyboard back up each time it scrolled into view.
    var focusSearch by remember(page.browseId) { mutableStateOf(false) }
    val closeSearch = { onSearchingChange(false) }
    // Back closes the search first — this handler is registered after the one
    // that pops the page, so it is the one that answers while it's enabled.
    BackHandler(enabled = searching) { closeSearch() }

    // Each surviving row still knows where it sat in the full running order, so
    // an album's track numbers stay the album's rather than becoming positions
    // in the filtered list.
    val matches = remember(songs, query) { songs.matching(query) }
    // What a tap plays: the filtered set of tracks currently standing in the
    // list. When no filter is active, matches contains the full running order
    // and plays the complete release/playlist from the tapped position.
    val queue = remember(matches) { matches.map { it.value } }
    val suggested = remember(page.suggestedSongs, query) {
        page.suggestedSongs.matching(query).map { it.value }
    }

    // A device folder's tab and Downloads selection. The selection answers
    // Back before the search does: it is registered after it, so it is asked
    // first while it is on.
    val folderState = folder?.state
    val folderGroups = if (folder != null && folderState != null) {
        rememberLocalFolderGroups(page.browseId, folder, folderState.tab, rawSongs, query)
    } else {
        emptyList()
    }
    // Folder category pages describe the category, not the 570 underlying tracks.
    val folderCategoryMeta = if (folder != null && page.browseId == "local:all") {
        when (folderState?.tab) {
            LocalFolderTab.ARTISTS -> "${folderGroups.size} artistas"
            LocalFolderTab.ALBUMS -> "${folderGroups.size} álbuns"
            else -> null
        }
    } else null
    BackHandler(enabled = folderState?.selecting == true) { folderState?.clear() }

    // What marks a row as already downloaded, tinted from the sleeve like the
    // rest of the page. Null on any page that is itself a reading of this
    // device — the Downloads folder, one downloaded playlist — where every row
    // qualifies and the badge would be decoration rather than information.
    val downloadedTint = palette.accent.takeUnless { page.browseId.startsWith("local:") }

    // Animated cover art on the header, the same feature the player has.
    // Albums only: a playlist's artwork is a collage and an artist page's is a
    // photograph, and neither is something a label publishes a canvas for.
    val saveVisualStills by AppSettings.saveVisualStillImages.collectAsStateWithLifecycle()
    var offlineAlbumPoster by remember(page.browseId) {
        mutableStateOf(VisualStillMedia.cached(
            VisualSurface.ALBUM, page.browseId,
        )?.toURI()?.toString())
    }
    LaunchedEffect(page.browseId, page.thumbnailUrl, saveVisualStills) {
        if (isArtist || page.type != BrowseType.ALBUM || !saveVisualStills) {
            return@LaunchedEffect
        }
        val url = page.thumbnailUrl?.takeIf(::isSafeVisualMediaUrl) ?: return@LaunchedEffect
        VisualStillMedia.save(VisualSurface.ALBUM, page.browseId,
            url.artworkAt(900) ?: url,
        )?.let { offlineAlbumPoster = it.toURI().toString() }
    }
    val animatedCanvasPreference by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val visualMode by AppSettings.visualRetentionMode.collectAsStateWithLifecycle()
    val canvasEnabled = animatedCanvasPreference &&
        visualMode != VisualRetentionMode.STILL_ONLY
    val prioritizeSpotifyCanvas by AppSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    // The credit line the header shows is the artist as far as the catalogue
    // services are concerned. A browse card's subtitle sometimes omits it, in
    // which case the tracks themselves know who it is.
    val credit = page.headerLines(songs.size).first.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, page.title, credit, canvasEnabled, prioritizeSpotifyCanvas) {
        // An artist's clip is set below, by the lookup that finds it.
        if (isArtist) return@LaunchedEffect
        if (!canvasEnabled || page.type != BrowseType.ALBUM) {
            canvas = null
            return@LaunchedEffect
        }
        // As on the player: the credit fills in once the tracks load, so this
        // can run twice. Keep a clip that is already playing if the second
        // pass comes back empty.
        canvas = CanvasRepository.canvasForAlbum(page.title, credit) ?: canvas
    }
    // An artist's clip comes with the same Apple lookup as its photograph.
    val artistVideo = appleArt?.videoUrl
    LaunchedEffect(artistVideo, canvasEnabled) {
        if (isArtist) canvas = artistVideo?.takeIf { canvasEnabled }?.let { CanvasArtwork(url = it, surface = com.music.bitchord.data.canvas.CanvasSurface.ARTIST) }
    }

    // Opening the search carries the page up to it, so the field lands just
    // clear of the frosted bar with the tracks under it rather than at the foot
    // of a screen still filled with artwork. Done as an effect rather than in
    // the tap, so the row it scrolls to is already in the list by the time it
    // runs.
    val searchStop = with(LocalDensity.current) { topBarContentPadding().roundToPx() }
    LaunchedEffect(searching) {
        if (searching) {
            focusSearch = true
            listState.animateScrollToItem(SEARCH_ITEM_INDEX, -searchStop)
        } else {
            query = ""
        }
    }

    AnimatedContent(
        targetState = activeShelf,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
        },
        label = "artist_shelf_transition",
        modifier = modifier.fillMaxSize(),
    ) { targetShelf ->
        if (targetShelf == null) {
            // An artist's photograph dissolves into [ArtworkPalette.wash]; a
            // release's sleeve sits framed on one flat colour with nothing to
            // dissolve, so it takes the same [ArtworkPalette.background] the
            // bars at either end of the screen are already fading into.
            val pageColor = if (isArtist) palette.wash else palette.background
            BoxWithConstraints(Modifier.fillMaxSize().background(pageColor)) {
                // Measured rather than read off the window. The player runs
                // full-screen at every window size, so this page's own width in
                // landscape is the *whole* window. Straight off that width, the ratio hands back
                // a picture taller than the window itself — the artwork and track list
                // end up scrolled out of sight beneath what reads as a blank page.
                // Capping against the window's own height is what keeps the ratio's
                // math honest once the width it's fed is no longer guaranteed to be
                // the narrow one it was written for.
                val artHeight = (maxWidth / ARTIST_PHOTO_RATIO).coerceAtMost(maxHeight * 0.6f)
                val sleeveSize = (maxWidth * SLEEVE_FRACTION).coerceAtMost(maxHeight * SLEEVE_MAX_HEIGHT)
                // A folder's grid view: the same column count the Library's
                // "Show all" grids reach at this width.
                val gridColumns = libraryGrid(maxWidth - PAGE_GUTTER * 2).columns

                // The artist photo is drawn behind the list rather than in it, so
                // both need to agree on its height without being able to ask each
                // other — see [artHeight]. A release has no such layer: its sleeve
                // is an ordinary item at the top of the list.
                // One native list of detail sections, independent from where
                // the artist or release metadata came from.
                val detailListItems: LazyListScope.() -> Unit = {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(artistName = page.title, palette = palette, artHeight = artHeight, appleArt = appleArt, signature = signature)
                } else {
                    ReleaseHeader(
                        page = page,
                        palette = palette,
                        sleeveSize = sleeveSize,
                        canvas = canvas,
                        offlinePoster = offlineAlbumPoster,
                        songs = songs,
                        canonicalTracks = canonicalTracks,
                        onPlay = {
                            if (songs.isNotEmpty()) onSongClick(songs, 0)
                            else canonicalTracks.firstOrNull { it.hasCatalogedAsset }
                                ?.musicBrainzId?.let { onCanonicalTrackPlay?.invoke(it) }
                        },
                        onShuffle = { if (songs.isNotEmpty()) onShuffle(songs) },
                        // A folder's credit says where its tracks live, not who made them.
                        onArtistClick = onArtistClick.takeIf { folder == null },
                        onToggleLibrary = onToggleLibrary,
                        rating = catalogRating,
                        onRate = if (catalogRatingTarget != null && !isArtist)
                            ({ editingCatalogRating = true }) else null,
                        isCatalogPinned = catalogPins.any { it.browseId == page.browseId },
                        onToggleCatalogPin = if (canPinCatalogRelease)
                            ({ catalogPinRepo.toggle(catalogPin); Unit }) else null,
                        metaOverride = folderCategoryMeta,
                    )
                    // Apple itself supplies these unmodified official badges.
                    // Keep its exact product link alongside the promo cover:
                    // Release or certified Group detail only, never Recording,
                    // unrelated Group editions or local albums.
                    if (applePromotionBadge != null && appleStorePromotionUrl != null) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = applePromotionBadge,
                                contentDescription = "Abrir este álbum na loja Apple",
                                modifier = Modifier.width(154.dp).height(44.dp)
                                    .clickable {
                                        runCatching {
                                            externalStoreUriHandler.openUri(appleStorePromotionUrl)
                                        }
                                    },
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }

            if (searching || (folder != null && page.browseId == "local:all")) {
                item(key = "search") {
                    DetailSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = if (folder != null && page.browseId == "local:all") {
                            { query = "" }
                        } else closeSearch,
                        autoFocus = focusSearch,
                        onFocused = { focusSearch = false },
                        palette = palette,
                    )
                }
            }

            val description = page.description

            if (isArtist && (songs.isNotEmpty() || publicTopRows.isNotEmpty() ||
                catalogRatingTarget?.entityType == "ARTIST")) {
                item(key = "actions") {
                    // The native artist action row does not disappear merely
                    // because MusicBrainz has rankings but no MediaAsset.
                    ActionRow(
                        onPlay = { if (songs.isNotEmpty()) onSongClick(songs, 0) },
                        onShuffle = { if (songs.isNotEmpty()) onShuffle(songs) },
                        canPlay = songs.isNotEmpty(),
                        canShuffle = songs.isNotEmpty(),
                        isSubscribed = if (catalogRatingTarget?.entityType == "ARTIST") null else
                            page.subscription?.takeIf { onToggleSubscription != null }?.subscribed,
                        onToggleSubscription = onToggleSubscription,
                        onRateArtist = if (catalogRatingTarget?.entityType == "ARTIST")
                            ({ editingCatalogRating = true }) else null,
                        artistRated = catalogRating != null,
                        bottomSpace = 22.dp,
                        playColor = appleArt?.keyColor?.let { Color(it) },
                    )
                }
            }

            // The first release on the shelves below, pulled out as a card of its own.
            val topRelease = if (isLocalArtist) localArtistRecent
                else if (isArtist && publicArtistMbid == null) page.sections.topRelease()
                else null
            if (isArtist && publicArtistMbid != null && canonicalFeaturedRelease != null &&
                onCanonicalFeaturedReleaseOpen != null
            ) {
                item(key = "canonical-public-artist-recent-release") {
                    NativeArtistPublicFeaturedRelease(
                        release = canonicalFeaturedRelease,
                        palette = palette,
                        onOpen = onCanonicalFeaturedReleaseOpen,
                    )
                }
            }
            if (topRelease != null) {
                item(key = "top-release") {
                    TopReleaseCard(
                        item = topRelease,
                        palette = palette,
                        onClick = { onSectionItemClick(topRelease) },
                        onLongPress = onSectionItemLongPress?.let { { it(topRelease) } },
                        saved = topRelease.browseId?.let { releaseLibrary[it]?.saved },
                        applePromotion = appleGroupCardBadgeFor(
                            topRelease.browseId, topRelease.thumbnailUrl,
                            appleGroupCardPromotions,
                        ),
                        onToggleSaved = onToggleReleaseLibrary?.let { toggle ->
                            topRelease.browseId?.let { id -> { toggle(id) } }
                        },
                    )
                }
            }

            // YouTube's own editorial blurb — an album or an artist only, per
            // [DetailPage.description]. A playlist never carries one, and the
            // section is skipped for it even on the rare response that does.
            //
            // On an artist page it closes the page, after the shelves, rather
            // than sitting between the action row and the songs.
            // The artist's counts close it, under the text, so a page whose blurb is
            // empty still gets the section for them.
            val hasStats = isArtist &&
                (page.subscriberCountText != null || page.monthlyListenerCount != null)
            // The header holds Apple's logo here, so the ink goes at the foot of the page instead.
            val aboutSignature = if (isArtist && appleArt?.logoUrl != null) signature else null
            val showAbout = (!description.isNullOrBlank() &&
                (page.type == BrowseType.ALBUM || isArtist)) || hasStats || aboutSignature != null
            fun LazyListScope.aboutItem() {
                if (!showAbout) return
                item(key = "about") {
                    // Clear of the last shelf above it.
                    Box(Modifier.padding(top = if (isArtist) 28.dp else 0.dp)) {
                    AboutSection(
                        title = stringResource(
                            if (isArtist) R.string.about_artist else R.string.about_album,
                        ),
                        text = description?.takeIf { it.isNotBlank() },
                        palette = palette,
                        horizontalPadding = if (isArtist) ARTIST_CONTENT_GUTTER else ABOUT_GUTTER,
                        stats = if (hasStats) {
                            {
                                ArtistStatsRow(
                                    subscriberCountText = page.subscriberCountText,
                                    monthlyListenerCount = page.monthlyListenerCount,
                                    palette = palette,
                                )
                            }
                        } else {
                            null
                        },
                        footer = aboutSignature?.let { sig ->
                            {
                                AboutSignature(
                                    signature = sig,
                                    name = page.title,
                                    palette = palette,
                                    horizontalPadding = ARTIST_CONTENT_GUTTER,
                                )
                            }
                        },
                    )
                    }
                }
            }
            if (!isArtist) aboutItem()

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (folder != null && folderState != null) {
                    localFolderItems(
                        folder = folder,
                        state = folderState,
                        songs = queue,
                        groups = folderGroups,
                        query = query,
                        columns = gridColumns,
                        hideTabPicker = page.browseId == "local:all",
                        palette = palette,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onSongClick = onSongClick,
                        onSongLongPress = onSongLongPress,
                        onSongSwipe = onSongSwipe,
                    )
                } else if (canonicalTracks.isNotEmpty()) {
                    // Keep the original release-page typography and numbered
                    // tracklist. A metadata-only row is simply non-playable;
                    // no synthetic video IDs or technical warning labels.
                    itemsIndexed(
                        items = canonicalVisibleTrackIndices,
                        key = { _, original -> "canonical:track:$original" },
                    ) { index, originalIndex ->
                        val row = canonicalTracks[originalIndex]
                        val canPlay = row.hasCatalogedAsset && onCanonicalTrackPlay != null
                        val hasMetadataActions = isVerifiedCatalogRecordingId(row.musicBrainzId)
                        var menuExpanded by remember(page.browseId, originalIndex) {
                            mutableStateOf(false)
                        }
                        NativeDetailTrackRowLayout(
                            onClick = if (canPlay) ({
                                onCanonicalTrackPlay?.invoke(row.musicBrainzId)
                            }) else null,
                            onLongPress = if (hasMetadataActions) ({ menuExpanded = true }) else null,
                            onMore = if (hasMetadataActions) ({ menuExpanded = true }) else null,
                            textGap = PAGE_GUTTER,
                            leading = {
                                NativeDetailTrackNumber(
                                    number = originalIndex + 1,
                                    color = palette.onBackgroundVariant,
                                )
                            },
                            title = {
                                Text(
                                    text = row.title.replace(Regex("^\\d+\\.\\d+\\s+"), ""),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            subtitle = {
                                row.subtitle?.takeIf { it.isNotBlank() }?.let { artist ->
                                    Text(
                                        text = artist,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.onBackgroundVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            rating = {},
                            showRatingSlot = false,
                            actions = {
                                CanonicalMetadataTrackMore(
                                    recordingId = row.musicBrainzId,
                                    title = row.title,
                                    artist = row.subtitle.orEmpty().ifBlank { page.title },
                                    palette = palette,
                                    expanded = menuExpanded,
                                    onOpen = { menuExpanded = true },
                                    onDismiss = { menuExpanded = false },
                                    onRate = { editingMetadataRecording = it },
                                    onAddToPlaylist = { id, title, artist ->
                                        addingMetadataRecording = Triple(id, title, artist)
                                    },
                                    tag = "canonical-album-track-more-$originalIndex",
                                )
                            },
                        )
                        if (index < canonicalVisibleTrackIndices.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = NUMBERED_ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                } else if (isArtist && publicArtistMbid != null &&
                    publicTopRows.isNotEmpty() && onCanonicalPublicTopOpen != null
                ) {
                    item(key = "canonical-artist-public-top") {
                        NativeArtistPublicTopSection(
                            artistMbid = publicArtistMbid,
                            rows = publicTopRows,
                            palette = palette,
                            sampled = canonicalPublicTopSampled,
                            onRecordingOpen = onCanonicalPublicTopOpen,
                        )
                    }
                } else if (isArtist && (
                    NativeArtistSection.TRACKS in artistVisibleSections ||
                        NativeArtistSection.TOP_TRACKS in artistVisibleSections
                )) {
                    // Exactly one track shelf. An online page can claim "Mais
                    // tocadas" only with ranking evidence; Library always shows
                    // "Faixas", never a fabricated popularity chart.
                    item {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        val isCurrentRow = playingRowMatcher(top, currentSong)
                        NativeArtistTracksShelf(
                            title = if (NativeArtistSection.TOP_TRACKS in artistVisibleSections)
                                stringResource(R.string.top_songs) else "Faixas",
                            tracks = top,
                            palette = palette,
                            horizontalPadding = ARTIST_CONTENT_GUTTER,
                        ) { song ->
                            CompactSongRow(
                                song = song,
                                palette = palette,
                                onClick = { onSongClick(top, top.indexOf(song)) },
                                onLongPress = { onSongLongPress(song) },
                                downloadedTint = downloadedTint,
                                isCurrent = isCurrentRow(song),
                                isPlaying = isCurrentRow(song) && isPlaying,
                            )
                        }
                    }
                } else {
                    // Every row on an album carries the same sleeve, which is
                    // already the largest thing on the page — Apple Music
                    // numbers those rows instead, and so does this.
                    val numbered = page.type == BrowseType.ALBUM
                    val isCurrentRow = playingRowMatcher(matches.map { it.value }, currentSong)
                    if (matches.isEmpty() && state.data.isNotEmpty()) {
                        item(key = "no-matches") {
                            MessageState(stringResource(R.string.nothing_matches, query))
                        }
                    }
                    itemsIndexed(
                        items = matches,
                        key = { _, entry -> "${entry.index}_${entry.value.videoId}" },
                    ) { position, entry ->
                        val song = entry.value
                        val isCurrent = isCurrentRow(song)
                        SongRow(
                            song = if (numbered) {
                                song
                            } else {
                                song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl)
                            },
                            onClick = {
                                onSongClick(queue, position)
                            },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            rowBackground = Color.Transparent,
                            // The track's place on the release, not its place in
                            // what the filter — or a sort — left standing.
                            trackNumber = originalTrackNumbers[song.videoId].takeIf { numbered },
                            subtitleColor = palette.onBackgroundVariant,
                            downloadedTint = downloadedTint,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                            activeTint = palette.accent,
                            searchPlayingStyle = true,
                        )
                        if (position < matches.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = if (numbered) NUMBERED_ROW_DIVIDER_INSET else ROW_DIVIDER_INSET,
                                ),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }

            if (page.type == BrowseType.ALBUM) {
                item(key = "album-metadata-footer") {
                    val year = page.subtitle.split("•", "·")
                        .map(String::trim).firstOrNull {
                            it.length == 4 && it.all(Char::isDigit)
                        }
                    val total = if (songs.isNotEmpty() && songs.all {
                        it.durationMillis() > 0L
                    }) songs.sumOf { it.durationMillis() }
                    else if (canonicalTracks.isNotEmpty() && canonicalTracks.all {
                        (it.durationMs ?: 0L) > 0L
                    }) canonicalTracks.sumOf { it.durationMs ?: 0L }
                    else null
                    val minutes = total?.let { ((it + 30_000L) / 60_000L).coerceAtLeast(1L) }
                    val count = if (songs.isNotEmpty()) songs.size else canonicalTracks.size
                    val summary = count.takeIf { it > 0 }?.let {
                        "$it ${if (it == 1) "música" else "músicas"}" +
                            (minutes?.let { min ->
                                ", $min ${if (min == 1L) "minuto" else "minutos"}"
                            } ?: "")
                    }
                    NativeAlbumMetadataFooter(
                        metadata = CanonicalAlbumFooterMetadata(year, summary),
                        palette = palette,
                    )
                }
            }

            // Tracks YouTube offers to round the playlist out, never folded
            // into the list above — see [DetailPage.suggestedSongs].
            if (suggested.isNotEmpty()) {
                item(key = "suggested-heading") {
                    SectionHeading(stringResource(R.string.suggested), palette)
                }
                val isSuggestedCurrent = playingRowMatcher(suggested, currentSong)
                itemsIndexed(
                    suggested,
                    key = { _, song -> "suggested-${song.videoId}" },
                ) { index, song ->
                    SuggestedSongRow(
                        song = song,
                        palette = palette,
                        onClick = { onSongClick(suggested, index) },
                        onLongPress = { onSongLongPress(song) },
                        onAdd = { onAddSuggested(song) },
                        downloadedTint = downloadedTint,
                        isCurrent = isSuggestedCurrent(song),
                        isPlaying = isSuggestedCurrent(song) && isPlaying,
                    )
                    if (index < suggested.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = palette.divider,
                        )
                    }
                }
            }

            // Albums / Singles & EPs carousels (artist pages).
            items(page.sections) { shelf ->
                val canShowAll = shelf.items.size > ARTIST_ROW_MAX_ITEMS
                val displayItems = remember(shelf.items) {
                    if (canShowAll) shelf.items.take(ARTIST_ROW_MAX_ITEMS) else shelf.items
                }
                NativeArtistReleaseShelf(
                    title = shelf.title,
                    releases = displayItems,
                    palette = palette,
                    horizontalPadding = ARTIST_CONTENT_GUTTER,
                    topPadding = 22.dp,
                    onShowAll = if (canShowAll) { { onActiveShelfChange(shelf) } } else null,
                ) { item ->
                        SectionCard(
                            item = item,
                            palette = palette,
                            onClick = { onSectionItemClick(item) },
                            onLongPress = onSectionItemLongPress?.let { { it(item) } },
                            applePromotion = appleGroupCardBadgeFor(
                                item.browseId, item.thumbnailUrl, appleGroupCardPromotions,
                            ),
                        )
                }
            }
            if (isArtist && publicArtistMbid != null &&
                onCanonicalFeaturedReleaseOpen != null
            ) {
                item(key = "artist-included-in") {
                    NativeArtistIncludedInShelf(
                        artistMbid = publicArtistMbid,
                        palette = palette,
                        onOpen = onCanonicalFeaturedReleaseOpen,
                    )
                }
                if (publicTopRows.isNotEmpty() && onCanonicalArtistPlaylistOpen != null) {
                    item(key = "artist-public-playlists") {
                        NativeArtistPublicPlaylistsShelf(
                            artistMbid = publicArtistMbid,
                            artistName = page.title,
                            verifiedRecordingMbids = publicTopRows
                                .map { it.musicBrainzId }.toSet(),
                            palette = palette,
                            onOpen = onCanonicalArtistPlaylistOpen,
                        )
                    }
                }
                item(key = "artist-similar") {
                    NativeArtistSimilarShelf(
                        artistMbid = publicArtistMbid,
                        palette = palette,
                        onOpen = onCanonicalFeaturedReleaseOpen,
                    )
                }
            }
            if (isArtist) aboutItem()
        }

                if (isArtist) {
                    NativeArtistDetailFrame(
                        palette = palette,
                        artHeight = artHeight,
                        listState = listState,
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                        canvas = canvas,
                        // Preserve the legacy cover fallback at this adapter
                        // boundary; the shared native frame only sees artwork.
                        heroUrl = appleArt?.heroUrl
                            ?: page.thumbnailUrl?.artworkAt(HEADER_ART_PX),
                        content = detailListItems,
                    )
                } else if (page.type == BrowseType.PLAYLIST) {
                    NativePlaylistDetailFrame(
                        palette = palette,
                        listState = listState,
                        bottomPadding = contentPadding.calculateBottomPadding(),
                        content = detailListItems,
                    )
                } else {
                    // Albums continue with their existing native release list.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                        content = detailListItems,
                    )
                }

                if (folder != null && folderState != null && folderState.selecting) {
                    LocalFolderSelectionBar(
                        folder = folder,
                        state = folderState,
                        songs = songs,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topBarContentPadding(), start = PAGE_GUTTER, end = PAGE_GUTTER),
                    )
                }

    }
} else {
    ArtistShelfGridPage(
        shelf = targetShelf,
        palette = palette,
        onItemClick = onSectionItemClick,
        onItemLongPress = onSectionItemLongPress,
        contentPadding = contentPadding,
        appleGroupCardPromotions = appleGroupCardPromotions,
    )
}
}
}

/**
 * An album or playlist, laid out as Apple Music does: the sleeve framed in the
 * middle of the page, the title, credit and meta under it, then Shuffle, a
 * labelled Play pill and Add to Library across one row.
 *
 * One flat page colour behind all of it — the sleeve is a picture *on* the
 * page, not the page's top edge, so there is nothing to blend into and no
 * layer drawn behind the list. Search, sort and the overflow menu are the top
 * bar's, not this header's.
 */
@Composable
private fun ReleaseHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    sleeveSize: Dp,
    canvas: CanvasArtwork?,
    offlinePoster: String?,
    songs: List<Song>,
    canonicalTracks: List<CanonicalCatalogSearchRow>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onArtistClick: ((String, String) -> Unit)?,
    onToggleLibrary: (() -> Unit)?,
    rating: Double? = null,
    onRate: (() -> Unit)? = null,
    isCatalogPinned: Boolean = false,
    onToggleCatalogPin: (() -> Unit)? = null,
    metaOverride: String? = null,
) {
    // Do not display a partial sum as if the album's total were complete.
    val allCanonicalDurationsKnown = canonicalTracks.isNotEmpty() &&
        canonicalTracks.all { (it.durationMs ?: 0L) > 0L }
    val canonicalMinutes = if (allCanonicalDurationsKnown) {
        canonicalTracks.sumOf { it.durationMs ?: 0L } / 60_000
    } else 0L
    val canonicalTime = when {
        canonicalMinutes <= 0L -> null
        canonicalMinutes < 60L -> stringResource(R.string.minutes_short, canonicalMinutes.toInt())
        else -> stringResource(
            R.string.hours_minutes_short,
            (canonicalMinutes / 60L).toInt(), (canonicalMinutes % 60L).toInt(),
        )
    }
    val (credit, originalMeta) = page.headerLines(
        if (songs.isNotEmpty()) songs.size else canonicalTracks.size,
        if (songs.isNotEmpty()) songs.playtime() else canonicalTime,
    )
    val meta = if (page.type == BrowseType.ALBUM) "" else metaOverride ?: originalMeta
    // Every row on a release carries the same credit — see [pageCredit] — so
    // the first one speaks for the whole page, the same source the rows'
    // own long-press "Open artist" already reads from.
    val artist = songs.firstOrNull()

    val onCreditClick: (() -> Unit)? = artist?.let { song ->
        song.artistId?.let { id ->
            if (onArtistClick != null) {
                { onArtistClick(id, song.artist) }
            } else null
        }
    }

    // Use the same approved native release compositor as unified Library
    // albums, nested artist albums and normal/smart playlists. This adapter
    // retains legacy artwork, Canvas, user library actions and credit links;
    // no provider-specific detail DTO enters the visual composition.
    NativeReleaseHeaderLayout(
        title = page.title,
        credit = credit,
        meta = meta,
        palette = palette,
        sleeveSize = sleeveSize,
        rating = rating,
        onRate = onRate,
        onCreditClick = onCreditClick,
        artwork = {
            var artFailed by remember(page.thumbnailUrl) { mutableStateOf(false) }
            if (page.thumbnailUrl == null || artFailed) {
                CoverPlaceholder(
                    icon = page.placeholderIcon(),
                    base = palette.elevated,
                    tint = palette.onBackgroundVariant,
                    modifier = Modifier.matchParentSize(),
                )
            }
            AsyncImage(
                model = (offlinePoster ?: page.thumbnailUrl).artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { artFailed = it is AsyncImagePainter.State.Error },
                modifier = Modifier.matchParentSize(),
            )
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = true,
                    modifier = Modifier.matchParentSize(),
                )
            }
        },
        actions = {
            if (songs.isNotEmpty() || canonicalTracks.isNotEmpty() ||
                onToggleCatalogPin != null || onRate != null) {
                val library = page.library?.takeIf { onToggleLibrary != null }
                val canPlay = songs.isNotEmpty() ||
                    (canonicalTracks.any { it.hasCatalogedAsset } &&
                        page.browseId.startsWith("catalog:"))
                Spacer(Modifier.height(18.dp))
                NativeReleaseActionRow(
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    canPlay = canPlay,
                    // Playlists keep identical header controls across sources.
                    isLocal = page.type != BrowseType.PLAYLIST &&
                        page.browseId.startsWith("local:"),
                    canShuffle = songs.isNotEmpty(),
                    isSaved = library?.saved,
                    onToggleLibrary = if (library == null) null else onToggleLibrary,
                    reservePinAction = onToggleCatalogPin != null,
                    isPinnedInLibrary = isCatalogPinned,
                    onTogglePin = onToggleCatalogPin,
                )
            }
        },
    )
}

/**
 * Shared release-page controls: a small Shuffle circle, dominant white Play
 * pill, and the contextual library circle. Kept here beside DetailScreen so
 * the canonical unified Album detail cannot drift into two Library action
 * pills or silently change the reference release visual hierarchy.
 */
/** Compatibility alias for pre-canonical detail callers. New pages use NativeDetailSearchField. */
@Composable
internal fun DetailSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    autoFocus: Boolean,
    onFocused: () -> Unit,
    palette: ArtworkPalette,
    placeholder: String? = null,
    horizontalInset: Dp = HEADER_GUTTER,
) = NativeDetailSearchField(
    query = query,
    onQueryChange = onQueryChange,
    onClose = onClose,
    autoFocus = autoFocus,
    onFocused = onFocused,
    palette = palette,
    placeholder = placeholder,
    horizontalInset = horizontalInset,
)

internal fun List<Song>.sortedForDetail(sort: SongSort): List<Song> = when (sort) {
    SongSort.DEFAULT -> this
    SongSort.TITLE_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    SongSort.TITLE_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
    // A catalogue row carries no added date, so its place in the running
    // order stands in for one: a playlist is appended to as songs are added,
    // and reversed that puts the most recent addition on top. Rows that do
    // carry a MediaStore timestamp — device tracks, downloads — are dated
    // properly, with the position order left to break the ties.
    SongSort.DATE_ADDED_ASC -> withIndex()
        .sortedWith(
            compareBy<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MAX_VALUE
            }.thenBy { (position, _) -> position },
        )
        .map { it.value }
    SongSort.DATE_ADDED_DESC -> withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<Song>> { (_, song) ->
                song.localDateAddedSeconds ?: Long.MIN_VALUE
            }.thenByDescending { (position, _) -> position },
        )
        .map { it.value }
}

/**
 * The rows a typed query leaves standing, each still carrying its place in the
 * full list — see the track numbers on an album, which are the release's own
 * and not positions in whatever the filter left.
 */
internal fun List<Song>.matching(query: String): List<IndexedValue<Song>> {
    val all = withIndex().toList()
    if (query.isBlank()) return all
    return all.filter { (_, song) -> song.matchesSearch(query) }
}

/** "1.2M subscribers" and "3.4M monthly listeners", off the artist header. */
@Composable
private fun ArtistStatsRow(
    subscriberCountText: String?,
    monthlyListenerCount: String?,
    palette: ArtworkPalette,
) {
    // Stacked and left-aligned with the text under it, since this now sits in
    // the About section rather than across the header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ARTIST_CONTENT_GUTTER, end = ARTIST_CONTENT_GUTTER, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // YouTube's own count text already reads "1.2M subscribers" in full,
        // so only the number is kept and the label re-said in the app's own
        // words — the one way to fit both stats on one line on a narrow
        // screen without either wrapping into two.
        subscriberCountText?.let {
            StatChip(
                icon = Icons.Rounded.Person,
                text = stringResource(R.string.subscribers, it.substringBefore(' ')),
                palette = palette,
            )
        }
        monthlyListenerCount?.let {
            StatChip(
                icon = Icons.Rounded.GraphicEq,
                text = stringResource(R.string.monthly_listeners, it.substringBefore(' ')),
                palette = palette,
            )
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, text: String, palette: ArtworkPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .lightweightLiquidGlass(
                shape = CircleShape,
                fallbackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.onBackgroundVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = palette.onBackgroundVariant,
        )
    }
}

/**
 * YouTube's own editorial note for a release or an artist, collapsed to a
 * few lines with a tap to read the rest — the same "About" block Apple
 * Music and YouTube Music itself show under the header.
 *
 * Whether there's anything to expand is only knowable once the text has
 * been laid out at the collapsed line count, so the "More" toggle is held
 * back until that measurement says the clipped text actually lost
 * something — otherwise a two-line bio would show a toggle with nothing
 * behind it to reveal.
 */
/** "Signed by **Name**" — the artist's name in bold, whatever order the language puts it in. */
/** Compact row used inside the artist song grid; no swipe, to keep the
 *  horizontal pager's gestures unambiguous. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactSongRow(
    song: Song,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    downloadedTint: Color? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            AsyncImage(
                model = com.music.bitchord.ui.player.rememberPlaybackArtworkImageModel(song, ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .thumbnailBorder(RoundedCornerShape(7.dp))
                    .background(palette.elevated),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) palette.accent else palette.onBackground,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The weighted title/artist block truncates before trailing stars.
        CanonicalSongRatingStars(
            song = song,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (downloadedTint != null) {
            DownloadedBadge(song.videoId, downloadedTint)
        }
        RowMoreButton(onClick = onLongPress, tint = palette.onBackgroundVariant)
    }
}

/**
 * A row under "Suggested" — a track YouTube offers to round the playlist
 * out but that was never added. [onAdd] is the point of the row, so it gets
 * the trailing spot a track already on the playlist spends on "more"; the
 * long-press sheet is still one gesture away for anything else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedSongRow(
    song: Song,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAdd: () -> Unit,
    downloadedTint: Color? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            AsyncImage(
                model = com.music.bitchord.ui.player.rememberPlaybackArtworkImageModel(song, ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(palette.elevated),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) palette.accent else palette.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The weighted title/artist block truncates before trailing stars.
        CanonicalSongRatingStars(
            song = song,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (downloadedTint != null) {
            DownloadedBadge(song.videoId, downloadedTint)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.16f))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.add_to_playlist),
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The newest release on an artist's shelves — an album, or a single or EP that
 * came out after it. Playlists, videos and related artists share these shelves
 * and are skipped: only an `MPRE…` browse id is a release.
 *
 * Newest by the year in the card's subtitle, which is all a shelf item says
 * about when. Ties and releases with no year keep YouTube's own order, which
 * lists albums first.
 */
private fun List<HomeShelf>.topRelease(): ShelfItem? =
    asSequence()
        .flatMap { it.items.asSequence() }
        .filter { it.browseId?.startsWith("MPRE") == true }
        .withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<ShelfItem>> { it.value.releaseYear() ?: 0 }
                .thenByDescending { it.index },
        )?.value

private val RELEASE_YEAR = Regex("""\b(19|20)\d{2}\b""")

private fun ShelfItem.releaseYear(): Int? =
    RELEASE_YEAR.find(subtitle)?.value?.toIntOrNull()

/** "Recent Single" and "Recent EP" where the card says so, otherwise "Recent Album". */
@androidx.annotation.StringRes
private fun ShelfItem.recentLabel(): Int {
    val kind = subtitle.lowercase()
    return when {
        kind.contains("single") -> R.string.recent_single
        Regex("""\bep\b""").containsMatchIn(kind) -> R.string.recent_ep
        else -> R.string.recent_album
    }
}

/**
 * The artist page's top-release card: sleeve, what it is and when, and its
 * title, in a rounded glass panel the width of the page.
 */
/**
 * Legacy ShelfItem is adapted here, never inside the canonical native card.
 * CAA timing and signed-in save/unsave behavior remain unchanged.
 */
@Composable
private fun TopReleaseCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    saved: Boolean?,
    onToggleSaved: (() -> Unit)?,
    applePromotion: AppleGroupCardBadge? = null,
) {
    val haptics = rememberHaptics()
    NativeArtistTopReleaseCard(
        title = item.title,
        subtitle = item.subtitle,
        label = stringResource(item.recentLabel()),
        palette = palette,
        onClick = onClick,
        onLongPress = onLongPress,
        artwork = { ArtistCardArtwork(item, applePromotion) },
        trailing = if (onToggleSaved != null && saved != null) {
            {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = LIGHT_FILL_ALPHA), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = TOP_RELEASE_EDGE_ALPHA), CircleShape)
                        .clickable {
                            haptics.play(if (saved) Haptic.ToggleOff else Haptic.ToggleOn)
                            onToggleSaved.invoke()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = stringResource(
                            if (saved) R.string.remove_from_library else R.string.add_to_library,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else null,
    )
}

/**
 * Anonymous timing of the actual Coil CAA image consumer. Duration begins at
 * first composition of that URL and ends once Coil signals Success or Error.
 * Distinct URLs record once each per composed card; never store their URL,
 * album name, MBID or user/device identity in the diagnostics report.
 */
@Composable
private fun rememberCaaCoverLoadObserver(
    url: String?,
): (AsyncImagePainter.State) -> Unit {
    val beganAt = remember(url) { android.os.SystemClock.elapsedRealtime() }
    val seen = remember(url) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val isCaa = remember(url) { isCoverArchiveImageHost(url) }
    return remember(url, beganAt) {
        { state: AsyncImagePainter.State ->
            val done = state is AsyncImagePainter.State.Success ||
                state is AsyncImagePainter.State.Error
            if (isCaa && done && seen.compareAndSet(false, true)) {
                com.music.bitchord.data.catalog.metadata.CatalogCoverTelemetry.process
                    .recordImageResult(
                        state is AsyncImagePainter.State.Success,
                        android.os.SystemClock.elapsedRealtime() - beganAt,
                        source = (state as? AsyncImagePainter.State.Success)
                            ?.result?.dataSource?.name,
                    )
            }
        }
    }
}

/**
 * The existing native release card is the single artist-shelf card.
 * Canonical Library releases provide artwork and real selection/More actions
 * through slots, without manufacturing a ShelfItem or provider browseId.
 * No layout fork: native catalog and Library both paint these same elements.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativeArtistReleaseCard(
    title: String,
    subtitle: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    artwork: @Composable () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    onLongPress: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    artworkShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
) {
    val shape = artworkShape
    Column(
        modifier = modifier.longPressMenuClickable(
            onClick = onClick, onLongClick = onLongPress,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(palette.elevated),
        ) {
            artwork()
            if (selectionMode && onToggleSelection != null) {
                IconButton(
                    onClick = onToggleSelection,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle
                        else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = "Selecionar $title",
                    )
                }
            } else if (onMore != null) {
                RowMoreButton(
                    onClick = onMore,
                    modifier = Modifier.align(Alignment.TopEnd),
                    contentDescription = "Opções de $title",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Legacy shelf metadata adapts to the native card; no provider IDs in UI. */
@Composable
private fun SectionCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    onLongPress: (() -> Unit)? = null,
    applePromotion: AppleGroupCardBadge? = null,
) {
    NativeArtistReleaseCard(
        title = item.title,
        subtitle = item.subtitle,
        palette = palette,
        onClick = onClick,
        modifier = modifier,
        onLongPress = onLongPress,
        artwork = { ArtistCardArtwork(item, applePromotion) },
    )
}

/** Promo badges are their original Apple assets, clickable independently of the card. */
@Composable
private fun ArtistCardArtwork(
    item: ShelfItem,
    applePromotion: AppleGroupCardBadge?,
) {
    val observer = rememberCaaCoverLoadObserver(item.thumbnailUrl)
    val uriHandler = LocalUriHandler.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            // Never rewrite a returned 100px Apple CDN URL to an unverified size.
            model = if (applePromotion != null) item.thumbnailUrl
                else item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            onState = observer,
            modifier = Modifier.fillMaxSize(),
        )
        if (applePromotion != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.68f)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = applePromotion.badgeUrl,
                    contentDescription = "Abrir lançamento na Apple Music",
                    modifier = Modifier.width(112.dp).height(32.dp)
                        .clickable {
                            runCatching { uriHandler.openUri(applePromotion.storeUrl) }
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun ArtistShelfGridPage(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    appleGroupCardPromotions: Map<String, Pair<String, String>> = emptyMap(),
) {
    val gridState = rememberLazyGridState()
    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = PaddingValues(
                top = topBarContentPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PAGE_GUTTER),
        ) {
            items(shelf.items, key = { it.browseId ?: it.title }) { item ->
                SectionCard(
                    item = item,
                    palette = palette,
                    onClick = { onItemClick(item) },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                    modifier = Modifier.fillMaxWidth(),
                    applePromotion = appleGroupCardBadgeFor(
                        item.browseId, item.thumbnailUrl, appleGroupCardPromotions,
                    ),
                )
            }
        }
    }
}

/**
 * Splits the one subtitle a browse row hands over — "Album • Travis Scott •
 * 2023", or sometimes just "Travis Scott" — into the credit line and the
 * metadata line the header shows separately.
 *
 * Everything is optional, because every caller supplies a different amount of
 * it: the player knows an album's artist but not its year, search knows both,
 * and a home card frequently knows neither.
 */
@Composable
private fun DetailPage.headerLines(trackCount: Int, playtime: String? = null): Pair<String, String> {
    val parts = subtitle.split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }
    val kind = parts.firstOrNull { it.lowercase(Locale.ROOT) in KIND_WORDS }
    val credit = parts.filter { it != year && it != kind }.joinToString(", ")
    // Title case, "Playlist · 2022 · 10 Tracks · 38 min" — except the
    // playtime, whose "min" and "1h 25m" are units, not words.
    // An artist grouped out of a device folder is a plain track list, not an
    // artist page, but it is still billed as the artist it is.
    val localKind = if (LocalGroups.isArtist(browseId)) stringResource(R.string.artist) else null
    val meta = listOfNotNull(
        (kind ?: type.localizedLabel()?.takeIf {
            com.music.bitchord.ui.canonicalMayUseAlbumFallbackLabel(browseId)
        } ?: localKind)?.capitalizeWords(),
        year,
        trackCount.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.track_count_plural, it, it).capitalizeWords()
        },
        playtime,
    ).joinToString(" · ")
    return credit to meta
}

/** The glyph on a release page's placeholder sleeve: what kind of thing has no cover. */
private fun DetailPage.placeholderIcon(): ImageVector = deviceFolderIcon(browseId) ?: when {
    LocalGroups.isArtist(browseId) -> Icons.Rounded.Person
    type == BrowseType.ALBUM -> Icons.Rounded.Album
    type == BrowseType.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
    else -> BitChordIcons.MusicNote
}

/** "10 tracks" → "10 Tracks", "playlist" → "Playlist". */
private fun String.capitalizeWords(): String =
    split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

/** Subtitle words that name what a page *is* rather than who made it. */
private val KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
)

@Composable
private fun BrowseType.localizedLabel(): String? = when (this) {
        BrowseType.ALBUM -> stringResource(R.string.album)
        BrowseType.PLAYLIST -> stringResource(R.string.playlist)
        BrowseType.ARTIST -> stringResource(R.string.artist)
        BrowseType.OTHER -> null
    }

/**
 * How long the page plays for — "41 min", "1h 25m" — summed over the rows
 * on it, or null when none of them carry a duration. A playlist still filling
 * in counts up with it, so the figure is never ahead of the list it sits over.
 */
@Composable
private fun List<Song>.playtime(): String? {
    val minutes = sumOf { it.durationMillis() } / 60_000
    return when {
        minutes <= 0 -> null
        minutes < 60 -> stringResource(R.string.minutes_short, minutes.toInt())
        else -> stringResource(R.string.hours_minutes_short, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}
