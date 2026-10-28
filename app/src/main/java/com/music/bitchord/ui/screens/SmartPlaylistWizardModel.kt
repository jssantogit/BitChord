package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.allowedOperators
import java.text.Normalizer
import java.util.Locale

/**
 * Capability-driven catalog for the 05/06 condition screens in the approved
 * docs/design/playlists. Only V2 rules implemented by the evaluator appear.
 * A future folder, codec, favorite-date or genre predicate MUST NOT be offered
 * as selectable before canonical evidence and serialization are implemented.
 */
internal data class SmartConditionCategory(
    val id: String,
    val title: String,
    val description: String,
    val fields: List<SmartPlaylistRuleField>,
)

internal val smartConditionCategories = listOf(
    SmartConditionCategory("music", "Músicas e metadados", "Título, artista, álbum e créditos", listOf(
        SmartPlaylistRuleField.TITLE, SmartPlaylistRuleField.ARTIST_DISPLAY,
        SmartPlaylistRuleField.RELEASE_TITLE, SmartPlaylistRuleField.ALBUM_ARTIST,
        SmartPlaylistRuleField.TRACK_ARTIST, SmartPlaylistRuleField.TRACK_NUMBER,
        SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE,
    )),
    SmartConditionCategory("ratings", "Avaliações", "Notas em estrelas e data da nota local", listOf(
        SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING,
    )),
    SmartConditionCategory("history", "Reprodução e histórico", "Conclusões, pulos e última reprodução", listOf(
        SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleField.EARLY_SKIPS,
        SmartPlaylistRuleField.LATE_SKIPS, SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE,
    )),
    SmartConditionCategory("dates", "Datas e lançamentos", "Ano da edição e adição de arquivo local", listOf(
        SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
    )),
    SmartConditionCategory("library", "Biblioteca e origens", "Fontes comprovadas do catálogo", listOf(
        SmartPlaylistRuleField.SOURCE_KIND,
    )),
    SmartConditionCategory("audio", "Áudio", "Duração comprovada das faixas", listOf(
        SmartPlaylistRuleField.DURATION_MS,
    )),
)

internal fun smartConditionLabel(field: SmartPlaylistRuleField): String = when (field) {
    SmartPlaylistRuleField.TITLE -> "Título da música"
    SmartPlaylistRuleField.ARTIST_DISPLAY -> "Artista"
    SmartPlaylistRuleField.RELEASE_TITLE -> "Álbum ou lançamento"
    SmartPlaylistRuleField.ALBUM_ARTIST -> "Artista do álbum"
    SmartPlaylistRuleField.TRACK_ARTIST -> "Créditos do artista da faixa"
    SmartPlaylistRuleField.TRACK_NUMBER -> "Número da faixa"
    SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE -> "Álbum com referência de capa"
    SmartPlaylistRuleField.EXPLICIT_RATING -> "Avaliação em estrelas"
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING -> "Dias desde alteração da avaliação"
    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> "Reproduções concluídas"
    SmartPlaylistRuleField.EARLY_SKIPS -> "Pulos antecipados"
    SmartPlaylistRuleField.LATE_SKIPS -> "Pulos tardios"
    SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE -> "Dias desde última reprodução concluída"
    SmartPlaylistRuleField.RELEASE_YEAR -> "Ano de lançamento da edição"
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED -> "Dias desde adição ao dispositivo"
    SmartPlaylistRuleField.SOURCE_KIND -> "Origem disponível"
    SmartPlaylistRuleField.DURATION_MS -> "Duração da música"
}

internal fun smartConditionDescription(field: SmartPlaylistRuleField): String = when (field) {
    SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE ->
        "Verifica somente se existe vínculo com uma referência de capa; não comprova que a imagem carregou."
    SmartPlaylistRuleField.RELEASE_YEAR ->
        "Ano de uma edição identificada pelo MusicBrainz; não é necessariamente o lançamento original."
    SmartPlaylistRuleField.SOURCE_KIND ->
        "Local ou OpenSubsonic na conta ativa, apenas quando a origem é comprovada."
    SmartPlaylistRuleField.DURATION_MS ->
        "Informe a duração em segundos. O motor compara milissegundos."
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED ->
        "Disponível para arquivos com data MediaStore confiável."
    else -> "A condição usa somente metadados verificáveis da biblioteca atual."
}

private fun smartConditionNormalize(input: String): String =
    Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .trim()

internal fun smartConditionSearch(
    query: String,
    category: SmartConditionCategory? = null,
): List<SmartPlaylistRuleField> {
    val term = smartConditionNormalize(query)
    return (category?.fields ?: smartConditionCategories.flatMap { it.fields }).filter { field ->
        val cat = smartConditionCategories.first { field in it.fields }
        val aliases = when (field) {
            SmartPlaylistRuleField.SOURCE_KIND -> "fonte catalogo navidrome local biblioteca"
            SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE -> "capa artwork imagem album"
            SmartPlaylistRuleField.DURATION_MS -> "duracao segundos tempo audio"
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> "plays execucoes escutas"
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED -> "adicao dispositivo mediastore"
            else -> ""
        }
        term.isEmpty() || smartConditionNormalize(
            smartConditionLabel(field) + " " + cat.title + " " + aliases
        ).contains(term)
    }
}

/** AST paths index Group children only; Not is edited as one atomic node. */
internal fun smartRuleAtPath(root: SmartPlaylistRule, path: List<Int>): SmartPlaylistRule =
    path.fold(root) { current, index ->
        val group = current as? SmartPlaylistRule.Group
            ?: throw IllegalArgumentException("Not a rule group")
        require(index in group.children.indices) { "Invalid condition index" }
        group.children[index]
    }

internal fun smartRuleReplaceAt(
    root: SmartPlaylistRule,
    path: List<Int>,
    replacement: SmartPlaylistRule,
): SmartPlaylistRule {
    if (path.isEmpty()) return replacement
    val group = root as? SmartPlaylistRule.Group
        ?: throw IllegalArgumentException("Not a rule group")
    val index = path.first()
    require(index in group.children.indices) { "Invalid rule index" }
    return group.copy(children = group.children.mapIndexed { i, child ->
        if (i == index) smartRuleReplaceAt(child, path.drop(1), replacement) else child
    })
}

internal fun smartRuleAppendAt(
    root: SmartPlaylistRule,
    path: List<Int>,
    added: SmartPlaylistRule,
): SmartPlaylistRule {
    val group = smartRuleAtPath(root, path) as? SmartPlaylistRule.Group
        ?: throw IllegalArgumentException("Target must be a group")
    require(group.children.size < 64) { "Maximum group size reached" }
    return smartRuleReplaceAt(root, path, group.copy(children = group.children + added))
}

/**
 * A legacy V2 root may contain comparisons directly, rather than a nested
 * group. Promoting one node into an ALL group keeps that node intact and lets
 * the user add another condition without making a misleading sibling group.
 */
internal fun smartRuleAddConditionAt(
    root: SmartPlaylistRule,
    path: List<Int>,
    added: SmartPlaylistRule,
): SmartPlaylistRule {
    val node = smartRuleAtPath(root, path)
    return if (node is SmartPlaylistRule.Group) {
        smartRuleAppendAt(root, path, added)
    } else {
        smartRuleReplaceAt(root, path, SmartPlaylistRule.Group(
            SmartPlaylistRuleJoin.ALL, listOf(node, added),
        ))
    }
}

internal fun smartRuleRemoveAt(root: SmartPlaylistRule, path: List<Int>): SmartPlaylistRule {
    require(path.isNotEmpty()) { "Cannot remove the root condition" }
    val parentPath = path.dropLast(1)
    val group = smartRuleAtPath(root, parentPath) as? SmartPlaylistRule.Group
        ?: throw IllegalArgumentException("Parent must be a group")
    require(group.children.size > 1) { "A group requires at least one condition" }
    val index = path.last()
    require(index in group.children.indices) { "Invalid rule index" }
    return smartRuleReplaceAt(root, parentPath,
        group.copy(children = group.children.filterIndexed { i, _ -> i != index }))
}

internal fun smartRuleNewComparison(field: SmartPlaylistRuleField): SmartPlaylistRule.Comparison =
    when (field) {
        SmartPlaylistRuleField.SOURCE_KIND -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL, textValue = "local",
        )
        SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        else -> {
            val op = if (SmartPlaylistRuleOperator.IS_PRESENT in field.allowedOperators())
                SmartPlaylistRuleOperator.IS_PRESENT
            else field.allowedOperators().first()
            SmartPlaylistRule.Comparison(field, op)
        }
    }

/** Structure-preserving: a quick summary never decides the actual boolean evaluation. */
internal fun smartRuleLeafCount(rule: SmartPlaylistRule): Int = when (rule) {
    is SmartPlaylistRule.Comparison, is SmartPlaylistRule.Reference -> 1
    is SmartPlaylistRule.Not -> smartRuleLeafCount(rule.child)
    is SmartPlaylistRule.Group -> rule.children.sumOf(::smartRuleLeafCount)
}

internal fun smartRuleJoinLabel(join: SmartPlaylistRuleJoin): String =
    if (join == SmartPlaylistRuleJoin.ALL) "Todas as condições" else "Qualquer condição"
