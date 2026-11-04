package com.music.bitchord.ui.components

import com.music.bitchord.ui.LocalPullToRefreshEnabled
import com.music.bitchord.ui.AppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.ui.player.MESH_CYCLE_MILLIS
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.WelcomeMeshPalettes
import com.music.bitchord.ui.player.cyclingMeshPalette
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.CircularProgressIndicator
import com.music.bitchord.data.model.artworkAt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.border

/**
 * The left and right inset every page's content sits at — the artist page's
 * edge, which every other page now shares, so a track row, a card or a
 * heading starts at the same place whichever page it is on.
 */
val PAGE_GUTTER = 16.dp

/**
 * The inset the floating chrome sits at: the mini player, the tab bar and the
 * top bar's controls. The same as [PAGE_GUTTER], so the bars' edges line up
 * with the content scrolling beneath them rather than sitting just outside it.
 */
val BAR_GUTTER = PAGE_GUTTER

/** Where a divider under a track row starts: clear of the 52dp of artwork. */
val ROW_DIVIDER_INSET = PAGE_GUTTER + 68.dp

/**
 * A numbered row's track-number column, the number centred in it: room for
 * three digits. It sits between two page gutters — the row's own on the left,
 * a matching gap before the title on the right — so the number has the same
 * space either side of it, counted from the screen edge.
 */
val TRACK_NUMBER_WIDTH = 24.dp

/** Where a divider under a numbered row starts: at its title, past the number. */
val NUMBERED_ROW_DIVIDER_INSET = PAGE_GUTTER + TRACK_NUMBER_WIDTH + PAGE_GUTTER

/**
 * How wide the floating bars at the foot of the page — the tab bar and the mini
 * player above it — are ever allowed to get.
 *
 * Both are fixed rows of controls with a fixed amount to say, not content that
 * benefits from room, and a phone is the width they were spaced for. Run right
 * across a tablet the four tabs end up a hand apart with their labels marooned
 * in the middle of nothing, and the mini player puts its artwork and its buttons
 * at opposite ends of the screen with a lake of frosted glass between. Past this
 * they stop growing and centre themselves over the page instead.
 *
 * Set clear of the widest phone (448dp less the two [PAGE_GUTTER]s is 428dp), so
 * on a phone it does nothing and the bars still line up with the page's content.
 */
val FLOATING_BAR_MAX_WIDTH = 440.dp

/**
 * Width of a card in the compact carousels — home shelves, library shelves and
 * the artist page's releases alike.
 *
 * Sized so a phone-width row shows two cards whole with the edge of a third
 * showing: enough to say the row scrolls without a card being half a card.
 */
val SHELF_CARD_WIDTH = 150.dp

/** Share of the row a lead-shelf card takes, so the next one peeks in past it. */
private const val HERO_CARD_FRACTION = 0.70f

/**
 * How wide a lead-shelf card is ever allowed to get.
 *
 * The fraction alone is a phone measurement wearing a percent sign: 70% of a
 * tablet is a card the better part of a foot across, and a hero card is a
 * caption over some artwork rather than a canvas — blown up that far it stops
 * being the top of a feed and becomes a poster with a shelf hiding under it.
 *
 * Set just clear of what the widest phone asks for (0.70 of 448dp is 314dp), so
 * every phone keeps the width the fraction gives it and only a screen wider than
 * any phone is held back to it.
 */
private val HERO_CARD_MAX_WIDTH = 320.dp

/** A lead-shelf card's proportions: a touch taller than it is wide. */
const val HERO_CARD_RATIO = 0.92f

/**
 * How wide a lead-shelf card should be in a row [available] wide — the shared
 * answer for the real shelf and for the skeleton that stands in for it, which
 * have to agree to the pixel or the feed jumps when the data lands.
 *
 * Given the row's own width rather than the window's, so it is still right in
 * the narrower column a tablet leaves once the player has taken its pane.
 * Height follows from [HERO_CARD_RATIO], so the card keeps its shape at any
 * width.
 */
fun heroCardWidth(available: Dp): Dp = minOf(available * HERO_CARD_FRACTION, HERO_CARD_MAX_WIDTH)

/** Share of the row a page of tracks takes, so the next page peeks in past it. */
private const val TRACK_COLUMN_FRACTION = 0.88f

/**
 * How wide a sideways-paging column of track rows is ever allowed to get.
 *
 * Same trap [HERO_CARD_MAX_WIDTH] answers, and worse for a list: a track row is
 * artwork, a title and a subtitle, none of which have any use for more room. At
 * 88% of a tablet the row's contents stay their own size and the space all lands
 * between the title and the overflow button, so four songs eat the width of the
 * screen and read as a page half-filled rather than a shelf.
 *
 * 400dp clears what the widest phone asks for (0.88 of 448dp is 394dp), so every
 * phone keeps the width the fraction gives it and only a tablet is held back —
 * to a column near a phone's own width, which is the size these rows were drawn
 * at, with the next page showing beside it.
 */
private val TRACK_COLUMN_MAX_WIDTH = 400.dp

/**
 * How wide a column of track rows should be in a row [available] wide — shared
 * by Home's Recents, an artist's Top songs, and the skeletons that stand in for
 * them, which have to agree to the pixel or the page jumps when the data lands.
 */
fun trackColumnWidth(available: Dp): Dp =
    minOf(available * TRACK_COLUMN_FRACTION, TRACK_COLUMN_MAX_WIDTH)

/** How many cards sit across a library grid row, and how wide each lands. */
data class LibraryGridSpec(val columns: Int, val cardWidth: Dp)

/**
 * The narrowest a library grid card is let get before another column gives way.
 *
 * Deliberately the same 140.dp minimum the Local Music and Downloads grids pass
 * to `GridCells.Adaptive`, so a cover on a "Show all" page is the same size as
 * the same cover on those pages rather than noticeably smaller.
 */
private val LIBRARY_GRID_MIN_CARD_WIDTH = 140.dp

/** Gap between cards in a library grid, in both directions. */
val LIBRARY_GRID_SPACING = 12.dp

private const val LIBRARY_GRID_MIN_COLUMNS = 2

/** Library shelves never grow past this many across, however wide the screen. */
private const val LIBRARY_GRID_MAX_COLUMNS = 5

/**
 * How a Library shelf's full "Show all" page lays out as a grid, in
 * [available] dp of row — see `LibraryGridPage`.
 *
 * Columns follow from [LIBRARY_GRID_MIN_CARD_WIDTH] — as many as fit — rather
 * than from a fixed count, so a phone settles on 2 and a tablet fills out
 * towards the 5-column ceiling. This is the same arithmetic `GridCells.Adaptive`
 * runs, at the same minimum, gutter and spacing the Local Music and Downloads
 * grids use, which is what keeps a card here the size of a card there. Every
 * width is already in dp, which is what
 * makes this "based on device width and dpi" rather than a raw pixel count: a
 * dp reads the same physical size on a 420ppi phone as on a 160ppi tablet, so
 * the column count tracks how much room there actually is rather than how
 * many pixels the panel happens to report.
 *
 * The shelf's own preview row on the Library page itself is unrelated — it
 * keeps the fixed [SHELF_CARD_WIDTH] every other shelf uses and a flat
 * five-card cap rather than a width-derived one, so a card is the same size
 * whether the row it's in scrolls or not. See `LibraryGridShelf`.
 */
fun libraryGrid(available: Dp): LibraryGridSpec {
    val raw = ((available + LIBRARY_GRID_SPACING) / (LIBRARY_GRID_MIN_CARD_WIDTH + LIBRARY_GRID_SPACING))
        .toInt()
    val columns = raw.coerceIn(LIBRARY_GRID_MIN_COLUMNS, LIBRARY_GRID_MAX_COLUMNS)
    val cardWidth = (available - LIBRARY_GRID_SPACING * (columns - 1)) / columns
    return LibraryGridSpec(columns, cardWidth)
}

/**
 * Pull-to-refresh for the tab feeds, with the usual circular puck suppressed.
 *
 * The feeds sit under a frosted bar that already occupies the top 96dp, so a
 * puck drawn here would be blurred out by the glass it lands behind. The bar
 * draws its own puck instead, sliding out from under its bottom edge — which
 * is why [state] is hoisted: the bar lives beside this content, not inside it,
 * and has to follow the same drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!LocalPullToRefreshEnabled.current) {
        Box(modifier.fillMaxSize()) { content() }
        return
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {},
    ) {
        content()
    }
}

/**
 * The prompt atop Home while signed out: a card in the welcome pages' living
 * mesh, so the one thing Home asks of a signed-out listener looks like an
 * invitation from the app rather than a settings row that wandered in.
 *
 * Drawn white-on-colour in either theme, like Replay and the welcome — the
 * mesh is the card, and white type is what sits on colour.
 */
@Composable
fun SignInBanner(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = 900f), label = "signInScale")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onSignIn),
    ) {
        // A card is a few hundred dp the reader is looking straight at, which
        // is the case the mesh lets keep moving — see its `continuous` note.
        MeshGradientBackground(
            palette = cyclingMeshPalette(WelcomeMeshPalettes),
            modifier = Modifier.matchParentSize(),
            continuous = true,
            driftMillis = 6_000,
            blurRadius = 36.dp,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
            Text(
                text = stringResource(Res.string.sign_in_youtube_music),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.W800,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.ic_google_g),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.sign_in_with_google),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.Black,
                )
            }
        }
    }
}

@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER + 6.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The narrow, pill-shaped text field this app uses instead of Material's
 * `OutlinedTextField` — the same box the alerts and the search bar are built
 * from.
 *
 * Flat `surfaceVariant`, an 11dp radius, no outline and no floating label. A
 * stock `OutlinedTextField` dropped onto one of these pages reads as a widget
 * borrowed from another app: it is the only thing on the screen wearing a
 * border, and its label reserves a band of empty space above every row it sits
 * in whether or not there is a label to put there.
 */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    /**
     * What the box is filled with.
     *
     * The default is the fill the alerts want, since their frosted card is
     * `surface` and this sits on top of it. A field dropped into a settings
     * card has to be told otherwise: those cards are *already* `surfaceVariant`
     * (see `SettingsGroup`), and a field the colour of the card it is in is a
     * field nobody can see.
     */
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** Optional accessible label supplied by a form, without duplicating this field. */
    accessibilityLabel: String? = null,
    /** The same native field can grow for notes and playlist descriptions. */
    multiline: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (multiline) 96.dp else 40.dp)
            .background(container, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = if (multiline) 12.dp else 0.dp),
        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (multiline) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 8 else 1,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth().then(
                if (accessibilityLabel != null)
                    Modifier.semantics { contentDescription = accessibilityLabel }
                else Modifier
            ),
        )
    }
}
