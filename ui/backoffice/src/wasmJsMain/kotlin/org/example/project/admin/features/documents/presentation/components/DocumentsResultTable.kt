package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentFacts
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.components.PosAdaptiveCard
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosContentRow
import org.example.project.ui.components.PosMiddleEllipsisText
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.requiredTableWidth
import org.example.project.ui.theme.PosSpacing

/**
 * The document archive's result table, contributed into the screen's single [LazyListScope]
 * rather than owning a `LazyColumn` of its own.
 *
 * `PosDataTable` (see its doc comment) is a plain, eagerly-measured `Column` — nesting it (or any
 * other `LazyColumn`) inside the screen's outer `LazyColumn` measures to infinite height and
 * crashes on wasm. This file reuses only `PosDataTable`'s column model ([PosTableColumn]) for a
 * consistent weighted/aligned layout, and exposes a [LazyListScope] extension so
 * `DocumentsScreen` can splice the header, rows, empty state and loading-more footer directly
 * into its own `LazyColumn` as `item`/`items` entries.
 */
@Composable
private fun documentColumns(): List<PosTableColumn> =
    listOf(
        PosTableColumn(str(AdminStringKeys.DocumentsColType), PosTableAlign.Start, 1.35f, minWidth = PosTableMin.Pill),
        PosTableColumn(str(AdminStringKeys.DocumentsColFile), PosTableAlign.Start, 1.7f, minWidth = 140.dp),
        PosTableColumn(str(AdminStringKeys.DocumentsColParty), PosTableAlign.Start, 1.45f, minWidth = 120.dp),
        PosTableColumn(str(AdminStringKeys.DocumentsColDate), PosTableAlign.Start, 1.2f, minWidth = PosTableMin.Date),
        PosTableColumn(str(AdminStringKeys.DocumentsColAmount), PosTableAlign.End, 1.3f, minWidth = PosTableMin.Money),
        PosTableColumn(str(AdminStringKeys.BelegflowColumnProgress), PosTableAlign.Start, 1f, minWidth = 72.dp),
        // Wide enough for the longer status words, which wrap rather than being cut off.
        PosTableColumn(str(AdminStringKeys.DocumentsColStatus), PosTableAlign.Start, 1.9f, minWidth = 150.dp),
        PosTableColumn(str(AdminStringKeys.DocumentsColLinked), PosTableAlign.Start, 1f, minWidth = 80.dp),
    )

/**
 * The width below which the table's columns no longer fit and the result list must switch to the
 * card form. Derived from [documentColumns] so the two cannot drift. End-aligned columns carry an
 * extra 8dp end gutter in header and rows, which is added to their minimum here.
 */
@Composable
fun documentTableRequiredWidth(): Dp =
    requiredTableWidth(
        documentColumns().map { col -> if (col.align == PosTableAlign.End) col.copy(minWidth = col.minWidth + 8.dp) else col },
    )

/**
 * Adds the document result table — header, rows (or an empty state), and a loading-more footer —
 * to the caller's [LazyListScope]. See the file doc comment for why this isn't a self-contained
 * `@Composable` owning its own `LazyColumn`.
 */
fun LazyListScope.documentsResultTable(
    items: List<DocumentDto.DocumentListItemDto>,
    isLoadingMore: Boolean,
    onOpen: (Long) -> Unit,
    phone: Boolean = false,
    horizontalPadding: Dp = 0.dp,
) {
    // The screen's LazyColumn has no side padding of its own (the filter bar pads itself), so every
    // item here carries the screen padding.
    val sides = Modifier.padding(horizontal = horizontalPadding)
    if (!phone) {
        item(key = "documents-table-header") {
            Column(sides) { DocumentsTableHeaderRow() }
        }
    }
    if (items.isEmpty()) {
        item(key = "documents-table-empty") {
            Text(
                text = str(AdminStringKeys.DocumentsEmpty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = sides.padding(vertical = 24.dp),
            )
        }
        return
    }
    if (phone) {
        items(items, key = { it.id }) { item ->
            Box(sides) { DocumentResultCard(item = item, onOpen = onOpen) }
        }
    } else {
        items(items, key = { it.id }) { item ->
            Column(sides) {
                DocumentResultRow(item = item, onOpen = onOpen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (isLoadingMore) {
        item(key = "documents-table-loading-more") {
            Box(sides.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun DocumentsTableHeaderRow() {
    val columns = documentColumns()
    Row(Modifier.fillMaxWidth()) {
        columns.forEach { col ->
            Text(
                text = col.title,
                modifier =
                    Modifier
                        .weight(col.weight)
                        .padding(
                            start = 4.dp,
                            end = 4.dp + if (col.align == PosTableAlign.End) 8.dp else 0.dp,
                            top = PosSpacing.sm,
                            bottom = PosSpacing.sm,
                        ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = col.align.toTextAlign(),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/**
 * The cells of one document row, in [documentColumns] order. Shared by the table row and the phone
 * card so both always render the same content.
 *
 * Zipped against `columns` by the callers, never indexed. Six positional indices survived only
 * because nobody edited this row; the first edit is where an off-by-one lands, and the symptom is
 * silent -- a cell under the wrong header, at the wrong weight and alignment. `zip` truncates on a
 * length mismatch, which is exactly why the callers' `check` is not optional.
 */
private fun documentCells(
    item: DocumentDto.DocumentListItemDto,
    language: Language,
    fill: Boolean,
): List<@Composable (PosTableColumn) -> Unit> =
    listOf(
        { _ -> DocumentTypePill(item.fileType) },
        // The title cell sits in a weight(1f) box on the card, so it may always fill.
        // NEW-05: generated names share their start ("Rechnung_2026-10-14…", "Ausgangsrechnung_RG-20…"),
        // so the middle is elided and the distinguishing end stays visible.
        { col ->
            PosMiddleEllipsisText(
                text = item.filename,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = col.align.toTextAlign(),
            )
        },
        { col -> CellText(partyLabel(item), col, overflow = TextOverflow.Ellipsis, fill = fill) },
        { col -> CellText(documentListDate(item, language), col, fill = fill) },
        { col ->
            CellText(
                item.amount?.let { formatMoneyForDisplay(it, item.currency ?: "EUR") } ?: EMPTY_VALUE,
                col,
                fill = fill,
            )
        },
        { _ -> DocumentFlowProgress(item.processingStatus, DocumentFacts.hasInvoice(item), DocumentFacts.realLinkCount(item), item.fileType) },
        { _ -> DocumentStatusDot(item.processingStatus, fileType = item.fileType) },
        { col -> CellText(DocumentFacts.realLinkCount(item).takeIf { it > 0 }?.toString() ?: EMPTY_VALUE, col, fill = fill) },
    )

/** Card roles, in [documentColumns] order: file is the title, the status pill sits trailing. */
private val DOCUMENT_CARD_ROLES =
    listOf(
        PosCardRole.Detail, // Typ
        PosCardRole.Title, // Datei
        PosCardRole.Detail, // Lieferant / Bank
        PosCardRole.Detail, // Datum
        PosCardRole.Detail, // Betrag
        PosCardRole.Meta, // Fortschritt
        PosCardRole.Trailing, // Status
        PosCardRole.Detail, // Verknüpft
    )

/** Phone form of a result row: one [PosAdaptiveCard] per document, same cells as the table row. */
@Composable
private fun DocumentResultCard(
    item: DocumentDto.DocumentListItemDto,
    onOpen: (Long) -> Unit,
) {
    val columns = documentColumns()
    val cells = documentCells(item, LocalAppLanguage.current, fill = false)
    check(columns.size == cells.size && columns.size == DOCUMENT_CARD_ROLES.size) {
        "documentColumns() declares ${columns.size} columns but the card renders ${cells.size} cells " +
            "and ${DOCUMENT_CARD_ROLES.size} roles"
    }
    PosAdaptiveCard(
        columns = columns.zip(DOCUMENT_CARD_ROLES).map { (col, role) -> PosAdaptiveColumn(col, role) },
        row =
            PosContentRow(
                cells = columns.zip(cells).map { (col, cell) -> { cell(col) } },
                onClick = { onOpen(item.id) },
            ),
        modifier = Modifier.alpha(if (item.isActive) 1f else 0.5f),
    )
}

@Composable
private fun DocumentResultRow(
    item: DocumentDto.DocumentListItemDto,
    onOpen: (Long) -> Unit,
) {
    val columns = documentColumns()
    val cells = documentCells(item, LocalAppLanguage.current, fill = true)
    check(columns.size == cells.size) {
        "documentColumns() declares ${columns.size} columns but the row renders ${cells.size} cells"
    }
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(item.id) }
            .alpha(if (item.isActive) 1f else 0.5f)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        columns.zip(cells).forEach { (col, cell) ->
            val gutter = if (col.align == PosTableAlign.End) 8.dp else 0.dp
            Box(Modifier.weight(col.weight).padding(start = 4.dp, end = 4.dp + gutter, top = PosSpacing.sm, bottom = PosSpacing.sm)) {
                cell(col)
            }
        }
    }
}

/** A plain text cell that fills its column so [PosTableColumn.align] actually has room to act. */
@Composable
private fun CellText(
    text: String,
    column: PosTableColumn,
    overflow: TextOverflow = TextOverflow.Clip,
    fill: Boolean = true,
) {
    Text(
        text = text,
        modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        softWrap = false,
        overflow = overflow,
        textAlign = column.align.toTextAlign(),
    )
}

/**
 * The counterparty shown in the "Lieferant / Bank" column: the vendor for an invoice, the bank for a
 * bank statement, an em dash when the document type carries neither.
 *
 * Deliberately **not** falling back to [DocumentDto.DocumentListItemDto.reference] — that field is
 * the *invoice number*, not a party, and the server mapper populates it only from invoice metadata.
 * Showing it here rendered an invoice number under a column headed "Bank". Kept pure so the
 * bank-statement case stays covered by a test rather than by a browser check.
 */
internal fun partyLabel(item: DocumentDto.DocumentListItemDto): String = item.vendorName ?: item.bankName ?: EMPTY_VALUE

/**
 * The "Datum" cell: the invoice date, else the statement date, as a short localized date.
 *
 * The server sends both as ISO local date-times (`2026-01-15T00:00`); rendered raw, the narrow
 * column clipped that to `2026-01-15T` (R6-3). Kept pure so the format is pinned by a test.
 */
internal fun documentListDate(
    item: DocumentDto.DocumentListItemDto,
    language: Language,
): String = (item.date ?: item.statementDate)?.takeIf { it.isNotBlank() }?.formatShortDate(language) ?: EMPTY_VALUE

private fun PosTableAlign.toTextAlign(): TextAlign =
    when (this) {
        PosTableAlign.Start -> TextAlign.Start
        PosTableAlign.Center -> TextAlign.Center
        PosTableAlign.End -> TextAlign.End
    }
