package com.music.bitchord.ui.screens

import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.listening.allowedOperators
import com.music.bitchord.data.listening.isTextField
import java.text.Normalizer
import java.util.Locale

/**
 * Capability-driven catalog for the 05/06 condition screens in the approved
 * docs/design/playlists. Only V2 rules implemented by the evaluator appear.
 * Any future predicate MUST NOT become selectable before canonical evidence,
 * evaluation and serialization are implemented. Local MediaStore fields below
 * already have dedicated evaluator branches and are not inferred from servers.
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
        SmartPlaylistRuleField.GENRE, SmartPlaylistRuleField.LOCAL_GENRE,
        SmartPlaylistRuleField.MOOD,
        SmartPlaylistRuleField.BPM, SmartPlaylistRuleField.STYLE,
        SmartPlaylistRuleField.COMPOSER,
        SmartPlaylistRuleField.DISC_NUMBER, SmartPlaylistRuleField.RELEASE_GROUP_TYPE,
        SmartPlaylistRuleField.ARTIST_COUNTRY,
    )),
    SmartConditionCategory("ratings", "Avaliações", "Notas em estrelas e data da nota local", listOf(
        SmartPlaylistRuleField.EXPLICIT_RATING, SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING,
    )),
    SmartConditionCategory("history", "Reprodução e histórico", "Conclusões, pulos e última reprodução", listOf(
        SmartPlaylistRuleField.AUDIBLE_COMPLETIONS, SmartPlaylistRuleField.EARLY_SKIPS,
        SmartPlaylistRuleField.LATE_SKIPS, SmartPlaylistRuleField.TOTAL_SKIPS,
        SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE,
        SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP,
    )),
    SmartConditionCategory("dates", "Datas e lançamentos", "Ano da edição e adição de arquivo local", listOf(
        SmartPlaylistRuleField.RELEASE_YEAR, SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR,
        SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED,
    )),
    SmartConditionCategory("library", "Biblioteca e pastas", "Origem das músicas e localização dos arquivos", listOf(
        SmartPlaylistRuleField.SOURCE_KIND,
        SmartPlaylistRuleField.FILE_PATH, SmartPlaylistRuleField.FOLDER_PATH,
        SmartPlaylistRuleField.LOCAL_FOLDER, SmartPlaylistRuleField.LOCAL_FILE_PATH,
    )),
    SmartConditionCategory("audio", "Áudio", "Duração comprovada das faixas", listOf(
        SmartPlaylistRuleField.DURATION_MS, SmartPlaylistRuleField.LOCAL_FILE_EXTENSION,
        SmartPlaylistRuleField.LOCAL_BITRATE_KBPS,
    )),
    SmartConditionCategory(
        "advanced", "Capas de álbuns",
        "Dados de imagem dos lançamentos cadastrados",
        listOf(SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE),
    ),
)

/** Search-style sub-tabs partition fields without adding section headings to lists. */
internal data class SmartConditionFieldTab(
    val key: String,
    val label: String,
    val fields: List<SmartPlaylistRuleField>,
)

internal fun smartConditionFieldTabs(category: SmartConditionCategory): List<SmartConditionFieldTab> {
    val all = SmartConditionFieldTab("all", "Todos", category.fields)
    return when (category.id) {
        "music" -> {
            val identity = setOf(
                SmartPlaylistRuleField.TITLE, SmartPlaylistRuleField.ARTIST_DISPLAY,
                SmartPlaylistRuleField.RELEASE_TITLE,
            )
            val credits = setOf(
                SmartPlaylistRuleField.ALBUM_ARTIST, SmartPlaylistRuleField.TRACK_ARTIST,
                SmartPlaylistRuleField.COMPOSER, SmartPlaylistRuleField.ARTIST_COUNTRY,
            )
            listOf(
                all,
                SmartConditionFieldTab("identity", "Identidade",
                    category.fields.filter { it in identity }),
                SmartConditionFieldTab("credits", "Créditos",
                    category.fields.filter { it in credits }),
                SmartConditionFieldTab("organization", "Organização",
                    category.fields.filter { it !in identity && it !in credits }),
            )
        }
        "history" -> {
            val plays = setOf(
                SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE,
            )
            listOf(
                all,
                SmartConditionFieldTab("plays", "Reproduções",
                    category.fields.filter { it in plays }),
                SmartConditionFieldTab("skips", "Pulos",
                    category.fields.filter { it !in plays }),
            )
        }
        "library" -> listOf(
            all,
            SmartConditionFieldTab("sources", "Origem",
                category.fields.filter { it == SmartPlaylistRuleField.SOURCE_KIND }),
            SmartConditionFieldTab("paths", "Arquivos e pastas",
                category.fields.filter { it != SmartPlaylistRuleField.SOURCE_KIND }),
        )
        // One- to three-field categories need no extra tab row:
        // the extra navigation would compete with the entire list.
        else -> emptyList()
    }
}

internal fun smartConditionLabel(field: SmartPlaylistRuleField): String = when (field) {
    SmartPlaylistRuleField.TITLE -> "Título da música"
    SmartPlaylistRuleField.ARTIST_DISPLAY -> "Artista"
    SmartPlaylistRuleField.RELEASE_TITLE -> "Nome do álbum ou lançamento"
    SmartPlaylistRuleField.ALBUM_ARTIST -> "Artista do álbum"
    SmartPlaylistRuleField.TRACK_ARTIST -> "Créditos do artista da faixa"
    SmartPlaylistRuleField.GENRE -> "Gênero musical"
    SmartPlaylistRuleField.MOOD -> "Humor"
    SmartPlaylistRuleField.BPM -> "Ritmo (BPM)"
    SmartPlaylistRuleField.STYLE -> "Estilo musical"
    SmartPlaylistRuleField.COMPOSER -> "Compositor"
    SmartPlaylistRuleField.FILE_PATH -> "Caminho do arquivo"
    SmartPlaylistRuleField.FOLDER_PATH -> "Pasta do arquivo"
    SmartPlaylistRuleField.TRACK_NUMBER -> "Número da faixa"
    SmartPlaylistRuleField.DISC_NUMBER -> "Número do disco"
    SmartPlaylistRuleField.RELEASE_GROUP_TYPE -> "Classificação do lançamento (álbum, single, EP)"
    SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE -> "Capa cadastrada no álbum"
    SmartPlaylistRuleField.EXPLICIT_RATING -> "Avaliação em estrelas"
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING -> "Dias desde alteração da avaliação"
    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> "Reproduções concluídas"
    SmartPlaylistRuleField.EARLY_SKIPS -> "Pulos antecipados"
    SmartPlaylistRuleField.LATE_SKIPS -> "Pulos tardios"
    SmartPlaylistRuleField.TOTAL_SKIPS -> "Número de pulos"
    SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP -> "Dias desde a última pulada"
    SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE -> "Dias desde última reprodução concluída"
    SmartPlaylistRuleField.RELEASE_YEAR -> "Ano de lançamento da edição"
    SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR -> "Ano do lançamento original"
    SmartPlaylistRuleField.ARTIST_COUNTRY -> "País do artista"
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED -> "Dias desde adição ao dispositivo"
    SmartPlaylistRuleField.SOURCE_KIND -> "Origem disponível"
    SmartPlaylistRuleField.LOCAL_FOLDER -> "Pasta da música local"
    SmartPlaylistRuleField.LOCAL_FILE_PATH -> "Caminho do arquivo local"
    SmartPlaylistRuleField.LOCAL_FILE_EXTENSION -> "Extensão do arquivo local"
    SmartPlaylistRuleField.LOCAL_GENRE -> "Gênero do arquivo local"
    SmartPlaylistRuleField.LOCAL_BITRATE_KBPS -> "Bitrate do arquivo local (kbps)"
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
    SmartPlaylistRuleField.GENRE ->
        "Filtra gêneros informados pelas fontes disponíveis, locais ou remotas."
    SmartPlaylistRuleField.MOOD ->
        "Filtra o humor registrado nos metadados locais ou remotos; valores ausentes não são inventados."
    SmartPlaylistRuleField.BPM ->
        "Compara o ritmo informado em batidas por minuto, sem supor BPM zero para tags ausentes."
    SmartPlaylistRuleField.STYLE ->
        "Filtra o estilo informado pela biblioteca local ou fonte remota, sem adivinhar tags ausentes."
    SmartPlaylistRuleField.COMPOSER ->
        "Compositores informados pelos créditos locais ou pela fonte ativa."
    SmartPlaylistRuleField.FILE_PATH ->
        "Compara o caminho comprovado de um arquivo local ou remoto, quando disponível."
    SmartPlaylistRuleField.FOLDER_PATH ->
        "Compara a pasta que contém o arquivo, mantendo o escopo da origem."
    SmartPlaylistRuleField.TRACK_NUMBER ->
        "Compare a posição conhecida da música no lançamento."
    SmartPlaylistRuleField.DISC_NUMBER ->
        "Número do disco da edição, cadastrado no MusicBrainz."
    SmartPlaylistRuleField.RELEASE_GROUP_TYPE ->
        "Compare Single, Album ou EP conforme o tipo oficial do lançamento, não o título."
    SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE ->
        "Seleciona músicas cujo álbum tem uma imagem cadastrada. Não verifica se a capa carrega."
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
    SmartPlaylistRuleField.TOTAL_SKIPS ->
        "Soma dos pulos antecipados e tardios registrados neste dispositivo; não inclui interrupções."
    SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP ->
        "Dias desde o último pulo antecipado ou tardio registrado neste dispositivo."
    SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ->
        "Tempo desde a última reprodução concluída registrada."
    SmartPlaylistRuleField.RELEASE_YEAR ->
        "Ano da edição verificada no MusicBrainz, não necessariamente o lançamento original."
    SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR ->
        "Ano original verificado no MusicBrainz para o grupo de lançamentos deste álbum."
    SmartPlaylistRuleField.ARTIST_COUNTRY ->
        "País do artista cadastrado no MusicBrainz (use o código de duas letras: BR, KR, US)."
    SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED ->
        "Tempo desde a adição de um arquivo local com data confiável."
    SmartPlaylistRuleField.SOURCE_KIND ->
        "Compare músicas locais ou OpenSubsonic da conta ativa, com origem comprovada."
    SmartPlaylistRuleField.LOCAL_FOLDER ->
        "Compare a pasta real do arquivo MediaStore (ex.: Music/Kpop); outros servidores são desconhecidos."
    SmartPlaylistRuleField.LOCAL_FILE_PATH ->
        "Compare o caminho real do arquivo local; caminhos de servidores não são inferidos."
    SmartPlaylistRuleField.LOCAL_FILE_EXTENSION ->
        "Compare a extensão do arquivo local (FLAC, MP3, M4A); não equivale ao codec."
    SmartPlaylistRuleField.LOCAL_GENRE ->
        "Digite o gênero para encontrar músicas locais identificadas pelo MediaStore (ex.: Pop)."
    SmartPlaylistRuleField.LOCAL_BITRATE_KBPS ->
        "Taxa de bits lida do arquivo local, em kbps decimais; não é taxa de transcodificação."
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
            SmartPlaylistRuleField.COMPOSER -> "compositor autoria composicao"
            SmartPlaylistRuleField.FOLDER_PATH -> "pasta de origem diretorio folder"
            SmartPlaylistRuleField.FILE_PATH -> "caminho completo arquivo filepath"
            SmartPlaylistRuleField.LOCAL_FOLDER -> "pasta diretorio local caminho mediastore"
            SmartPlaylistRuleField.LOCAL_FILE_PATH -> "arquivo caminho pasta filepath path mediastore"
            SmartPlaylistRuleField.LOCAL_FILE_EXTENSION -> "formato extensão extensao mp3 flac m4a"
            SmartPlaylistRuleField.DISC_NUMBER -> "disco numero disc medium faixa"
            SmartPlaylistRuleField.RELEASE_GROUP_TYPE -> "single álbum album ep tipo lançamento"
            SmartPlaylistRuleField.LOCAL_GENRE -> "genero gênero musica tag"
            SmartPlaylistRuleField.ARTIST_COUNTRY -> "país origem artista musicbrainz country"
            SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR -> "ano original lançamento data"
            SmartPlaylistRuleField.LOCAL_BITRATE_KBPS -> "bitrate kbps taxa bits áudio"
            SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE -> "capa artwork imagem album"
            SmartPlaylistRuleField.DURATION_MS -> "duracao segundos tempo audio"
            SmartPlaylistRuleField.TOTAL_SKIPS -> "pulos puladas skips total quantidade"
            SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP -> "ultima último pulada skip data dias"
            SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> "plays execucoes escutas"
            SmartPlaylistRuleField.DAYS_SINCE_LOCAL_FILE_ADDED -> "adicao dispositivo mediastore"
            else -> ""
        }
        term.isEmpty() || smartConditionNormalize(
            smartConditionLabel(field) + " " + cat.title + " " + aliases
        ).contains(term)
    }
}

/**
 * A draft's Add/Save action uses the same typed validation contract as the
 * persisted query. "Nonblank" and "finite" alone accept impossible years,
 * negative counters and text beyond the persisted limit.
 */
internal fun smartConditionDraftValid(draft: SmartPlaylistRule.Comparison): Boolean =
    runCatching { SmartPlaylistV2Query(rules = draft) }.isSuccess

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

/**
 * Remove any selected rule, including a named group or the last condition.
 * Empty ancestor groups are pruned recursively, never persisted as invalid
 * rule trees. Null is a deliberate editor empty state (the builder owns its
 * non-persisted placeholder); no empty V2 query is saved.
 */
internal fun smartRuleRemovePruningEmptyGroups(
    root: SmartPlaylistRule,
    path: List<Int>,
): SmartPlaylistRule? {
    if (path.isEmpty()) return null
    fun remove(node: SmartPlaylistRule, indices: List<Int>): SmartPlaylistRule? =
        when (node) {
            is SmartPlaylistRule.Not ->
                remove(node.child, indices)?.let { SmartPlaylistRule.Not(it) }
            is SmartPlaylistRule.Group -> {
                val index = indices.first()
                require(index in node.children.indices) { "Invalid rule index" }
                val kept = node.children.mapIndexedNotNull { i, child ->
                    when {
                        i != index -> child
                        indices.size == 1 -> null
                        else -> remove(child, indices.drop(1))
                    }
                }
                if (kept.isEmpty()) null else node.copy(children = kept)
            }
            else -> throw IllegalArgumentException("Target has no child rules")
        }
    return remove(root, path)
}

internal fun smartRuleNewComparison(field: SmartPlaylistRuleField): SmartPlaylistRule.Comparison =
    when (field) {
        SmartPlaylistRuleField.SOURCE_KIND -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL, textValue = "local",
        )
        SmartPlaylistRuleField.RELEASE_ARTWORK_REFERENCE -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.IS_PRESENT,
        )
        SmartPlaylistRuleField.LOCAL_GENRE -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.CONTAINS, textValue = "",
        )
        SmartPlaylistRuleField.ARTIST_COUNTRY -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL, textValue = "",
        )
        SmartPlaylistRuleField.RELEASE_GROUP_TYPE -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL, textValue = "",
        )
        SmartPlaylistRuleField.TOTAL_SKIPS,
        SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL,
        )
        SmartPlaylistRuleField.DISC_NUMBER -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL,
        )
        SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR -> SmartPlaylistRule.Comparison(
            field, SmartPlaylistRuleOperator.EQUAL,
        )
        else -> {
            val op = if (field.isTextField() &&
                SmartPlaylistRuleOperator.CONTAINS in field.allowedOperators()
            ) SmartPlaylistRuleOperator.CONTAINS
            else SmartPlaylistRuleOperator.EQUAL
            SmartPlaylistRule.Comparison(field, op,
                textValue = if (field.isTextField()) "" else null)
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
