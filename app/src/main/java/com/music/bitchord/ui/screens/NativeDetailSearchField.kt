package com.music.bitchord.ui.screens

import com.music.bitchord.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.ArtworkPalette

/** Source-independent search surface shared by native artist, album and playlist details. */
// The Apple Library REA confirms a 48dp background in specific search resources,
// but does not establish their use in every category. Preserve the existing
// 46dp native detail control and scope the 48dp candidate to library collections.
private val NativeSearchShape = RoundedCornerShape(12.dp)

/**
 * The filter box, shown under the header while the top bar's search button is on.
 *
 * Live rather than submit-on-enter, and for the same reason the Local Music
 * one is (see `LocalSearchField`): the list it narrows is already in memory, so
 * there is nothing for a submit action to wait for.
 *
 * Glass rather than a filled field — it is one of the header's controls that
 * happens to be typed into, and it sits close enough to the circles that a
 * Material text field beside them would read as a different app's furniture.
 */
@Composable
internal fun NativeDetailSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    autoFocus: Boolean,
    onFocused: () -> Unit,
    palette: ArtworkPalette,
    placeholder: String? = null,
    horizontalInset: Dp = HEADER_GUTTER,
    appleLibraryCategoryStyle: Boolean = false,
) {
    // Opened by a tap on a button, which is as clear a statement of intent as
    // the keyboard is going to get — so it comes up with the field rather than
    // making the tap land twice. Once only: see [autoFocus]'s owner.
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchHint = placeholder ?: stringResource(R.string.search_this_list)
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focus.requestFocus()
            onFocused()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset)
            .padding(bottom = 10.dp)
            .height(if (appleLibraryCategoryStyle) 48.dp else 46.dp)
            .testTag("native-detail-search-field")
            .clip(NativeSearchShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (appleLibraryCategoryStyle) 1f else 0.6f))
            .then(if (appleLibraryCategoryStyle) Modifier else
                Modifier.border(0.5.dp, Color.White.copy(alpha = 0.10f), NativeSearchShape))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!appleLibraryCategoryStyle) {
            Icon(
                imageVector = BitChordIcons.Search,
                contentDescription = null,
                tint = palette.onBackgroundVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = searchHint,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // This is an in-memory live filter; submitting must not
                    // discard the query or trigger a remote search.
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.onBackground),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = searchHint },
            )
        }
        if (!appleLibraryCategoryStyle || query.isNotEmpty()) Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                // Clearing and closing are the same gesture: an empty filter
                // showing an unfiltered list is a row of furniture with nothing
                // left to do.
                .clickable { if (query.isEmpty()) onClose() else onQueryChange("") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(
                    if (query.isEmpty()) R.string.close_search else R.string.clear_search,
                ),
                tint = palette.onBackgroundVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

