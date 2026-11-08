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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

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
        labels.firstOrNull()?.let { add(("Gravadora: " + it).take(320)) }
        display.editorialKind?.let {
            add("Tipo segundo Discogs: ${it.displayName}")
        }
        if (display.editorialKind == null) {
            formats.take(4).takeIf(List<String>::isNotEmpty)?.let {
                add(("Formato: " + it.joinToString(" · ") { fmt -> nativeEditionDisplayFormat(fmt) }).take(320))
            }
        }
        display.country?.takeIf { it.isNotBlank() }?.let {
            nativeEditionDisplayCountry(it)?.let { country -> add(("País: " + country).take(320)) }
        }
        display.editionYear?.let { add("Ano: " + it) }
    }
    val additionalLines = buildList {
        // Preserve carrier/format details under Ver mais when a release kind is
        // more useful to show in the compact summary.
        if (display.editorialKind != null && formats.isNotEmpty()) {
            add(("Formato: " + formats.take(4).joinToString(" · ") {
                nativeEditionDisplayFormat(it)
            }).take(320))
        }
        labels.drop(1).forEach { add(("Gravadora: " + it).take(320)) }
        formats.drop(4).chunked(4).forEach {
            add(("Formato: " + it.joinToString(" · ") { fmt -> nativeEditionDisplayFormat(fmt) }).take(320))
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


/** Human-readable descriptions of edition facts, leaving source provenance intact. */
internal fun nativeAlbumEditionDisplayFact(raw: String): String? {
    val parts = raw.split(" · ", limit = 2)
    if (parts.size != 2) return raw.trim().takeIf(String::isNotBlank)?.take(320)
    val value = parts[1].trim().takeIf(String::isNotBlank) ?: return null
    return when (parts[0]) {
        "Gravadora" -> "Gravadora: ${value.take(250)}"
        "Formato" -> "Formato: ${value.split(" · ").joinToString(" · ") { nativeEditionDisplayFormat(it) }.take(260)}"
        "País da edição" -> nativeEditionDisplayCountry(value)?.let { "País: $it" }
        "Data da edição", "Lançamento" -> "Data: ${nativeEditionDisplayDate(value)}"
        "Estado da publicação" -> "Estado da publicação: ${value.take(100)}"
        "Gênero" -> "Gênero: ${value.take(160)}"
        else -> "${parts[0].trim()}: ${value.take(250)}"
    }
}

internal fun nativeEditionDisplayFormat(format: String): String = when (format.trim()) {
    "Digital Media" -> "Mídia digital"
    "Vinyl" -> "Vinil"
    "Cassette" -> "Fita cassete"
    else -> format.trim()
}

internal fun nativeEditionDisplayCountry(raw: String): String? {
    val code = raw.trim().uppercase(Locale.ROOT)
    return when (code) {
        "XW", "WORLDWIDE" -> "Mundial"
        "XE", "EUROPE" -> "Europa"
        "KR", "SOUTH KOREA" -> "Coreia do Sul"
        "BR", "BRAZIL" -> "Brasil"
        else -> {
            val countryCode = if (code.matches(Regex("[A-Z]{2}"))) code else {
                Locale.getISOCountries().firstOrNull { iso ->
                    Locale("", iso).getDisplayCountry(Locale.ENGLISH)
                        .equals(raw.trim(), ignoreCase = true)
                }
            }
            countryCode?.takeIf { it in Locale.getISOCountries() }?.let { iso ->
                Locale("", iso).getDisplayCountry(Locale.forLanguageTag("pt-BR"))
                    .takeIf { it.isNotBlank() && !it.equals(iso, ignoreCase = true) }
                    ?.replaceFirstChar { it.titlecase(Locale.forLanguageTag("pt-BR")) }
            } ?: raw.trim().takeIf { it.isNotBlank() && !it.matches(Regex("[A-Z]{2}")) }
                ?.take(80)
        }
    }
}

internal fun nativeEditionDisplayDate(raw: String): String {
    val ptBr = Locale.forLanguageTag("pt-BR")
    return try {
        when {
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(raw) ->
                LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' uuuu", ptBr))
            Regex("\\d{4}-\\d{2}").matches(raw) ->
                YearMonth.parse(raw).format(DateTimeFormatter.ofPattern("MMMM 'de' uuuu", ptBr))
            else -> raw.take(64)
        }
    } catch (_: DateTimeParseException) {
        raw.take(64)
    }
}

/** Decide whether the entire native metadata footer has useful visible content. */
internal fun nativeAlbumMetadataFooterHasContent(
    metadata: CanonicalAlbumFooterMetadata,
    editorial: NativeAlbumEditorialFooterData?,
    showAlbumEditionMetadata: Boolean = true,
): Boolean =
    metadata.date != null || metadata.summary != null ||
        metadata.genre != null ||
        (showAlbumEditionMetadata && (editorial != null || metadata.showAlbumAbout))

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
        metadata.summary?.takeIf(String::isNotBlank)?.let { add(it) }
        // The edition-specific date replaces the generic release year.
        if (metadata.editionFacts.none { it.startsWith("Data da edição · ") }) {
            metadata.date?.takeIf(String::isNotBlank)?.let { add("Data: ${nativeEditionDisplayDate(it)}") }
        }
        metadata.genre?.takeIf(String::isNotBlank)?.let { add("Gênero: $it") }
        metadata.editionFacts.filter(String::isNotBlank).take(10).forEach {
            nativeAlbumEditionDisplayFact(it)?.let(::add)
        }
        if (metadata.hasMusicBrainzEdition && metadata.editionFacts.isNotEmpty()) {
            add("Fonte: MusicBrainz")
        }
    }.distinct().take(14)
    val status = when (evidence) {
        is DiscogsEditionEnrichment.Verified ->
            if (editorial != null) null else
                "Discogs · não há informações complementares válidas para exibir."
        DiscogsEditionEnrichment.NoLink -> null
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
        null -> null
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
