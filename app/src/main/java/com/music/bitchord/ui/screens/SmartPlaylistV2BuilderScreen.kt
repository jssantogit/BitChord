package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.SavedSmartPlaylistDefinition
import com.music.bitchord.data.listening.SmartPlaylistDefinitionRepository
import com.music.bitchord.data.listening.toV2Copy
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.allowedOperators
import com.music.bitchord.data.listening.isTextField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.listening.SmartPlaylistV2Sort
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.smart_collection_batch_play
import com.music.bitchord.sharedui.resources.smart_collection_batch_queue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.util.UUID

private fun newRule(): SmartPlaylistRule = SmartPlaylistRule.Comparison(
    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
    SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
    1.0,
)

private fun fieldLabel(value: SmartPlaylistRuleField): String = when (value) {
    SmartPlaylistRuleField.EXPLICIT_RATING -> "Avaliação (estrelas)"
    SmartPlaylistRuleField.AUDIBLE_COMPLETIONS -> "Reproduções concluídas"
    SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE -> "Dias desde última reprodução concluída"
    SmartPlaylistRuleField.DURATION_MS -> "Duração (milissegundos)"
    SmartPlaylistRuleField.TITLE -> "Título da faixa"
    SmartPlaylistRuleField.ARTIST_DISPLAY -> "Artista (catálogo)"
    SmartPlaylistRuleField.EARLY_SKIPS -> "Pulos antecipados"
    SmartPlaylistRuleField.LATE_SKIPS -> "Pulos tardios"
    SmartPlaylistRuleField.RELEASE_TITLE -> "Álbum / lançamento (catálogo)"
    SmartPlaylistRuleField.SOURCE_KIND -> "Origem disponível"
}

private fun operatorLabel(value: SmartPlaylistRuleOperator): String = when (value) {
    SmartPlaylistRuleOperator.EQUAL -> "Igual a"
    SmartPlaylistRuleOperator.NOT_EQUAL -> "Diferente de"
    SmartPlaylistRuleOperator.GREATER_THAN -> "Maior que"
    SmartPlaylistRuleOperator.GREATER_OR_EQUAL -> "Pelo menos"
    SmartPlaylistRuleOperator.LESS_THAN -> "Menor que"
    SmartPlaylistRuleOperator.LESS_OR_EQUAL -> "No máximo"
    SmartPlaylistRuleOperator.IS_PRESENT -> "Possui valor"
    SmartPlaylistRuleOperator.IS_MISSING -> "Sem valor"
    SmartPlaylistRuleOperator.CONTAINS -> "Contém"
    SmartPlaylistRuleOperator.DOES_NOT_CONTAIN -> "Não contém"
    SmartPlaylistRuleOperator.STARTS_WITH -> "Começa com"
    SmartPlaylistRuleOperator.ENDS_WITH -> "Termina com"
}

@Composable
private fun <T> V2Option(
    name: String,
    value: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(name, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }) { Text(label(value)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

/**
 * Immutable recursive editor: a child update rebuilds only the traversed branch
 * of the expression tree. No hidden text-to-identity heuristics or rule flattening.
 */
@Composable
private fun V2RuleNode(
    rule: SmartPlaylistRule,
    depth: Int,
    onChange: (SmartPlaylistRule) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(4) * 8).dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (rule) {
            is SmartPlaylistRule.Group -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V2Option(
                        "Grupo", rule.join, SmartPlaylistRuleJoin.entries,
                        label = { if (it == SmartPlaylistRuleJoin.ALL) "Todas (E)" else "Qualquer (OU)" },
                        onSelect = { onChange(rule.copy(join = it)) },
                    )
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remover grupo") }
                }
                rule.children.forEachIndexed { index, child ->
                    androidx.compose.runtime.key(index) {
                        V2RuleNode(
                            child, depth + 1,
                            onChange = { updated ->
                                onChange(rule.copy(children = rule.children.toMutableList().apply {
                                    this[index] = updated
                                }))
                            },
                            onRemove = if (rule.children.size > 1) {
                                { onChange(rule.copy(children = rule.children.filterIndexed { i, _ -> i != index })) }
                            } else null,
                        )
                    }
                }
                if (depth < 7 && rule.children.size < 64) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            onChange(rule.copy(children = rule.children + newRule()))
                        }) { Text("+ Regra") }
                        TextButton(onClick = {
                            onChange(rule.copy(children = rule.children +
                                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ANY, listOf(newRule()))))
                        }) { Text("+ Grupo") }
                    }
                }
            }
            is SmartPlaylistRule.Not -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NÃO", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { onChange(rule.child) }) { Text("Desfazer negação") }
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remover") }
                }
                V2RuleNode(rule.child, depth + 1, onChange = { onChange(SmartPlaylistRule.Not(it)) })
            }
            is SmartPlaylistRule.Comparison -> {
                V2Option(
                    "Campo", rule.field, SmartPlaylistRuleField.entries,
                    ::fieldLabel,
                ) { newField ->
                    val op = rule.operator.takeIf { it in newField.allowedOperators() }
                        ?: if (newField == SmartPlaylistRuleField.SOURCE_KIND)
                            SmartPlaylistRuleOperator.EQUAL
                        else if (newField.isTextField()) SmartPlaylistRuleOperator.CONTAINS
                        else SmartPlaylistRuleOperator.GREATER_OR_EQUAL
                    val presence = op == SmartPlaylistRuleOperator.IS_PRESENT ||
                        op == SmartPlaylistRuleOperator.IS_MISSING
                    onChange(SmartPlaylistRule.Comparison(
                        field = newField,
                        operator = op,
                        value = if (!newField.isTextField() && !presence) rule.value ?: 1.0 else null,
                        textValue = if (newField == SmartPlaylistRuleField.SOURCE_KIND) "local"
                            else if (newField.isTextField() && !presence)
                                rule.textValue ?: "" else null,
                    ))
                }
                V2Option(
                    "Condição", rule.operator, rule.field.allowedOperators(),
                    ::operatorLabel,
                ) { op ->
                    val presence = op == SmartPlaylistRuleOperator.IS_PRESENT ||
                        op == SmartPlaylistRuleOperator.IS_MISSING
                    onChange(rule.copy(
                        operator = op,
                        value = if (presence || rule.field.isTextField()) null
                            else rule.value ?: 1.0,
                        textValue = if (presence || !rule.field.isTextField()) null
                            else rule.textValue ?: "",
                    ))
                }
                if (rule.operator != SmartPlaylistRuleOperator.IS_PRESENT &&
                    rule.operator != SmartPlaylistRuleOperator.IS_MISSING
                ) {
                    if (rule.field == SmartPlaylistRuleField.SOURCE_KIND) {
                        V2Option(
                            "Origem", rule.textValue ?: "local",
                            listOf("local", "opensubsonic"),
                            label = {
                                if (it == "local") "Músicas locais"
                                else "OpenSubsonic (Navidrome)"
                            },
                            onSelect = { onChange(rule.copy(value = null, textValue = it)) },
                        )
                    } else if (rule.field.isTextField()) {
                        var entered by remember(rule.field, rule.operator) {
                            mutableStateOf(rule.textValue.orEmpty())
                        }
                        OutlinedTextField(
                            value = entered,
                            onValueChange = { raw ->
                                entered = raw
                                onChange(rule.copy(value = null, textValue = raw))
                            },
                            label = { Text("Texto a procurar") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        var entered by remember(rule.field, rule.operator) {
                            mutableStateOf(rule.value?.toString().orEmpty())
                        }
                        OutlinedTextField(
                            value = entered,
                            onValueChange = { raw ->
                                entered = raw
                                onChange(rule.copy(value = raw.toDoubleOrNull(), textValue = null))
                            },
                            label = { Text("Valor") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (depth < 7) TextButton(onClick = {
                        onChange(SmartPlaylistRule.Not(rule))
                    }) { Text("Negar") }
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remover") }
                }
            }
        }
    }
}

private data class V2DisplayRow(val id: String, val title: String, val artist: String)

/**
 * Initial Android V2 editing surface. Legacy playlists remain available via
 * "Editor clássico" until their definitions have been migrated safely.
 */
@Composable
internal fun SmartPlaylistV2BuilderScreen(
    onBack: () -> Unit,
    onClassic: () -> Unit,
    onSavedAction: ((String, CanonicalCollectionIntent) -> Unit)? = null,
    onPreviewAction: ((SmartPlaylistV2Query, CanonicalCollectionIntent) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) {
        SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
    }
    val classicRepo = remember(context) {
        SmartPlaylistDefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).definitionDao(),
        )
    }
    val catalogDatabase = remember(context) { CanonicalCatalogDatabase.get(context) }
    val listeningDatabase = remember(context) { ListeningEventDatabase.get(context) }
    val accountPersistence = remember(context) { SharedPrefsOpenSubsonicAccountPersistence(context) }
    val catalog = remember(catalogDatabase) { catalogDatabase.catalogDao() }
    val evaluator = remember(context) { persistentSmartPlaylistPreview(context) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var rule by remember { mutableStateOf<SmartPlaylistRule>(
        SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(newRule())),
    ) }
    var sort by remember { mutableStateOf(SmartPlaylistV2Sort.RATING_DESC) }
    var limit by remember { mutableStateOf("100") }
    var seed by remember { mutableStateOf("0") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<V2DisplayRow>?>(null) }
    var previewQuery by remember { mutableStateOf<SmartPlaylistV2Query?>(null) }
    var savedFailure by remember { mutableStateOf(false) }
    var legacyFailure by remember { mutableStateOf(false) }
    var editorEpoch by remember { mutableStateOf(0) }
    val saved by produceState(initialValue = emptyList<SavedSmartPlaylistV2Definition>(), key1 = repo) {
        try {
            repo.observe().collect { value = it; savedFailure = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            savedFailure = true
        }
    }

    val legacySaved by produceState(initialValue = emptyList<SavedSmartPlaylistDefinition>(), key1 = classicRepo) {
        try {
            classicRepo.observe().collect { value = it; legacyFailure = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            legacyFailure = true
        }
    }

    fun reset() {
        editorEpoch++
        editingId = null
        name = ""
        rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(newRule()))
        sort = SmartPlaylistV2Sort.RATING_DESC
        limit = "100"
        seed = "0"
        preview = null
        previewQuery = null
        error = null
    }
    fun query() = SmartPlaylistV2Query(
        rules = rule,
        sort = sort,
        limit = limit.toIntOrNull() ?: throw IllegalArgumentException("Limite inválido"),
        randomSeed = seed.toIntOrNull() ?: throw IllegalArgumentException("Semente inválida"),
    )

    suspend fun loadPreviewRows(parsed: SmartPlaylistV2Query): List<V2DisplayRow> =
        withContext(Dispatchers.IO) {
            val matched = evaluator.preview(parsed)
            val known = matched.map { it.recordingId }.chunked(400)
                .flatMap { catalog.recordingsByIds(it) }
                .associateBy { it.id }
            matched.mapNotNull { item ->
                val info = known[item.recordingId] ?: return@mapNotNull null
                V2DisplayRow(info.id, info.title, info.artistDisplay)
            }
        }

    // Preview observation is active only while the app is STARTED. Compose
    // effects may outlive an Android onStop, so scope both account watching and
    // age ticks to ProcessLifecycleOwner rather than relying on composition.
    LaunchedEffect(previewQuery, catalogDatabase, listeningDatabase, accountPersistence) {
        val active = previewQuery ?: return@LaunchedEffect
        var firstForegroundActivation = true
        ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Account changes or a relative-day boundary while the app was
            // stopped are not emitted by a fresh subscription; refresh once on
            // every foreground RESUME, but not immediately after manual Preview.
            if (firstForegroundActivation) {
                firstForegroundActivation = false
            } else {
                try {
                    val rows = loadPreviewRows(active)
                    if (previewQuery == active) {
                        preview = rows
                        error = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (previewQuery == active) {
                        preview = null
                        error = "Não foi possível atualizar a prévia ao voltar ao app."
                    }
                }
            }
            merge(
                smartPlaylistEvidenceChanges(catalogDatabase, listeningDatabase)
                    .drop(1), // Room emits an initial snapshot on each subscription.
                smartPlaylistAccountChanges(accountPersistence.observe()),
                smartPlaylistRelativeAgeChanges(active),
            ).collectLatest {
                try {
                    val rows = loadPreviewRows(active)
                    if (previewQuery == active) {
                        preview = rows
                        error = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (previewQuery == active) {
                        preview = null
                        error = "A biblioteca mudou. Não foi possível atualizar a prévia."
                    }
                }
            }
        }
    }

    ListeningLibraryPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "v2-header") {
                SmartPlaylistBuilderHeader(onBack)
                Text("Regras avançadas", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClassic) { Text("Editor clássico / playlists anteriores") }
            }
            item(key = "v2-editor") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nome da playlist") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    androidx.compose.runtime.key(editingId, editorEpoch) {
                        V2RuleNode(rule, 0, onChange = { rule = it; preview = null; previewQuery = null })
                    }
                    V2Option("Ordenar por", sort, SmartPlaylistV2Sort.entries, { it.name.replace('_', ' ') }) {
                        sort = it
                        preview = null
                        previewQuery = null
                    }
                    OutlinedTextField(
                        value = limit, onValueChange = { limit = it; preview = null; previewQuery = null },
                        label = { Text("Máximo de músicas (1–10.000)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    if (sort == SmartPlaylistV2Sort.STABLE_RANDOM) OutlinedTextField(
                        value = seed, onValueChange = { seed = it; preview = null; previewQuery = null },
                        label = { Text("Semente da ordem aleatória") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (loading) return@Button
                            loading = true
                            scope.launch {
                                try {
                                    val parsed = query()
                                    val id = editingId ?: UUID.randomUUID().toString()
                                    withContext(Dispatchers.IO) {
                                        repo.save(SavedSmartPlaylistV2Definition(id, name, parsed))
                                    }
                                    reset()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "Não foi possível salvar as regras"
                                } finally { loading = false }
                            }
                        }, enabled = !loading) { Text("Salvar") }
                        TextButton(onClick = {
                            if (loading) return@TextButton
                            loading = true
                            scope.launch {
                                try {
                                    val parsed = query()
                                    val rows = loadPreviewRows(parsed)
                                    preview = rows
                                    previewQuery = parsed
                                    error = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "Não foi possível avaliar a playlist"
                                    preview = null
                                    previewQuery = null
                                } finally { loading = false }
                            }
                        }, enabled = !loading) { Text("Prévia") }
                        TextButton(onClick = { reset() }, enabled = !loading) { Text("Limpar") }
                    }
                    if (loading) Text("Carregando…")
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            if (preview != null) {
                item(key = "v2-preview-header") {
                    Text("Prévia • " + preview!!.size + " músicas",
                        style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Atualização automática", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            val active = previewQuery ?: return@TextButton
                            if (loading) return@TextButton
                            loading = true
                            scope.launch {
                                try {
                                    val refreshed = loadPreviewRows(active)
                                    if (previewQuery == active) {
                                        preview = refreshed
                                        error = null
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (previewQuery == active) {
                                        preview = null
                                        error = "Não foi possível atualizar a prévia"
                                    }
                                } finally { loading = false }
                            }
                        }, enabled = !loading && previewQuery != null) { Text("Atualizar") }
                    }
                    if (preview!!.size > 100) {
                        Text("A execução da fila ainda é limitada a 100 músicas; a prévia pode ser maior.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (onPreviewAction != null && preview!!.isNotEmpty() && preview!!.size <= 100) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                previewQuery?.let { onPreviewAction(it, CanonicalCollectionIntent.PLAY_NOW) }
                            }, enabled = !loading && previewQuery != null) {
                                Text(stringResource(Res.string.smart_collection_batch_play))
                            }
                            TextButton(onClick = {
                                previewQuery?.let { onPreviewAction(it, CanonicalCollectionIntent.ADD_TO_QUEUE) }
                            }, enabled = !loading && previewQuery != null) {
                                Text(stringResource(Res.string.smart_collection_batch_queue))
                            }
                        }
                    }
                }
                items(preview.orEmpty(), key = { "v2-preview-" + it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        Text(item.artist, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item(key = "v2-saved-title") {
                Text("Playlists avançadas salvas", style = MaterialTheme.typography.titleMedium)
                if (savedFailure) Text("Não foi possível carregar as definições")
                else if (saved.isEmpty()) Text("Nenhuma playlist avançada salva")
            }
            items(saved, key = { "v2-saved-" + it.id }) { item ->
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            editorEpoch++
                            editingId = item.id
                            name = item.name
                            rule = item.query.rules
                            sort = item.query.sort
                            limit = item.query.limit.toString()
                            seed = item.query.randomSeed.toString()
                            preview = null
                            previewQuery = null
                            error = null
                        }, enabled = !loading) { Text("Editar") }
                        TextButton(onClick = {
                            if (loading) return@TextButton
                            loading = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { repo.remove(item.id) }
                                    if (editingId == item.id) reset()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) { error = "Não foi possível excluir" }
                                finally { loading = false }
                            }
                        }, enabled = !loading) { Text("Excluir") }
                    }
                    if (onSavedAction != null) Row {
                        TextButton(onClick = {
                            onSavedAction(item.id, CanonicalCollectionIntent.PLAY_NOW)
                        }, enabled = !loading) {
                            Text(stringResource(Res.string.smart_collection_batch_play))
                        }
                        TextButton(onClick = {
                            onSavedAction(item.id, CanonicalCollectionIntent.ADD_TO_QUEUE)
                        }, enabled = !loading) {
                            Text(stringResource(Res.string.smart_collection_batch_queue))
                        }
                    }
                }
            }
            item(key = "v2-classic-heading") {
                Text("Playlists clássicas", style = MaterialTheme.typography.titleMedium)
                Text("Você pode converter uma cópia sem alterar a playlist original.",
                    style = MaterialTheme.typography.bodySmall)
                if (legacyFailure) Text("Não foi possível carregar as playlists clássicas")
            }
            items(legacySaved, key = { "v2-classic-" + it.id }) { original ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(original.name, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(
                        onClick = {
                            val copy = original.toV2Copy(UUID.randomUUID().toString())
                            editorEpoch++
                            editingId = copy.id
                            name = copy.name
                            rule = copy.query.rules
                            sort = copy.query.sort
                            limit = copy.query.limit.toString()
                            seed = copy.query.randomSeed.toString()
                            preview = null
                            previewQuery = null
                            error = null
                        },
                        enabled = !loading,
                    ) { Text("Converter cópia") }
                }
            }
        }
    }
}
