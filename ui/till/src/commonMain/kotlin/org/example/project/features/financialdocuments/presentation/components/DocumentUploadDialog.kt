/**
 * PDF Upload dialog component.
 *
 * Provides interface for uploading PDF documents: file selection or scanning, a document
 * type and a free-text description. Type-specific metadata is no longer typed here — invoice
 * data is entered in adminApp's capture form after upload (Stage 4).
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.docUtil.DocumentScannerButton
import org.example.project.core.presentation.components.fileBrowser.FileBrowserDialog
import org.example.project.core.presentation.components.fileBrowser.rememberDocumentPickerLauncher
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DocumentManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isDocumentScannerAvailable
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.features.financialdocuments.presentation.viewmodel.DocumentUploadViewModel
import org.example.project.ui.components.*
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

/**
 * Dialog for uploading PDF documents. The upload carries the file only.
 *
 * @param language Current application language
 * @param onDismiss Callback when dialog is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentUploadDialog(
    language: Language,
    onDismiss: () -> Unit,
) {
    val viewModel: DocumentUploadViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    var scannerError by remember { mutableStateOf<String?>(null) }
    var showFileBrowser by remember { mutableStateOf(false) }
    val documentPicker =
        rememberDocumentPickerLauncher(
            mimeTypes = listOf("application/pdf", "image/*"),
            onPicked = { file ->
                viewModel.selectFile(file.bytes, file.name)
            },
            onFailed = { reason -> Logger.withTag("DocumentUploadDialog").w { "Document pick failed: $reason" } },
        )

    LaunchedEffect(uiState.uploadProgress) {
        if (uiState.uploadProgress == 100 && uiState.successMessage.isNullOrBlank()) {
            viewModel.clearUploadForm()
            onDismiss()
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 600.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .keyboardAwarePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = str(DocumentManagementKeys.uploadDialogTitle),
                    style = MaterialTheme.typography.headlineSmall,
                )
                PosIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = str(CommonKeys.close),
                    onClick = onDismiss,
                )
            }

            HorizontalDivider()

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // File Selection
                PosSecondaryButton(
                    text = uiState.selectedFileName ?: str(DocumentManagementKeys.selectFileButton),
                    onClick = { documentPicker?.launch() ?: run { showFileBrowser = true } },
                    icon = Icons.Default.UploadFile,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Document Scanner (Android only — hidden on desktop)
                if (isDocumentScannerAvailable()) {
                    DocumentScannerButton(
                        onScanResult = { scan ->
                            scannerError = null
                            // The scanner has only ever supplied bytes and a filename
                            // (DocumentScannerButton.android.kt constructs
                            // DocumentScanResult(pdfBytes, fileName) and nothing else), so the
                            // 22-argument selectFileWithOcr it used to call was already inert
                            // plumbing. There is no capability lost here.
                            viewModel.selectFile(scan.pdfBytes, scan.fileName)
                        },
                        onError = { message -> scannerError = message },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(str(DocumentManagementKeys.scanDocumentButton))
                    }
                }

                // Scanner Error
                scannerError?.let { message ->
                    PosCard(
                        variant = PosCardVariant.Plain,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        body {
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // File Type Dropdown
                var expanded by remember { mutableStateOf(false) }
                Box {
                    PosPickerField(
                        value =
                            DocumentType
                                .fromStringOrNull(uiState.fileType)
                                ?.getDisplayName()
                                ?: uiState.fileType,
                        onClick = { expanded = true },
                        label = str(DocumentManagementKeys.fileTypeLabel),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PosDropdown(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        uiState.availableDocumentTypes.forEach { type ->
                            PosDropdownItem(
                                text =
                                    DocumentType
                                        .fromStringOrNull(type)
                                        ?.getDisplayName()
                                        ?: type,
                                selected = type == uiState.fileType,
                                onClick = {
                                    viewModel.updateFileType(type)
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                // Description
                PosTextField(
                    value = uiState.description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = str(DocumentManagementKeys.descriptionLabel),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2,
                    imeAction = ImeAction.Done,
                )

                // Upload Progress
                if (uiState.isUploading) {
                    LinearProgressIndicator(
                        progress = { uiState.uploadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Success Message
                uiState.successMessage?.let { message ->
                    PosCard(
                        variant = PosCardVariant.Plain,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        body {
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                // Error Message
                uiState.error?.let { err ->
                    PosCard(
                        variant = PosCardVariant.Plain,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        body {
                            Text(
                                text = str(err.messageKey, *err.messageArgs.toTypedArray()),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosSecondaryButton(
                    text = getString(CommonKeys.cancel, language),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )

                PosButton(
                    text = str(DocumentManagementKeys.uploadButton),
                    onClick = { viewModel.submitUpload() },
                    enabled = uiState.canSubmit(),
                    isLoading = uiState.isUploading,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // File Browser Dialog for selecting PDFs and images
    if (showFileBrowser) {
        FileBrowserDialog(
            mode = org.example.project.core.domain.fileBrowser.models.FileBrowserMode.FILE_SYSTEM,
            isMultiSelect = false,
            onDismiss = { showFileBrowser = false },
            onSelect = { selectedFiles ->
                selectedFiles.firstOrNull()?.let { file ->
                    viewModel.selectFile(file.bytes, file.name)
                }
                showFileBrowser = false
            },
        )
    }
}
