package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import com.music.bitchord.ui.components.ConfirmationAlert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import com.music.bitchord.ui.components.CanonicalRatingStars
import com.music.bitchord.ui.components.LocalCanonicalRatingDisplay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import com.music.bitchord.ui.icons.PlaylistEditorIcons
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.CoverPlaceholder
import com.music.bitchord.ui.components.ArtworkPageBackButton
import com.music.bitchord.ui.components.NativeDetailTrackRowFrame
import com.music.bitchord.ui.components.NativeDetailTrackRowLayout
import com.music.bitchord.ui.components.NativeTrackRowText
import com.music.bitchord.ui.theme.themeArtworkPalette
import com.music.bitchord.playback.CanonicalCollectionIntent
import com.music.bitchord.playback.CanonicalCollectionIntentPlanner
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.smart_collection_batch_play
import com.music.bitchord.sharedui.resources.smart_collection_batch_queue
import org.jetbrains.compose.resources.stringResource

// The am-descomp docs/DESIGN.md and new-surface-patterns.md take priority for
// Apple Music-inspired composition. Form geometry and callbacks remain unknown
// there, so existing BitChord theme, components and tokens are documented
// fallbacks — never evidence of Apple parity.
/** Shared singular/plural copy for the editor and saved-filter manager. */
internal fun smartConditionCountLabel(count: Int, configured: Boolean = false): String {
    val label = "$count ${if (count == 1) "condição" else "condições"}"
    return if (configured) "$label ${if (count == 1) "configurada" else "configuradas"}"
    else label
}

private enum class WizardStep { IDENTITY, EDIT, SONGS, RULES, GROUP, CATEGORIES, FIELDS, EDITOR, REVIEW }

private fun operatorUiLabel(operator: SmartPlaylistRuleOperator): String = when (operator) {
    SmartPlaylistRuleOperator.EQUAL -> "É igual a"
    SmartPlaylistRuleOperator.NOT_EQUAL -> "É diferente de"
    SmartPlaylistRuleOperator.GREATER_THAN -> "É maior que"
    SmartPlaylistRuleOperator.GREATER_OR_EQUAL -> "Pelo menos"
    SmartPlaylistRuleOperator.LESS_THAN -> "É menor que"
    SmartPlaylistRuleOperator.LESS_OR_EQUAL -> "No máximo"
    SmartPlaylistRuleOperator.IS_PRESENT -> "Possui valor informado"
    SmartPlaylistRuleOperator.IS_MISSING -> "Não possui valor informado"
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
        listOfNotNull(rule.name.ifBlank { null },
            "${smartRuleLeafCount(rule)} condições · ${smartRuleJoinLabel(rule.join)}").joinToString(" · ")
}

@Composable
private fun WizardCard(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = true,
    content: @Composable (() -> Unit)? = null,
) {
    // This is the exact surface and row shared with Android Settings, not
    // a separate Material3 Card with duplicated paddings and chevrons.
    SettingsGroupSurface {
        SettingsRow(
            title = title,
            subtitle = subtitle.ifBlank { null },
            onClick = onClick,
            reserveIconSpace = false,
            trailing = if (onClick != null && showChevron) null else ({}),
        )
        if (content != null) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = ROW_INSET, end = ROW_INSET, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun WizardHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Same release hero as the native playlist detail, with actual RecordingId art. */
@Composable
private fun WizardReviewHeader(
    name: String,
    description: String,
    cover: V2DisplayRow?,
    customCoverUri: String? = null,
) {
    val palette = themeArtworkPalette()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        NativeReleaseHeaderLayout(
            title = name.ifBlank { "Sem nome" },
            credit = "",
            meta = description,
            palette = palette,
            sleeveSize = maxWidth * 0.76f,
            topInset = 0.dp,
            artwork = {
                if (customCoverUri != null) {
                    Box(Modifier.fillMaxSize()) {
                        if (cover != null) {
                            SmartPlaylistPreviewArtwork(cover, Modifier.fillMaxSize())
                        } else {
                            PlaylistWizardArtworkPlaceholder(224.dp)
                        }
                        AsyncImage(
                            model = customCoverUri,
                            contentDescription = "Capa escolhida da playlist",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else if (cover != null) {
                    SmartPlaylistPreviewArtwork(cover, Modifier.fillMaxSize())
                } else {
                    CoverPlaceholder(
                        icon = PlaylistEditorIcons.Music,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
            actions = {},
        )
    }
}

@Composable
private fun WizardJoinPicker(
    value: SmartPlaylistRuleJoin,
    onChange: (SmartPlaylistRuleJoin) -> Unit,
) {
    PlaylistWizardSegmented(
        first = "Todas (E)",
        second = "Qualquer (OU)",
        firstSelected = value == SmartPlaylistRuleJoin.ALL,
        onSelectFirst = { onChange(SmartPlaylistRuleJoin.ALL) },
        onSelectSecond = { onChange(SmartPlaylistRuleJoin.ANY) },
    )
    // E/OU are already explicit in the segmented labels. A second paragraph
    // on every nested group turns the editor into technical documentation.
}

private fun iconForConditionCategory(id: String) = when (id) {
    "music" -> PlaylistEditorIcons.Music
    "ratings" -> PlaylistEditorIcons.Ratings
    "history" -> PlaylistEditorIcons.History
    "dates" -> PlaylistEditorIcons.Calendar
    "library" -> PlaylistEditorIcons.Folder
    "audio" -> PlaylistEditorIcons.Audio
    "advanced" -> PlaylistEditorIcons.Artwork
    else -> PlaylistEditorIcons.Filters
}


/**
 * The original wizard's native modal shell, reused verbatim for any playlist
 * selection, including RecordingId destinations from the unified Library.
 * Only data/rows differ; there is no second full-screen creation dialog.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistWizardSheetChrome(
    title: String,
    onClose: () -> Unit,
    canClose: Boolean = true,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (canClose) onClose() },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = PAGE_GUTTER, end = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                PlaylistWizardCompactAction(
                    label = "Fechar",
                    onClick = onClose,
                    enabled = canClose,
                    icon = PlaylistEditorIcons.Close,
                )
            }
            SettingsGroupSurface(modifier = Modifier.padding(horizontal = PAGE_GUTTER)) {
                content()
            }
        }
    }
}

@Composable
internal fun <T> WizardChoiceDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    onChosen: (T) -> Unit,
    onClose: () -> Unit,
    radioSelected: ((T) -> Boolean)? = null,
) {
    PlaylistWizardSheetChrome(title = title, onClose = onClose) {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            itemsIndexed(options) { index, option ->
                SettingsRow(
                    title = label(option),
                    onClick = { onChosen(option); onClose() },
                    reserveIconSpace = false,
                    modifier = Modifier.testTag("playlist-choice-option-${label(option)}")
                        .then(if (radioSelected == null) Modifier else
                            Modifier.semantics { selected = radioSelected(option) }),
                    trailing = if (radioSelected != null) ({
                        Icon(
                            imageVector = if (radioSelected(option))
                                PlaylistEditorIcons.Selected else PlaylistEditorIcons.Unselected,
                            contentDescription = null,
                            tint = if (radioSelected(option)) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }) else ({}),
                )
                if (index < options.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
internal fun SmartPlaylistV2WizardUi(
    name: String,
    rule: SmartPlaylistRule,
    hasConfiguredRules: Boolean = true,
    sort: SmartPlaylistV2Sort,
    limit: String,
    seed: String,
    preview: List<V2DisplayRow>?,
    loading: Boolean,
    error: String?,
    editingId: String?,
    editingFilterId: String?,
    creatingFilter: Boolean = false,
    filterNotice: String? = null,
    hasExportedFilter: Boolean = false,
    savedDefinitions: List<SavedSmartPlaylistV2Definition>,
    onNameChange: (String) -> Unit,
    description: String = "",
    onDescriptionChange: (String) -> Unit = {},
    coverUri: String? = null,
    onChooseCover: () -> Unit = {},
    onClearCover: () -> Unit = {},
    onRuleChange: (SmartPlaylistRule) -> Unit,
    onClearRules: () -> Unit = {},
    onSortChange: (SmartPlaylistV2Sort) -> Unit,
    onLimitChange: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onSaveFilter: () -> Unit,
    normalMode: Boolean = false,
    editingNormalId: String? = null,
    openingAsEdit: Boolean = false,
    startOnNormalSongs: Boolean = false,
    embeddedInLibrary: Boolean = false,
    normalRows: List<V2DisplayRow> = emptyList(),
    normalSelectedRows: List<V2DisplayRow> = emptyList(),
    normalSelectedIds: List<String> = emptyList(),
    normalSearch: String = "",
    normalLoading: Boolean = false,
    normalSourceProviderKey: String? = null,
    availableSourceKeys: List<String> = emptyList(),
    onNormalSourceChange: (String?) -> Unit = {},
    onSmartSourceChange: (String?) -> Unit = {},
    normalBrowseType: CanonicalPlaylistBrowseType = CanonicalPlaylistBrowseType.TRACKS,
    normalBrowseGroup: CanonicalPlaylistBrowseGroup? = null,
    normalGroups: List<CanonicalPlaylistBrowseGroup> = emptyList(),
    onNormalBrowseTypeChange: (CanonicalPlaylistBrowseType) -> Unit = {},
    onNormalBrowseGroupChange: (CanonicalPlaylistBrowseGroup?) -> Unit = {},
    onNormalModeChange: (Boolean) -> Unit = {},
    onNormalSearchChange: (String) -> Unit = {},
    onToggleNormalTrack: (String) -> Unit = {},
    onAddNormalAlbum: (CanonicalPlaylistBrowseGroup) -> Unit = {},
    normalAddingAlbumId: String? = null,
    normalGroupMembers: Map<String, List<String>> = emptyMap(),
    onAddNormalArtist: (CanonicalPlaylistBrowseGroup) -> Unit = {},
    onMoveNormalTrack: (String, Boolean) -> Unit = { _, _ -> },
    onSaveNormal: () -> Unit = {},
    onDeleteNormal: () -> Unit = {},
    onDeleteSmart: () -> Unit = {},
    onPreviewAction: ((SmartPlaylistV2Query, String?, CanonicalCollectionIntent) -> Unit)?,
    currentPreviewQuery: SmartPlaylistV2Query?,
    onManage: () -> Unit,
    onBack: () -> Unit,
) {
    val filterMode = editingFilterId != null || creatingFilter
    val isEditing = openingAsEdit ||
        editingId != null || editingNormalId != null || filterMode
    var page by remember {
        mutableStateOf(when {
            isEditing && normalMode && startOnNormalSongs -> WizardStep.SONGS
            isEditing -> WizardStep.EDIT
            else -> WizardStep.IDENTITY
        })
    }
    var selectedPath by remember { mutableStateOf<List<Int>>(emptyList()) }
    var expandedGroups by remember { mutableStateOf(setOf<Int>()) }
    var pendingRuleDeletion by remember { mutableStateOf<List<Int>?>(null) }
    var pendingRuleDeletionIsGroup by remember { mutableStateOf(false) }
    var targetGroupPath by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(smartConditionCategories.first()) }
    var selectedFieldTab by remember { mutableStateOf("all") }
    // Global catalog search and in-category search have separate ownership.
    // A query entered inside a category must never become a global query on Back.
    var globalSearch by remember { mutableStateOf("") }
    var categorySearch by remember { mutableStateOf("") }
    var editingPath by remember { mutableStateOf<List<Int>?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(smartRuleNewComparison(SmartPlaylistRuleField.TITLE)) }
    var negated by remember { mutableStateOf(false) }
    var returnAfterEditor by remember { mutableStateOf(WizardStep.GROUP) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDeleteNormal by remember { mutableStateOf(false) }
    var confirmDeleteSmart by remember { mutableStateOf(false) }
    val names = savedDefinitions.associate { it.id to it.name }

    fun openField(field: SmartPlaylistRuleField) {
        draft = smartRuleNewComparison(field)
        negated = false
        editingPath = null
        // A field may be opened from global search or within a category.
        // Back must restore that exact catalog context, including its query.
        returnAfterEditor = page
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
    // Both Back and Save should return to the containing group. Jumping
    // directly to Rules from a nested group loses the editing context.
    fun parentAfterGroup(): WizardStep =
        if (selectedPath.size > 1) {
            selectedPath = selectedPath.dropLast(1)
            WizardStep.GROUP
        } else WizardStep.RULES

    fun back() {
        page = when (page) {
            WizardStep.EDIT -> {
                confirmDiscard = true
                WizardStep.EDIT
            }
            WizardStep.IDENTITY -> {
                if (name.isNotBlank() || description.isNotBlank() || editingId != null || filterMode) {
                    confirmDiscard = true
                } else {
                    onBack()
                }
                WizardStep.IDENTITY
            }
            WizardStep.SONGS -> if (isEditing) WizardStep.EDIT else WizardStep.IDENTITY
            WizardStep.RULES -> if (isEditing) WizardStep.EDIT else WizardStep.IDENTITY
            WizardStep.GROUP -> parentAfterGroup()
            WizardStep.CATEGORIES -> if (addingGroup || targetGroupPath.isEmpty())
                WizardStep.RULES else WizardStep.GROUP
            WizardStep.FIELDS -> WizardStep.CATEGORIES
            WizardStep.EDITOR -> returnAfterEditor
            WizardStep.REVIEW -> if (normalMode) WizardStep.SONGS else WizardStep.RULES
        }
    }
    BackHandler { back() }

    val header = when (page) {
        WizardStep.IDENTITY -> "Nova playlist"
        WizardStep.EDIT -> if (filterMode) {
            if (editingFilterId != null) "Editar filtro" else "Novo filtro"
        } else "Editar playlist"
        WizardStep.SONGS -> if (isEditing) "Editar músicas" else "Adicionar músicas"
        WizardStep.RULES -> if (filterMode) "Regras do filtro" else "Regras da playlist"
        WizardStep.GROUP -> "Editar grupo de regras"
        WizardStep.CATEGORIES -> "Adicionar condição"
        WizardStep.FIELDS -> selectedCategory.title
        WizardStep.EDITOR -> smartConditionLabel(draft.field)
        WizardStep.REVIEW -> "Revisar e criar"
    }
    // Each logical catalog route owns its scroll position. Going to an editor
    // and backing out restores that route; switching tabs scrolls to the top
    // without remounting its search TextField or losing keyboard focus.
    val routeScrollStates = remember { mutableMapOf<String, LazyListState>() }
    val routeScrollKey = when (page) {
        WizardStep.FIELDS -> "FIELDS:${selectedCategory.id}"
        WizardStep.GROUP -> "GROUP:${selectedPath.joinToString(",")}"
        else -> page.name
    }
    val pageListState = remember(routeScrollKey) {
        routeScrollStates.getOrPut(routeScrollKey) { LazyListState() }
    }
    LaunchedEffect(globalSearch) {
        if (page == WizardStep.CATEGORIES) pageListState.scrollToItem(0)
    }
    LaunchedEffect(categorySearch) {
        if (page == WizardStep.FIELDS) pageListState.scrollToItem(0)
    }
    LaunchedEffect(selectedFieldTab) {
        if (page == WizardStep.FIELDS) pageListState.scrollToItem(0)
    }
    val canContinue = name.trim().isNotEmpty() && name.length <= 80
    val canReview = hasConfiguredRules && canContinue && runCatching {
        SmartPlaylistV2Query(rule, sort, limit.toInt(), seed.toInt())
    }.isSuccess

    // Review recomputes automatically, without a request loop on loading/preview changes.
    LaunchedEffect(page, rule, sort, limit, seed, normalMode) {
        if (page == WizardStep.REVIEW && !normalMode && preview == null && canReview) {
            onPreview()
        }
    }

    // The wizard keeps a stationary toolbar and primary action while its
    // actual step content enters. No second LazyColumn is kept alive, so
    // keyboard focus, selections and scroll state retain single ownership.
    val wizardStepArrival = com.music.bitchord.ui.components.rememberRouteArrivalModifier(
        routeKey = "wizard:${page.name}",
        reduceMotion = com.music.bitchord.ui.LocalReduceAnimations.current,
        forward = page != WizardStep.EDIT && page != WizardStep.IDENTITY,
    )

    // Do not mix the editor with the app's own scrollable library screen.
    // The caller hosts this in a full-width Dialog over the library tab bar.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize()
                .then(if (embeddedInLibrary) Modifier else Modifier.statusBarsPadding())
                .imePadding(),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = PAGE_GUTTER),
            ) {
                ArtworkPageBackButton(
                    onClick = ::back,
                    hazeState = null,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (page == WizardStep.IDENTITY || page == WizardStep.EDIT) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        CircleIconButton(
                            icon = PlaylistEditorIcons.Filters,
                            contentDescription = "Filtros reutilizáveis",
                            onClick = onManage,
                            size = 40.dp,
                        )
                    }
                }
            }
            LazyColumn(
                state = pageListState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .then(wizardStepArrival)
                    .testTag("smart-playlist-wizard-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (page == WizardStep.SONGS) 0.dp else 12.dp,
                ),
            ) {
                item {
                    val headingDetail = when (page) {
                        WizardStep.IDENTITY -> ""
                        WizardStep.EDIT -> if (filterMode)
                            "Configure o nome e as regras deste filtro reutilizável."
                        else "Atualize a capa, a descrição e as faixas ou regras desta playlist."
                        WizardStep.SONGS -> "Escolha as faixas que farão parte desta playlist, na ordem desejada."
                        WizardStep.RULES -> ""
                        WizardStep.GROUP -> ""
                        WizardStep.CATEGORIES -> ""
                        WizardStep.FIELDS -> ""
                        WizardStep.EDITOR -> ""
                        WizardStep.REVIEW -> ""
                    }
                    WizardHeading(header, headingDetail)
                }

                when (page) {
                    WizardStep.IDENTITY, WizardStep.EDIT -> {
                        if (page == WizardStep.EDIT) {
                            item {
                                WizardCard(
                                    title = if (normalMode) "Editar músicas" else "Editar regras",
                                    subtitle = if (normalMode)
                                        "${normalSelectedIds.size} músicas selecionadas"
                                    else smartConditionCountLabel(smartRuleLeafCount(rule), configured = true),
                                    onClick = {
                                        page = if (normalMode) WizardStep.SONGS else WizardStep.RULES
                                    },
                                )
                            }
                        }
                        if (!filterMode) item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // Preview real canonical member art when available,
                                // as the Library's own playlist page does.
                                val automaticCoverRows = if (normalMode)
                                    normalSelectedRows else preview.orEmpty()
                                // In a form, the cover is an editable preview, not a release
                                // detail hero. Keep it subordinate to the name field.
                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    val coverDimension = (maxWidth * 0.56f).coerceAtMost(196.dp)
                                    Box(
                                        modifier = Modifier.size(coverDimension)
                                            .align(Alignment.Center)
                                            .clip(RoundedCornerShape(12.dp)),
                                    ) {
                                        if (automaticCoverRows.isNotEmpty()) {
                                            CanonicalPlaylistCoverArt(
                                                rows = automaticCoverRows,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            PlaylistWizardArtworkPlaceholder(coverDimension)
                                        }
                                        if (coverUri != null) {
                                            AsyncImage(
                                                model = coverUri,
                                                contentDescription = "Capa escolhida da playlist",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                        // The cover itself owns its edit affordance, as
                                        // native media artwork does. No full-width form
                                        // button competing with the playlist title.
                                        Box(
                                            modifier = Modifier.align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                                .testTag("playlist-cover-edit-action"),
                                        ) {
                                            CircleIconButton(
                                                icon = PlaylistEditorIcons.Edit,
                                                contentDescription = "Escolher capa",
                                                onClick = onChooseCover,
                                                size = 40.dp,
                                            )
                                        }
                                    }
                                }
                                if (coverUri != null) {
                                    TextButton(
                                        onClick = onClearCover,
                                        modifier = Modifier.heightIn(min = 44.dp),
                                    ) {
                                        Text("Remover capa", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                PlaylistWizardTextEntry(
                                    value = name,
                                    onValueChange = onNameChange,
                                    label = if (filterMode) "Nome do filtro"
                                        else "Nome da playlist",
                                    hint = "Obrigatório · até 80 caracteres",
                                    error = name.length > 80,
                                    emphasized = true,
                                )
                                if (!filterMode) {
                                    PlaylistWizardTextEntry(
                                        value = description,
                                        onValueChange = {
                                            if (it.length <= 5000) onDescriptionChange(it)
                                        },
                                        label = "Descrição (opcional)",
                                        hint = "Exibida em Sobre a playlist · até 5.000 caracteres",
                                        multiline = true,
                                        emphasized = true,
                                    )
                                }
                            }
                        }
                        item {
                            val selected = if (normalMode) normalSourceProviderKey
                                else if (hasConfiguredRules) smartRuleGlobalSource(rule)
                                else null
                            val label = if (normalMode) when {
                                selected == null -> "Todas as fontes disponíveis"
                                selected == "local-mediastore" -> "Música local"
                                selected in availableSourceKeys -> "Navidrome"
                                else -> "Fonte desconectada"
                            } else when (selected) {
                                "local" -> "Música local"
                                "opensubsonic" -> "Navidrome"
                                else -> "Todas as fontes disponíveis"
                            }
                            WizardCard(
                                title = "Fontes de origem",
                                subtitle = label,
                                onClick = { picker = "source" },
                            )
                            if (normalMode && selected != null && selected !in availableSourceKeys) {
                                Text(
                                    "A fonte salva está desconectada; as músicas continuam na playlist.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!normalMode && selected == "opensubsonic" &&
                                availableSourceKeys.none { it.startsWith("opensubsonic:") }) {
                                Text(
                                    "Nenhum servidor Navidrome está conectado.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (page == WizardStep.IDENTITY) item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Tipo de playlist",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (editingId != null || filterMode || editingNormalId != null) {
                                    Text(if (normalMode) "Normal" else "Inteligente",
                                        style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    PlaylistWizardSegmented(
                                        first = "Normal", second = "Inteligente",
                                        firstSelected = normalMode,
                                        onSelectFirst = { onNormalModeChange(true) },
                                        onSelectSecond = { onNormalModeChange(false) },
                                    )
                                }
                            }
                        }
                    }
                    WizardStep.SONGS -> {
                        item {
                            Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                SegmentedControl(
                                    options = listOf("Músicas", "Álbuns", "Artistas"),
                                    selectedIndex = CanonicalPlaylistBrowseType.entries.indexOf(normalBrowseType),
                                    onSelect = { index ->
                                        onNormalBrowseTypeChange(CanonicalPlaylistBrowseType.entries[index])
                                    },
                                )
                            }
                        }
                        item {
                            Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                                PlaylistWizardSearchEntry(
                                    value = normalSearch,
                                    onValueChange = onNormalSearchChange,
                                    placeholder = when (normalBrowseType) {
                                        CanonicalPlaylistBrowseType.TRACKS -> "Buscar músicas ou artistas"
                                        CanonicalPlaylistBrowseType.ALBUMS -> "Buscar álbuns"
                                        CanonicalPlaylistBrowseType.ARTISTS -> "Buscar artistas"
                                    },
                                )
                            }
                        }
                        if (normalBrowseType != CanonicalPlaylistBrowseType.TRACKS) {
                            if (normalGroups.isEmpty() && !normalLoading) item {
                                Text("Nenhum item disponível para esta pesquisa.",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            normalGroups.forEach { group ->
                                val members = normalGroupMembers[group.id].orEmpty()
                                val selected = allCanonicalGroupMembersSelected(members, normalSelectedIds)
                                item(key = "group-${group.type}-${group.id}") {
                                    CanonicalPlaylistPickerGroupRow(
                                        group = group,
                                        selected = selected,
                                        loading = normalAddingAlbumId != null,
                                        onOpen = {
                                            if (group.type == CanonicalPlaylistBrowseType.ALBUMS) {
                                                onNormalBrowseGroupChange(
                                                    if (normalBrowseGroup?.id == group.id) null else group,
                                                )
                                            } else {
                                                onAddNormalArtist(group)
                                            }
                                        },
                                        onToggleGroup = {
                                            if (group.type == CanonicalPlaylistBrowseType.ALBUMS)
                                                onAddNormalAlbum(group)
                                            else onAddNormalArtist(group)
                                        },
                                    )
                                    PickerRowDivider()
                                }
                                if (group.type == CanonicalPlaylistBrowseType.ALBUMS &&
                                    normalBrowseGroup?.id == group.id) {
                                    items(normalRows, key = { "member-${group.id}-${it.id}" }) { row ->
                                        Box(Modifier.padding(start = PAGE_GUTTER)) {
                                            NormalPlaylistTrackPickerRow(
                                                row, row.id in normalSelectedIds,
                                                onToggleNormalTrack,
                                            )
                                        }
                                    }
                                    if (normalRows.isEmpty() && !normalLoading) item {
                                        Text("Nenhuma faixa disponível neste álbum.",
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        } else {
                            if (normalRows.size >= 200) item {
                                Text("Exibindo até 200 resultados. Refine a busca para encontrar outras músicas.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (normalRows.isEmpty() && !normalLoading) item {
                                Text("Nenhuma música disponível para esta pesquisa.",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            items(normalRows, key = { it.id }) { row ->
                                NormalPlaylistTrackPickerRow(
                                    row, row.id in normalSelectedIds, onToggleNormalTrack,
                                )
                                PickerRowDivider()
                            }
                        }
                    }
                    WizardStep.RULES -> {
                        val root = if (hasConfiguredRules)
                            rule as? SmartPlaylistRule.Group else null
                        item {
                            if (root != null && root.children.size > 1) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Combinar condições",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    WizardJoinPicker(root.join) {
                                        onRuleChange(root.copy(join = it))
                                    }
                                }
                            }
                        }
                        val children = if (!hasConfiguredRules) emptyList()
                            else root?.children ?: listOf(rule)
                        if (!hasConfiguredRules) item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Nenhuma condição adicionada",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Adicione uma regra para começar.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        itemsIndexed(children, key = { index, _ -> "group-$index" }) { index, child ->
                            val path = if (root == null) emptyList() else listOf(index)
                            val core = if (child is SmartPlaylistRule.Not) child.child else child
                            val named = (core as? SmartPlaylistRule.Group)?.name.orEmpty()
                            val expanded = index in expandedGroups
                            SettingsGroupSurface {
                                SettingsRow(
                                    title = named.ifBlank { "Grupo ${index + 1}" },
                                    subtitleContent = {
                                        Text(
                                            ruleUiSummary(child, names),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = {
                                        selectedPath = path
                                        page = WizardStep.GROUP
                                    },
                                    reserveIconSpace = false,
                                    trailing = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            PlaylistWizardCompactAction(
                                                label = if (expanded) "Recolher" else "Expandir",
                                                icon = if (expanded) PlaylistEditorIcons.ChevronUp
                                                    else PlaylistEditorIcons.ChevronDown,
                                                onClick = {
                                                    expandedGroups = if (expanded) expandedGroups - index
                                                        else expandedGroups + index
                                                },
                                            )
                                            PlaylistWizardCompactAction(
                                                label = if (core is SmartPlaylistRule.Group)
                                                    "Excluir grupo" else "Excluir condição",
                                                icon = PlaylistEditorIcons.Delete,
                                                onClick = {
                                                    pendingRuleDeletion = path
                                                    pendingRuleDeletionIsGroup =
                                                        core is SmartPlaylistRule.Group
                                                },
                                            )
                                        }
                                    },
                                )
                                if (expanded) {
                                    val conditions = (core as? SmartPlaylistRule.Group)?.children
                                        ?: listOf(child)
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(
                                            start = ROW_INSET, end = ROW_INSET, bottom = 12.dp,
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        conditions.forEachIndexed { i, condition ->
                                            Text(
                                                "${i + 1}. " + ruleUiSummary(condition, names),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            PlaylistWizardRuleActions(
                                listOf(
                                    PlaylistWizardRuleAction(
                                        label = if (hasConfiguredRules) "Adicionar grupo"
                                            else "Adicionar primeira condição",
                                        icon = PlaylistEditorIcons.Add,
                                        enabled = !hasConfiguredRules ||
                                            (root != null && root.children.size < 64),
                                        onClick = {
                                            targetGroupPath = emptyList()
                                            addingGroup = hasConfiguredRules
                                            globalSearch = ""
                                            categorySearch = ""
                                            routeScrollStates.remove(WizardStep.CATEGORIES.name)
                                            page = WizardStep.CATEGORIES
                                        },
                                    ),
                                ),
                            )
                        }
                        if (!filterMode) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Limite e ordenação",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                SettingsGroupSurface {
                                    SettingsRow(
                                        title = "Ordenar por",
                                        value = sortUiLabel(sort),
                                        onClick = { picker = "sort" },
                                        reserveIconSpace = false,
                                    )
                                    RowDivider()
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(
                                            horizontal = ROW_INSET, vertical = 12.dp,
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        PlaylistWizardTextEntry(
                                            value = limit,
                                            onValueChange = onLimitChange,
                                            label = "Máximo de músicas",
                                            hint = "De 1 a 10.000",
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            emphasized = true,
                                        )
                                        if (sort == SmartPlaylistV2Sort.STABLE_RANDOM ||
                                            sort == SmartPlaylistV2Sort.STABLE_RANDOM_ALBUMS) {
                                            PlaylistWizardTextEntry(
                                                value = seed,
                                                onValueChange = onSeedChange,
                                                label = "Semente aleatória",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                emphasized = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    WizardStep.GROUP -> {
                        val selected = runCatching { smartRuleAtPath(rule, selectedPath) }.getOrNull()
                        val core = if (selected is SmartPlaylistRule.Not) selected.child else selected
                        val group = core as? SmartPlaylistRule.Group
                        if (selected != null) item {
                            PlaylistWizardTextEntry(
                                    value = group?.name.orEmpty(),
                                    onValueChange = { text ->
                                        if (text.length <= 80) {
                                            val changed = if (group != null) {
                                                smartRuleUpdateGroup(rule, selectedPath) {
                                                    it.copy(name = text)
                                                }
                                            } else smartRuleReplaceAt(rule, selectedPath,
                                                SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL,
                                                    listOf(selected), name = text))
                                            onRuleChange(changed)
                                        }
                                    },
                                    label = "Nome (opcional)",
                                    hint = "Até 80 caracteres",
                                    emphasized = true,
                                )
                        }
                        if (group != null) {
                            item {
                                if (group.children.size > 1) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "Combinar condições",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                        WizardJoinPicker(group.join) {
                                            val nextJoin = it
                                            onRuleChange(smartRuleUpdateGroup(
                                                rule, selectedPath,
                                            ) { current ->
                                                current.copy(join = nextJoin)
                                            })
                                        }
                                    }
                                }
                            }
                            itemsIndexed(group.children) { i, child ->
                                val path = selectedPath + i
                                var actionsOpen by remember(path) { mutableStateOf(false) }
                                SettingsGroupSurface {
                                    SettingsRow(
                                        title = "Condição ${i + 1}",
                                        subtitleContent = {
                                            Text(
                                                ruleUiSummary(child, names),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = { editAt(path, WizardStep.GROUP) },
                                        reserveIconSpace = false,
                                        trailing = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Box {
                                                    PlaylistWizardCompactAction(
                                                        label = "Opções",
                                                        icon = PlaylistEditorIcons.More,
                                                        onClick = { actionsOpen = true },
                                                    )
                                                    androidx.compose.material3.DropdownMenu(
                                                        expanded = actionsOpen,
                                                        onDismissRequest = { actionsOpen = false },
                                                    ) {
                                                        androidx.compose.material3.DropdownMenuItem(
                                                            text = { Text("Editar condição") },
                                                            onClick = {
                                                                actionsOpen = false
                                                                editAt(path, WizardStep.GROUP)
                                                            },
                                                        )
                                                        if (i > 0) {
                                                            androidx.compose.material3.DropdownMenuItem(
                                                                text = { Text("Mover para cima") },
                                                                onClick = {
                                                                    actionsOpen = false
                                                                    onRuleChange(smartRuleUpdateGroup(
                                                                        rule, selectedPath,
                                                                    ) { it.moveChild(i, i - 1) })
                                                                },
                                                            )
                                                        }
                                                        if (i < group.children.lastIndex) {
                                                            androidx.compose.material3.DropdownMenuItem(
                                                                text = { Text("Mover para baixo") },
                                                                onClick = {
                                                                    actionsOpen = false
                                                                    onRuleChange(smartRuleUpdateGroup(
                                                                        rule, selectedPath,
                                                                    ) { it.moveChild(i, i + 1) })
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                                PlaylistWizardCompactAction(
                                                    label = if (child is SmartPlaylistRule.Group ||
                                                        child is SmartPlaylistRule.Not &&
                                                        child.child is SmartPlaylistRule.Group)
                                                        "Excluir grupo" else "Excluir condição",
                                                    icon = PlaylistEditorIcons.Delete,
                                                    onClick = {
                                                        pendingRuleDeletion = path
                                                        pendingRuleDeletionIsGroup =
                                                            child is SmartPlaylistRule.Group ||
                                                            child is SmartPlaylistRule.Not &&
                                                            child.child is SmartPlaylistRule.Group
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            item {
                                PlaylistWizardRuleActions(buildList {
                                    add(PlaylistWizardRuleAction(
                                        label = "Adicionar condição",
                                        icon = PlaylistEditorIcons.Add,
                                        enabled = group.children.size < 64,
                                        onClick = {
                                            targetGroupPath = selectedPath
                                            addingGroup = false
                                            globalSearch = ""
                                            categorySearch = ""
                                            routeScrollStates.remove(WizardStep.CATEGORIES.name)
                                            page = WizardStep.CATEGORIES
                                        },
                                    ))
                                    add(PlaylistWizardRuleAction(
                                        label = "Grupo aninhado",
                                        icon = PlaylistEditorIcons.Folder,
                                        enabled = group.children.size < 64,
                                        onClick = {
                                            onRuleChange(smartRuleAddConditionAt(
                                                rule, selectedPath,
                                                SmartPlaylistRule.Group(
                                                    SmartPlaylistRuleJoin.ALL,
                                                    listOf(smartRuleNewComparison(
                                                        SmartPlaylistRuleField.AUDIBLE_COMPLETIONS,
                                                    )),
                                                ),
                                            ))
                                        },
                                    ))
                                    if (savedDefinitions.isNotEmpty()) {
                                        add(PlaylistWizardRuleAction(
                                            label = "Usar playlist salva",
                                            icon = PlaylistEditorIcons.Library,
                                            onClick = { picker = "reference" },
                                        ))
                                    }
                                    if (selected is SmartPlaylistRule.Not) {
                                        add(PlaylistWizardRuleAction(
                                            label = "Remover negação",
                                            icon = PlaylistEditorIcons.Unselected,
                                            onClick = {
                                                onRuleChange(smartRuleReplaceAt(
                                                    rule, selectedPath, selected.child,
                                                ))
                                            },
                                        ))
                                    }
                                })
                            }
                        } else if (selected != null) {
                            item {
                                WizardCard(
                                    "Condição deste grupo",
                                    ruleUiSummary(selected, names),
                                    onClick = { editAt(selectedPath, WizardStep.GROUP) },
                                )
                            }
                            item {
                                PlaylistWizardRuleActions(buildList {
                                    add(PlaylistWizardRuleAction(
                                        label = "Adicionar condição",
                                        icon = PlaylistEditorIcons.Add,
                                        onClick = {
                                            targetGroupPath = selectedPath
                                            addingGroup = false
                                            globalSearch = ""
                                            categorySearch = ""
                                            routeScrollStates.remove(WizardStep.CATEGORIES.name)
                                            page = WizardStep.CATEGORIES
                                        },
                                    ))
                                    if (selected is SmartPlaylistRule.Not) {
                                        add(PlaylistWizardRuleAction(
                                            label = "Remover negação",
                                            icon = PlaylistEditorIcons.Unselected,
                                            onClick = {
                                                onRuleChange(smartRuleReplaceAt(
                                                    rule, selectedPath, selected.child,
                                                ))
                                            },
                                        ))
                                    }
                                    if (core is SmartPlaylistRule.Reference) {
                                        add(PlaylistWizardRuleAction(
                                            label = "Trocar playlist referenciada",
                                            icon = PlaylistEditorIcons.Library,
                                            onClick = { picker = "replaceReference" },
                                        ))
                                    }
                                })
                            }
                        }
                    }
                    WizardStep.CATEGORIES -> {
                        item {
                            PlaylistWizardSearchEntry(globalSearch, { globalSearch = it }, "Buscar condições")
                        }
                        if (globalSearch.isNotBlank()) {
                            val matches = smartConditionSearch(globalSearch)
                            if (matches.isEmpty()) item {
                                Text(
                                    "Nenhuma condição encontrada.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            } else item {
                                PlaylistWizardFieldList(matches.map { field ->
                                    PlaylistWizardNavOption(
                                        title = smartConditionLabel(field),
                                        description = if (smartConditionDescription(field).startsWith(
                                            "A condição usa somente")) "" else smartConditionDescription(field),
                                        icon = iconForConditionCategory(
                                            smartConditionCategories.firstOrNull { field in it.fields }?.id ?: "music"),
                                        onClick = { openField(field) },
                                    )
                                })
                            }
                        } else {
                            item {
                                PlaylistWizardCategoryGrid(smartConditionCategories.map { category ->
                                    PlaylistWizardNavOption(
                                        title = category.title,
                                        description = category.description,
                                        icon = iconForConditionCategory(category.id),
                                        onClick = {
                                            selectedCategory = category
                                            selectedFieldTab = "all"
                                            categorySearch = ""
                                            // A fresh visit starts at the first field, but
                                            // editor Back retains the current visit's scroll.
                                            routeScrollStates.remove("FIELDS:${category.id}")
                                            page = WizardStep.FIELDS
                                        },
                                    )
                                })
                            }
                        }
                    }
                    WizardStep.FIELDS -> {
                        item {
                            PlaylistWizardSearchEntry(
                                categorySearch, {
                                    categorySearch = it
                                    // The search entry says "nesta categoria": it must
                                    // search across all sub-tabs, not just the last tab
                                    // selected while browsing the catalog.
                                    if (it.isNotBlank()) selectedFieldTab = "all"
                                }, "Buscar nesta categoria")
                        }
                        val tabs = smartConditionFieldTabs(selectedCategory)
                        if (tabs.isNotEmpty()) item {
                            PlaylistWizardFieldTabs(
                                tabs = tabs,
                                selectedKey = selectedFieldTab,
                                onSelect = { selectedFieldTab = it },
                            )
                        }
                        val selectedFields = tabs.firstOrNull { it.key == selectedFieldTab }?.fields
                        val matches = smartConditionSearch(categorySearch, selectedCategory)
                            .filter { selectedFields == null || it in selectedFields }
                        if (matches.isNotEmpty()) item {
                            PlaylistWizardFieldList(matches.map { field ->
                                PlaylistWizardNavOption(
                                    title = smartConditionLabel(field),
                                    description = if (smartConditionDescription(field).startsWith(
                                        "A condição usa somente")) "" else smartConditionDescription(field),
                                    icon = iconForConditionCategory(selectedCategory.id),
                                    onClick = { openField(field) },
                                )
                            })
                        }
                        if (matches.isEmpty()) item {
                            Text(
                                "Nenhuma condição encontrada.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                        if (selectedCategory.id == "library") item {
                            Text(
                                "Pastas e caminhos exigem arquivos locais com localização verificada.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    WizardStep.EDITOR -> {
                        item {
                            SettingsGroupSurface {
                                SettingsRow(
                                    title = "Comparação",
                                    subtitle = operatorUiLabel(draft.operator),
                                    onClick = { picker = "operator" },
                                    reserveIconSpace = false,
                                    trailing = {}, // Comparison is a flat choice row, not a drill-down.
                                )
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                val presence = draft.operator == SmartPlaylistRuleOperator.IS_PRESENT ||
                                    draft.operator == SmartPlaylistRuleOperator.IS_MISSING
                                if (presence) {
                                    Text(
                                        "Verifica se este metadado está presente ou ausente.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    if (draft.field == SmartPlaylistRuleField.SOURCE_KIND) {
                                        PlaylistWizardSegmented(
                                            first = "Dispositivo",
                                            second = "OpenSubsonic",
                                            firstSelected = draft.textValue == "local",
                                            onSelectFirst = {
                                                draft = draft.copy(value = null, textValue = "local")
                                            },
                                            onSelectSecond = {
                                                draft = draft.copy(
                                                    value = null, textValue = "opensubsonic")
                                            },
                                        )
                                    } else if (draft.field.isTextField()) {
                                        PlaylistWizardTextEntry(
                                            value = draft.textValue.orEmpty(),
                                            onValueChange = {
                                                draft = draft.copy(textValue = it, value = null)
                                            },
                                            label = when (draft.field) {
                                                SmartPlaylistRuleField.RELEASE_GROUP_TYPE ->
                                                    "Tipo (ex.: Single, Album, EP)"
                                                SmartPlaylistRuleField.LOCAL_GENRE ->
                                                    "Gênero da música (ex.: K-pop)"
                                                SmartPlaylistRuleField.ARTIST_COUNTRY ->
                                                    "Código do país (ex.: BR, KR)"
                                                SmartPlaylistRuleField.LOCAL_FOLDER ->
                                                    "Pasta (ex.: Music/Kpop)"
                                                SmartPlaylistRuleField.LOCAL_FILE_PATH ->
                                                    "Caminho do arquivo"
                                                SmartPlaylistRuleField.LOCAL_FILE_EXTENSION ->
                                                    "Extensão (ex.: FLAC)"
                                                SmartPlaylistRuleField.RELEASE_TITLE ->
                                                    "Nome do álbum"
                                                else -> "Texto a comparar"
                                            },
                                            emphasized = true,
                                        )
                                    } else {
                                        var numericText by remember(draft.field, editingPath) {
                                            val shown = if (draft.field == SmartPlaylistRuleField.DURATION_MS)
                                                draft.value?.div(1000.0) else draft.value
                                            mutableStateOf(shown?.toString()?.removeSuffix(".0").orEmpty())
                                        }
                                        PlaylistWizardTextEntry(
                                            value = numericText,
                                            onValueChange = { raw ->
                                                numericText = raw
                                                val number = raw.replace(',', '.').toDoubleOrNull()
                                                draft = draft.copy(value = number?.let {
                                                    if (draft.field == SmartPlaylistRuleField.DURATION_MS)
                                                        it * 1000.0 else it
                                                }, textValue = null)
                                            },
                                            label = when (draft.field) {
                                                SmartPlaylistRuleField.DURATION_MS -> "Duração (segundos)"
                                                SmartPlaylistRuleField.ORIGINAL_RELEASE_YEAR ->
                                                    "Ano do primeiro lançamento (ex.: 2018)"
                                                SmartPlaylistRuleField.RELEASE_YEAR ->
                                                    "Ano desta edição (ex.: 2024)"
                                                SmartPlaylistRuleField.DISC_NUMBER ->
                                                    "Número do disco (ex.: 2)"
                                                SmartPlaylistRuleField.TOTAL_SKIPS ->
                                                    "Quantidade total de pulos"
                                                SmartPlaylistRuleField.DAYS_SINCE_LAST_SKIP ->
                                                    "Dias desde a última pulada"
                                                SmartPlaylistRuleField.LOCAL_BITRATE_KBPS ->
                                                    "Bitrate (kbps)"
                                                else -> "Valor numérico"
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            emphasized = true,
                                        )
                                    }
                                }
                                }
                            }
                        }
                        item {
                            SettingsGroupSurface {
                                SettingsRow(
                                    title = "Inverter resultado",
                                    subtitle = if (negated) "Ativado" else "Desativado",
                                    onClick = { negated = !negated },
                                    reserveIconSpace = false,
                                    trailing = {
                                        Icon(
                                            imageVector = if (negated) PlaylistEditorIcons.Selected
                                                else PlaylistEditorIcons.Unselected,
                                            contentDescription = if (negated) "Ativado" else "Desativado",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    WizardStep.REVIEW -> {
                        item {
                            WizardReviewHeader(
                                name = name.trim(),
                                description = if (normalMode)
                                    "Playlist normal · ${normalSelectedIds.size} músicas"
                                else "Playlist inteligente · ${smartRuleLeafCount(rule)} condições",
                                cover = if (normalMode) normalSelectedRows.firstOrNull()
                                    else preview?.firstOrNull(),
                                customCoverUri = coverUri,
                            )
                        }
                        if (description.isNotBlank()) {
                            item(key = "playlist-review-description") {
                                AboutSection(
                                    title = "Sobre a playlist",
                                    text = description,
                                    palette = themeArtworkPalette(),
                                    horizontalPadding = 0.dp,
                                )
                            }
                        }
                        if (normalMode) {
                            item {
                                WizardCard(
                                    "Fontes de origem",
                                    when (normalSourceProviderKey) {
                                        null -> "Todas as fontes disponíveis"
                                        "local-mediastore" -> "Música local"
                                        else -> if (normalSourceProviderKey in availableSourceKeys)
                                            "Servidor (Navidrome)"
                                        else "Servidor não disponível"
                                    },
                                    onClick = { page = WizardStep.IDENTITY },
                                )
                            }
                            if (normalSelectedIds.isEmpty()) item {
                                Text(
                                    "Nenhuma música selecionada. Você poderá adicionar faixas depois.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(normalSelectedRows, key = { it.id }) { row ->
                                PlaylistReviewRatedRow(row)
                            }
                        } else {
                        item {
                            WizardCard(
                                "Fontes de origem",
                                when (smartRuleGlobalSource(rule)) {
                                    "local" -> "Somente música local"
                                    "opensubsonic" -> "Somente servidor atual (Navidrome)"
                                    else -> "Todas as fontes (respeitando as condições configuradas)"
                                },
                                onClick = { page = WizardStep.IDENTITY },
                            )
                        }
                        item {
                            SettingsGroupSurface {
                                SettingsRow(
                                    title = "Regras aplicadas",
                                    subtitleContent = {
                                        Text(
                                            ruleUiSummary(rule, names),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = { page = WizardStep.RULES },
                                    reserveIconSpace = false,
                                )
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Prévia",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    if (preview == null) "Buscando músicas…"
                                    else if (preview.size == 1) "1 música encontrada"
                                    else "${preview.size} músicas encontradas",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(preview.orEmpty().take(24), key = { it.id }) { row ->
                            PlaylistReviewRatedRow(row)
                        }
                        if ((preview?.size ?: 0) > 24) item {
                            Text("Exibindo as primeiras 24 músicas da prévia.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        }
                    }
                }
                if (filterNotice != null) item {
                    Text(filterNotice, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (error != null) item {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (loading) item { NativeLoadingSpinner() }
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 12.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (page == WizardStep.SONGS) {
                    Text(
                        if (normalAddingAlbumId != null) "Adicionando músicas do álbum…"
                        else "Selecionadas: ${normalSelectedIds.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (page) {
                    WizardStep.EDIT -> {
                        PlaylistWizardPrimaryAction(
                            label = if (filterMode) "Salvar filtro" else "Salvar alterações",
                            enabled = canContinue && !loading &&
                                (normalMode || canReview) &&
                                !normalLoading && normalAddingAlbumId == null,
                            onClick = if (filterMode) onSaveFilter
                                else if (normalMode) onSaveNormal else onSave,
                        )
                        if (!normalMode && !filterMode) {
                            PlaylistWizardTextAction(
                                label = if (hasExportedFilter)
                                    "Atualizar filtro reutilizável" else
                                    "Salvar como filtro reutilizável",
                                enabled = canReview && !loading,
                                onClick = onSaveFilter,
                            )
                        }
                        if (!filterMode &&
                            (editingNormalId != null || editingId != null)
                        ) {
                            PlaylistWizardTextAction(
                                label = "Excluir playlist",
                                destructive = true,
                                enabled = !loading,
                                onClick = {
                                    if (normalMode) confirmDeleteNormal = true
                                    else confirmDeleteSmart = true
                                },
                            )
                        }
                    }
                    WizardStep.IDENTITY -> PlaylistWizardPrimaryAction(
                        label = "Continuar",
                        enabled = canContinue,
                        onClick = { page = if (normalMode) WizardStep.SONGS else WizardStep.RULES },
                    )
                    WizardStep.SONGS -> PlaylistWizardPrimaryAction(
                        label = if (isEditing) "Concluir seleção" else "Revisar playlist",
                        enabled = canContinue && !normalLoading && normalAddingAlbumId == null,
                        onClick = { page = if (isEditing) WizardStep.EDIT else WizardStep.REVIEW },
                    )
                    WizardStep.RULES -> PlaylistWizardPrimaryAction(
                        label = if (isEditing) "Concluir regras" else "Revisar playlist",
                        enabled = canReview,
                        onClick = { page = if (isEditing) WizardStep.EDIT else WizardStep.REVIEW },
                    )
                    WizardStep.GROUP -> PlaylistWizardPrimaryAction(
                        label = "Salvar grupo",
                        onClick = { page = parentAfterGroup() },
                    )
                    WizardStep.EDITOR -> {
                        // Do not enable Add/Save for rules that the real V2
                        // query validator would reject at the review stage.
                        val valueOk = smartConditionDraftValid(draft)
                        PlaylistWizardPrimaryAction(
                            label = if (editingPath != null)
                                "Salvar condição" else "Adicionar condição",
                            onClick = {
                                val added: SmartPlaylistRule =
                                    if (negated) SmartPlaylistRule.Not(draft) else draft
                                val updated = if (!hasConfiguredRules) {
                                    SmartPlaylistRule.Group(
                                        SmartPlaylistRuleJoin.ALL, listOf(added))
                                } else if (editingPath != null) {
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
                        )
                    }
                    WizardStep.REVIEW -> {
                        PlaylistWizardPrimaryAction(
                            label = if (editingId != null || editingNormalId != null)
                                "Salvar alterações" else "Criar playlist",
                            onClick = if (normalMode) onSaveNormal else onSave,
                            enabled = if (normalMode) canContinue && !loading && !normalLoading &&
                                normalAddingAlbumId == null
                                else canReview && !loading &&
                                    currentPreviewQuery != null && preview != null,
                        )
                        if (!normalMode && preview == null) {
                            Text("Calcule a prévia antes de criar a playlist.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!normalMode) PlaylistWizardTextAction(
                            label = if (editingFilterId != null || hasExportedFilter)
                                "Atualizar filtro reutilizável" else "Salvar como filtro reutilizável",
                            enabled = canReview && !loading,
                            onClick = onSaveFilter,
                        )
                    }
                    WizardStep.CATEGORIES, WizardStep.FIELDS -> Unit
                }
            }
        }
    }

    // The full-screen editor captures back itself; an open native alert must
    // dismiss before that back action attempts to leave the draft.
    BackHandler(enabled = confirmDiscard || confirmDeleteNormal || confirmDeleteSmart) {
        confirmDiscard = false
        confirmDeleteNormal = false
        confirmDeleteSmart = false
    }
    if (confirmDiscard) {
        ConfirmationAlert(
            hazeState = null, // Wizard lives in a separate full-screen Dialog.
            title = "Descartar alterações?",
            description = "O rascunho desta playlist não será salvo.",
            confirmLabel = "Descartar",
            dismissLabel = "Continuar editando",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    if (confirmDeleteNormal) {
        ConfirmationAlert(
            hazeState = null,
            title = "Excluir playlist normal?",
            description = "A lista de músicas selecionadas será removida do dispositivo. As músicas originais não serão apagadas.",
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                confirmDeleteNormal = false
                onDeleteNormal()
            },
            onDismiss = { confirmDeleteNormal = false },
        )
    }

    pendingRuleDeletion?.let { path ->
        ConfirmationAlert(
            hazeState = null,
            title = if (pendingRuleDeletionIsGroup) "Excluir grupo de regras?"
                else "Excluir condição?",
            description = "Esta exclusão altera a lógica da playlist. " +
                "Nenhuma música original será apagada.",
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                val updated = smartRuleRemovePruningEmptyGroups(rule, path)
                pendingRuleDeletion = null
                if (updated == null) onClearRules() else onRuleChange(updated)
                selectedPath = emptyList()
                expandedGroups = emptySet()
                page = WizardStep.RULES
            },
            onDismiss = { pendingRuleDeletion = null },
        )
    }

    if (confirmDeleteSmart) {
        ConfirmationAlert(
            hazeState = null,
            title = "Excluir playlist inteligente?",
            description = "A playlist salva será removida do dispositivo. Suas músicas originais não serão apagadas. Playlists que dependem dela podem impedir a exclusão.",
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                confirmDeleteSmart = false
                onDeleteSmart()
            },
            onDismiss = { confirmDeleteSmart = false },
        )
    }
    if (picker == "source") {
        val normalOptions: List<Pair<String?, String>> =
            listOf(null to "Todas as fontes disponíveis") +
                availableSourceKeys.distinct().mapIndexed { index, provider ->
                    provider to if (provider == "local-mediastore") "Música local"
                        else if (availableSourceKeys.count { it.startsWith("opensubsonic:") } > 1)
                            "Navidrome ${index + 1}"
                        else "Navidrome"
                }
        val smartOptions: List<Pair<String?, String>> =
            listOf(null to "Todas as fontes disponíveis", "local" to "Música local") +
                if (availableSourceKeys.any { it.startsWith("opensubsonic:") })
                    listOf("opensubsonic" to "Navidrome") else emptyList()
        WizardChoiceDialog(
            title = "Fontes de origem",
            options = if (normalMode) normalOptions else smartOptions,
            label = { it.second },
            onChosen = { (source, _) ->
                if (normalMode) onNormalSourceChange(source)
                else onSmartSourceChange(source)
            },
            onClose = { picker = null },
            radioSelected = { (source, _) ->
                source == (if (normalMode) normalSourceProviderKey
                    else smartRuleGlobalSource(rule))
            },
        )
    }
    if (picker == "sort") {
        WizardChoiceDialog("Ordenar músicas", SmartPlaylistV2Sort.entries, ::sortUiLabel,
            onChosen = onSortChange, onClose = { picker = null },
            radioSelected = { it == sort })
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
            }, onClose = { picker = null },
            radioSelected = { it == draft.operator })
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

/** Native list row plus canonical, account-scoped rating strip. */
@Composable
private fun PlaylistReviewRatedRow(row: V2DisplayRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            LibraryRow(
                title = row.title, subtitle = row.artist, imageUrl = null,
                horizontalPadding = 0.dp,
                artwork = {
                    SmartPlaylistPreviewArtwork(row = row, modifier = Modifier.fillMaxSize())
                },
            )
        }
        CanonicalRatingStars(
            rating = row.canonicalPlaylistRating(LocalCanonicalRatingDisplay.current),
            modifier = Modifier.padding(end = 8.dp),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The same cached Apple Music artist photograph as DetailScreen, with art fallback. */
@Composable
private fun PlaylistWizardArtistArtwork(group: CanonicalPlaylistBrowseGroup) {
    val art by produceState(
        initialValue = AppleArtistArtRepository.cached(group.title),
        key1 = group.title,
    ) { value = AppleArtistArtRepository.artFor(group.title) }
    if (art != null) {
        AsyncImage(
            model = art?.heroUrl, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    } else if (group.artworkRow != null) {
        SmartPlaylistPreviewArtwork(group.artworkRow, Modifier.fillMaxSize())
    } else PlaylistWizardArtworkPlaceholder(52.dp)
}

/** A native SongRow-size divider, indented past the 52dp artwork. */
@Composable
private fun PickerRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** Reuses SongRow's native frame, thumbnail width, baseline and selection glyph. */
@Composable
private fun CanonicalPlaylistPickerGroupRow(
    group: CanonicalPlaylistBrowseGroup,
    selected: Boolean,
    loading: Boolean,
    onOpen: () -> Unit,
    onToggleGroup: () -> Unit,
) {
    val album = group.type == CanonicalPlaylistBrowseType.ALBUMS
    NativeDetailTrackRowLayout(
        onClick = onOpen,
        modifier = Modifier.testTag("canonical-${if (album) "album" else "artist"}-picker-${group.id}"),
        horizontalInset = 0.dp,
        showRatingSlot = false,
        rating = {},
        background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else Color.Transparent,
        leading = {
            val artworkShape = if (album) RoundedCornerShape(8.dp) else CircleShape
            Box(Modifier.size(52.dp).clip(artworkShape)) {
                if (!album) PlaylistWizardArtistArtwork(group)
                else if (group.artworkRow != null) {
                    SmartPlaylistPreviewArtwork(group.artworkRow, Modifier.fillMaxSize())
                } else PlaylistWizardArtworkPlaceholder(52.dp)
            }
        },
        title = {
            Text(
                group.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = {
            Text(
                if (album) group.artworkRow?.artist.orEmpty() else "Artista",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(
                onClick = onToggleGroup,
                enabled = !loading,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (selected) PlaylistEditorIcons.Selected else PlaylistEditorIcons.Unselected,
                    contentDescription = when {
                        album && selected -> "Remover álbum selecionado"
                        album -> "Adicionar álbum inteiro"
                        selected -> "Remover artista selecionado"
                        else -> "Adicionar artista inteiro"
                    },
                    tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).testTag("playlist-wizard-group-selection"),
                )
            }
        },
    )
}

@Composable
private fun NormalPlaylistTrackPickerRow(
    row: V2DisplayRow,
    selected: Boolean,
    onToggle: (String) -> Unit,
) {
    NativeDetailTrackRowLayout(
        onClick = { onToggle(row.id) },
        modifier = Modifier.testTag("canonical-track-picker-${row.id}"),
        horizontalInset = 0.dp,
        showRatingSlot = false,
        rating = {},
        background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else Color.Transparent,
        leading = {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))) {
                SmartPlaylistPreviewArtwork(row, Modifier.fillMaxSize())
            }
        },
        title = {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = {
            Text(
                row.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(onClick = { onToggle(row.id) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (selected) PlaylistEditorIcons.Selected else PlaylistEditorIcons.Unselected,
                    contentDescription = if (selected) "Remover música" else "Adicionar música",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).testTag("playlist-wizard-track-selection"),
                )
            }
        },
    )
}
