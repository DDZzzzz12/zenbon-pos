package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton

/**
 * Header for the document detail two-pane layout: back, filename + type pill, then download and the
 * delete/save actions. Each write carries the capability its route names
 * (`DocumentRules`/`InvoiceRules`): Save is [canSave] (`DOCUMENT_WRITE`), Delete is [canDelete]
 * (`DOCUMENT_DELETE`), Stornieren is [mayCancelInvoice] (`INVOICE_WRITE`). The server enforces the
 * same rules, this only hides the controls (CLAUDE.md rule 8).
 *
 * A Beleg an invoice hangs off ([hasInvoice]) is never deleted (T9-19): in place of *Löschen* it
 * offers *Stornieren*, which cancels the invoice by a correction and keeps both. That action is
 * disabled once the invoice is cancelled ([canCancelInvoice]); the server refuses a second
 * correction too.
 *
 * Download stays here for every file type, also for a PDF whose viewer has its own: it is the one way
 * to get at a file the viewer cannot open (a password-protected or damaged PDF) and it is reachable
 * from the "Daten" pane on a phone. Print and "open in a new tab" act on the PDF itself and sit in the
 * viewer's toolbar. [compact] (a phone) is one row: download, delete/cancel and save are icons beside
 * the title, so the header costs one row of height instead of two (NEW-24).
 */
@Composable
fun DocumentHeaderBar(
    filename: String,
    fileType: String,
    canSave: Boolean,
    canDelete: Boolean,
    mayCancelInvoice: Boolean,
    hasInvoice: Boolean,
    canCancelInvoice: Boolean,
    isDirty: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancelInvoice: () -> Unit,
    onSave: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        CompactDocumentHeader(
            filename = filename,
            fileType = fileType,
            canSave = canSave,
            canDelete = canDelete,
            mayCancelInvoice = mayCancelInvoice,
            hasInvoice = hasInvoice,
            canCancelInvoice = canCancelInvoice,
            isDirty = isDirty,
            isSaving = isSaving,
            onBack = onBack,
            onDownload = onDownload,
            onDelete = onDelete,
            onCancelInvoice = onCancelInvoice,
            onSave = onSave,
            modifier = modifier,
        )
        return
    }
    val primaryActions: @Composable () -> Unit = {
        DocumentPrimaryActions(
            canSave = canSave,
            canDelete = canDelete,
            mayCancelInvoice = mayCancelInvoice,
            hasInvoice = hasInvoice,
            canCancelInvoice = canCancelInvoice,
            isDirty = isDirty,
            isSaving = isSaving,
            onDelete = onDelete,
            onCancelInvoice = onCancelInvoice,
            onSave = onSave,
        )
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = str(CommonKeys.back),
            onClick = onBack,
        )
        Spacer(Modifier.width(8.dp))
        // Takes every pixel the actions leave; the pill sits right after the (possibly cut) name.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = filename,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            DocumentTypePill(fileType)
        }
        Spacer(Modifier.width(8.dp))
        // Download, Delete/Cancel and Save stay pinned at the right edge so they never scroll out of reach.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosSecondaryButton(text = str(AdminStringKeys.DocumentDownload), onClick = onDownload)
            primaryActions()
        }
    }
}

@Composable
private fun DocumentPrimaryActions(
    canSave: Boolean,
    canDelete: Boolean,
    mayCancelInvoice: Boolean,
    hasInvoice: Boolean,
    canCancelInvoice: Boolean,
    isDirty: Boolean,
    isSaving: Boolean,
    onDelete: () -> Unit,
    onCancelInvoice: () -> Unit,
    onSave: () -> Unit,
) {
    if (hasInvoice) {
        if (mayCancelInvoice) {
            PosDangerOutlinedButton(
                text = str(AdminStringKeys.DocumentCancelInvoice),
                onClick = onCancelInvoice,
                enabled = canCancelInvoice,
            )
        }
    } else if (canDelete) {
        PosDangerOutlinedButton(text = str(AdminStringKeys.DocumentDelete), onClick = onDelete)
    }
    if (canSave) {
        PosButton(
            text = str(CommonKeys.save),
            onClick = onSave,
            enabled = isDirty && !isSaving,
            isLoading = isSaving,
        )
    }
}

/**
 * The phone header: one row, back, name over type pill, then download, delete (or cancel the
 * invoice) and save as icons. Text buttons here made a second row, and Save ran off the edge.
 * Every icon keeps its text as the content description, and the same gates and enabled rules as
 * [DocumentPrimaryActions].
 */
@Composable
private fun CompactDocumentHeader(
    filename: String,
    fileType: String,
    canSave: Boolean,
    canDelete: Boolean,
    mayCancelInvoice: Boolean,
    hasInvoice: Boolean,
    canCancelInvoice: Boolean,
    isDirty: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancelInvoice: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val danger = MaterialTheme.colorScheme.error
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PosIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = str(CommonKeys.back),
            onClick = onBack,
            size = 48.dp,
        )
        Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = filename,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DocumentTypePill(fileType)
        }
        PosIconButton(
            icon = Icons.Filled.Download,
            contentDescription = str(AdminStringKeys.DocumentDownload),
            onClick = onDownload,
            size = 48.dp,
        )
        if (hasInvoice) {
            if (mayCancelInvoice) {
                PosIconButton(
                    icon = Icons.Filled.Block,
                    contentDescription = str(AdminStringKeys.DocumentCancelInvoice),
                    onClick = onCancelInvoice,
                    enabled = canCancelInvoice,
                    tint = danger,
                    size = 48.dp,
                )
            }
        } else if (canDelete) {
            PosIconButton(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = str(AdminStringKeys.DocumentDelete),
                onClick = onDelete,
                tint = danger,
                size = 48.dp,
            )
        }
        if (canSave) {
            PosIconButton(
                icon = Icons.Filled.Save,
                contentDescription = str(CommonKeys.save),
                onClick = onSave,
                enabled = isDirty && !isSaving,
                tint = MaterialTheme.colorScheme.primary,
                size = 48.dp,
            )
        }
    }
}
