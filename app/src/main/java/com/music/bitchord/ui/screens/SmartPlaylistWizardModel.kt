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
    SmartConditionCategory(
        "advanced", "Outros · avançado",
        "Evidência técnica do catálogo, não qualidade visual ou reprodução",
        listOf(SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE),
    ),
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
    SmartPlaylistRuleField.TITLE ->
        "Escolha palavras do título e como elas devem corresponder."
    SmartPlaylistRuleField.ARTIST_DISPLAY ->
        "Compare o nome de artista exibido no catálogo."
    SmartPlaylistRuleField.RELEASE_TITLE ->
        "Compare o álbum ou lançamento ao qual a música está vinculada."
    SmartPlaylistRuleField.ALBUM_ARTIST ->
        "Considere apenas créditos de artista do álbum identificados."
    SmartPlaylistRuleField.TRACK_ARTIST ->
        "Use os créditos específicos da música, quando conhecidos."
    SmartPlaylistRuleField.TRACK_NUMBER ->
        "Compare a posição conhecida da música no lançamento."
    SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE ->
        "Verifica se há uma referência de capa no lançamento; não garante imagem disponível."
    SmartPlaylistRuleField.EXPLICIT_RATING ->
        "Compare a avaliação de uma a cinco estrelas, quando atribuída."
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING ->
        "Tempo desde que a nota desta música foi alterada no dispositivo."
    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS ->
        "Número de vezes em que a reprodução foi concluída."
    SmartPlaylistRuleField.EARLY_SKIPS ->
        "Quantidade de vezes em que a música foi pulada no início."
    SmartPlaylistRuleField.LATE_SKIPS ->
        "Quantidade de vezes em que a música foi pulada perto do fim."
    SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ->
        "Tempo desde a última reprodução concluída registrada."
    SmartPlaylistRuleField.RELEASE_YEAR ->
        "Ano da edição verificada no MusicBrainz, não necessariamente o lançamento original."
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED ->
        "Tempo desde a adição de um arquivo local com data confiável."
    SmartPlaylistRuleField.SOURCE_KIND ->
        "Compare músicas locais ou OpenSubsonic da conta ativa, com origem comprovada."
    SmartPlaylistRuleField.DURATION_MS ->
        "Informe a duração total da música em segundos."
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

/** Rule paths index Group children; traverse a NOT-wrapped Group transparently. */
internal fun smartRuleAtPath(root: SmartPlaylistRule, path: List<Int>): SmartPlaylistRule =
    path.fold(root) { current, index ->
        val group = when (current) {
            is SmartPlaylistRule.Group -> current
            is SmartPlaylistRule.Not -> current.child as? SmartPlaylistRule.Group
            else -> null
        } ?: throw IllegalArgumentException("Not a rule group")
        require(index in group.children.indices) { "Invalid condition index" }
        group.children[index]
    }

internal fun smartRuleReplaceAt(
    root: SmartPlaylistRule,
    path: List<Int>,
    replacement: SmartPlaylistRule,
): SmartPlaylistRule {
    if (path.isEmpty()) return replacement
    if (root is SmartPlaylistRule.Not) {
        return root.copy(child = smartRuleReplaceAt(root.child, path, replacement))
    }
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
    return smartRuleUpdateGroup(root, path) { group ->
        require(group.children.size < 64) { "Maximum group size reached" }
        group.copy(children = group.children + added)
    }
}

/**
 * Edits the selected group without unwrapping its NOT operator.
 */
internal fun smartRuleUpdateGroup(
    root: SmartPlaylistRule,
    path: List<Int>,
    update: (SmartPlaylistRule.Group) -> SmartPlaylistRule.Group,
): SmartPlaylistRule {
    val node = smartRuleAtPath(root, path)
    val group = when (node) {
        is SmartPlaylistRule.Group -> node
        is SmartPlaylistRule.Not -> node.child as? SmartPlaylistRule.Group
        else -> null
    } ?: throw IllegalArgumentException("Target must be a group")
    val changed = update(group)
    return smartRuleReplaceAt(root, path,
        if (node is SmartPlaylistRule.Not) node.copy(child = changed) else changed)
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
    return if (node is SmartPlaylistRule.Group ||
        (node is SmartPlaylistRule.Not && node.child is SmartPlaylistRule.Group)
    ) {
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
    return smartRuleUpdateGroup(root, parentPath) { group ->
        require(group.children.size > 1) { "A group requires at least one condition" }
        val index = path.last()
        require(index in group.children.indices) { "Invalid rule index" }
        group.copy(children = group.children.filterIndexed { i, _ -> i != index })
    }
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

/** An explicit global source is an ALL-condition, never a casual OR child. */
private fun SmartPlaylistRule.isGlobalSourceComparison(): Boolean =
    this is SmartPlaylistRule.Comparison &&
        field == SmartPlaylistRuleField.SOURCE_KIND &&
        operator == SmartPlaylistRuleOperator.EQUAL &&
        textValue in setOf("local", "opensubsonic")

internal fun smartRuleGlobalSource(rule: SmartPlaylistRule): String? =
    when (rule) {
        is SmartPlaylistRule.Group ->
            if (rule.join == SmartPlaylistRuleJoin.ALL) {
                (rule.children.firstOrNull { it.isGlobalSourceComparison() }
                    as? SmartPlaylistRule.Comparison)?.textValue
            } else null
        is SmartPlaylistRule.Comparison -> if (rule.isGlobalSourceComparison())
            rule.textValue else null
        else -> null
    }

/**
 * Null means no configured conditions remain. Existing ANY/NOT trees are
 * preserved under mandatory ALL. A direct SOURCE_KIND equality in the ALL
 * root is the editor's global constraint; nested rules stay untouched.
 */
internal fun smartRuleWithGlobalSource(
    rule: SmartPlaylistRule, source: String?, hasConfiguredRules: Boolean,
): SmartPlaylistRule? {
    require(source == null || source == "local" || source == "opensubsonic")
    val existing: SmartPlaylistRule? = if (!hasConfiguredRules) null else when (rule) {
        is SmartPlaylistRule.Group ->
            if (rule.join == SmartPlaylistRuleJoin.ALL) {
                val kept = rule.children.filterNot { it.isGlobalSourceComparison() }
                if (kept.isEmpty()) null else rule.copy(children = kept)
            } else rule
        else -> if (rule.isGlobalSourceComparison()) null else rule
    }
    if (source == null) return existing
    val selector = SmartPlaylistRule.Comparison(
        field = SmartPlaylistRuleField.SOURCE_KIND,
        operator = SmartPlaylistRuleOperator.EQUAL,
        textValue = source,
    )
    return if (existing == null) selector else if (existing is SmartPlaylistRule.Group &&
        existing.join == SmartPlaylistRuleJoin.ALL) {
        existing.copy(children = existing.children + selector)
    } else SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(existing, selector))
}

/**
 * Read-only description of SOURCE_KIND evidence requested by the rule tree.
 * This is NOT a selection of sourceRefs. In OR/NOT trees even an equality is
 * not a global constraint. Never infer authorization from this summary.
 */
internal fun smartRuleSourceSummary(rule: SmartPlaylistRule): String {
    val mentioned = linkedSetOf<String>()
    fun visit(node: SmartPlaylistRule) {
        when (node) {
            is SmartPlaylistRule.Group -> node.children.forEach(::visit)
            is SmartPlaylistRule.Not -> visit(node.child)
            is SmartPlaylistRule.Reference -> Unit // Resolved by the evaluator.
            is SmartPlaylistRule.Comparison -> if (node.field ==
                SmartPlaylistRuleField.SOURCE_KIND
            ) {
                mentioned += when (node.textValue?.lowercase(Locale.ROOT)) {
                    "local" -> "músicas locais"
                    "opensubsonic" -> "OpenSubsonic (conta ativa)"
                    else -> "origem não identificada"
                }
            }
        }
    }
    visit(rule)
    return if (mentioned.isEmpty()) {
        "Nenhuma condição de origem nesta playlist. O motor consulta a biblioteca canônica autorizada."
    } else {
        "As condições mencionam ${mentioned.joinToString(" e ")}. " +
            "A combinação E/OU/NÃO determina o resultado; isso não seleciona fontes separadamente."
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
