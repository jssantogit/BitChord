package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.listening.SmartPlaylistV2Sort
import com.music.bitchord.data.listening.allowedOperators
import com.music.bitchord.data.listening.isTextField
import com.music.bitchord.data.listening.moveChild
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.smart_collection_batch_play
import com.music.bitchord.sharedui.resources.smart_collection_batch_queue
import org.jetbrains.compose.resources.stringResource

// Respect DESIGN.md: SF Pro Display and semantic ColorScheme from BitChordTheme,
// the shared 16.dp PAGE_GUTTER, and locally rounded neutral cards.
// Do not invent new global color or shape tokens for this flow.
private enum class WizardStep { IDENTITY, RULES, GROUP, CATEGORIES, FIELDS, EDITOR, REVIEW }

private fun operatorUiLabel(operator: SmartPlaylistRuleOperator): String = when (operator) {
    SmartPlaylistRuleOperator.EQUAL -> "É igual a"
    SmartPlaylistRuleOperator.NOT_EQUAL -> "É diferente de"
    SmartPlaylistRuleOperator.GREATER_THAN -> "É maior que"
    SmartPlaylistRuleOperator.GREATER_OR_EQUAL -> "Pelo menos"
    SmartPlaylistRuleOperator.LESS_THAN -> "É menor que"
    SmartPlaylistRuleOperator.LESS_OR_EQUAL -> "No máximo"
    SmartPlaylistRuleOperator.IS_PRESENT -> "Possui valor conhecido"
    SmartPlaylistRuleOperator.IS_MISSING -> "Sem valor confirmado"
    SmartPlaylistRuleOperator.CONTAINS -> "Contém"
    SmartPlaylistRuleOperator.DOES_NOT_CONTAIN -> "Não contém"
    SmartPlaylistRuleOperator.STARTS_WITH -> "Começa com"
    SmartPlaylistRuleOperator.ENDS_WITH -> "Termina com"
}

private fun sortUiLabel(sort: SmartPlaylistV2Sort): String = when (sort) {
    SmartPlaylistV2Sort.RATING_DESC -> "Mais bem avaliadas"
    SmartPlaylistV2Sort.RATING_ASC -> "Menos bem avaliadas"
    SmartPlaylistV2Sort.COMPLETIONS_DESC -> "Mais reproduzidas"
    SmartPlaylistV2Sort.COMPLETIONS_ASC -> "Menos reproduzidas"
    SmartPlaylistV2Sort.DURATION_DESC -> "Mais longas"
    SmartPlaylistV2Sort.DURATION_ASC -> "Mais curtas"
    SmartPlaylistV2Sort.LAST_AUDIBLE_DESC -> "Ouvidas recentemente"
    SmartPlaylistV2Sort.LAST_AUDIBLE_ASC -> "Ouvidas há mais tempo"
    SmartPlaylistV2Sort.RECORDING_ID_ASC -> "Identificador canônico"
    SmartPlaylistV2Sort.STABLE_RANDOM -> "Aleatória estável"
    SmartPlaylistV2Sort.STABLE_RANDOM_ALBUMS -> "Álbuns em ordem aleatória"
    SmartPlaylistV2Sort.TITLE_ASC -> "Título: A a Z"
    SmartPlaylistV2Sort.TITLE_DESC -> "Título: Z a A"
    SmartPlaylistV2Sort.ARTIST_ASC -> "Artista: A a Z"
    SmartPlaylistV2Sort.ARTIST_DESC -> "Artista: Z a A"
    SmartPlaylistV2Sort.LOCAL_RATING_NEWEST -> "Avaliações mais recentes"
    SmartPlaylistV2Sort.LOCAL_RATING_OLDEST -> "Avaliações mais antigas"
}

private fun ruleUiSummary(
    rule: SmartPlaylistRule,
    names: Map<String, String>,
): String = when (rule) {
    is SmartPlaylistRule.Comparison ->
        smartConditionLabel(rule.field) + " · " + operatorUiLabel(rule.operator) +
            when {
                rule.textValue != null -> " · " + rule.textValue
                rule.value != null -> " · " +
                    if (rule.field == SmartPlaylistRuleField.DURATION_MS)
                        "${rule.value / 1000.0} s"
                    else rule.value.toString().removeSuffix(".0")
                else -> ""
            }
    is SmartPlaylistRule.Not -> "NÃO: " + ruleUiSummary(rule.child, names)
    is SmartPlaylistRule.Reference -> "Playlist: " +
        (names[rule.playlistId] ?: "Referência indisponível")
    is SmartPlaylistRule.Group ->
        "${smartRuleLeafCount(rule)} condições · " + smartRuleJoinLabel(rule.join)
}

@Composable
private fun WizardCard(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PAGE_GUTTER),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
            content?.invoke()
        }
    }
}

@Composable
private fun WizardHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WizardJoinPicker(
    value: SmartPlaylistRuleJoin,
    onChange: (SmartPlaylistRuleJoin) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(
            selected = value == SmartPlaylistRuleJoin.ALL,
            onClick = { onChange(SmartPlaylistRuleJoin.ALL) },
            label = { Text("Todas (E)") },
        )
        FilterChip(
            selected = value == SmartPlaylistRuleJoin.ANY,
            onClick = { onChange(SmartPlaylistRuleJoin.ANY) },
            label = { Text("Qualquer (OU)") },
        )
    }
    Text(
        if (value == SmartPlaylistRuleJoin.ALL)
            "A música deve satisfazer todas as condições deste nível."
        else "Basta a música satisfazer pelo menos uma condição deste nível.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> WizardChoiceDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    onChosen: (T) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(options) { option ->
                    Text(
                        label(option),
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onChosen(option); onClose() }
                            .padding(vertical = 15.dp, horizontal = 6.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
    )
}

@Composable
internal fun SmartPlaylistV2WizardUi(
    name: String,
    rule: SmartPlaylistRule,
    sort: SmartPlaylistV2Sort,
    limit: String,
    seed: String,
    preview: List<V2DisplayRow>?,
    loading: Boolean,
    error: String?,
    editingId: String?,
    editingFilterId: String?,
    savedDefinitions: List<SavedSmartPlaylistV2Definition>,
    onNameChange: (String) -> Unit,
    onRuleChange: (SmartPlaylistRule) -> Unit,
    onSortChange: (SmartPlaylistV2Sort) -> Unit,
    onLimitChange: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onSaveFilter: () -> Unit,
    onPreviewAction: ((SmartPlaylistV2Query, String?, CanonicalCollectionIntent) -> Unit)?,
    currentPreviewQuery: SmartPlaylistV2Query?,
    onManage: () -> Unit,
    onBack: () -> Unit,
) {
    var page by remember { mutableStateOf(WizardStep.IDENTITY) }
    var selectedPath by remember { mutableStateOf<List<Int>>(emptyList()) }
    var targetGroupPath by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(smartConditionCategories.first()) }
    var search by remember { mutableStateOf("") }
    var editingPath by remember { mutableStateOf<List<Int>?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(smartRuleNewComparison(SmartPlaylistRuleField.TITLE)) }
    var negated by remember { mutableStateOf(false) }
    var returnAfterEditor by remember { mutableStateOf(WizardStep.GROUP) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val names = savedDefinitions.associate { it.id to it.name }

    fun openField(field: SmartPlaylistRuleField) {
        draft = smartRuleNewComparison(field)
        negated = false
        editingPath = null
        returnAfterEditor = WizardStep.FIELDS
        page = WizardStep.EDITOR
    }
    fun editAt(path: List<Int>, back: WizardStep) {
        val old = smartRuleAtPath(rule, path)
        val core = if (old is SmartPlaylistRule.Not) old.child else old
        if (core is SmartPlaylistRule.Comparison) {
            draft = core
            negated = old is SmartPlaylistRule.Not
            editingPath = path
            returnAfterEditor = back
            page = WizardStep.EDITOR
        } else {
            selectedPath = path
            page = WizardStep.GROUP
        }
    }
    fun back() {
        page = when (page) {
            WizardStep.IDENTITY -> {
                if (name.isNotBlank() || editingId != null || editingFilterId != null) {
                    confirmDiscard = true
                } else {
                    onBack()
                }
                WizardStep.IDENTITY
            }
            WizardStep.RULES -> WizardStep.IDENTITY
            WizardStep.GROUP -> WizardStep.RULES
            WizardStep.CATEGORIES -> if (addingGroup || targetGroupPath.isEmpty())
                WizardStep.RULES else WizardStep.GROUP
            WizardStep.FIELDS -> WizardStep.CATEGORIES
            WizardStep.EDITOR -> returnAfterEditor
            WizardStep.REVIEW -> WizardStep.RULES
        }
    }
    BackHandler { back() }

    val header = when (page) {
        WizardStep.IDENTITY -> "Nova playlist"
        WizardStep.RULES -> "Regras da playlist"
        WizardStep.GROUP -> "Editar grupo de regras"
        WizardStep.CATEGORIES -> "Adicionar condição"
        WizardStep.FIELDS -> selectedCategory.title
        WizardStep.EDITOR -> smartConditionLabel(draft.field)
        WizardStep.REVIEW -> "Revisar e criar"
    }
    val stepNote = when (page) {
        WizardStep.IDENTITY -> "ETAPA 1 DE 3"
        WizardStep.RULES, WizardStep.GROUP, WizardStep.CATEGORIES,
        WizardStep.FIELDS, WizardStep.EDITOR -> "ETAPA 2 DE 3"
        WizardStep.REVIEW -> "ETAPA 3 DE 3"
    }
    // Every stage has its own scroll origin. Reusing one LazyColumn state
    // caused the condition catalog to open midway down after editing a
    // scrolled group; the first category and header were not composed.
    val pageListState = remember(page, selectedPath, selectedCategory.id) {
        LazyListState()
    }
    val canContinue = name.trim().isNotEmpty() && name.length <= 80
    val canReview = canContinue && runCatching {
        SmartPlaylistV2Query(rule, sort, limit.toInt(), seed.toInt())
    }.isSuccess

    // Do not mix the editor with the app's own scrollable library screen.
    // The caller hosts this in a full-width Dialog over the library tab bar.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = ::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    Text("Voltar")
                }
                Text(stepNote, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 15.dp))
                if (page == WizardStep.IDENTITY) {
                    TextButton(onClick = onManage) { Text("Minhas playlists") }
                } else {
                    Spacer(Modifier.padding(30.dp))
                }
            }
            LazyColumn(
                state = pageListState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .testTag("smart-playlist-wizard-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                item {
                    WizardHeading(header, when (page) {
                        WizardStep.IDENTITY -> "Dê um nome e escolha como a biblioteca será organizada."
                        WizardStep.RULES -> "Organize condições em grupos. As opções avançadas ficam dentro de cada grupo."
                        WizardStep.GROUP -> "Ajuste as condições, a combinação e a ordem deste grupo."
                        WizardStep.CATEGORIES -> "Explore os tipos de condição disponíveis ou pesquise diretamente."
                        WizardStep.FIELDS -> "Escolha uma condição compatível com o catálogo atual."
                        WizardStep.EDITOR -> smartConditionDescription(draft.field)
                        WizardStep.REVIEW -> "Confira fontes, regras e músicas antes de salvar."
                    })
                }

                when (page) {
                    WizardStep.IDENTITY -> {
                        item {
                            OutlinedTextField(name, onNameChange, label = { Text("Nome da playlist") },
                                supportingText = { Text("Obrigatório · até 80 caracteres") },
                                isError = name.length > 80,
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        item {
                            WizardCard(
                                "Fontes do catálogo",
                                "Biblioteca canônica disponível neste dispositivo e na conta remota ativa.",
                            ) {
                                Text(
                                    smartRuleSourceSummary(rule),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        item {
                            WizardCard("Playlist inteligente",
                                "Seleciona músicas por regras e atualiza o resultado quando os dados relevantes mudam.")
                        }
                        item {
                            Text("Capas, descrição e destino de armazenamento exigem suporte próprio " +
                                "antes de entrarem neste fluxo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    WizardStep.RULES -> {
                        val root = rule as? SmartPlaylistRule.Group
                        item {
                            WizardCard("Combinação entre grupos",
                                "Como os blocos abaixo se relacionam") {
                                if (root != null) WizardJoinPicker(root.join) {
                                    onRuleChange(root.copy(join = it))
                                } else Text("A playlist importada tem uma condição principal.")
                            }
                        }
                        val children = root?.children ?: listOf(rule)
                        itemsIndexed(children, key = { index, _ -> "group-$index" }) { index, child ->
                            val path = if (root == null) emptyList() else listOf(index)
                            WizardCard(
                                "Grupo ${index + 1}",
                                ruleUiSummary(child, names),
                                onClick = {
                                    selectedPath = path
                                    page = WizardStep.GROUP
                                },
                            ) {
                                Text("${smartRuleLeafCount(child)} condição(ões)",
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = {
                                    targetGroupPath = emptyList()
                                    addingGroup = true
                                    search = ""
                                    page = WizardStep.CATEGORIES
                                },
                                enabled = root != null && root.children.size < 64,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Text(" Adicionar grupo")
                            }
                        }
                        item {
                            WizardCard("Limite e ordenação",
                                "Aplicados depois de avaliar todas as condições") {
                                OutlinedButton(onClick = { picker = "sort" },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(sortUiLabel(sort))
                                }
                                OutlinedTextField(limit, onLimitChange,
                                    label = { Text("Máximo de músicas") },
                                    supportingText = { Text("De 1 a 10.000") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                                if (sort == SmartPlaylistV2Sort.STABLE_RANDOM ||
                                    sort == SmartPlaylistV2Sort.STABLE_RANDOM_ALBUMS) {
                                    OutlinedTextField(seed, onSeedChange,
                                        label = { Text("Semente de ordem aleatória") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                                }
                            }
                        }
                    }
                    WizardStep.GROUP -> {
                        val selected = runCatching { smartRuleAtPath(rule, selectedPath) }.getOrNull()
                        val core = if (selected is SmartPlaylistRule.Not) selected.child else selected
                        val group = core as? SmartPlaylistRule.Group
                        if (group != null) {
                            item {
                                WizardCard("Combinar condições",
                                    "Escolha a lógica somente deste grupo") {
                                    WizardJoinPicker(group.join) {
                                        val nextJoin = it
                                        onRuleChange(smartRuleUpdateGroup(rule, selectedPath) { current ->
                                            current.copy(join = nextJoin)
                                        })
                                    }
                                }
                            }
                            itemsIndexed(group.children) { i, child ->
                                val path = selectedPath + i
                                WizardCard("Condição ${i + 1}", ruleUiSummary(child, names),
                                    onClick = { editAt(path, WizardStep.GROUP) }) {
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = {
                                            onRuleChange(smartRuleUpdateGroup(rule, selectedPath) {
                                                it.moveChild(i, (i - 1).coerceAtLeast(0))
                                            })
                                        }, enabled = i > 0) { Text("Subir") }
                                        TextButton(onClick = {
                                            onRuleChange(smartRuleUpdateGroup(rule, selectedPath) {
                                                it.moveChild(i, (i + 1).coerceAtMost(group.children.lastIndex))
                                            })
                                        }, enabled = i < group.children.lastIndex) { Text("Descer") }
                                        TextButton(onClick = {
                                            if (group.children.size > 1)
                                                onRuleChange(smartRuleRemoveAt(rule, path))
                                        }, enabled = group.children.size > 1) { Text("Excluir") }
                                    }
                                }
                            }
                            item {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = group.children.size < 64,
                                    onClick = {
                                        targetGroupPath = selectedPath
                                        addingGroup = false
                                        search = ""
                                        page = WizardStep.CATEGORIES
                                    },
                                ) { Text("+ Adicionar condição") }
                            }
                            item {
                                OutlinedButton(onClick = {
                                    onRuleChange(smartRuleAddConditionAt(rule, selectedPath,
                                        SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL,
                                            listOf(smartRuleNewComparison(SmartPlaylistRuleField.AUDIBLE_COMPLETIONS)))))
                                }, enabled = group.children.size < 64,
                                    modifier = Modifier.fillMaxWidth()) { Text("+ Grupo aninhado") }
                            }
                            if (savedDefinitions.isNotEmpty()) item {
                                TextButton(onClick = { picker = "reference" }) {
                                    Text("+ Usar playlist salva como condição")
                                }
                            }
                            if (selected is SmartPlaylistRule.Not) item {
                                TextButton(onClick = {
                                    onRuleChange(smartRuleReplaceAt(rule, selectedPath, selected.child))
                                }) { Text("Retirar NÃO deste grupo") }
                            }
                        } else if (selected != null) {
                            item {
                                WizardCard("Condição deste grupo", ruleUiSummary(selected, names),
                                    onClick = { editAt(selectedPath, WizardStep.GROUP) })
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        targetGroupPath = selectedPath
                                        addingGroup = false
                                        search = ""
                                        page = WizardStep.CATEGORIES
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("+ Adicionar condição") }
                            }
                            if (selected is SmartPlaylistRule.Not) item {
                                TextButton(onClick = {
                                    onRuleChange(smartRuleReplaceAt(rule, selectedPath, selected.child))
                                }) { Text("Remover negação") }
                            }
                            if (core is SmartPlaylistRule.Reference) item {
                                TextButton(onClick = { picker = "replaceReference" }) {
                                    Text("Trocar playlist referenciada")
                                }
                            }
                        }
                    }
                    WizardStep.CATEGORIES -> {
                        item {
                            OutlinedTextField(search, { search = it },
                                label = { Text("Buscar condições") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        if (search.isNotBlank()) {
                            val matches = smartConditionSearch(search)
                            if (matches.isEmpty()) item {
                                Text("Nenhuma condição implementada corresponde à busca.")
                            }
                            items(matches) { field ->
                                WizardCard(smartConditionLabel(field),
                                    smartConditionDescription(field),
                                    onClick = { openField(field) })
                            }
                        } else {
                            items(smartConditionCategories) { category ->
                                WizardCard(category.title, category.description, onClick = {
                                    selectedCategory = category
                                    page = WizardStep.FIELDS
                                })
                            }
                        }
                    }
                    WizardStep.FIELDS -> {
                        item {
                            OutlinedTextField(search, { search = it },
                                label = { Text("Buscar nesta categoria") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                        val matches = smartConditionSearch(search, selectedCategory)
                        items(matches) { field ->
                            WizardCard(smartConditionLabel(field),
                                smartConditionDescription(field),
                                onClick = { openField(field) })
                        }
                        if (matches.isEmpty()) item { Text("Nenhuma condição encontrada.") }
                        if (selectedCategory.id == "library") item {
                            Text("Pasta de origem, disponibilidade offline e caminho de arquivo " +
                                "ainda não possuem o vínculo autorizado exigido pelo motor. " +
                                "Não serão oferecidos como regras funcionais.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    WizardStep.EDITOR -> {
                        item {
                            WizardCard("Condição", smartConditionLabel(draft.field)) {
                                OutlinedButton(onClick = { picker = "operator" },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(operatorUiLabel(draft.operator))
                                }
                                val presence = draft.operator == SmartPlaylistRuleOperator.IS_PRESENT ||
                                    draft.operator == SmartPlaylistRuleOperator.IS_MISSING
                                if (!presence) {
                                    if (draft.field == SmartPlaylistRuleField.SOURCE_KIND) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            FilterChip(selected = draft.textValue == "local",
                                                onClick = {
                                                    draft = draft.copy(value = null, textValue = "local")
                                                }, label = { Text("Dispositivo") })
                                            FilterChip(selected = draft.textValue == "opensubsonic",
                                                onClick = {
                                                    draft = draft.copy(value = null,
                                                        textValue = "opensubsonic")
                                                }, label = { Text("OpenSubsonic") })
                                        }
                                    } else if (draft.field.isTextField()) {
                                        OutlinedTextField(
                                            draft.textValue.orEmpty(),
                                            { draft = draft.copy(textValue = it, value = null) },
                                            label = { Text("Texto a comparar") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                    } else {
                                        var numericText by remember(draft.field, editingPath) {
                                            val shown = if (draft.field == SmartPlaylistRuleField.DURATION_MS)
                                                draft.value?.div(1000.0) else draft.value
                                            mutableStateOf(shown?.toString()?.removeSuffix(".0").orEmpty())
                                        }
                                        OutlinedTextField(
                                            numericText,
                                            { raw ->
                                                numericText = raw
                                                val number = raw.replace(',', '.').toDoubleOrNull()
                                                draft = draft.copy(value = number?.let {
                                                    if (draft.field == SmartPlaylistRuleField.DURATION_MS)
                                                        it * 1000.0 else it
                                                }, textValue = null)
                                            },
                                            label = { Text(if (draft.field == SmartPlaylistRuleField.DURATION_MS)
                                                "Duração (segundos)" else "Valor numérico") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            WizardCard("Inverter condição",
                                "Exclui músicas que correspondem; dados desconhecidos continuam excluídos da prévia.") {
                                FilterChip(selected = negated, onClick = { negated = !negated },
                                    label = { Text(if (negated) "NÃO ativado" else "Adicionar NÃO") })
                            }
                        }
                    }
                    WizardStep.REVIEW -> {
                        item {
                            WizardCard(name.trim().ifEmpty { "Sem nome" },
                                "Playlist inteligente · ${smartRuleLeafCount(rule)} condições")
                        }
                        item {
                            WizardCard("Fontes do catálogo",
                                "Biblioteca canônica da conta ativa e arquivos locais disponíveis") {
                                Text(smartRuleSourceSummary(rule),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            WizardCard("Regras aplicadas",
                                ruleUiSummary(rule, names),
                                onClick = { page = WizardStep.RULES })
                        }
                        item {
                            WizardCard("Prévia real",
                                when (preview) {
                                    null -> "Ainda não calculada para estas regras"
                                    else -> "${preview.size} músicas após ordenação e limite"
                                }) {
                                OutlinedButton(onClick = onPreview, enabled = !loading) {
                                    Text(if (preview == null) "Calcular prévia" else "Atualizar prévia")
                                }
                                Text("Contagem após o limite, não o total de correspondências.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (preview != null &&
                                    preview.size > CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE) {
                                    Text("Play/Fila aceitam no máximo " +
                                        "${CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE} músicas.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (onPreviewAction != null && currentPreviewQuery != null &&
                                    preview != null && preview.isNotEmpty() &&
                                    preview.size <= CanonicalCollectionIntentPlanner.MAX_INPUT_SIZE) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = {
                                            onPreviewAction(currentPreviewQuery, editingId,
                                                CanonicalCollectionIntent.PLAY_NOW)
                                        }) { Text(stringResource(Res.string.smart_collection_batch_play)) }
                                        TextButton(onClick = {
                                            onPreviewAction(currentPreviewQuery, editingId,
                                                CanonicalCollectionIntent.ADD_TO_QUEUE)
                                        }) { Text(stringResource(Res.string.smart_collection_batch_queue)) }
                                    }
                                }
                            }
                        }
                        items(preview.orEmpty().take(24), key = { it.id }) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // A neutral artwork placeholder, not an invented
                                // cover or an unverified release image association.
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp))
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(row.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(row.artist, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if ((preview?.size ?: 0) > 24) item {
                            Text("Exibindo as primeiras 24 músicas da prévia.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (error != null) item {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (loading) item { Text("Consultando biblioteca…") }
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 12.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (page) {
                    WizardStep.IDENTITY -> Button(
                        onClick = { page = WizardStep.RULES },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canContinue,
                    ) { Text("Continuar") }
                    WizardStep.RULES -> Button(
                        onClick = { page = WizardStep.REVIEW },
                        enabled = canReview, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Revisar playlist") }
                    WizardStep.GROUP -> Button(
                        onClick = { page = WizardStep.RULES },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Salvar grupo") }
                    WizardStep.EDITOR -> {
                        val presence = draft.operator == SmartPlaylistRuleOperator.IS_PRESENT ||
                            draft.operator == SmartPlaylistRuleOperator.IS_MISSING
                        val valueOk = presence || when {
                            draft.field.isTextField() -> !draft.textValue.isNullOrBlank()
                            else -> draft.value?.isFinite() == true
                        }
                        Button(
                            onClick = {
                                val added: SmartPlaylistRule =
                                    if (negated) SmartPlaylistRule.Not(draft) else draft
                                val updated = if (editingPath != null) {
                                    smartRuleReplaceAt(rule, editingPath!!, added)
                                } else if (addingGroup) {
                                    smartRuleAppendAt(rule, emptyList(),
                                        SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL,
                                            listOf(added)))
                                } else smartRuleAddConditionAt(rule, targetGroupPath, added)
                                onRuleChange(updated)
                                page = if (addingGroup || targetGroupPath.isEmpty())
                                    WizardStep.RULES else WizardStep.GROUP
                                addingGroup = false
                                editingPath = null
                            },
                            enabled = valueOk,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (editingPath != null) "Salvar condição" else "Adicionar condição") }
                    }
                    WizardStep.REVIEW -> {
                        Button(
                            onClick = onSave,
                            enabled = canReview && !loading &&
                                currentPreviewQuery != null && preview != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (editingId != null) "Salvar alterações" else "Criar playlist")
                        }
                        if (preview == null) {
                            Text("Calcule a prévia antes de criar a playlist.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onSaveFilter, enabled = canReview && !loading,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (editingFilterId == null) "Salvar como filtro reutilizável"
                                else "Atualizar filtro reutilizável")
                        }
                    }
                    WizardStep.CATEGORIES, WizardStep.FIELDS -> Unit
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Descartar alterações?") },
            text = { Text("O rascunho desta playlist não será salvo.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text("Descartar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("Continuar editando")
                }
            },
        )
    }

    if (picker == "sort") {
        WizardChoiceDialog("Ordenar músicas", SmartPlaylistV2Sort.entries, ::sortUiLabel,
            onChosen = onSortChange, onClose = { picker = null })
    }
    if (picker == "operator") {
        WizardChoiceDialog("Comparar por", draft.field.allowedOperators(), ::operatorUiLabel,
            onChosen = { op ->
                val presence = op == SmartPlaylistRuleOperator.IS_PRESENT ||
                    op == SmartPlaylistRuleOperator.IS_MISSING
                draft = draft.copy(
                    operator = op,
                    value = if (presence || draft.field.isTextField()) null else draft.value ?:
                        when (draft.field) {
                            SmartPlaylistRuleField.DURATION_MS -> 180_000.0
                            SmartPlaylistRuleField.RELEASE_YEAR -> 2026.0
                            SmartPlaylistRuleField.EXPLICIT_RATING -> 3.0
                            else -> 1.0
                        },
                    textValue = if (presence || !draft.field.isTextField()) null
                        else draft.textValue ?: "",
                )
            }, onClose = { picker = null })
    }
    if (picker == "reference" || picker == "replaceReference") {
        WizardChoiceDialog("Playlist salva", savedDefinitions, { it.name },
            onChosen = { saved ->
                if (picker == "replaceReference") {
                    val selected = smartRuleAtPath(rule, selectedPath)
                    val changed: SmartPlaylistRule =
                        SmartPlaylistRule.Reference(saved.id)
                    onRuleChange(smartRuleReplaceAt(rule, selectedPath,
                        if (selected is SmartPlaylistRule.Not) SmartPlaylistRule.Not(changed)
                        else changed))
                } else {
                    onRuleChange(smartRuleAppendAt(rule, selectedPath,
                        SmartPlaylistRule.Reference(saved.id)))
                }
            }, onClose = { picker = null })
    }
}
