@file:OptIn(ExperimentalLayoutApi::class)

package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.scanner.ScannerScreen
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.file.PickedFile
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.theme.PosSpacing

/**
 * Where an upload starts: pick the document type, then either choose a file or scan one with the
 * camera. It only collects the decision; the host uploads through [DocumentUploadViewModel], so the
 * unlock prompt and the outcome panel stay where they were.
 *
 * Scanning swaps this dialog for a full-screen one that hosts [ScannerScreen]. The scanner's live
 * preview is a DOM `<video>` and must not share a dialog with a scrolling [PosDialog] (which
 * self-scrolls, so the scanner's fill-height layout would not measure), and there is nothing to
 * overlap it while it runs. Leaving it goes through the leave guard, because captured pages are
 * unsaved work; the scanner registers them as dirty itself.
 *
 * @param fileTypes the types on offer, as the server names them; the first entry that is
 *   [DocumentType.INVOICE] is preselected, else the first.
 * @param allowTypeChoice false pins the type to [DocumentType.INVOICE] (the Rechnungen screen).
 * @param onPickFile the operator chose "file": the host closes this dialog and opens the file dialog.
 * @param onScanned a finished scan (always a PDF) and the type chosen for it.
 */
@Composable
fun DocumentUploadDialog(
    fileTypes: List<String>,
    allowTypeChoice: Boolean,
    onDismiss: () -> Unit,
    onPickFile: (fileType: String) -> Unit,
    onScanned: (file: PickedFile, fileType: String) -> Unit,
) {
    val invoice = DocumentType.INVOICE.name
    var fileType by remember { mutableStateOf(invoice) }
    var scan by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val chosenType = if (allowTypeChoice) fileType else invoice
    val guard = LocalLeaveGuard.current

    if (scanning) {
        val stop = { guard.requestLeave { scanning = false } }
        Dialog(
            onDismissRequest = stop,
            properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ScannerScreen(
                    onResult = { file -> onScanned(file, chosenType) },
                    onCancel = stop,
                )
            }
        }
        return
    }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.DocumentUploadDialogTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(if (scan) AdminStringKeys.DocumentUploadStartScan else AdminStringKeys.DocumentUploadPickFile),
                onClick = { if (scan) scanning = true else onPickFile(chosenType) },
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) {
            if (allowTypeChoice) {
                TypeChoice(fileTypes = fileTypes, selected = chosenType, onSelect = { fileType = it })
            }
            Row(
                Modifier.fillMaxWidth().clickable { scan = !scan },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = scan, onCheckedChange = { scan = it })
                Text(str(AdminStringKeys.DocumentUploadScanToggle))
            }
        }
    }
}

@Composable
private fun TypeChoice(
    fileTypes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
        Text(str(AdminStringKeys.DocumentUploadTypeLabel), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm), verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
            fileTypes.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onSelect(type) },
                    label = { Text(documentTypeLabel(type, language)) },
                )
            }
        }
    }
}
