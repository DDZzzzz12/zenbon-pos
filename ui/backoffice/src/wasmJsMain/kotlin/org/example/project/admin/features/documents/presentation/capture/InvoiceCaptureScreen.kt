package org.example.project.admin.features.documents.presentation.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlow
import org.example.project.admin.core.presentation.belegfluss.InvoiceFlowStrip
import org.example.project.admin.core.presentation.belegfluss.rememberInvoiceFlowCounts
import org.example.project.admin.core.presentation.layout.PdfFormPane
import org.example.project.admin.core.presentation.layout.PdfFormPaneHost
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentPreviewKind
import org.example.project.admin.features.documents.presentation.capture.components.InvoiceBookingSection
import org.example.project.admin.features.documents.presentation.capture.components.InvoiceContentSection
import org.example.project.admin.features.documents.presentation.capture.components.VatBreakdownSection
import org.example.project.admin.features.documents.presentation.components.DocumentNoPreviewPane
import org.example.project.admin.features.documents.presentation.previewKind
import org.example.project.admin.features.invoices.presentation.InvoicesAccess
import org.example.project.admin.features.supplier.presentation.components.CreateSupplierDialog
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.beleg.BELEG_PROGRESS_STEPS
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.pdf.PdfSource
import org.example.project.ui.components.pdf.PdfViewer
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.LocalPosColors
import org.koin.compose.viewmodel.koinViewModel

private val FORM_WIDTH = 520.dp

/**
 * Rechnungserfassung — capture or check one document's invoice data beside its PDF.
 *
 * The page does not scroll as a whole: both panes take the full height and scroll internally (the
 * shared `PdfViewer` its pages, the form column its fields). The viewer paints into the Compose canvas,
 * so dialogs and toasts draw over it like over any other content.
 *
 * Widths: the form gets 520 dp beside the PDF, not `DocumentDetailScreen`'s 360 dp, because a
 * rate-plus-gross row measures unusably narrow at 360. Below 840dp of content width the two panes
 * switch ("Beleg | Daten") instead of stacking, and this screen opens on the form, because here the
 * form is the task and the PDF is the reference.
 */
@Composable
internal fun InvoiceCaptureScreen(
    documentId: Long,
    onBack: () -> Unit,
    onAssign: () -> Unit,
    onOpenInvoices: (String) -> Unit,
    viewModel: InvoiceCaptureViewModel = koinViewModel(),
) {
    val canManage = InvoicesAccess.of(LocalAccessPolicy.current).mayCapture
    LaunchedEffect(documentId) { viewModel.load(documentId, canManage) }
    CaptureBody(
        viewModel = viewModel,
        canManage = canManage,
        hasDocument = true,
        onBack = onBack,
        onAssign = onAssign,
        onOpenInvoices = onOpenInvoices,
        onRetry = { viewModel.load(documentId, canManage) },
    )
}

/**
 * The same form, reached by the invoice's own id — for an invoice that has **no document**, because
 * it arrived as a bare XRechnung XML or was entered by hand, and for a Storno correction, which is
 * opened by its own id but sits on the original's Beleg and therefore shows that file.
 *
 * A second entry point rather than a nullable `documentId`: the two identities are genuinely
 * different, and both hand off to the same [CaptureBody] so the form cannot drift between them.
 */
@Composable
internal fun InvoiceEditScreen(
    invoiceId: Long,
    onBack: () -> Unit,
    onAssign: () -> Unit,
    onOpenInvoices: (String) -> Unit,
    viewModel: InvoiceCaptureViewModel = koinViewModel(),
) {
    val canManage = InvoicesAccess.of(LocalAccessPolicy.current).mayEditInvoice
    LaunchedEffect(invoiceId) { viewModel.loadByInvoice(invoiceId, canManage) }
    CaptureBody(
        viewModel = viewModel,
        canManage = canManage,
        // Not a constant: a Storno correction is opened by its own id yet sits on the original's
        // Beleg, and must show that file rather than claim it has none (R6-2).
        hasDocument = viewModel.state.belegDocumentId != null,
        onBack = onBack,
        onAssign = onAssign,
        onOpenInvoices = onOpenInvoices,
        onRetry = { viewModel.loadByInvoice(invoiceId, canManage) },
    )
}

/**
 * Everything both entry points share. On the document-keyed path [hasDocument] is fixed by the entry
 * point, so a half-loaded document screen never renders as the document-less one; the invoice-keyed
 * path derives it from the loaded invoice's Beleg.
 */
@Composable
private fun CaptureBody(
    viewModel: InvoiceCaptureViewModel,
    canManage: Boolean,
    hasDocument: Boolean,
    onBack: () -> Unit,
    onAssign: () -> Unit,
    onOpenInvoices: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val state = viewModel.state
    // "Speichern und zuordnen" leads to the Zuordnung queue, which is the ACCOUNTING_READ screen: a
    // principal who cannot read it is not offered the button.
    val mayAssign = LocalAccessPolicy.current.allows(Capability.ACCOUNTING_READ)
    // The mini strip (Prüfen selected on a document being checked); absent without INVOICE_READ.
    val flowCounts = rememberInvoiceFlowCounts()
    val footer: @Composable () -> Unit = {
        if (canManage) {
            CaptureFooter(
                state = state,
                mayAssign = mayAssign,
                onBack = onBack,
                onSave = viewModel::save,
                onSaveAndAssign = { viewModel.saveThen(onAssign) },
                onConfirmReviewed = viewModel::confirmReviewed,
            )
        }
    }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.InvoiceCaptureTitle),
        onClear = viewModel::clearError,
        retryAction = onRetry,
    )

    // A toast, not a dialog: the save already re-baselines `pristine`, which greys the Save button
    // out, so this only has to say that the greying meant success rather than a swallowed failure.
    PosSuccessWatcher(
        message = state.successMessage?.let { str(it) },
        // The heading follows the form's own title: checking a stored invoice says "prüfen", a new capture "erfassen".
        title = str(captureTitleKeyFor(state.invoice != null)),
        onClear = viewModel::clearSuccessMessage,
    )

    // Leaving is guarded centrally (AdminNavHost back/go); the screen reseeds on load, so Discard has nothing to reset.
    rememberDirtySource(dirty = { viewModel.state.isDirty }, onDiscard = viewModel::discardDraft)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < PaneBreakpoints.PHONE_MAX
        Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 16.dp)) {
            CaptureHeaderBar(state = state, compact = compact, onBack = onBack)
            Spacer(Modifier.height(12.dp))
            flowCounts?.let { counts ->
                InvoiceFlowStrip(
                    counts = counts,
                    selected = InvoiceFlow.stageForCapture(state.document?.processingStatus),
                    onSelect = { stage -> onOpenInvoices(stage.bucket.name) },
                )
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (!hasDocument) {
                // No preview pane at all: there is no file to show, so the form takes the whole width
                // instead of leaving a dead gap.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Not while loading: the invoice-keyed path only learns whether there is a Beleg
                    // once the invoice arrives, and must not claim there is none before that.
                    if (!state.isLoading) {
                        Text(
                            text = str(AdminStringKeys.InvoiceCaptureNoDocumentFile),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FormColumn(state, canManage, viewModel, Modifier.fillMaxWidth().weight(1f), footer)
                }
            } else {
                PdfFormPaneHost(
                    initialPane = PdfFormPane.FORM,
                    formWidth = FORM_WIDTH,
                    pdf = { paneModifier ->
                        when (previewKind(state.fileMediaType)) {
                            DocumentPreviewKind.PDF -> CapturePdfPane(state.fileBytes, state.isLoading, captureFileName(state), paneModifier)
                            DocumentPreviewKind.NONE -> DocumentNoPreviewPane(modifier = paneModifier)
                        }
                    },
                    form = { paneModifier -> FormColumn(state, canManage, viewModel, paneModifier, footer) },
                )
            }
        }
    }

    if (state.isLoading) {
        PosLoadingOverlay(title = str(AdminStringKeys.InvoiceCaptureTitle))
    }
}

/** The name the viewer's download saves under; the invoice-keyed path has no document record to name it. */
private fun captureFileName(state: InvoiceCaptureState): String = state.document?.filename ?: "beleg-${state.belegDocumentId}.pdf"

/**
 * The Beleg beside the form, from the bytes the view model already holds ([InvoiceCaptureState.fileBytes]).
 * The source is remembered per byte array: `PdfViewer` compares sources by identity, so building one
 * inline would reopen the document on every keystroke in the form. No bytes after loading means the
 * file could not be fetched (an invoice-keyed Storno's Beleg is loaded best-effort), so the pane says so.
 */
@Composable
private fun CapturePdfPane(
    fileBytes: ByteArray?,
    isLoading: Boolean,
    fileName: String,
    modifier: Modifier,
) {
    if (fileBytes == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (!isLoading) Text(str(AdminStringKeys.DocumentPdfUnavailable))
        }
        return
    }
    val source = remember(fileBytes) { PdfSource.Bytes(fileBytes) }
    PdfViewer(source = source, fileName = fileName, modifier = modifier)
}

/**
 * Back, the title and a one-line subtitle ("Lieferant · Schritt 2 von 4").
 *
 * A document the server already extracted (`COMPLETED`) is being *checked*, not entered, so the title
 * says so — the same distinction the entry point on the detail screen makes. The write actions moved
 * to [CaptureFooter] under the form, where the operator ends up after the last field.
 */
@Composable
private fun CaptureHeaderBar(
    state: InvoiceCaptureState,
    compact: Boolean,
    onBack: () -> Unit,
) {
    val title = str(captureTitleKeyFor(state.invoice != null))
    val step = InvoiceFlow.stageForCapture(state.document?.processingStatus).ordinal + 1
    val seller = state.form.sellerName.trim()
    val subtitle =
        if (seller.isEmpty()) {
            str(AdminStringKeys.BelegflowProgressStep, step, BELEG_PROGRESS_STEPS)
        } else {
            str(AdminStringKeys.BelegflowCaptureSubtitle, seller, step, BELEG_PROGRESS_STEPS)
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = str(CommonKeys.back),
            onClick = onBack,
            size = if (compact) 48.dp else 40.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The write actions at the foot of the form: *Geändert* while there are unsaved edits, *Speichern und
 * zuordnen* (primary, full width, with ACCOUNTING_READ), then *Abbrechen* beside *Speichern*, and
 * *Als geprüft bestätigen* beneath. Nothing is greyed out for an invalid form: pressing validates
 * (UI-E2-06). Hidden altogether for a principal who may not write.
 *
 * *Abbrechen* leaves through the guarded back, so unsaved edits ask first.
 */
@Composable
private fun CaptureFooter(
    state: InvoiceCaptureState,
    mayAssign: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAndAssign: () -> Unit,
    onConfirmReviewed: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.isDirty) {
            Text(
                text = str(AdminStringKeys.BelegflowChanged),
                style = MaterialTheme.typography.labelMedium,
                color = LocalPosColors.current.posOccupied,
            )
        }
        if (mayAssign) {
            PosButton(
                text = str(AdminStringKeys.BelegflowActionSaveAndAssign),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = onSaveAndAssign,
                enabled = !state.isSaving && !state.stornoReadOnly,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onBack, modifier = Modifier.weight(1f))
            PosSecondaryButton(
                text = str(CommonKeys.save),
                onClick = onSave,
                enabled = state.isDirty && !state.isSaving && !state.stornoReadOnly,
                isLoading = state.isSaving,
                modifier = Modifier.weight(1f),
            )
        }
        PosSecondaryButton(
            text = str(AdminStringKeys.InvoiceCaptureConfirmReviewed),
            onClick = onConfirmReviewed,
            enabled = state.canConfirmReviewed,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A stored invoice is being checked and corrected, not entered: "Rechnungsdaten prüfen"; a new one is captured. */
private fun captureTitleKeyFor(hasInvoice: Boolean) =
    if (hasInvoice) {
        AdminStringKeys.InvoiceCaptureVerifyTitle
    } else {
        AdminStringKeys.InvoiceCaptureTitle
    }

/**
 * The only scrolling surface on the screen. Booking stays enabled on a supplier e-invoice — it is our
 * decision, not the supplier's assertion — while content and VAT follow the provenance guard.
 */
@Composable
private fun FormColumn(
    state: InvoiceCaptureState,
    canManage: Boolean,
    viewModel: InvoiceCaptureViewModel,
    modifier: Modifier,
    footer: @Composable () -> Unit,
) {
    val contentEnabled = canManage && !state.supplierReadOnly && !state.stornoReadOnly
    var showCreateSupplier by remember { mutableStateOf(false) }

    if (showCreateSupplier) {
        CreateSupplierDialog(
            initialName = state.form.sellerName,
            isSaving = state.isCreatingSupplier,
            error = state.supplierCreateError,
            onDismiss = {
                showCreateSupplier = false
                viewModel.clearSupplierCreateError()
            },
            onCreate = viewModel::createSupplier,
        )
    }

    // Closes itself once the creditor exists and the capture is attached to it.
    LaunchedEffect(state.matchedSupplier?.id) {
        if (state.matchedSupplier != null) showCreateSupplier = false
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.storno?.let { storno -> StornoReadOnlyNotice(storno, isCorrection = state.invoice?.id == storno.correctionInvoiceId) }
        // A refused save scrolls to and focuses the first field at fault, in on-screen order.
        val focus = rememberPosFormFocus<InvoiceCaptureField>()
        LaunchedEffect(state.validationAttempt) {
            if (state.validationAttempt > 0) focus.revealFirst(CAPTURE_FIELD_ORDER, state.fieldErrors)
        }
        val anchor: (InvoiceCaptureField) -> Modifier = { field -> with(focus) { Modifier.formAnchor(field) } }
        InvoiceContentSection(
            anchor = anchor,
            form = state.form,
            errors = state.fieldErrors,
            enabled = contentEnabled,
            supplierReadOnly = state.supplierReadOnly,
            supplierSuggestions = state.supplierSuggestions,
            matchedSupplier = state.matchedSupplier,
            supplierSearchAnswered = state.supplierSearchAnswered,
            onChange = viewModel::updateForm,
            onPickSupplier = viewModel::pickSupplier,
            onCreateSupplier = { showCreateSupplier = true },
            currencyCode = state.invoice?.currency.orEmpty(),
        )
        VatBreakdownSection(
            form = state.form,
            currencyCode = state.invoice?.currency.orEmpty(),
            errors = state.fieldErrors,
            rowErrors = state.rowErrors,
            enabled = contentEnabled,
            onChange = viewModel::updateForm,
            onAddRow = viewModel::addVatRow,
            onRemoveRow = viewModel::removeVatRow,
        )
        InvoiceBookingSection(
            anchor = anchor,
            form = state.form,
            errors = state.fieldErrors,
            enabled = canManage && !state.stornoReadOnly,
            onChange = viewModel::updateForm,
        )
        state.warnings.forEach { key ->
            Text(
                text = str(key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        footer()
    }
}

/** Why every field is disabled: this invoice is one half of a Storno (T9-19). */
@Composable
private fun StornoReadOnlyNotice(
    storno: DocumentDto.InvoiceStornoDto,
    isCorrection: Boolean,
) {
    val date = storno.cancelledAt.formatShortDate(LocalAppLanguage.current)
    val reason = storno.reason.orEmpty()
    Text(
        text =
            if (isCorrection) {
                str(AdminStringKeys.InvoiceStornoReadOnlyCorrection, storno.originalInvoiceId.toString(), date, reason)
            } else {
                str(AdminStringKeys.InvoiceStornoReadOnlyOriginal, date, reason)
            },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** The capture form's validated fields as they appear on screen (the enum is in validation order). */
private val CAPTURE_FIELD_ORDER =
    listOf(
        InvoiceCaptureField.PRECEDING_REFERENCE,
        InvoiceCaptureField.INVOICE_NUMBER,
        InvoiceCaptureField.ISSUE_DATE,
        InvoiceCaptureField.SELLER_NAME,
        InvoiceCaptureField.GROSS_TOTAL,
        InvoiceCaptureField.VAT_ROW_SUM,
        InvoiceCaptureField.BP_ACCOUNT_NO,
        InvoiceCaptureField.ACCOUNT_NO,
        InvoiceCaptureField.BOOKING_TEXT,
    )
