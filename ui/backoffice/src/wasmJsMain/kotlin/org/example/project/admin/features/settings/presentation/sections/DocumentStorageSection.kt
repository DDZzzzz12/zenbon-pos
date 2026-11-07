package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canBrowseServerDirectories
import org.example.project.admin.core.auth.canConfigureSystem
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.components.SettingsStackableRow
import org.example.project.core.auth.TokenStore
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Belegablage — the server-side storage root for uploaded documents, the upload size cap, and the
 * custom document types that sit beside the built-in `DocumentType` values.
 *
 * Restores a surface deleted from composeApp in `498867657` before adminApp had an equivalent.
 *
 * The upload cap is a **quantity in MB, not money** — a plain numeric field, never `PosMoneyText`.
 *
 * Two different gates apply: [canConfigureSystem] mirrors the PUT's
 * `PermissionOrRole(EXCEPTION_SYSTEM_CONFIG, MANAGER)`, while the Browse button additionally needs
 * [canBrowseServerDirectories], because `GET /api/v1/backup/directories` is ADMIN-only. A MANAGER can
 * still type the path.
 */
@Composable
fun DocumentStorageSection(viewModel: DocumentStorageViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.canConfigureSystem
    val canBrowse = canEdit && tokenStore.canBrowseServerDirectories
    val state = viewModel.state
    val sectionTitle = str(AdminStringKeys.SettingsSectionDocumentStorage)
    rememberDirtySource(dirty = state.isDirty, onDiscard = viewModel::refresh)
    val focus = rememberPosFormFocus<DocumentStorageField>()
    LaunchedEffect(state.validationAttempt) {
        if (state.validationAttempt > 0) focus.revealFirst(DocumentStorageField.entries, state.fieldErrors)
    }

    PosErrorWatcher(
        error = state.error,
        title = sectionTitle,
        onClear = viewModel::clearError,
        // Not ::refresh — the same error surface carries config-load, save and directory-listing
        // failures, and the VM knows which of the three actually failed.
        retryAction = viewModel::retryFailedAction,
    )
    PosSuccessWatcher(
        message = state.successMessage?.let { str(it) },
        title = sectionTitle,
        onClear = viewModel::clearSuccessMessage,
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LocalPosLayout.current.screenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = str(AdminStringKeys.SettingsDocStorageDescription),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!canEdit) {
            Text(
                text = str(AdminStringKeys.SettingsSystemConfigReadOnly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PosCard(modifier = Modifier.fillMaxWidth()) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsStackableRow(
                        verticalAlignment = Alignment.Bottom,
                        hasActions = canBrowse,
                        leading = { rowModifier ->
                            PosTextField(
                                value = state.path,
                                onValueChange = viewModel::updatePath,
                                label = str(AdminStringKeys.SettingsDocStoragePath),
                                enabled = canEdit,
                                modifier = rowModifier,
                            )
                        },
                    ) {
                        PosButton(
                            text = str(AdminStringKeys.SettingsDocStorageBrowse),
                            onClick = viewModel::openBrowser,
                        )
                    }
                    Text(
                        text = str(AdminStringKeys.SettingsDocStoragePathHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PosTextField(
                        value = state.maxUploadSizeInput,
                        onValueChange = viewModel::updateMaxUploadSize,
                        label = requiredLabel(str(AdminStringKeys.SettingsDocStorageMaxUpload)),
                        enabled = canEdit,
                        keyboardType = KeyboardType.Number,
                        isError = state.uploadSizeError != null,
                        errorMessage = state.uploadSizeError?.let { str(it) },
                        modifier =
                            with(focus) {
                                Modifier.fillMaxWidth().widthIn(max = 260.dp).formAnchor(DocumentStorageField.MAX_UPLOAD_SIZE)
                            },
                    )
                }
            }
        }

        PosCard(modifier = Modifier.fillMaxWidth()) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = str(AdminStringKeys.SettingsDocStorageDefaultTypes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.defaultDocumentTypes.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PosCard(modifier = Modifier.fillMaxWidth()) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = str(AdminStringKeys.SettingsDocStorageAdditionalTypes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (state.additionalDocumentTypes.isEmpty()) {
                        Text(
                            text = str(AdminStringKeys.SettingsDocStorageNoAdditionalTypes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.additionalDocumentTypes.forEach { type ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = type,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (canEdit) {
                                PosButton(
                                    text = str(CommonKeys.delete),
                                    onClick = { viewModel.removeDocumentType(type) },
                                )
                            }
                        }
                    }
                    if (canEdit) {
                        SettingsStackableRow(
                            verticalAlignment = Alignment.Bottom,
                            leading = { rowModifier ->
                                PosTextField(
                                    value = state.newDocumentType,
                                    onValueChange = viewModel::updateNewDocumentType,
                                    label = str(AdminStringKeys.SettingsDocStorageNewType),
                                    isError = state.newDocumentTypeError != null,
                                    errorMessage = state.newDocumentTypeError?.let { str(it) },
                                    modifier = rowModifier,
                                )
                            },
                        ) {
                            PosButton(
                                text = str(AdminStringKeys.SettingsDocStorageAddType),
                                onClick = viewModel::addDocumentType,
                                enabled = state.canAddDocumentType,
                            )
                        }
                    }
                }
            }
        }

        if (canEdit) {
            PosButton(
                text = str(CommonKeys.save),
                onClick = viewModel::save,
                enabled = state.canSave,
                isLoading = state.isSaving,
            )
        }
    }

    state.browser?.let { browser ->
        DirectoryBrowserDialog(
            browser = browser,
            onNavigate = viewModel::browseTo,
            onParent = viewModel::navigateToParent,
            onSelect = viewModel::selectDirectory,
            onDismiss = viewModel::closeBrowser,
            allowNewFolder = false,
        )
    }
}
