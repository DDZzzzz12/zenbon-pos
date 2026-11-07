package org.example.project.features.settings.presentation.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.touchTargetSize
import org.example.project.features.settings.presentation.state.TableSortColumn
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownAnchor
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.components.table.PosEditableColumn
import org.example.project.ui.components.table.PosEditableDataTable
import org.example.project.ui.components.table.PosPaginationSpec
import org.example.project.ui.components.table.PosSortState
import org.example.project.ui.components.table.PosTableActionsCell
import org.example.project.ui.components.table.PosTableCellInset
import org.example.project.ui.components.table.PosTableDropdownCell
import org.example.project.ui.components.table.PosTableNumberCell
import org.example.project.ui.components.table.PosTableReadonlyCell
import org.example.project.ui.components.table.PosTableTextCell
import org.example.project.ui.theme.PosSpacing

private const val COL_NUMBER = "number"
private const val COL_SEATS = "seats"
private const val COL_LOCATION = "location"
private const val COL_STATUS = "status"
private const val COL_ACTIONS = "actions"

/** An inactive table is shown at half opacity. */
private const val INACTIVE_ROW_ALPHA = 0.5f

/** Column weights of the wide, inline-editable grid. */
private data class TableColumnWeights(
    val number: Float,
    val seats: Float,
    val location: Float,
    val status: Float,
    val actions: Float,
) {
    companion object {
        val DEFAULT = TableColumnWeights(number = 1.5f, seats = 1f, location = 1.5f, status = 1.5f, actions = 1f)
    }
}

private fun TableSortColumn.toColumnId(): String =
    when (this) {
        TableSortColumn.TABLE_NUMBER -> COL_NUMBER
        TableSortColumn.SEATS -> COL_SEATS
        TableSortColumn.LOCATION_TYPE -> COL_LOCATION
        TableSortColumn.STATUS -> COL_STATUS
    }

private fun String.toSortColumn(): TableSortColumn? =
    when (this) {
        COL_NUMBER -> TableSortColumn.TABLE_NUMBER
        COL_SEATS -> TableSortColumn.SEATS
        COL_LOCATION -> TableSortColumn.LOCATION_TYPE
        COL_STATUS -> TableSortColumn.STATUS
        else -> null
    }

/**
 * The wide, inline-editable table grid of the web admin (adminApp, from 840dp); composeApp does not call it.
 * Renders on the generic [PosEditableDataTable] with the "new table" row under the header; the
 * per-row dirty-confirm edit buffer stays here because it couples the number/seats/location cells
 * with the confirm action.
 *
 * When [canEdit] is false every field renders as read-only text and confirm/delete/combine and the
 * new-row are hidden. An inactive table and a system table (the virtual VOUCHER table) are read-only
 * too; an inactive row is drawn at half opacity.
 *
 * [statusContent] draws the status cell (the host owns the status words, colours and the waiter /
 * open amount line); [newRowLabel] fills the new-row's status column ("Neuer Tisch").
 *
 * [onDirtyChange] (optional) reports whether a row holds un-confirmed edits:
 * `rowId` is the table id, or `null` for the new-row. A row that leaves composition reports clean.
 */
@Composable
fun TableDataTable(
    tables: List<RTable>,
    availableLocationTypes: List<Pair<Long, String>>,
    sortColumn: TableSortColumn,
    sortAscending: Boolean,
    currentPage: Int,
    totalPages: Int,
    totalFilteredCount: Int,
    pageSize: Int,
    language: Language,
    canEdit: Boolean,
    onSortChanged: (TableSortColumn) -> Unit,
    onPageChanged: (Int) -> Unit,
    onCreate: (tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    onUpdate: (id: Long, tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    onDelete: (RTable) -> Unit,
    onOpenCombine: (RTable) -> Unit,
    statusContent: @Composable (RTable) -> Unit,
    newRowLabel: String,
    modifier: Modifier = Modifier,
    onDirtyChange: ((rowId: Long?, dirty: Boolean) -> Unit)? = null,
) {
    val weights = TableColumnWeights.DEFAULT
    // Editable cells start their text at PosTableCellInset (tapped or not); read-only cells draw at the edge.
    val inset = if (canEdit) PosTableCellInset else 0.dp
    val columns =
        listOf(
            PosEditableColumn(COL_NUMBER, str(SettingsKeys.tableNumber), weights.number, sortable = true, headerInset = inset),
            PosEditableColumn(COL_SEATS, str(SettingsKeys.seats), weights.seats, sortable = true, headerInset = inset),
            PosEditableColumn(COL_LOCATION, str(SettingsKeys.locationLabel), weights.location, sortable = true, headerInset = inset),
            PosEditableColumn(COL_STATUS, str(CommonKeys.status), weights.status, sortable = true),
            PosEditableColumn(COL_ACTIONS, str(CommonKeys.actions), weights.actions, align = PosTableAlign.Start),
        )

    PosEditableDataTable(
        columns = columns,
        items = tables,
        rowKey = { it.id },
        emptyText = str(SettingsKeys.noTablesFound),
        modifier = modifier,
        newRowOnTop = true,
        rowModifier = { table -> if (table.isActive) Modifier else Modifier.alpha(INACTIVE_ROW_ALPHA) },
        sortState = PosSortState(sortColumn.toColumnId(), sortAscending),
        onSortChanged = { id -> id.toSortColumn()?.let(onSortChanged) },
        newRowContent =
            if (canEdit && availableLocationTypes.isNotEmpty()) {
                { NewTableRowCells(availableLocationTypes, language, weights, newRowLabel, onCreate, onDirtyChange) }
            } else {
                null
            },
        pagination =
            PosPaginationSpec(
                currentPage = currentPage,
                totalPages = totalPages,
                totalFilteredCount = totalFilteredCount,
                pageSize = pageSize,
                currentPageItemCount = tables.size,
                onPageChanged = onPageChanged,
            ),
        rowContent = { table, _ ->
            TableRowCells(
                table = table,
                availableLocationTypes = availableLocationTypes,
                language = language,
                canEdit = canEdit,
                weights = weights,
                statusContent = statusContent,
                onUpdate = onUpdate,
                onDelete = { onDelete(table) },
                onOpenCombine = { onOpenCombine(table) },
                onDirtyChange = onDirtyChange,
            )
        },
    )
}

@Composable
private fun RowScope.TableRowCells(
    table: RTable,
    availableLocationTypes: List<Pair<Long, String>>,
    language: Language,
    canEdit: Boolean,
    weights: TableColumnWeights,
    statusContent: @Composable (RTable) -> Unit,
    onUpdate: (id: Long, tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    onDelete: () -> Unit,
    onOpenCombine: () -> Unit,
    onDirtyChange: ((rowId: Long?, dirty: Boolean) -> Unit)?,
) {
    // A system table (the virtual VOUCHER table) is shown read-only: the server refuses to change or delete it.
    // An inactive table is shown, not edited: the server keeps it out of service.
    if (canEdit && !table.isSystem && table.isActive) {
        val edit = rememberTableRowEdit(table)
        ReportRowDirty(rowId = table.id, dirty = edit.isDirty(table), onDirtyChange = onDirtyChange)

        TableNumberCell(
            value = edit.numberText,
            onValueChange = { edit.numberText = it },
            weight = weights.number,
            placeholder = "",
            fieldErrors = edit.fieldErrors,
            validationAttempt = edit.validationAttempt,
        )
        PosTableNumberCell(value = edit.seatsText, onValueChange = { edit.seatsText = it }, weight = weights.seats)
        PosTableDropdownCell(
            selectedLabel = edit.locationName(availableLocationTypes),
            options = availableLocationTypes,
            onSelected = { edit.locationTypeId = it },
            weight = weights.location,
        )
        Box(Modifier.weight(weights.status)) { statusContent(table) }
        PosTableActionsCell(weight = weights.actions) {
            TableRowActions(
                edit = edit,
                table = table,
                language = language,
                onUpdate = onUpdate,
                onOpenCombine = onOpenCombine,
                onDelete = onDelete,
            )
        }
    } else {
        // Beside editable rows the headers sit at the fields' text inset, so a read-only row (the
        // VOUCHER system table) starts its text there too instead of ~16dp further left.
        val readonlyInset = if (canEdit) PosTableCellInset else 0.dp
        PosTableReadonlyCell(text = table.tableNumber, weight = weights.number, modifier = Modifier.padding(start = readonlyInset))
        PosTableReadonlyCell(text = table.seats.toString(), weight = weights.seats, modifier = Modifier.padding(start = readonlyInset))
        Box(modifier = Modifier.weight(weights.location).padding(start = readonlyInset)) {
            Box(
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = PosSpacing.md, vertical = 4.dp),
            ) {
                Text(
                    text = table.locationTypeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Box(Modifier.weight(weights.status)) { statusContent(table) }
        if (canEdit && table.isSystem) {
            Text(
                text = str(BackOfficeErrorKeys.systemRecordHint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(weights.actions),
            )
        } else {
            Spacer(modifier = Modifier.weight(weights.actions))
        }
    }
}

@Composable
private fun RowScope.NewTableRowCells(
    availableLocationTypes: List<Pair<Long, String>>,
    language: Language,
    weights: TableColumnWeights,
    label: String,
    onCreate: (tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    onDirtyChange: ((rowId: Long?, dirty: Boolean) -> Unit)?,
) {
    val draft = rememberNewTableDraft(availableLocationTypes)
    ReportRowDirty(rowId = null, dirty = draft.isDirty, onDirtyChange = onDirtyChange)

    TableNumberCell(
        value = draft.numberText,
        onValueChange = { draft.numberText = it },
        weight = weights.number,
        placeholder = requiredLabel(getString(SettingsKeys.tableNumber, language)),
        fieldErrors = draft.fieldErrors,
        validationAttempt = draft.validationAttempt,
    )
    PosTableNumberCell(
        value = draft.seatsText,
        onValueChange = { draft.seatsText = it },
        weight = weights.seats,
        placeholder = getString(SettingsKeys.seats, language),
    )
    PosTableDropdownCell(
        selectedLabel = draft.locationName(availableLocationTypes),
        options = availableLocationTypes,
        onSelected = { draft.locationTypeId = it },
        weight = weights.location,
    )
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weights.status),
    )
    PosTableActionsCell(weight = weights.actions) {
        PosFilledIconButton(
            icon = Icons.Default.Add,
            contentDescription = getString(CommonKeys.add, language),
            onClick = { draft.submit(availableLocationTypes, onCreate) },
            size = touchTargetSize(32.dp),
            iconSize = 18.dp,
            colors = IconButtonDefaults.filledIconButtonColors(),
        )
    }
}

/**
 * The table-number cell of a grid row. A failed add/save outlines it in the error colour, shows the
 * error on its own line beneath the field (the cell's message slot, so the placeholder stays a
 * placeholder) and scrolls to and focuses it, which opens the cell for typing.
 */
@Composable
private fun RowScope.TableNumberCell(
    value: String,
    onValueChange: (String) -> Unit,
    weight: Float,
    placeholder: String,
    fieldErrors: Map<TableFormField, StringKey>,
    validationAttempt: Int,
) {
    val focus = rememberPosFormFocus<TableFormField>()
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) focus.revealFirst(TableFormField.entries, fieldErrors)
    }
    val error = fieldErrors[TableFormField.NUMBER]?.let { str(it) }
    val anchor = with(focus) { Modifier.formAnchor(TableFormField.NUMBER) }
    PosTableTextCell(
        value = value,
        onValueChange = onValueChange,
        weight = weight,
        placeholder = placeholder,
        modifier = anchor,
        errorText = error,
    )
}

/**
 * Save (while dirty) plus link and delete for one row, shared by the table and card forms. Below the
 * expanded window (touch-sized targets) link and delete collapse into one overflow menu; on an
 * expanded window they stay inline. A row shows one of the two, never both.
 */
@Composable
private fun RowScope.TableRowActions(
    edit: TableRowEdit,
    table: RTable,
    language: Language,
    onUpdate: (id: Long, tableNumber: String, seats: Int?, locationTypeId: Long) -> Unit,
    onOpenCombine: () -> Unit,
    onDelete: () -> Unit,
) {
    val touch = touchTargetSize(32.dp) > 32.dp
    if (edit.isDirty(table)) {
        PosFilledIconButton(
            icon = Icons.Default.Check,
            contentDescription = getString(CommonKeys.save, language),
            onClick = { edit.submit(table.id, onUpdate) },
            size = touchTargetSize(32.dp),
            iconSize = 18.dp,
            colors = IconButtonDefaults.filledIconButtonColors(),
        )
        Spacer(Modifier.width(8.dp))
    }
    if (touch) {
        RowActionsMenu(language = language, onOpenCombine = onOpenCombine, onDelete = onDelete)
    } else {
        PosIconButton(
            icon = Icons.Default.Link,
            contentDescription = getString(SettingsKeys.canCombineWith, language),
            onClick = onOpenCombine,
            size = 32.dp,
            iconSize = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        PosIconButton(
            icon = Icons.Default.Delete,
            contentDescription = getString(CommonKeys.delete, language),
            onClick = onDelete,
            size = 32.dp,
            iconSize = 18.dp,
        )
    }
}

/** The link and delete actions of a row as one 48dp overflow target, for touch-sized windows. */
@Composable
private fun RowActionsMenu(
    language: Language,
    onOpenCombine: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PosIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = getString(CommonKeys.actions, language),
            onClick = { expanded = true },
            size = touchTargetSize(32.dp),
            iconSize = 18.dp,
        )
        PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }, anchor = PosDropdownAnchor.BottomEnd) {
            PosDropdownItem(
                text = getString(SettingsKeys.canCombineWith, language),
                leadingIcon = Icons.Default.Link,
                onClick = {
                    expanded = false
                    onOpenCombine()
                },
            )
            PosDropdownItem(
                text = getString(CommonKeys.delete, language),
                leadingIcon = Icons.Default.Delete,
                color = MaterialTheme.colorScheme.error,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** Tells [onDirtyChange] whether this row is dirty, and that it is clean again once it leaves composition. */
@Composable
private fun ReportRowDirty(
    rowId: Long?,
    dirty: Boolean,
    onDirtyChange: ((rowId: Long?, dirty: Boolean) -> Unit)?,
) {
    if (onDirtyChange == null) return
    DisposableEffect(rowId, dirty) {
        onDirtyChange(rowId, dirty)
        onDispose { onDirtyChange(rowId, false) }
    }
}
