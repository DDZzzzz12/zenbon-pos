package org.example.project.admin.features.tables.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canManageProducts
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.components.RegisterTopBarAction
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.tables.presentation.components.CombinablePartnerDialog
import org.example.project.admin.features.tables.presentation.components.TableEditDialog
import org.example.project.admin.features.tables.presentation.components.TableEditValues
import org.example.project.admin.features.tables.presentation.components.TableStatusLabel
import org.example.project.admin.features.tables.presentation.components.TablesCompactActions
import org.example.project.admin.features.tables.presentation.components.TablesCompactList
import org.example.project.admin.features.tables.presentation.components.TablesLocationSidebar
import org.example.project.admin.features.tables.presentation.components.TablesMediumHeader
import org.example.project.admin.features.tables.presentation.components.TablesReadOnlyTable
import org.example.project.admin.features.tables.presentation.components.TablesWideHeader
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.domain.dto.table.RTableDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.SystemRecords
import org.example.project.features.settings.presentation.components.table.TableDataTable
import org.example.project.features.settings.presentation.state.getFilteredTables
import org.example.project.features.settings.presentation.state.getPagedTables
import org.example.project.features.settings.presentation.state.getTotalPages
import org.example.project.features.settings.presentation.viewmodel.LocationTypeViewModel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.filter.PosFilterPanelState
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.table.PosPaginationSpec
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.screens.settings.components.AddItemDialog
import org.example.project.ui.screens.settings.components.LocationTypeManagementContent
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private enum class TablesSubSection { TABLES, LOCATION_TYPES }

/** Key of the new-row in the dirty-row map; table ids are positive. */
private const val NEW_ROW_KEY = -1L

/** What the table dialog is open for: a new table, or an existing one by id (looked up fresh, never captured). */
private sealed interface TableDialogTarget {
    data object Create : TableDialogTarget

    data class Edit(
        val tableId: Long,
    ) : TableDialogTarget
}

/**
 * adminApp's Tische destination — a peer of Produkte, not a settings section. It lays the table list
 * out by the width it really has ([TablesLayout]): wide, a location sidebar and the grid, edited
 * inline (the new-table row on top); medium, chips and a read-only table; compact, list rows. Below
 * the wide layout a table is created and edited in [TableEditDialog]; its combinable partners are
 * paired in [CombinablePartnerDialog]. Design: docs/design/admin-tables-floorplan-responsive.
 *
 * Data loads from [LaunchedEffect], not VM `init`: adminApp shares one ViewModelStoreOwner, so a
 * retained VM would show the previous session's data after a re-login. Refresh re-fetches straight
 * from the server — adminApp has no local cache to synchronize against.
 */
@Composable
fun TablesScreen(
    viewModel: TableViewModel = koinViewModel(),
    locationTypeViewModel: LocationTypeViewModel = koinViewModel(),
    onEditFloorPlan: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        viewModel.refresh()
        locationTypeViewModel.loadLocationTypes()
    }

    val language = LocalAppLanguage.current
    val canEdit = koinInject<TokenStore>().canManageProducts
    val locationState by locationTypeViewModel.uiState.collectAsState()
    var subSection by remember { mutableStateOf(TablesSubSection.TABLES) }
    var dialogTarget by remember { mutableStateOf<TableDialogTarget?>(null) }
    var pendingDeleteTable by remember { mutableStateOf<RTable?>(null) }
    var pendingDeleteLocationType by remember { mutableStateOf<LocationTypeDto.ResponseDto?>(null) }
    // Table whose combinable-partner picker is open (null = closed).
    var combineTargetId by remember { mutableStateOf<Long?>(null) }

    // Rows edit inline in composable-local state inside the shared table, which reports each row's
    // dirtiness (key -1 = the new-row). The sources are registered here, on the screen, which stays
    // composed. Discard remounts the table (new `key`), so every row reseeds from the server data.
    val dirtyRows = remember { mutableStateMapOf<Long, Boolean>() }
    var tableEpoch by remember { mutableStateOf(0) }
    rememberDirtySource(
        dirty = dirtyRows.isNotEmpty(),
        onDiscard = {
            dirtyRows.clear()
            tableEpoch++
        },
    )
    val guard = LocalLeaveGuard.current
    // Above the layout branch, so a resize keeps an open filter panel and its draft.
    val filterPanel = rememberPosFilterPanelState(TablePanelFilter.of(viewModel.state), FilterPanelKeys.TABLES)
    val onRefresh = { guard.requestLeave { viewModel.refresh() } }
    val state = viewModel.state
    val locations = SystemRecords.selectableLocationTypes(locationState.locationTypes).map { it.id to it.name }

    PosErrorWatcher(
        error = state.error,
        title = str(SettingsKeys.tableManagementSection),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )
    PosErrorWatcher(
        error = locationState.error,
        title = str(SettingsKeys.locationTypeSection),
        onClear = locationTypeViewModel::clearError,
        retryAction = locationTypeViewModel::loadLocationTypes,
    )

    var tablesLayoutForDialogs by remember { mutableStateOf(TablesLayout.WIDE) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = TablesLayout.forWidth(maxWidth)
        tablesLayoutForDialogs = layout
        val compact = layout == TablesLayout.COMPACT
        val tableTools = subSection == TablesSubSection.TABLES
        // The phone's top bar owns the refresh; wider layouts draw it in their header.
        if (compact) {
            RegisterTopBarAction { TablesTopBarRefresh(onRefresh) }
        }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.gridGap)) {
            when (layout) {
                TablesLayout.WIDE ->
                    TablesWideHeader(
                        counts = if (tableTools) state.tables.statusCounts() else null,
                        searchQuery = state.searchQuery,
                        onSearchChange = { query -> guard.requestLeave { viewModel.updateSearchQuery(query) } },
                        onEditFloorPlan = onEditFloorPlan.takeIf { canEdit },
                        onRefresh = onRefresh,
                    )
                TablesLayout.MEDIUM ->
                    TablesMediumHeader(
                        onEditFloorPlan = onEditFloorPlan.takeIf { canEdit },
                        onCreate = { dialogTarget = TableDialogTarget.Create }.takeIf { canEdit && tableTools },
                        onRefresh = onRefresh,
                    )
                TablesLayout.COMPACT -> Unit
            }

            TablesSectionSwitch(subSection, compact) { target -> if (target != subSection) guard.requestLeave { subSection = target } }

            when (subSection) {
                TablesSubSection.TABLES ->
                    TablesBody(
                        layout = layout,
                        viewModel = viewModel,
                        filterPanel = filterPanel,
                        locations = locations,
                        canEdit = canEdit,
                        language = language,
                        tableEpoch = tableEpoch,
                        onEditFloorPlan = onEditFloorPlan.takeIf { canEdit },
                        onCreateDialog = { dialogTarget = TableDialogTarget.Create },
                        onEditDialog = { table ->
                            viewModel.loadCombinablePartners(table.id)
                            dialogTarget = TableDialogTarget.Edit(table.id)
                        },
                        onDelete = { pendingDeleteTable = it },
                        onOpenCombine = { table ->
                            viewModel.loadCombinablePartners(table.id)
                            combineTargetId = table.id
                        },
                        onDirtyChange = { rowId, dirty ->
                            val rowKey = rowId ?: NEW_ROW_KEY
                            if (dirty) dirtyRows[rowKey] = true else dirtyRows.remove(rowKey)
                        },
                        modifier = Modifier.weight(1f),
                    )
                TablesSubSection.LOCATION_TYPES ->
                    LocationTypeManagementContent(
                        locationTypes = locationState.locationTypes,
                        isLoading = locationState.isLoading,
                        language = language,
                        onAddClick = locationTypeViewModel::showAddLocationTypeDialog,
                        onEditClick = locationTypeViewModel::showEditLocationTypeDialog,
                        onDeleteClick = { pendingDeleteLocationType = it },
                        modifier = Modifier.weight(1f),
                    )
            }
        }
    }

    // Outside the layout branch above, so a resize keeps an open panel and its draft.
    TablesFilterPanel(state, viewModel, filterPanel, compact = tablesLayoutForDialogs == TablesLayout.COMPACT)

    TableDialogs(
        target = dialogTarget,
        viewModel = viewModel,
        locations = locations,
        onCloseDialog = {
            viewModel.clearCombinablePartners()
            dialogTarget = null
        },
        onOpenCombine = { table -> combineTargetId = table.id },
        onDelete = { pendingDeleteTable = it },
    )

    // Focused combinable-partner picker for a single table (from a row, or from the edit dialog).
    combineTargetId?.let { id ->
        state.tables.firstOrNull { it.id == id }?.let { target ->
            val closePicker = {
                viewModel.resetCombinableSelection()
                combineTargetId = null
                // Opened from a row (no edit dialog behind it): nothing else needs the staged ids.
                if (dialogTarget == null) viewModel.clearCombinablePartners()
            }
            CombinablePartnerDialog(
                table = target,
                allTables = state.tables,
                savedIds = state.combinablePartnerIds,
                selectedIds = state.editingCombinablePartnerIds,
                onToggle = viewModel::toggleCombinablePartner,
                onSave = {
                    viewModel.saveCombinablePartners(target.id)
                    combineTargetId = null
                    if (dialogTarget == null) viewModel.clearCombinablePartners()
                },
                onDismiss = closePicker,
            )
        }
    }

    // Add/Edit Location Type dialog — adminApp-local. composeApp's own `LocationTypeDialog` was a
    // private composable on its BUSINESS tab (never exported to shared-ui), so it is mirrored here
    // rather than imported.
    if (locationState.showAddDialog) {
        val editingLocationType = locationState.editingLocationType
        LocationTypeDialog(
            editingItem = editingLocationType,
            language = language,
            isSaving = locationState.isSaving,
            onDismiss = locationTypeViewModel::hideAddLocationTypeDialog,
            onConfirm = { name, englishName, chineseName, isVirtual, isTakeaway, sortOrder ->
                if (editingLocationType != null) {
                    locationTypeViewModel.updateLocationType(
                        id = editingLocationType.id,
                        name = name,
                        englishName = englishName,
                        chineseName = chineseName,
                        isVirtual = isVirtual,
                        isTakeaway = isTakeaway,
                        sortOrder = sortOrder,
                    )
                } else {
                    locationTypeViewModel.addLocationType(
                        name = name,
                        englishName = englishName,
                        chineseName = chineseName,
                        isVirtual = isVirtual,
                        isTakeaway = isTakeaway,
                        sortOrder = sortOrder,
                    )
                }
            },
        )
    }

    pendingDeleteTable?.let { table ->
        val seats = table.seats.toString()
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityTable),
            entityName = table.tableNumber,
            titleKey = AdminStringKeys.TablesDeleteTitle,
            consequence = str(AdminStringKeys.TablesDeleteMessage, table.tableNumber, table.locationTypeName, seats),
            irreversible = false,
            onConfirm = {
                // Deleting reloads the list, which drops every row's unsaved edits.
                guard.requestLeave {
                    viewModel.deleteTable(table.id)
                    pendingDeleteTable = null
                    if (dialogTarget is TableDialogTarget.Edit) {
                        viewModel.clearCombinablePartners()
                        dialogTarget = null
                    }
                }
            },
            onDismiss = { pendingDeleteTable = null },
        )
    }

    pendingDeleteLocationType?.let { locationType ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityLocationType),
            entityName = locationType.name,
            onConfirm = {
                locationTypeViewModel.deleteLocationType(locationType)
                pendingDeleteLocationType = null
            },
            onDismiss = { pendingDeleteLocationType = null },
        )
    }
}

/** The phone top bar's refresh icon. */
@Composable
private fun TablesTopBarRefresh(onRefresh: () -> Unit) {
    PosIconButton(
        icon = Icons.Default.Refresh,
        contentDescription = str(SettingsKeys.sync),
        onClick = onRefresh,
        size = 48.dp,
        iconSize = 24.dp,
    )
}

/** Tische / Standorttypen: full width on a phone, content-sized beside the page padding otherwise. */
@Composable
private fun TablesSectionSwitch(
    selected: TablesSubSection,
    compact: Boolean,
    onSelect: (TablesSubSection) -> Unit,
) {
    val padding = LocalPosLayout.current.screenPadding
    PosSegmentedButtonRow(
        options = TablesSubSection.entries,
        selectedOption = selected,
        onOptionSelected = onSelect,
        modifier = Modifier.padding(horizontal = padding).heightIn(min = adminTouchSize(40.dp)).then(if (compact) Modifier.fillMaxWidth() else Modifier),
    ) { section ->
        when (section) {
            TablesSubSection.TABLES -> str(AdminStringKeys.NavTables)
            TablesSubSection.LOCATION_TYPES -> str(SettingsKeys.locationTypeSection)
        }
    }
}

/** The create / edit dialog for [target], saving through the view model. */
@Composable
private fun TableDialogs(
    target: TableDialogTarget?,
    viewModel: TableViewModel,
    locations: List<Pair<Long, String>>,
    onCloseDialog: () -> Unit,
    onOpenCombine: (RTable) -> Unit,
    onDelete: (RTable) -> Unit,
) {
    val state = viewModel.state
    when (target) {
        null -> Unit
        TableDialogTarget.Create ->
            TableEditDialog(
                table = null,
                locations = locations,
                combineCount = 0,
                onSave = { values ->
                    viewModel.addTable(values.toCreateRequest(assignmentPriority = 0))
                    onCloseDialog()
                },
                onOpenCombine = {},
                onDelete = {},
                onDismiss = onCloseDialog,
            )
        is TableDialogTarget.Edit -> {
            val table = state.tables.firstOrNull { it.id == target.tableId } ?: return
            TableEditDialog(
                table = table,
                locations = locations,
                combineCount = state.combinablePartnerIds.size,
                onSave = { values ->
                    // Keep the stored auto-assign priority: CreateRTableRequestDto defaults it to 0 (not
                    // null) and updateTable forwards it, so omitting it would silently reset it server-side.
                    viewModel.updateTable(table.id, values.toCreateRequest(table.assignmentPriority))
                    onCloseDialog()
                },
                onOpenCombine = { onOpenCombine(table) },
                onDelete = { onDelete(table) },
                onDismiss = onCloseDialog,
            )
        }
    }
}

private fun TableEditValues.toCreateRequest(assignmentPriority: Int) =
    RTableDto.CreateRTableRequestDto(
        tableNumber = tableNumber,
        seats = seats,
        locationTypeId = locationTypeId,
        assignmentPriority = assignmentPriority,
    )

/** The table list for the current [layout]. */
@Composable
private fun TablesBody(
    layout: TablesLayout,
    viewModel: TableViewModel,
    filterPanel: PosFilterPanelState<TablePanelFilter>,
    locations: List<Pair<Long, String>>,
    canEdit: Boolean,
    language: Language,
    tableEpoch: Int,
    onEditFloorPlan: (() -> Unit)?,
    onCreateDialog: () -> Unit,
    onEditDialog: (RTable) -> Unit,
    onDelete: (RTable) -> Unit,
    onOpenCombine: (RTable) -> Unit,
    onDirtyChange: (rowId: Long?, dirty: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state
    val guard = LocalLeaveGuard.current
    val padding = LocalPosLayout.current.screenPadding
    if (state.isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val pagination =
        PosPaginationSpec(
            currentPage = state.currentPage,
            totalPages = state.getTotalPages(),
            totalFilteredCount = state.getFilteredTables().size,
            pageSize = state.pageSize,
            currentPageItemCount = state.getPagedTables().size,
            onPageChanged = { page -> guard.requestLeave { viewModel.setPage(page) } },
        )
    when (layout) {
        TablesLayout.WIDE ->
            Row(modifier.fillMaxSize().padding(horizontal = padding), horizontalArrangement = Arrangement.spacedBy(LocalPosLayout.current.paneGap)) {
                TablesLocationSidebar(
                    locations = state.locationOptions(),
                    totalCount = state.tables.size,
                    selectedId = state.selectedLocationFilter,
                    onSelect = { id -> guard.requestLeave { viewModel.selectLocationFilter(id) } },
                )
                Column(Modifier.weight(1f)) {
                    key(tableEpoch) {
                        TableDataTable(
                            tables = state.getPagedTables(),
                            availableLocationTypes = locations,
                            sortColumn = state.sortColumn,
                            sortAscending = state.sortAscending,
                            currentPage = state.currentPage,
                            totalPages = state.getTotalPages(),
                            totalFilteredCount = state.getFilteredTables().size,
                            pageSize = state.pageSize,
                            language = language,
                            canEdit = canEdit,
                            onSortChanged = { sort -> guard.requestLeave { viewModel.setSort(sort) } },
                            onPageChanged = { page -> guard.requestLeave { viewModel.setPage(page) } },
                            onCreate = { number, seats, locationTypeId ->
                                viewModel.addTable(RTableDto.CreateRTableRequestDto(tableNumber = number, seats = seats, locationTypeId = locationTypeId))
                            },
                            onUpdate = { id, number, seats, locationTypeId ->
                                viewModel.updateTable(
                                    id,
                                    RTableDto.CreateRTableRequestDto(
                                        tableNumber = number,
                                        seats = seats,
                                        locationTypeId = locationTypeId,
                                        // See TableDialogs: an inline edit must not reset the stored priority.
                                        assignmentPriority = state.tables.firstOrNull { it.id == id }?.assignmentPriority ?: 0,
                                    ),
                                )
                            },
                            onDelete = onDelete,
                            onOpenCombine = onOpenCombine,
                            statusContent = { table -> TableStatusLabel(table) },
                            newRowLabel = str(AdminStringKeys.TablesNewRowLabel),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onDirtyChange = onDirtyChange,
                        )
                    }
                    if (canEdit) {
                        Text(
                            text = str(AdminStringKeys.TablesInlineEditHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth().padding(vertical = PosSpacing.sm),
                        )
                    }
                }
            }
        TablesLayout.MEDIUM ->
            Column(modifier.fillMaxSize()) {
                TablesFilterBar(state, viewModel, filterPanel, showPanel = false)
                TablesReadOnlyTable(
                    tables = state.getPagedTables(),
                    sortColumn = state.sortColumn,
                    sortAscending = state.sortAscending,
                    canEdit = canEdit,
                    pagination = pagination,
                    onSortChanged = { sort -> guard.requestLeave { viewModel.setSort(sort) } },
                    onEdit = onEditDialog,
                    onOpenCombine = onOpenCombine,
                    onDelete = onDelete,
                    modifier = Modifier.weight(1f).padding(horizontal = padding),
                )
            }
        TablesLayout.COMPACT ->
            Column(modifier.fillMaxSize()) {
                TablesCompactActions(
                    onCreate = onCreateDialog.takeIf { canEdit },
                    onEditFloorPlan = onEditFloorPlan,
                )
                TablesFilterBar(state, viewModel, filterPanel, showPanel = true)
                TablesCompactList(
                    tables = state.getPagedTables(),
                    pagination = pagination,
                    onEdit = onEditDialog.takeIf { canEdit },
                    emptyContent = { TablesEmptyState(state, viewModel) },
                    modifier = Modifier.weight(1f),
                )
            }
    }
}

/**
 * Add/edit dialog for a location type. Mirrors composeApp's former private `LocationTypeDialog` —
 * that composable isn't exported to shared-ui, so it is re-declared here rather than imported.
 */
@Composable
private fun LocationTypeDialog(
    editingItem: LocationTypeDto.ResponseDto?,
    language: Language,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        englishName: String,
        chineseName: String,
        isVirtual: Boolean,
        isTakeaway: Boolean,
        sortOrder: Int,
    ) -> Unit,
) {
    val isEditMode = editingItem != null
    var name by remember { mutableStateOf(editingItem?.name ?: "") }
    var englishName by remember { mutableStateOf(editingItem?.englishName ?: "") }
    var chineseName by remember { mutableStateOf(editingItem?.chineseName ?: "") }
    var isVirtual by remember { mutableStateOf(editingItem?.isVirtual ?: false) }
    var isTakeaway by remember { mutableStateOf(editingItem?.isTakeaway ?: false) }
    var sortOrderText by remember { mutableStateOf(editingItem?.sortOrder?.toString() ?: "0") }

    val dirty =
        name != (editingItem?.name ?: "") ||
            englishName != (editingItem?.englishName ?: "") ||
            chineseName != (editingItem?.chineseName ?: "") ||
            isVirtual != (editingItem?.isVirtual ?: false) ||
            isTakeaway != (editingItem?.isTakeaway ?: false) ||
            sortOrderText != (editingItem?.sortOrder?.toString() ?: "0")
    val close = rememberGuardedDismiss(dirty = dirty, onClose = onDismiss)

    // Transient validation state (not part of `dirty`): set by a refused Save, cleared per field on edit.
    var fieldErrors by remember { mutableStateOf<Map<LocationTypeField, StringKey>>(emptyMap()) }
    var validationAttempt by remember { mutableStateOf(0) }
    val focus = rememberPosFormFocus<LocationTypeField>()
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(LocationTypeField.entries, fieldErrors)
    }
    val nameError = fieldErrors[LocationTypeField.NAME]?.let { str(it) }
    val sortOrderError = fieldErrors[LocationTypeField.SORT_ORDER]?.let { str(it) }

    AddItemDialog(
        title = if (isEditMode) str(SettingsKeys.editLocationType) else str(SettingsKeys.addLocationType),
        onDismiss = close,
        onConfirm = {
            val errors = validateLocationTypeInput(name, sortOrderText)
            if (errors.isNotEmpty()) {
                fieldErrors = errors
                validationAttempt++
            } else {
                onConfirm(name, englishName, chineseName, isVirtual, isTakeaway, sortOrderText.trim().toIntOrNull() ?: 0)
            }
        },
        confirmText = getString(CommonKeys.save, language),
        dismissText = getString(CommonKeys.cancel, language),
        confirmLoading = isSaving,
    ) {
        PosTextField(
            value = name,
            onValueChange = {
                name = it
                fieldErrors = fieldErrors - LocationTypeField.NAME
            },
            label = requiredLabel(str(SettingsKeys.locationTypeName)),
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(LocationTypeField.NAME) },
            isError = nameError != null,
            supportingText = nameError?.let { msg -> { Text(msg) } },
        )
        PosTextField(value = englishName, onValueChange = {
            englishName = it
        }, label = str(SettingsKeys.locationTypeEnglishName), modifier = Modifier.fillMaxWidth())
        PosTextField(value = chineseName, onValueChange = {
            chineseName = it
        }, label = str(SettingsKeys.locationTypeChineseName), modifier = Modifier.fillMaxWidth())
        PosTextField(
            value = sortOrderText,
            onValueChange = {
                sortOrderText = it
                fieldErrors = fieldErrors - LocationTypeField.SORT_ORDER
            },
            label = str(SettingsKeys.sortOrderLabel),
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(LocationTypeField.SORT_ORDER) },
            imeAction = ImeAction.Done,
            isError = sortOrderError != null,
            supportingText = sortOrderError?.let { msg -> { Text(msg) } },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) { Text(str(SettingsKeys.isVirtualLabel)) }
            Switch(checked = isVirtual, onCheckedChange = { isVirtual = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) { Text(str(SettingsKeys.isTakeawayLabel)) }
            Switch(checked = isTakeaway, onCheckedChange = { isTakeaway = it })
        }
    }
}
