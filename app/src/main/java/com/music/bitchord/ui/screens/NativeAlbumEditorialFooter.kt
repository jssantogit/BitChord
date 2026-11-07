package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionPresentation
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsObservation
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.delay

/** Small native typography footer, never an independent Discogs detail screen. */
internal data class NativeAlbumEditorialFooterData(
    val lines: List<String>,
    val attributionText: String,
    val attributionUrl: String,
    val expiresAtMillis: Long,
)

/**
 * Pure presentation gate shared by Library and online release screens.
 * MusicBrainz's date/track-count/copyright footer is rendered separately
 * above this optional section. A source observation is not a canonical fact.
 */
internal fun nativeAlbumEditorialFooter(
    evidence: DiscogsEditionEnrichment?,
    nowMillis: Long,
): NativeAlbumEditorialFooterData? {
    val verified = evidence as? DiscogsEditionEnrichment.Verified ?: return null
    val display = DiscogsEditionPresentation.from(verified, nowMillis) ?: return null
    val lines = buildList {
        // Keep the first edition label and avoid a wall of credits.
        display.labelLines.take(1).forEach { label ->
            add(("Gravadora · " + label).take(320))
        }
        display.formats.take(4).filter { it.isNotBlank() }.let { formats ->
            if (formats.isNotEmpty()) add(("Formato · " + formats.joinToString(" · ")).take(320))
        }
        display.country?.takeIf { it.isNotBlank() }?.let {
            add(("País da edição · " + it).take(320))
        }
        display.editionYear?.let { add("Ano da edição · " + it) }
    }
    if (lines.isEmpty()) return null
    return NativeAlbumEditorialFooterData(
        lines = lines,
        attributionText = display.attributionText,
        attributionUrl = display.attributionUrl,
        expiresAtMillis = verified.observation.fetchedAtMillis + DiscogsObservation.TTL_MILLIS,
    )
}

/**
 * Must be placed inside NativeAlbumMetadataFooter, below the existing date,
 * duration and rights lines and before optional artist recommendations.
 *
 * Rechecks the validity deadline while the screen remains mounted, so a
 * long-lived detail screen cannot silently keep API content visible past TTL.
 */
@Composable
internal fun NativeAlbumEditorialFooter(
    evidence: DiscogsEditionEnrichment?,
    palette: ArtworkPalette,
) {
    val expiry = (evidence as? DiscogsEditionEnrichment.Verified)
        ?.observation?.fetchedAtMillis?.plus(DiscogsObservation.TTL_MILLIS)
    var checkedAtMillis by remember(evidence) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(evidence, expiry) {
        if (expiry == null) return@LaunchedEffect
        val remaining = expiry - System.currentTimeMillis()
        if (remaining > 0L) delay(remaining)
        checkedAtMillis = maxOf(System.currentTimeMillis(), expiry)
    }
    val footer = nativeAlbumEditorialFooter(evidence, checkedAtMillis) ?: return
    val uriHandler = LocalUriHandler.current

    Spacer(Modifier.height(12.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("native-album-editorial-footer"),
    ) {
        Text(
            text = "Sobre esta edição",
            color = palette.onBackground,
            style = MaterialTheme.typography.titleSmall,
        )
        footer.lines.forEach { line ->
            Text(
                text = line,
                color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = footer.attributionText,
            color = palette.onBackgroundVariant,
            style = MaterialTheme.typography.labelSmall.copy(
                textDecoration = TextDecoration.Underline,
            ),
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable { uriHandler.openUri(footer.attributionUrl) }
                .testTag("native-album-editorial-attribution"),
        )
    }
}
