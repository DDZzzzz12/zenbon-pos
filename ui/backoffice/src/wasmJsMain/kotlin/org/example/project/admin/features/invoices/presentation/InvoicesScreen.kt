package org.example.project.admin.features.invoices.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canWriteAccounting
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlow
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStage
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStrip
import org.example.project.admin.core.presentation.belegfluss.InvoiceNextStepBar
import org.example.project.admin.core.presentation.belegfluss.InvoiceRowAction
import org.example.project.admin.core.presentation.belegfluss.InvoiceRowProgress
import org.example.project.admin.core.presentation.belegfluss.InvoiceRowStatus
import org.example.project.admin.core.presentation.belegfluss.rememberOpenPayments
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.admin.core.presentation.components.AdminListScaffold
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentsAccess
import org.example.project.admin.features.documents.presentation.components.DocumentImportCard
import org.example.project.admin.features.finance.presentation.BuchungsbeginnBanner
import org.example.project.admin.features.finance.presentation.BuchungsbeginnDialog
import org.example.project.admin.features.finance.presentation.BuchungsbeginnLine
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.enumStrings.eInvoiceFormatLabel
import org.example.project.core.utils.formatShortDate
import org.example.project.core.utils.orDash
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMiddleEllipsisText
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Column weights, shared by the header and every row so the two line up. */
private const val SUPPLIER_WEIGHT = 3.2f
private const val NUMBER_WEIGHT = 2f
private const val DATE_WEIGHT = 1.4f
private const val DUE_WEIGHT = 1.4f
private const val AMOUNT_WEIGHT = 2f
private const val OPEN_WEIGHT = 2f
private const val PROGRESS_WEIGHT = 1.6f
private const val STATUS_WEIGHT = 2f
private const val ACTION_WEIGHT = 2.6f

/** Right padding of an End-aligned money column, so it keeps a gutter from its neighbour. */
private val MONEY_GUTTER: Dp = 8.dp

/** Content width from which the rows are a table; below it they are the phone/tablet list. */
private val TABLE_MIN_WIDTH = 900.dp

/**
 * Rechnungen — the supplier-invoice pipeline as the Belegfluss strip (Erfassen · Prüfen · Zuordnen ·
 * Erledigt). The strip is the bucket filter: Erfassen = `ZU_ERFASSEN`, Prüfen = `ZU_PRUEFEN`,
 * Zuordnen = `OFFEN` (with the sub-filters Überfällig and Überzahlt as chips below it), Erledigt =
 * `FERTIG`. It replaces the two labelled axes and the three sum tiles; the axes' point — capture
 * state and payment state are independent questions, so the counts do not add up — stays true and is
 * visible through the sub-filters. See [InvoiceListDto.Bucket].
 *
 * The rows are laid out by hand rather than with `PosDataTable`, whose cells are plain strings: a
 * row here carries a money amount (which must go through [PosMoneyText], never a hand-formatted
 * string), a progress indicator, a status and one primary action that depends on the row's state.
 *
 * A row whose invoice arrived as bare XML or was entered by hand has no document behind it. It
 * opens through [onOpenInvoice] — the invoice-keyed capture form — while a row that does have a
 * document opens through [onCapture]. Every row is openable.
 *
 * @param onOpenAllocations leads to the Zuordnung queue ("Zuordnen" on a row, the next-step bar).
 *   The queue lists bank transactions, not invoices, so no invoice is preselected there.
 */
@Composable
fun InvoicesScreen(
    onCapture: (Long) -> Unit,
    onOpenInvoice: (Long) -> Unit,
    onOpenAllocations: () -> Unit,
    initialBucket: String = "",
) {
    val viewModel: InvoicesViewModel = koinViewModel()
    val state = viewModel.state
    val bookkeeping = viewModel.bookkeeping
    val canWriteAccounting = koinInject<TokenStore>().canWriteAccounting
    val policy = LocalAccessPolicy.current
    val mayAssign = policy.allows(Capability.ACCOUNTING_READ)
    val mayCapture = InvoicesAccess.of(policy).mayCapture
    val mayUpload = DocumentsAccess.of(policy).mayWrite
    // The upload actions open the upload dialog directly; its outcome appears under the header.
    var showImport by remember { mutableStateOf(false) }
    val layout = LocalPosLayout.current
    val phone = layout.isCompact

    // LaunchedEffect, not VM init: adminApp VMs share one ViewModelStoreOwner and survive logout,
    // so init would show the previous session's invoices.
    //
    // An empty initialBucket is the sidebar's "none named": plain load(), so the screen keeps its
    // redirect off an empty opening bucket. A named bucket is a dashboard tile (or a Belegfluss
    // strip on another screen) stating where to land, and openBucket suppresses that redirect.
    LaunchedEffect(initialBucket) {
        val explicit = InvoiceListDto.Bucket.entries.firstOrNull { it.name == initialBucket }
        if (explicit == null) viewModel.load() else viewModel.openBucket(explicit)
    }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.InvoicesTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    // The strip's Zuordnen tile states open payments, like on every other screen (the sub-chips below
    // keep counting invoices: they filter this list).
    val flow = state.flowCounts().withOpenPayments(rememberOpenPayments(state.bucketCounts))
    val handlers =
        InvoiceRowHandlers(
            onCapture = onCapture,
            onOpenInvoice = onOpenInvoice,
            onMarkReviewed = viewModel::markReviewed,
            onAssign = onOpenAllocations,
        )
    // The next-step action of a capture/review stage switches the bucket here; the assign stage's
    // leads to Zuordnung, which the principal may not be allowed to read.
    val nextStepBar: @Composable () -> Unit = {
        InvoiceNextStepBar(
            counts = flow,
            onOpen = { stage -> if (stage == InvoiceFlowStage.ASSIGN) onOpenAllocations() else viewModel.selectBucket(stage.bucket) },
            canOpen = { stage -> stage != InvoiceFlowStage.ASSIGN || mayAssign },
        )
    }

    // "Rechnung erfassen" opens the Erfassen bucket, where the scans wait; once there it opens the
    // first one in the capture form. There is no way to enter an invoice without a file (no API).
    val captureInvoice: () -> Unit = {
        if (state.bucket != InvoiceListDto.Bucket.ZU_ERFASSEN) {
            viewModel.selectBucket(InvoiceListDto.Bucket.ZU_ERFASSEN)
        } else {
            state.items.firstNotNullOfOrNull { it.documentId }?.let(onCapture)
        }
    }

    AdminListScaffold(
        title = str(AdminStringKeys.InvoicesTitle),
        subtitle =
            str(if (phone || layout == PosLayout.Medium) AdminStringKeys.BelegflowInvoicesSubtitleShort else AdminStringKeys.BelegflowInvoicesSubtitleLong),
        actions =
            if (phone || (!mayUpload && !mayCapture)) {
                null
            } else {
                {
                    if (mayUpload) {
                        PosSecondaryButton(
                            text = str(AdminStringKeys.BelegflowActionUploadDocument),
                            icon = Icons.Default.Upload,
                            onClick = { showImport = true },
                        )
                    }
                    if (mayCapture) {
                        PosButton(
                            text = str(AdminStringKeys.BelegflowActionCaptureInvoice),
                            icon = Icons.Default.Add,
                            onClick = captureInvoice,
                        )
                    }
                }
            },
        toolbar = {
            if (phone && (mayUpload || mayCapture)) {
                Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    if (mayCapture) {
                        PosButton(
                            text = str(AdminStringKeys.BelegflowActionCaptureInvoice),
                            icon = Icons.Default.Add,
                            onClick = captureInvoice,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (mayUpload) {
                        PosIconButton(
                            icon = Icons.Default.Upload,
                            contentDescription = str(AdminStringKeys.BelegflowActionUploadDocument),
                            onClick = { showImport = true },
                            size = 48.dp,
                        )
                    }
                }
            }
            if (mayUpload) {
                DocumentImportCard(
                    dialogOpen = showImport,
                    onDialogDismiss = { showImport = false },
                    onUploaded = { viewModel.load() },
                    onOpenDocument = onCapture,
                    allowTypeChoice = false,
                )
            }
            val startDate = bookkeeping.state.startDate
            if (bookkeeping.state.showBanner) {
                BuchungsbeginnBanner(
                    startDate = startDate.orEmpty(),
                    canChange = canWriteAccounting,
                    onChange = bookkeeping::startEditing,
                    onDismiss = bookkeeping::acknowledge,
                )
            } else if (bookkeeping.state.showCurrentDate) {
                BuchungsbeginnLine(
                    startDate = startDate.orEmpty(),
                    canChange = canWriteAccounting,
                    onChange = bookkeeping::startEditing,
                )
            }
        },
        filters = {
            Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                InvoiceFlowStrip(
                    counts = flow,
                    selected = InvoiceFlow.stageOf(state.bucket),
                    onSelect = { stage -> if (stage != InvoiceFlow.stageOf(state.bucket)) viewModel.selectBucket(stage.bucket) },
                )
                if (InvoiceFlow.stageOf(state.bucket) == InvoiceFlowStage.ASSIGN) {
                    AssignSubFilters(state = state, onSelect = viewModel::selectBucket)
                }
                ResultAndSums(state)
            }
        },
        isLoading = state.isLoading,
        isEmpty = state.items.isEmpty() && !state.isLoading,
        empty = {
            Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.xl)) {
                AdminListEmptyState(
                    message = str(emptyMessageKey(state.bucket)),
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                )
                nextStepBar()
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.xl)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < TABLE_MIN_WIDTH) {
                    InvoiceCardList(items = state.items, actionsEnabled = !state.isLoading, handlers = handlers)
                } else {
                    InvoiceTable(items = state.items, actionsEnabled = !state.isLoading, handlers = handlers)
                }
            }
            nextStepBar()
        }
    }

    if (bookkeeping.state.isEditing) {
        BuchungsbeginnDialog(
            currentValue = bookkeeping.state.startDate.orEmpty(),
            isSaving = bookkeeping.state.isSaving,
            onSave = viewModel::saveBookkeepingStart,
            onDismiss = bookkeeping::cancelEditing,
        )
    }
}

/**
 * The sub-filters of the Zuordnen stage: *Alle offen*, *Überfällig* (a narrowing of it) and
 * *Überzahlt* (its negative half). Shown only while the stage is selected; each carries its count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignSubFilters(
    state: InvoicesState,
    onSelect: (InvoiceListDto.Bucket) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.md),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        InvoiceFlow.ASSIGN_BUCKETS.forEach { bucket ->
            val label =
                if (bucket == InvoiceListDto.Bucket.OFFEN) str(AdminStringKeys.BelegflowChipAllOpen) else str(bucketLabelKey(bucket))
            FilterChip(
                selected = bucket == state.bucket,
                onClick = { onSelect(bucket) },
                label = { Text(label) },
                trailingIcon = { Text(state.countOf(bucket).toString()) },
                modifier = Modifier.heightIn(min = adminTouchSize(32.dp)),
            )
        }
    }
}

/** "Überfällig: 4 Rechnungen" on the left, what is open and what is late in money on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultAndSums(state: InvoicesState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(invoicesResultLineKey(state), str(bucketLabelKey(state.bucket)), invoicesResultCount(state)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.xl), verticalAlignment = Alignment.CenterVertically) {
            SumFigure(state, InvoiceListDto.Bucket.OFFEN)
            SumFigure(state, InvoiceListDto.Bucket.UEBERFAELLIG)
        }
    }
}

/**
 * One payment figure: a bucket's name and what it is worth. Late money is the one figure worth
 * colouring; a bucket the server sent no sum for draws nothing rather than a zero.
 */
@Composable
private fun SumFigure(
    state: InvoicesState,
    bucket: InvoiceListDto.Bucket,
) {
    val sum = state.sumOf(bucket) ?: return
    val critical = bucket == InvoiceListDto.Bucket.UEBERFAELLIG && state.countOf(bucket) > 0
    Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = str(bucketLabelKey(bucket)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosMoneyText(
            amount = sum,
            currencyCode = state.currency,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                ),
        )
    }
}

@Composable
private fun InvoiceTable(
    items: List<InvoiceListDto.RowDto>,
    actionsEnabled: Boolean,
    handlers: InvoiceRowHandlers,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        InvoiceHeaderRow()
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        items.forEach { row ->
            InvoiceRow(row = row, actionsEnabled = actionsEnabled, handlers = handlers)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun InvoiceHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell(AdminStringKeys.InvoicesColumnSupplier, SUPPLIER_WEIGHT)
        HeaderCell(AdminStringKeys.InvoicesColumnNumber, NUMBER_WEIGHT)
        HeaderCell(AdminStringKeys.InvoicesColumnDate, DATE_WEIGHT)
        HeaderCell(AdminStringKeys.InvoicesColumnDue, DUE_WEIGHT)
        HeaderCell(AdminStringKeys.InvoicesColumnAmount, AMOUNT_WEIGHT, TextAlign.End, endGutter = true)
        HeaderCell(AdminStringKeys.InvoicesColumnOpen, OPEN_WEIGHT, TextAlign.End, endGutter = true)
        HeaderCell(AdminStringKeys.BelegflowColumnProgress, PROGRESS_WEIGHT)
        HeaderCell(CommonKeys.status, STATUS_WEIGHT)
        Spacer(Modifier.weight(ACTION_WEIGHT))
    }
}

@Composable
private fun RowScope.HeaderCell(
    key: StringKey,
    weight: Float,
    align: TextAlign = TextAlign.Start,
    endGutter: Boolean = false,
) {
    Text(
        text = str(key),
        modifier = Modifier.weight(weight).padding(top = PosSpacing.sm, bottom = PosSpacing.sm, start = 4.dp, end = if (endGutter) MONEY_GUTTER else 4.dp),
        style = MaterialTheme.typography.labelLarge,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InvoiceRow(
    row: InvoiceListDto.RowDto,
    actionsEnabled: Boolean,
    handlers: InvoiceRowHandlers,
) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        SupplierCell(row)
        NumberCell(row)
        BodyCell(invoiceListDate(row.issueDate, LocalAppLanguage.current), DATE_WEIGHT, nowrap = true)
        DueCell(row)
        AmountCell(row)
        OpenAmountCell(row)
        Row(Modifier.weight(PROGRESS_WEIGHT).padding(horizontal = 4.dp)) { InvoiceRowProgress(row.bucket) }
        Row(Modifier.weight(STATUS_WEIGHT).padding(horizontal = 4.dp)) { InvoiceRowStatus(row.bucket) }
        Row(
            modifier = Modifier.weight(ACTION_WEIGHT).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InvoiceActionButtons(row = row, actionsEnabled = actionsEnabled, handlers = handlers, height = 40.dp, filled = true)
        }
    }
}

/** The supplier, with how the invoice arrived (e-invoice format or scan) in small print beneath. */
@Composable
private fun RowScope.SupplierCell(row: InvoiceListDto.RowDto) {
    Column(modifier = Modifier.weight(SUPPLIER_WEIGHT).padding(vertical = PosSpacing.sm, horizontal = 4.dp)) {
        if (row.bucket == InvoiceListDto.Bucket.ZU_ERFASSEN && row.supplierName.isNullOrBlank()) {
            // An uncaptured scan carries no invoice data yet; its file name is the only identifier.
            // NEW-05: scan names share their start, so the middle is elided, not the end.
            PosMiddleEllipsisText(
                text = uncapturedScanTitle(row),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(row.supplierName.orDash(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = invoiceSourceLabel(row),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowScope.BodyCell(
    text: String,
    weight: Float,
    nowrap: Boolean = false,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        textAlign = align,
        modifier =
            Modifier.weight(weight).padding(
                top = PosSpacing.sm,
                bottom = PosSpacing.sm,
                start = 4.dp,
                end = if (align == TextAlign.End) MONEY_GUTTER else 4.dp,
            ),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (nowrap) 1 else Int.MAX_VALUE,
        softWrap = !nowrap,
        overflow = TextOverflow.Clip,
    )
}

/** The invoice number, with a "storniert" marker under it when the invoice has been cancelled. */
@Composable
private fun RowScope.NumberCell(row: InvoiceListDto.RowDto) {
    Column(modifier = Modifier.weight(NUMBER_WEIGHT).padding(vertical = PosSpacing.sm, horizontal = 4.dp)) {
        // NEW-05: numbers share long prefixes; keep the distinguishing end.
        PosMiddleEllipsisText(
            text = row.invoiceNumber.orDash(),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (row.cancelled) InvoiceCancelledMarker()
    }
}

/** The gross total stays a string all the way to [PosMoneyText], with the row's own currency. */
@Composable
private fun RowScope.AmountCell(row: InvoiceListDto.RowDto) {
    val amount = row.grossTotal
    if (amount == null) {
        BodyCell(EMPTY_VALUE, AMOUNT_WEIGHT, align = TextAlign.End)
        return
    }
    PosMoneyText(
        amount = amount,
        currencyCode = row.currency,
        modifier = Modifier.weight(AMOUNT_WEIGHT).padding(top = PosSpacing.sm, bottom = PosSpacing.sm, start = 4.dp, end = MONEY_GUTTER),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
    )
}

/** Late is worth seeing at a glance, so an overdue row's due date carries the error colour — keyed off `row.overdue`, not the bucket, so a late invoice listed under Zu prüfen is marked too. */
@Composable
private fun RowScope.DueCell(row: InvoiceListDto.RowDto) {
    Text(
        text = invoiceListDate(row.dueDate, LocalAppLanguage.current),
        modifier = Modifier.weight(DUE_WEIGHT).padding(vertical = PosSpacing.sm, horizontal = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        color =
            if (row.overdue) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    )
}

/** The residual stays a string all the way to [PosMoneyText], with the row's own currency; it is the figure the row is about, so it is bold. */
@Composable
private fun RowScope.OpenAmountCell(row: InvoiceListDto.RowDto) {
    val open = row.openAmount
    if (open == null) {
        BodyCell(EMPTY_VALUE, OPEN_WEIGHT, align = TextAlign.End)
        return
    }
    PosMoneyText(
        amount = open,
        currencyCode = row.currency,
        modifier = Modifier.weight(OPEN_WEIGHT).padding(top = PosSpacing.sm, bottom = PosSpacing.sm, start = 4.dp, end = MONEY_GUTTER),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.End,
    )
}

/**
 * The row's actions, shared by the wide table and the phone list so the two cannot drift apart: the
 * one primary action [InvoiceFlow.rowAction] picks for the row's state (filled for Erfassen, Prüfen
 * and Zuordnen, plain for Öffnen), plus the quiet "mark as reviewed" tick on a row to review.
 *
 * @param height the control height: 40dp in the table, the 48dp touch size on a phone.
 * @param filled filled buttons only in the large table; the phone and tablet list draw the action as a
 *   plain green text link under the amount, as the design does (Öffnen is a text link everywhere).
 */
@Composable
internal fun InvoiceActionButtons(
    row: InvoiceListDto.RowDto,
    actionsEnabled: Boolean,
    handlers: InvoiceRowHandlers,
    height: Dp,
    filled: Boolean,
) {
    val policy = LocalAccessPolicy.current
    val access = InvoicesAccess.of(policy)
    val action =
        InvoiceFlow.rowAction(row.bucket, mayCapture = access.mayCapture, mayAssign = policy.allows(Capability.ACCOUNTING_READ))
    // The document is preferred when there is one: that form shows the scan beside the fields.
    // Without one the invoice's own id is the only key there is.
    val documentId = row.documentId
    val invoiceId = row.invoiceId
    if (action != null) {
        val label = str(rowActionKey(action))
        val onClick: () -> Unit = {
            when {
                action == InvoiceRowAction.ASSIGN -> handlers.onAssign()
                documentId != null -> handlers.onCapture(documentId)
                invoiceId != null -> handlers.onOpenInvoice(invoiceId)
            }
        }
        val enabled = actionsEnabled && (action == InvoiceRowAction.ASSIGN || documentId != null || invoiceId != null)
        if (action == InvoiceRowAction.OPEN || !filled) {
            PosTextButton(text = label, onClick = onClick, enabled = enabled, height = height)
        } else {
            PosButton(text = label, onClick = onClick, enabled = enabled, height = height)
        }
    }
    if (row.bucket == InvoiceListDto.Bucket.ZU_PRUEFEN && invoiceId != null && access.mayMarkReviewed) {
        // The secondary action is an icon: two German text labels beside each other clipped to
        // illegibility in the narrow action cell, and an icon scales if a third ever lands here.
        PosIconButton(
            icon = Icons.Default.Check,
            contentDescription = str(AdminStringKeys.InvoicesMarkReviewed),
            onClick = { handlers.onMarkReviewed(invoiceId) },
            enabled = actionsEnabled,
        )
    }
}

/** How the invoice arrived: its e-invoice format, or "Scan" for a scanned document. */
@Composable
internal fun invoiceSourceLabel(row: InvoiceListDto.RowDto): String = row.eInvoiceFormat?.let(::eInvoiceFormatLabel) ?: str(AdminStringKeys.InvoicesSourceScan)

/**
 * A *Datum* or *Fällig* cell: the wire's ISO `LocalDateTime` as a short localized date
 * (`15.01.2026`, `2026年01月15日`), or `—` when the invoice carries none.
 *
 * Rendered raw, the Datum column showed `2026-06-01T00:00` — a time nobody entered — and after
 * cutting at `T` still the ISO `2026-06-01` beside every other localized date in the console.
 * Kept pure so the format is pinned by a test.
 */
internal fun invoiceListDate(
    isoDateTime: String?,
    language: Language,
): String = isoDateTime?.takeIf { it.isNotBlank() }?.formatShortDate(language) ?: EMPTY_VALUE

/** What identifies an uncaptured scan: its file name, as there is no supplier or number yet. */
internal fun uncapturedScanTitle(row: InvoiceListDto.RowDto): String = row.filename.orDash()

private fun rowActionKey(action: InvoiceRowAction): StringKey =
    when (action) {
        InvoiceRowAction.CAPTURE -> AdminStringKeys.InvoicesCapture
        InvoiceRowAction.REVIEW -> AdminStringKeys.BelegflowStageReview
        InvoiceRowAction.ASSIGN -> AdminStringKeys.BelegflowStageAssign
        InvoiceRowAction.OPEN -> AdminStringKeys.InvoicesOpen
    }

private fun bucketLabelKey(bucket: InvoiceListDto.Bucket): StringKey =
    when (bucket) {
        InvoiceListDto.Bucket.ZU_ERFASSEN -> AdminStringKeys.InvoicesBucketToCapture
        InvoiceListDto.Bucket.ZU_PRUEFEN -> AdminStringKeys.InvoicesBucketToReview
        InvoiceListDto.Bucket.FERTIG -> AdminStringKeys.InvoicesBucketDone
        InvoiceListDto.Bucket.OFFEN -> AdminStringKeys.InvoicesBucketOpen
        InvoiceListDto.Bucket.UEBERFAELLIG -> AdminStringKeys.InvoicesBucketOverdue
        InvoiceListDto.Bucket.UEBERZAHLT -> AdminStringKeys.InvoicesBucketOverpaid
    }

private fun emptyMessageKey(bucket: InvoiceListDto.Bucket): StringKey =
    when (bucket) {
        InvoiceListDto.Bucket.ZU_ERFASSEN -> AdminStringKeys.InvoicesEmptyToCapture
        InvoiceListDto.Bucket.ZU_PRUEFEN -> AdminStringKeys.InvoicesEmptyToReview
        InvoiceListDto.Bucket.FERTIG -> AdminStringKeys.InvoicesEmptyDone
        InvoiceListDto.Bucket.OFFEN -> AdminStringKeys.InvoicesEmptyOpen
        InvoiceListDto.Bucket.UEBERFAELLIG -> AdminStringKeys.InvoicesEmptyOverdue
        InvoiceListDto.Bucket.UEBERZAHLT -> AdminStringKeys.InvoicesEmptyOverpaid
    }
