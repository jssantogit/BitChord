package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account button in the toolbar: the listener's photo, or a person glyph when there is nobody
 * signed in.
 */
@Composable
internal fun DesktopAccountButton(
    avatar: String?,
    onClick: () -> Unit,
) {
    Box(
        // Rests at the toolbar buttons' hover wash and brightens a step when pointed at.
        Modifier.size(36.dp).clip(CircleShape)
            .desktopHoverWash(restAlpha = 0.12f, hoverAlpha = 0.2f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            DesktopArtwork(
                avatar,
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentScale = ContentScale.Crop,
                px = 96,
            )
        } else {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    DesktopStrings["accounts", "Accounts"],
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The account switcher: every login this app holds, the channels under each, and the actions that
 * change the set.
 */
@Composable
internal fun DesktopAccountSelector(
    accounts: List<DesktopGoogleAccount>,
    activeAccountId: String?,
    activeProfileId: String?,
    busy: Boolean,
    onSelect: (DesktopGoogleAccount, DesktopYouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (DesktopGoogleAccount) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        // The scrim is its own layer rather than a modifier on the container.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                // The same frosted pane the floating bars are made of, sampling the page underneath
                // rather than sitting on a flat fill.
                .desktopBarGlass(shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (accounts.isEmpty()) "Accounts" else "Switch account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(color = DesktopAccent, modifier = Modifier.size(16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (accounts.isEmpty()) {
                    item {
                        Text(
                            DesktopStrings[
                                "d_nobody_is_signed_in",
                                "Nobody is signed in. Your library, playlists and history need a " +
                                    "YouTube Music account.",
                            ],
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
                accounts.forEach { account ->
                    item(key = "header-${account.accountId}") {
                        Text(
                            account.email.ifBlank { account.name.ifBlank { "Account" } },
                            style = MaterialTheme.typography.labelMedium,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(account.profiles.size, key = { "p-${account.accountId}-$it" }) { index ->
                        val profile = account.profiles[index]
                        DesktopProfileRow(
                            profile = profile,
                            selected = account.accountId == activeAccountId &&
                                profile.profileId == activeProfileId,
                            managing = managing,
                            onClick = { onSelect(account, profile); onDismiss() },
                            onRemove = { onRemoveAccount(account) },
                        )
                    }
                }
                item { DesktopSelectorAction(BitChordIcons.Plus, "Add account", onAddAccount) }
                if (accounts.isNotEmpty()) {
                    item {
                        DesktopSelectorAction(Icons.Rounded.ManageAccounts, "Manage accounts") {
                            managing = !managing
                        }
                    }
                }
                item { DesktopSelectorAction(Icons.Rounded.Settings, "Settings", onOpenSettings) }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

@Composable
private fun DesktopProfileRow(
    profile: DesktopYouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = if (managing) onRemove else onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            DesktopArtwork(
                profile.avatar,
                Modifier.size(38.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
                px = 128,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                null,
                tint = DesktopSecondary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                profile.handle.ifBlank { if (profile.isBrandAccount) "Brand account" else "Personal" },
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            managing -> Text(DesktopStrings["sign_out", "Sign out"], color = DesktopDestructive, style = MaterialTheme.typography.labelMedium)
            selected -> Icon(BitChordIcons.Check, DesktopStrings["selected", "Selected"], tint = DesktopAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DesktopSelectorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White)
    }
}

/** Historical YouTube authentication is retired. Keep this notice while old account controls migrate. */
internal const val WEB_SIGN_IN = "Google"

@Composable
internal fun DesktopSignInDialog(
    @Suppress("UNUSED_PARAMETER") webSignIn: Boolean,
    @Suppress("UNUSED_PARAMETER") onWebSignIn: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onImport: (DesktopBrowserCookies.Profile) -> Unit,
    @Suppress("UNUSED_PARAMETER") onPaste: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") busy: String?,
    @Suppress("UNUSED_PARAMETER") error: String?,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .clip(shape).desktopBarGlass(shape).padding(22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "YouTube Music sign-in retired",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, DesktopStrings["close", "Close"], tint = DesktopSecondary)
                }
            }
            Text(
                "Stored account information remains available for historical compatibility, " +
                    "but BitChord no longer collects YouTube Music cookies or opens its login flow.",
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
