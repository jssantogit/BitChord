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
import androidx.compose.runtime.mutableStateOf
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
    /** Additional verified labels/formats stay collapsed until requested. */
    val additionalLines: List<String> = emptyList(),
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
    val labels = display.labelLines.filter(String::isNotBlank).take(6)
    val formats = display.formats.filter(String::isNotBlank).take(12)
    val lines = buildList {
        // Keep the summary short and leave the original album footer intact.
        labels.firstOrNull()?.let { add(("Gravadora · " + it).take(320)) }
        formats.take(4).takeIf(List<String>::isNotEmpty)?.let {
            add(("Formato · " + it.joinToString(" · ")).take(320))
        }
        display.country?.takeIf { it.isNotBlank() }?.let {
            add(("País da edição · " + it).take(320))
        }
        display.editionYear?.let { add("Ano da edição · " + it) }
    }
    val additionalLines = buildList {
        labels.drop(1).forEach { add(("Gravadora · " + it).take(320)) }
        formats.drop(4).chunked(4).forEach {
            add(("Formato · " + it.joinToString(" · ")).take(320))
        }
    }
    if (lines.isEmpty()) return null
    return NativeAlbumEditorialFooterData(
        lines = lines,
        additionalLines = additionalLines,
        attributionText = display.attributionText,
        attributionUrl = display.attributionUrl,
        expiresAtMillis = verified.observation.fetchedAtMillis + DiscogsObservation.TTL_MILLIS,
    )
}

/** Decide whether the entire native metadata footer has useful visible content. */
internal fun nativeAlbumMetadataFooterHasContent(
    metadata: CanonicalAlbumFooterMetadata,
    editorial: NativeAlbumEditorialFooterData?,
): Boolean =
    metadata.date != null || metadata.summary != null ||
        metadata.genre != null || editorial != null || metadata.showAlbumAbout

/**
 * The MusicBrainz/Library overview remains useful when a Discogs URL
 * relationship is absent. A missing link is not a network failure, and it is
 * never proof that the album is missing from the Discogs database.
 */
internal data class NativeAlbumAboutOverview(
    val title: String,
    val facts: List<String>,
    val status: String?,
)

internal fun nativeAlbumAboutOverview(
    metadata: CanonicalAlbumFooterMetadata,
    editorial: NativeAlbumEditorialFooterData?,
    evidence: DiscogsEditionEnrichment?,
): NativeAlbumAboutOverview? {
    if (!metadata.showAlbumAbout) return null
    val facts = buildList {
        metadata.date?.takeIf(String::isNotBlank)?.let { add("Lançamento · $it") }
        metadata.summary?.takeIf(String::isNotBlank)?.let { add(it) }
        metadata.genre?.takeIf(String::isNotBlank)?.let { add("Gênero · $it") }
        metadata.editionFacts.filter(String::isNotBlank).take(10).forEach {
            add(it.take(320))
        }
        if (metadata.hasMusicBrainzEdition && metadata.editionFacts.isNotEmpty()) {
            add("Fonte dos detalhes da edição · MusicBrainz")
        }
    }.distinct().take(14)
    val status = when (evidence) {
        is DiscogsEditionEnrichment.Verified ->
            if (editorial != null) null else
                "Discogs · não há informações complementares válidas para exibir."
        DiscogsEditionEnrichment.NoLink ->
            "Discogs · sem vínculo Discogs confirmado para esta edição no MusicBrainz."
        DiscogsEditionEnrichment.Ambiguous ->
            "Discogs · há mais de um vínculo; esta edição não foi confirmada."
        DiscogsEditionEnrichment.Candidate ->
            "Discogs · ainda não existem evidências suficientes para confirmar esta edição."
        DiscogsEditionEnrichment.Conflict ->
            "Discogs · os dados publicados divergem desta edição; nada foi incorporado."
        DiscogsEditionEnrichment.Unavailable ->
            "Informações complementares temporariamente indisponíveis; os dados do álbum permanecem."
        DiscogsEditionEnrichment.Invalid ->
            "Discogs · dados ou identificador inválidos; nenhuma informação foi incorporada."
        DiscogsEditionEnrichment.Disabled ->
            "Discogs · provedor desativado."
        null -> "Ainda não há informações complementares verificadas no Discogs para esta edição."
    }
    return NativeAlbumAboutOverview("Sobre esta edição", facts, status)
}

/**
 * The parent footer must observe the same expiration as the editorial child.
 * Otherwise it retains an empty, padded wrapper after a Discogs-only edition
 * observation expires while the detail screen stays mounted.
 */
@Composable
internal fun rememberNativeAlbumEditorialFooter(
    evidence: DiscogsEditionEnrichment?,
): NativeAlbumEditorialFooterData? {
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
    return nativeAlbumEditorialFooter(evidence, checkedAtMillis)
}

/**
 * Keep attribution beside verified content, below the album's own metadata.
 * The caller owns expiry; this child never renders an empty placeholder.
 */
@Composable
internal fun NativeAlbumEditorialFooter(
    footer: NativeAlbumEditorialFooterData?,
    palette: ArtworkPalette,
    showTitle: Boolean = true,
) {
    if (footer == null) return
    var expanded by remember(footer.attributionUrl, footer.expiresAtMillis) {
        mutableStateOf(false)
    }
    val uriHandler = LocalUriHandler.current

    Spacer(Modifier.height(12.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("native-album-editorial-footer"),
    ) {
        if (showTitle) {
            Text(
                text = "Sobre esta edição",
                color = palette.onBackground,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        footer.lines.forEach { line ->
            Text(
                text = line,
                color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (footer.additionalLines.isNotEmpty()) {
            Text(
                text = if (expanded) "Ver menos" else "Ver mais",
                color = palette.onBackgroundVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .testTag("native-album-editorial-more"),
            )
            if (expanded) {
                footer.additionalLines.forEach { line ->
                    Text(
                        text = line,
                        color = palette.onBackgroundVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
