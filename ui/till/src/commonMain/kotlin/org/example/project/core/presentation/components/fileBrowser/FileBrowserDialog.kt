package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.project.core.domain.fileBrowser.models.FileBrowserMode
import org.example.project.core.domain.fileBrowser.models.SelectedFile
import org.example.project.core.presentation.strings.keys.FileBrowserKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.presentation.viewmodel.FileBrowserEvent
import org.example.project.core.presentation.viewmodel.FileBrowserViewMode
import org.example.project.core.presentation.viewmodel.FileBrowserViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.koin.compose.viewmodel.koinViewModel

/**
 * Modal bottom sheet dialog for browsing and selecting files.
 *
 * Supports two modes:
 * - FILE_SYSTEM: Browse device file system starting from home directory
 * - IMAGE_LIBRARY: Browse device photo library
 *
 * Features:
 * - Directory navigation with breadcrumb
 * - Grid/list view toggle
 * - Multi-select support
 * - File previews (images and PDFs)
 * - Sorting options
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserDialog(
    mode: FileBrowserMode = FileBrowserMode.FILE_SYSTEM,
    isMultiSelect: Boolean = true,
    onDismiss: () -> Unit,
    onSelect: (List<SelectedFile>) -> Unit,
) {
    val viewModel: FileBrowserViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Initialize on first composition
    LaunchedEffect(mode) {
        viewModel.onEvent(FileBrowserEvent.Initialize(mode))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 800.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (mode == FileBrowserMode.FILE_SYSTEM) {
                            str(FileBrowserKeys.fileBrowserTitle)
                        } else {
                            str(FileBrowserKeys.imageLibraryTitle)
                        },
                    style = MaterialTheme.typography.headlineSmall,
                )

                PosIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = str(FileBrowserKeys.close),
                    onClick = onDismiss,
                )
            }

            HorizontalDivider()

            // Controls row: back button + top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Navigate up button
                if (uiState.parentPath != null) {
                    PosIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(FileBrowserKeys.goUp),
                        onClick = { viewModel.onEvent(FileBrowserEvent.NavigateToParent) },
                    )
                } else {
                    Spacer(modifier = Modifier.size(40.dp))
                }

                FileBrowserTopBar(
                    sortOrder = uiState.sortOrder,
                    viewMode = uiState.viewMode,
                    hideHiddenFiles = uiState.hideHiddenFiles,
                    onSortChange = { viewModel.onEvent(FileBrowserEvent.SetSortOrder(it)) },
                    onViewModeChange = { viewModel.onEvent(FileBrowserEvent.SetViewMode(it)) },
                    onToggleHiddenFiles = { viewModel.onEvent(FileBrowserEvent.ToggleHiddenFiles) },
                )
            }

            // Breadcrumb navigation
            if (uiState.currentPath.isNotBlank()) {
                FileBrowserBreadcrumb(
                    currentPath = uiState.currentPath,
                    onPathSegmentClick = { viewModel.onEvent(FileBrowserEvent.NavigateToPath(it)) },
                )
            }

            // Content area
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp, max = 500.dp),
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    uiState.error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = uiState.error ?: str(FileBrowserKeys.errorOccurred),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    uiState.entries.isEmpty() -> {
                        Text(
                            text = str(FileBrowserKeys.noFilesFound),
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    uiState.viewMode == FileBrowserViewMode.GRID -> {
                        FileBrowserGrid(
                            entries = uiState.entries,
                            selectedFiles = uiState.selectedFiles,
                            thumbnailCache = uiState.thumbnailCache,
                            isMultiSelect = isMultiSelect,
                            onEntryClick = { viewModel.onEvent(FileBrowserEvent.ToggleSelection(it)) },
                            onEntryDoubleClick = { viewModel.onEvent(FileBrowserEvent.HandleEntryDoubleClick(it)) },
                        )
                    }
                    else -> {
                        FileBrowserList(
                            entries = uiState.entries,
                            selectedFiles = uiState.selectedFiles,
                            thumbnailCache = uiState.thumbnailCache,
                            isMultiSelect = isMultiSelect,
                            onEntryClick = { viewModel.onEvent(FileBrowserEvent.ToggleSelection(it)) },
                            onEntryDoubleClick = { viewModel.onEvent(FileBrowserEvent.HandleEntryDoubleClick(it)) },
                        )
                    }
                }
            }

            // Preview pane for selected file
            uiState.selectedFiles.firstOrNull()?.let { selectedFile ->
                FileBrowserPreview(
                    file = selectedFile,
                    onClearSelection = { viewModel.onEvent(FileBrowserEvent.ClearSelection) },
                )
            }

            HorizontalDivider()

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PosSecondaryButton(
                    onClick = onDismiss,
                    text = str(FileBrowserKeys.cancel),
                )
                Spacer(modifier = Modifier.weight(1f))
                PosButton(
                    onClick = { viewModel.onEvent(FileBrowserEvent.ConfirmSelection(onSelect)) },
                    enabled = uiState.selectedFiles.isNotEmpty(),
                    text =
                        if (uiState.selectedFiles.isEmpty()) {
                            str(FileBrowserKeys.select)
                        } else if (uiState.selectedFiles.size == 1) {
                            "${str(FileBrowserKeys.select)} (${uiState.selectedFiles.first().name})"
                        } else {
                            str(FileBrowserKeys.selectCount, uiState.selectedFiles.size)
                        },
                )
            }
        }
    }
}
