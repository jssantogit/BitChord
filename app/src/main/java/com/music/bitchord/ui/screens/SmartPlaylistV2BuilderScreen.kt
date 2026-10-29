package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import com.music.bitchord.ui.components.PAGE_GUTTER
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Definition
import com.music.bitchord.data.listening.SavedSmartPlaylistV2Filter
import com.music.bitchord.data.listening.SmartPlaylistV2FilterRepository
import com.music.bitchord.data.listening.andSavedFilter
import com.music.bitchord.data.listening.toPlaylistCopy
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.SavedSmartPlaylistDefinition
import com.music.bitchord.data.listening.SmartPlaylistDefinitionRepository
import com.music.bitchord.data.listening.toV2Copy
import com.music.bitchord.data.listening.SmartPlaylistDefinitionDatabase
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.referencedPlaylistIds
import com.music.bitchord.data.listening.moveChild
import com.music.bitchord.data.listening.appendPlaylistReference
import com.music.bitchord.data.listening.allowedOperators
import com.music.bitchord.data.listening.isTextField
import com.music.bitchord.data.listening.SmartPlaylistRuleJoin
import com.music.bitchord.data.listening.SmartPlaylistRuleOperator
import com.music.bitchord.data.listening.SmartPlaylistV2DefinitionRepository
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import com.music.bitchord.data.listening.SmartPlaylistV2Sort
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
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

private enum class SmartPlaylistManageTab { PLAYLISTS, FILTERS, CLASSIC }
private data class PendingSmartDeletion(val id: String, val name: String, val isFilter: Boolean)

internal data class V2DisplayRow(
    val id: String,
    val title: String,
    val artist: String,
    // Only a MediaAsset linked to this exact RecordingId and an active source.
    val localArtworkUri: String? = null,
    val remoteMediaAssetId: String? = null,
    val remoteArtworkRevision: String? = null,
)

/**
 * Initial Android V2 editing surface. Legacy playlists remain available via
 * "Editor clássico" until their definitions have been migrated safely.
 */
@Composable
internal fun SmartPlaylistV2BuilderScreen(
    onBack: () -> Unit,
    initialPlaylistId: String? = null,
    onClassic: () -> Unit,
    onSavedAction: ((String, CanonicalCollectionIntent) -> Unit)? = null,
    onPreviewAction: ((SmartPlaylistV2Query, String?, CanonicalCollectionIntent) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) {
        SmartPlaylistV2DefinitionRepository(
            SmartPlaylistDefinitionDatabase.get(context).v2DefinitionDao(),
        )
    }
    val definitionDatabase = remember(context) { SmartPlaylistDefinitionDatabase.get(context) }
    val filterRepo = remember(definitionDatabase) {
        SmartPlaylistV2FilterRepository(definitionDatabase.v2FilterDao())
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
    var editingFilterId by remember { mutableStateOf<String?>(null) }
    var rule by remember { mutableStateOf<SmartPlaylistRule>(
        SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(newRule())),
    ) }
    // The AST needs one child; never treat this seed as a chosen rule.
    var hasConfiguredRules by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SmartPlaylistV2Sort.RATING_DESC) }
    var limit by remember { mutableStateOf("100") }
    var seed by remember { mutableStateOf("0") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<V2DisplayRow>?>(null) }
    var previewQuery by remember { mutableStateOf<SmartPlaylistV2Query?>(null) }
    var savedFailure by remember { mutableStateOf(false) }
    var filtersFailure by remember { mutableStateOf(false) }
    var legacyFailure by remember { mutableStateOf(false) }
    var editorEpoch by remember { mutableStateOf(0) }
    var showWizard by remember { mutableStateOf(true) }
    var manageTab by remember { mutableStateOf(SmartPlaylistManageTab.PLAYLISTS) }
    var pendingDeletion by remember { mutableStateOf<PendingSmartDeletion?>(null) }
    // Selecting a different library section starts at its own heading,
    // instead of inheriting the prior tab's last LazyColumn scroll index.
    val manageListState = remember(manageTab) { LazyListState() }
    val saved by produceState(initialValue = emptyList<SavedSmartPlaylistV2Definition>(), key1 = repo) {
        try {
            repo.observe().collect { value = it; savedFailure = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            savedFailure = true
        }
    }

    val savedFilters by produceState(
        initialValue = emptyList<SavedSmartPlaylistV2Filter>(), key1 = filterRepo,
    ) {
        try {
            filterRepo.observe().collect { value = it; filtersFailure = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            filtersFailure = true
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
        editingFilterId = null
        name = ""
        rule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(newRule()))
        hasConfiguredRules = false
        sort = SmartPlaylistV2Sort.RATING_DESC
        limit = "100"
        seed = "0"
        preview = null
        previewQuery = null
        error = null
    }
    // Opening a saved card from the Library edits exactly that definition.
    // This does not infer an ID from its visible name or create a duplicate.
    LaunchedEffect(initialPlaylistId, repo) {
        val id = initialPlaylistId ?: return@LaunchedEffect
        val item = withContext(Dispatchers.IO) {
            repo.list().firstOrNull { it.id == id }
        }
        if (item == null) {
            error = "Playlist não encontrada na biblioteca local."
            return@LaunchedEffect
        }
        editorEpoch++
        editingId = item.id
        editingFilterId = null
        name = item.name
        rule = item.query.rules
        hasConfiguredRules = true
        sort = item.query.sort
        limit = item.query.limit.toString()
        seed = item.query.randomSeed.toString()
        preview = null
        previewQuery = null
        error = null
        showWizard = true
    }

    fun query() = SmartPlaylistV2Query(
        rules = rule,
        sort = sort,
        limit = limit.toIntOrNull() ?: throw IllegalArgumentException("Limite inválido"),
        randomSeed = seed.toIntOrNull() ?: throw IllegalArgumentException("Semente inválida"),
    )

    suspend fun loadPreviewRows(
        parsed: SmartPlaylistV2Query,
        ownerId: String? = editingId,
    ): List<V2DisplayRow> = withContext(Dispatchers.IO) {
            val matched = evaluator.preview(parsed, ownerId)
            val ids = matched.map { it.recordingId }
            val accountId = SharedPrefsOpenSubsonicAccountPersistence(
                context.applicationContext,
            ).read()?.accountId
            val providerKeys = listOfNotNull(
                CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER,
                accountId?.let { "opensubsonic:$it" },
            )
            val known = ids.chunked(400)
                .flatMap { catalog.recordingsByIds(it) }
                .associateBy { it.id }
            // Lookup stays bounded to IDs selected by the existing evaluator.
            // No title/artist heuristic and no whole-library artwork scan.
            val assets = ids.chunked(400).flatMap {
                catalog.browseAssetsForRecordingIds(providerKeys, it)
            }.filter { it.artworkUri?.isNotBlank() == true }
                .groupBy { it.recordingId }
            matched.mapNotNull { item ->
                val info = known[item.recordingId] ?: return@mapNotNull null
                val linked = assets[item.recordingId].orEmpty()
                val local = linked.firstOrNull {
                    it.providerKey == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER &&
                        (it.artworkUri?.startsWith("content://") == true ||
                         it.artworkUri?.startsWith("file://") == true ||
                         it.artworkUri?.startsWith("https://") == true)
                }
                val remote = linked.firstOrNull {
                    it.providerKey.startsWith("opensubsonic:") &&
                        it.providerKey == accountId?.let { id -> "opensubsonic:$id" }
                }
                V2DisplayRow(
                    id = info.id,
                    title = info.title,
                    artist = info.artistDisplay,
                    localArtworkUri = local?.artworkUri,
                    remoteMediaAssetId = if (local == null) remote?.mediaAssetId else null,
                    remoteArtworkRevision = if (local == null) remote?.artworkUri else null,
                )
            }
        }

    // Preview observation is active only while the app is STARTED. Compose
    // effects may outlive an Android onStop, so scope both account watching and
    // age ticks to ProcessLifecycleOwner rather than relying on composition.
    var lastReferenceSnapshot by remember { mutableStateOf<List<SavedSmartPlaylistV2Definition>?>(null) }
    LaunchedEffect(previewQuery, catalogDatabase, listeningDatabase, accountPersistence, saved) {
        val active = previewQuery ?: return@LaunchedEffect
        val referencesChanged = lastReferenceSnapshot != null &&
            lastReferenceSnapshot != saved && active.rules.referencedPlaylistIds().isNotEmpty()
        lastReferenceSnapshot = saved
        if (referencesChanged) {
            try {
                val refreshed = loadPreviewRows(active)
                if (previewQuery == active) { preview = refreshed; error = null }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (previewQuery == active) {
                    preview = null
                    error = "A playlist referenciada mudou ou ficou indisponível."
                }
            }
        }
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
                smartPlaylistReferenceDefinitionChanges(definitionDatabase).drop(1),
                smartPlaylistRelativeAgeChanges(active, references = saved),
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


    fun loadPreview() {
        if (loading) return
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
                preview = null
                previewQuery = null
                error = "Não foi possível avaliar a playlist; verifique regras e fontes."
            } finally {
                loading = false
            }
        }
    }

    // Full-viewport dialog prevents the library tab bar and mini player from
    // overlapping the creation flow. Dismissing it never discards this draft.
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        if (showWizard) {
            androidx.compose.runtime.key(editorEpoch) {
                SmartPlaylistV2WizardUi(
                    name = name,
                    rule = rule,
                    hasConfiguredRules = hasConfiguredRules,
                    sort = sort,
                    limit = limit,
                    seed = seed,
                    preview = preview,
                    currentPreviewQuery = previewQuery,
                    loading = loading,
                    error = error,
                    editingId = editingId,
                    editingFilterId = editingFilterId,
                    savedDefinitions = saved.filterNot { it.id == editingId },
                    onNameChange = { name = it },
                    onRuleChange = {
                        rule = it
                        hasConfiguredRules = true
                        preview = null
                        previewQuery = null
                        error = null
                    },
                    onSortChange = { sort = it; preview = null; previewQuery = null },
                    onLimitChange = { limit = it; preview = null; previewQuery = null },
                    onSeedChange = { seed = it; preview = null; previewQuery = null },
                    onPreview = ::loadPreview,
                    onSave = {
                        if (!loading) {
                            loading = true
                            scope.launch {
                                try {
                                    val parsed = query()
                                    val id = editingId ?: UUID.randomUUID().toString()
                                    withContext(Dispatchers.IO) {
                                        repo.save(SavedSmartPlaylistV2Definition(id, name, parsed))
                                    }
                                    reset()
                                    showWizard = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "Não foi possível salvar. Verifique as condições e referências."
                                } finally { loading = false }
                            }
                        }
                    },
                    onSaveFilter = {
                        if (!loading) {
                            loading = true
                            scope.launch {
                                try {
                                    val parsed = query()
                                    withContext(Dispatchers.IO) {
                                        filterRepo.save(SavedSmartPlaylistV2Filter(
                                            editingFilterId ?: UUID.randomUUID().toString(),
                                            name, parsed.rules,
                                        ))
                                    }
                                    reset()
                                    showWizard = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "Não foi possível salvar o filtro reutilizável."
                                } finally { loading = false }
                            }
                        }
                    },
                    onPreviewAction = onPreviewAction,
                    onManage = {
                        manageTab = SmartPlaylistManageTab.FILTERS
                        showWizard = false
                    },
                    onBack = onBack,
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Filtros e versões anteriores",
                            style = MaterialTheme.typography.headlineMedium)
                        TextButton(onClick = onBack) { Text("Fechar") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { reset(); showWizard = true }) {
                            Text("Nova playlist")
                        }
                        TextButton(onClick = { showWizard = true }) {
                            Text("Voltar ao rascunho")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(
                            selected = manageTab == SmartPlaylistManageTab.PLAYLISTS,
                            onClick = { manageTab = SmartPlaylistManageTab.PLAYLISTS },
                            label = { Text("Inteligentes (${saved.size})") },
                        )
                        FilterChip(
                            selected = manageTab == SmartPlaylistManageTab.FILTERS,
                            onClick = { manageTab = SmartPlaylistManageTab.FILTERS },
                            label = { Text("Filtros (${savedFilters.size})") },
                        )
                        FilterChip(
                            selected = manageTab == SmartPlaylistManageTab.CLASSIC,
                            onClick = { manageTab = SmartPlaylistManageTab.CLASSIC },
                            label = { Text("Clássicas (${legacySaved.size})") },
                        )
                    }
                    if (error != null) {
                        Text(error.orEmpty(),
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                            color = MaterialTheme.colorScheme.error)
                    }
                    LazyColumn(
                        state = manageListState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
            if (manageTab == SmartPlaylistManageTab.FILTERS) {
            item(key = "v2-saved-filter-heading") {
                Text("Filtros reutilizáveis", style = MaterialTheme.typography.titleMedium)
                Text("Filtros guardam somente regras. Aplicar copia as regras; não vincula alterações futuras.",
                    style = MaterialTheme.typography.bodySmall)
                if (filtersFailure) Text("Não foi possível ler os filtros salvos.")
                else if (savedFilters.isEmpty()) Text("Nenhum filtro reutilizável salvo.")
            }
            items(savedFilters, key = { "v2-filter-" + it.id }) { filter ->
                var more by remember(filter.id) { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(PAGE_GUTTER),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(filter.name, style = MaterialTheme.typography.titleMedium)
                        Text("${smartRuleLeafCount(filter.rules)} condições · Filtro reutilizável",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                showWizard = true
                                editorEpoch++
                                editingId = null
                                editingFilterId = filter.id
                                name = filter.name
                                rule = filter.rules
                            hasConfiguredRules = true
                                preview = null
                                previewQuery = null
                                error = null
                            }, enabled = !loading) { Text("Editar") }
                            TextButton(onClick = {
                                val copy = filter.toPlaylistCopy(
                                    UUID.randomUUID().toString(), filter.name,
                                )
                                showWizard = true
                                editorEpoch++
                                editingId = copy.id
                                editingFilterId = null
                                name = copy.name
                                rule = copy.query.rules
                            hasConfiguredRules = true
                                sort = copy.query.sort
                                limit = copy.query.limit.toString()
                                seed = copy.query.randomSeed.toString()
                                preview = null
                                previewQuery = null
                                error = null
                            }, enabled = !loading) { Text("Criar playlist") }
                            Box {
                                TextButton(onClick = { more = true },
                                    enabled = !loading) { Text("Mais") }
                                DropdownMenu(
                                    expanded = more,
                                    onDismissRequest = { more = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Copiar regras para novo rascunho") },
                                        onClick = {
                                            more = false
                                            showWizard = true
                                            editorEpoch++
                                            editingId = null
                                            editingFilterId = null
                                            rule = filter.rules
                            hasConfiguredRules = true
                                            preview = null
                                            previewQuery = null
                                            error = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Combinar com o rascunho (E)") },
                                        onClick = {
                                            more = false
                                            try {
                                                val combined = rule.andSavedFilter(filter)
                                                showWizard = true
                                                editorEpoch++
                                                editingFilterId = null
                                                rule = combined
                            hasConfiguredRules = true
                                                preview = null
                                                previewQuery = null
                                                error = null
                                            } catch (_: IllegalArgumentException) {
                                                error = "O conjunto de regras excede o limite permitido."
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Excluir filtro") },
                                        onClick = {
                                            more = false
                                            pendingDeletion = PendingSmartDeletion(
                                                filter.id, filter.name, true)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            if (manageTab == SmartPlaylistManageTab.PLAYLISTS) {
            item(key = "v2-saved-title") {
                Text("Playlists avançadas salvas", style = MaterialTheme.typography.titleMedium)
                if (savedFailure) Text("Não foi possível carregar as definições")
                else if (saved.isEmpty()) Text("Nenhuma playlist avançada salva")
            }
            items(saved, key = { "v2-saved-" + it.id }) { item ->
                var more by remember(item.id) { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(PAGE_GUTTER),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text("${smartRuleLeafCount(item.query.rules)} condições · Inteligente",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                showWizard = true
                                editorEpoch++
                                editingId = item.id
                                editingFilterId = null
                                name = item.name
                                rule = item.query.rules
                            hasConfiguredRules = true
                                sort = item.query.sort
                                limit = item.query.limit.toString()
                                seed = item.query.randomSeed.toString()
                                preview = null
                                previewQuery = null
                                error = null
                            }, enabled = !loading) { Text("Editar") }
                            Box {
                                TextButton(onClick = { more = true },
                                    enabled = !loading) { Text("Mais") }
                                DropdownMenu(
                                    expanded = more,
                                    onDismissRequest = { more = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Excluir playlist") },
                                        onClick = {
                                            more = false
                                            pendingDeletion = PendingSmartDeletion(
                                                item.id, item.name, false)
                                        },
                                    )
                                }
                            }
                        }
                        if (onSavedAction != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }
            }
            if (manageTab == SmartPlaylistManageTab.CLASSIC) {
            item(key = "v2-classic-heading") {
                TextButton(onClick = onClassic) { Text("Abrir editor clássico") }
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
                            showWizard = true
                            editorEpoch++
                            editingId = copy.id
                            editingFilterId = null
                            name = copy.name
                            rule = copy.query.rules
                            hasConfiguredRules = true
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
            }
        }
        pendingDeletion?.let { pending ->
            SmartPlaylistDeletionConfirmation(
                name = pending.name,
                isFilter = pending.isFilter,
                onCancel = { pendingDeletion = null },
                onConfirm = {
                    pendingDeletion = null
                    if (!loading) {
                        loading = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (pending.isFilter) filterRepo.remove(pending.id)
                                    else repo.remove(pending.id)
                                }
                                if (pending.isFilter && editingFilterId == pending.id)
                                    editingFilterId = null
                                if (!pending.isFilter && editingId == pending.id) reset()
                                error = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = if (pending.isFilter)
                                    "Não foi possível excluir o filtro."
                                else "Não foi possível excluir: outra playlist pode depender desta."
                            } finally { loading = false }
                        }
                    }
                },
            )
        }
    }
}
