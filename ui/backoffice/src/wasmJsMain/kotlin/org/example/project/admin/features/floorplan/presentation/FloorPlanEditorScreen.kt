package org.example.project.admin.features.floorplan.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canManageProducts
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.floorplan.presentation.components.EDITOR_FIT_MAX_ZOOM
import org.example.project.admin.features.floorplan.presentation.components.EDITOR_MIN_ZOOM
import org.example.project.admin.features.floorplan.presentation.components.EDITOR_PAN_OVERSCROLL
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanEditorCanvas
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanInspectorWidth
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanPaletteLayout
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanSaveBar
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanSelectionLayout
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanSelectionPanel
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanToolPalette
import org.example.project.admin.features.floorplan.presentation.components.FloorPlanZoomControls
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.table.LocationTypeDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.GridConverter
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.ProvideWindowSizeClass
import org.example.project.core.utils.SystemRecords
import org.example.project.features.settings.presentation.viewmodel.LocationTypeViewModel
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.floorplan.FloorPlanContentBounds
import org.example.project.ui.components.floorplan.FloorPlanViewport
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.screens.settings.components.AddItemDialog
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Floor-plan editor for one location (adminApp sub-project D). Assembles the already-built
 * [FloorPlanEditorViewModel] + [FloorPlanEditorCanvas]: a location selector on top, a mutating
 * toolbar (add table / add room element / delete selected / save) gated on [canManageProducts], and
 * the pan/zoom canvas filling the rest.
 *
 * [ProvideWindowSizeClass] wraps the whole body because adminApp has no app-root provider, while the
 * shared Pos* components read [org.example.project.core.utils.LocalWindowSizeClass], a
 * `staticCompositionLocalOf` that throws when unset — the crash would only surface at runtime.
 *
 * The location list loads from a [LaunchedEffect], not the VM `init`: adminApp shares one
 * ViewModelStoreOwner, so a retained VM would otherwise show the previous session's data after a
 * re-login. The editor re-loads whenever the selected location changes.
 */
@Composable
fun FloorPlanEditorScreen(
    onBack: () -> Unit,
    editorVm: FloorPlanEditorViewModel = koinViewModel(),
    locationVm: LocationTypeViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { locationVm.loadLocationTypes() }

    val locationState by locationVm.uiState.collectAsState()
    // The virtual Gutschein/Schnellverkauf types are system rows, not areas a plan can be drawn for.
    val areas = SystemRecords.selectableLocationTypes(locationState.locationTypes)
    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canManageProducts

    var selectedLocationId by remember { mutableStateOf<Long?>(null) }
    var showAddTableDialog by remember { mutableStateOf(false) }
    var editSeatsTableId by remember { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Unsaved moves/resizes live in the app-wide editor VM; every reload (location switch, add,
    // delete) would silently drop them, so those go through the guard below.
    rememberDirtySource(dirty = editorVm.state.hasUnsavedChanges, onDiscard = editorVm::discardDraft)
    val guard = LocalLeaveGuard.current

    // Default to the first location once the list arrives; leave a manual pick untouched.
    LaunchedEffect(areas) {
        if (selectedLocationId == null) {
            selectedLocationId = areas.firstOrNull()?.id
        }
    }

    // Load (or reload) the editor whenever the selected location changes.
    LaunchedEffect(selectedLocationId) {
        selectedLocationId?.let(editorVm::load)
    }

    PosErrorWatcher(
        error = editorVm.state.error,
        title = str(AdminStringKeys.FloorPlanTitle),
        onClear = editorVm::clearError,
        retryAction = { selectedLocationId?.let(editorVm::load) },
    )

    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var paneWidth by remember { mutableStateOf(0f) }
    var paneHeight by remember { mutableStateOf(0f) }
    // True once the operator has zoomed or panned by hand; until then the view follows the fitted one,
    // so a pane that is first measured or resized after load still opens whole.
    var userAdjusted by remember { mutableStateOf(false) }
    val setZoom: (Float) -> Unit = { target ->
        val z = target.coerceIn(EDITOR_MIN_ZOOM, FloorPlanViewport.MAX_ZOOM)
        pan = FloorPlanViewport.panAfterZoom(pan, zoom, z, paneWidth, paneHeight, EDITOR_PAN_OVERSCROLL)
        zoom = z
        userAdjusted = true
    }
    // Fit frames what the plan DRAWS: the bounding box of the location's tables and room elements
    // (each clamped onto the grid exactly as FloorPlanPlacement places it), zooming in up to
    // EDITOR_FIT_MAX_ZOOM so a small plan is not left small; an empty location frames the bare grid.
    // Fitting the grid alone (UI-E1-13) left a small plan tiny and every table a few pixels wide.
    val contentBounds =
        remember(editorVm.state.tables, editorVm.state.elements) {
            FloorPlanContentBounds.of(editorVm.state.tables, editorVm.state.elements)
        }
    val fitted =
        remember(paneWidth, paneHeight, contentBounds) {
            FloorPlanViewport.fit(
                bounds = contentBounds ?: FloorPlanContentBounds.GRID,
                paneWidth = paneWidth,
                paneHeight = paneHeight,
                cells = GridConverter.calculateCellDimensions(paneWidth, paneHeight),
                minZoom = EDITOR_MIN_ZOOM,
                maxZoom = EDITOR_FIT_MAX_ZOOM,
            )
        }
    val applyFit: () -> Unit = {
        zoom = fitted.zoom
        pan = fitted.pan
        userAdjusted = false
    }
    LaunchedEffect(selectedLocationId) {
        zoom = 1f
        pan = Offset.Zero
        userAdjusted = false
    }
    val loaded = editorVm.state.locationTypeId == selectedLocationId && !editorVm.state.isLoading
    // Keyed on the load and the pane size, NOT on `fitted`: moving a table changes the content box, and
    // re-fitting then would rescale the plan under the operator's hand. After a move, Fit is offered
    // instead (the view no longer matches the content's fit).
    LaunchedEffect(selectedLocationId, loaded, paneWidth, paneHeight) {
        if (loaded && !userAdjusted) {
            zoom = fitted.zoom
            pan = fitted.pan
        }
    }

    ProvideWindowSizeClass {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = FloorPlanLayout.forWidth(maxWidth)
            val page = LocalPosLayout.current
            val canvasModifier = layout.canvasHeight?.let { Modifier.fillMaxWidth().height(it) } ?: Modifier
            val canvas: @Composable (Modifier) -> Unit = { box ->
                // Clipped: the plan is scaled by a graphicsLayer, which would otherwise paint over the
                // header and the bars once zoomed. Fixed or flexible size, never driven by the selection.
                Box(modifier = box.border(1.dp, MaterialTheme.colorScheme.outline).clipToBounds()) {
                    FloorPlanEditorCanvas(
                        tables = editorVm.state.tables,
                        elements = editorVm.state.elements,
                        selection = editorVm.state.selection,
                        zoom = zoom,
                        pan = pan,
                        onTransform = { newZoom, newPan ->
                            userAdjusted = true
                            zoom = newZoom
                            pan = newPan
                        },
                        onViewportChanged = { w, h ->
                            paneWidth = w
                            paneHeight = h
                        },
                        onWheelZoom = { factor -> setZoom(zoom * factor) },
                        onSelect = editorVm::select,
                        onMoveTable = editorVm::moveTable,
                        onMoveElement = editorVm::moveElement,
                        modifier = Modifier.fillMaxSize(),
                    )
                    FloorPlanZoomControls(
                        zoom = zoom,
                        onZoomIn = { setZoom(zoom * FloorPlanViewport.STEP) },
                        onZoomOut = { setZoom(zoom / FloorPlanViewport.STEP) },
                        onFit = applyFit,
                        canZoomIn = zoom < FloorPlanViewport.MAX_ZOOM,
                        canZoomOut = zoom > EDITOR_MIN_ZOOM,
                        canFit = !FloorPlanViewport.isSameView(zoom, pan, fitted),
                        horizontal = layout != FloorPlanLayout.WIDE,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    )
                }
            }
            val palette: @Composable (FloorPlanPaletteLayout) -> Unit = { paletteLayout ->
                FloorPlanToolPalette(
                    layout = paletteLayout,
                    enabled = selectedLocationId != null,
                    onTool = { tool ->
                        if (tool == FloorPlanTool.TABLE) {
                            showAddTableDialog = true
                        } else {
                            guard.requestLeave {
                                editorVm.addElement(tool.elementType.orEmpty(), gridX = 4, gridY = 4, gridWidth = tool.gridWidth, gridHeight = tool.gridHeight)
                            }
                        }
                    },
                )
            }
            val selectionPanel: @Composable (FloorPlanSelectionLayout, Modifier) -> Unit = { selectionLayout, box ->
                FloorPlanSelectionPanel(
                    editorVm = editorVm,
                    layout = selectionLayout,
                    onEditTable = { id -> editSeatsTableId = id },
                    onDelete = { showDeleteConfirm = true },
                    modifier = box,
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                FloorPlanHeader(
                    layout = layout,
                    areas = areas,
                    selectedLocationId = selectedLocationId,
                    onBack = onBack,
                    onSelectLocation = { id ->
                        // The selection only moves once the user has confirmed.
                        if (id != selectedLocationId) guard.requestLeave { selectedLocationId = id }
                    },
                )
                // Mutating tools are hidden below MANAGER (the server enforces the same rule); a reader
                // still sees the plan, the zoom and the selection, but no palette, inspector or save bar.
                if (layout == FloorPlanLayout.WIDE) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = page.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(page.paneGap),
                    ) {
                        if (canEdit) palette(FloorPlanPaletteLayout.COLUMN)
                        canvas(Modifier.weight(1f).fillMaxHeight())
                        if (canEdit) selectionPanel(FloorPlanSelectionLayout.INSPECTOR, Modifier.width(FloorPlanInspectorWidth).fillMaxHeight())
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = page.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(page.paneGap),
                    ) {
                        if (canEdit) palette(if (layout == FloorPlanLayout.MEDIUM) FloorPlanPaletteLayout.ROW else FloorPlanPaletteLayout.TILES)
                        canvas(canvasModifier)
                        if (layout == FloorPlanLayout.COMPACT && canEdit) {
                            Text(
                                text = str(AdminStringKeys.FloorPlanTouchHint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canEdit) {
                            selectionPanel(
                                if (layout == FloorPlanLayout.MEDIUM) FloorPlanSelectionLayout.BAR else FloorPlanSelectionLayout.CARD,
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (canEdit) {
                    val state = editorVm.state
                    FloorPlanSaveBar(
                        changedCount = state.dirtyTableIds.size + state.dirtyElementIds.size,
                        isSaving = state.isSaving,
                        onSave = editorVm::save,
                        onCancel = {
                            if (state.hasUnsavedChanges) {
                                // Drop the staged moves and reload the saved plan.
                                editorVm.discardDraft()
                                selectedLocationId?.let(editorVm::load)
                            } else {
                                onBack()
                            }
                        },
                    )
                }
            }
        }

        if (showAddTableDialog) {
            AddFloorPlanTableDialog(
                onDismiss = { showAddTableDialog = false },
                onConfirm = { tableNumber, seats ->
                    guard.requestLeave {
                        editorVm.addTable(tableNumber, seats, gridX = 4, gridY = 4)
                        showAddTableDialog = false
                    }
                },
            )
        }

        editSeatsTableId?.let { id ->
            editorVm.state.tables.firstOrNull { it.id == id }?.let { table ->
                EditTableSeatsDialog(
                    table = table,
                    onDismiss = { editSeatsTableId = null },
                    onConfirm = { number, seats ->
                        // Saving reloads the plan, which would drop staged moves: ask first.
                        guard.requestLeave {
                            editorVm.updateTableNumberAndSeats(id, number, seats)
                            editSeatsTableId = null
                        }
                    },
                )
            }
        }

        if (showDeleteConfirm && editorVm.state.selection != null) {
            val (entityType, entityName) = floorPlanSelectionLabel(editorVm.state)
            DeleteConfirmDialog(
                entityType = entityType,
                entityName = entityName,
                onConfirm = {
                    guard.requestLeave {
                        editorVm.deleteSelected()
                        showDeleteConfirm = false
                    }
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }
    }
}

/** Back button, "Grundriss" and the location select: one row wide and medium, the select on its own row on a phone. */
@Composable
private fun FloorPlanHeader(
    layout: FloorPlanLayout,
    areas: List<LocationTypeDto.ResponseDto>,
    selectedLocationId: Long?,
    onBack: () -> Unit,
    onSelectLocation: (Long) -> Unit,
) {
    val page = LocalPosLayout.current
    val compact = layout == FloorPlanLayout.COMPACT
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedName = areas.firstOrNull { it.id == selectedLocationId }?.name.orEmpty()
    val picker: @Composable (Modifier) -> Unit = { box ->
        Box(box) {
            PosPickerField(
                value = selectedName,
                onClick = { menuExpanded = true },
                label = str(AdminStringKeys.FloorPlanLocation),
                modifier = Modifier.fillMaxWidth(),
                enabled = areas.isNotEmpty(),
            )
            PosDropdown(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                areas.forEach { locationType ->
                    PosDropdownItem(
                        text = locationType.name,
                        selected = locationType.id == selectedLocationId,
                        onClick = {
                            menuExpanded = false
                            onSelectLocation(locationType.id)
                        },
                    )
                }
            }
        }
    }
    val back: @Composable () -> Unit = {
        Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outline)) {
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = str(AdminStringKeys.NavBack),
                onClick = onBack,
                size = if (compact) 48.dp else 40.dp,
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = page.screenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        back()
        if (compact) {
            // The phone top bar already reads "Grundriss": back and the location select share the row.
            picker(Modifier.weight(1f))
        } else {
            AdminPageTitle(str(AdminStringKeys.FloorPlanTitle), Modifier.align(Alignment.CenterVertically))
            Spacer(Modifier.weight(1f))
            picker(Modifier.width(280.dp))
        }
    }
}

/**
 * Add-table form. Reuses shared-ui's [AddItemDialog]; the table number is required, seats optional.
 * The new table lands on a default free cell — the manager drags it into place on the canvas.
 */
@Composable
private fun AddFloorPlanTableDialog(
    onDismiss: () -> Unit,
    onConfirm: (tableNumber: String, seats: Int?) -> Unit,
) {
    var tableNumber by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("") }

    AddItemDialog(
        title = str(AdminStringKeys.FloorPlanAddTable),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(tableNumber.trim(), seats.toIntOrNull()) },
        confirmEnabled = tableNumber.isNotBlank(),
        confirmText = str(CommonKeys.save),
        dismissText = str(CommonKeys.cancel),
    ) {
        PosTextField(
            value = tableNumber,
            onValueChange = { tableNumber = it },
            label = str(AdminStringKeys.FloorPlanTableNumber),
            modifier = Modifier.fillMaxWidth(),
        )
        PosTextField(
            value = seats,
            onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) seats = it },
            label = str(SettingsKeys.seats),
            modifier = Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.Number,
        )
    }
}

/**
 * "Nummer und Plätze ändern": the selected table's number and seats. Number required (Save stays
 * enabled and marks the field), seats optional digits. Location, combinable partners and delete stay
 * in the Tische list; this dialog only exists because the plan is where the table is found.
 */
@Composable
private fun EditTableSeatsDialog(
    table: RTable,
    onDismiss: () -> Unit,
    onConfirm: (tableNumber: String, seats: Int?) -> Unit,
) {
    val seedSeats =
        table.seats
            .takeIf { it > 0L }
            ?.toString()
            .orEmpty()
    var number by remember(table.id) { mutableStateOf(table.tableNumber) }
    var seats by remember(table.id) { mutableStateOf(seedSeats) }
    var numberError by remember(table.id) { mutableStateOf(false) }
    val close = rememberGuardedDismiss(dirty = number != table.tableNumber || seats != seedSeats, onClose = onDismiss)

    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.FloorPlanEditSeatsTitle, table.tableNumber),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(str(CommonKeys.save), {
                if (number.isBlank()) numberError = true else onConfirm(number.trim(), seats.toIntOrNull())
            }),
    ) {
        PosTextField(
            value = number,
            onValueChange = {
                number = it
                numberError = false
            },
            label = requiredLabel(str(AdminStringKeys.FloorPlanTableNumber)),
            modifier = Modifier.fillMaxWidth(),
            isError = numberError,
            errorMessage = if (numberError) str(CommonKeys.fieldRequired) else null,
        )
        PosTextField(
            value = seats,
            onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) seats = it },
            label = str(SettingsKeys.seats),
            modifier = Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.Number,
        )
    }
}

/** The localized kind and the name of the selected table or element, for the delete confirmation. */
@Composable
private fun floorPlanSelectionLabel(state: FloorPlanEditorState): Pair<String, String> =
    when (val selection = state.selection) {
        is FloorPlanSelection.Table ->
            str(AdminStringKeys.DeleteEntityTable) to
                state.tables
                    .firstOrNull { it.id == selection.id }
                    ?.tableNumber
                    .orEmpty()
        is FloorPlanSelection.Element -> {
            val name =
                when (val type = state.elements.firstOrNull { it.id == selection.id }?.elementType) {
                    "WALL" -> str(AdminStringKeys.FloorPlanElementWall)
                    "DOOR" -> str(AdminStringKeys.FloorPlanElementDoor)
                    "WINDOW" -> str(AdminStringKeys.FloorPlanElementWindow)
                    "COUNTER" -> str(AdminStringKeys.FloorPlanElementCounter)
                    else -> type.orEmpty()
                }
            str(AdminStringKeys.DeleteEntityFloorElement) to name
        }
        null -> "" to ""
    }
