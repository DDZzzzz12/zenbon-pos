package org.example.project.core.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.domain.fileBrowser.FileSystemBrowser
import org.example.project.core.domain.fileBrowser.models.FileBrowserMode
import org.example.project.core.domain.fileBrowser.models.FileEntry
import org.example.project.core.domain.fileBrowser.models.SelectedFile
import org.example.project.core.domain.fileBrowser.models.SortOrder

/**
 * ViewModel state for the file browser dialog.
 */
data class FileBrowserUiState(
    val mode: FileBrowserMode = FileBrowserMode.FILE_SYSTEM,
    val currentPath: String = "",
    val parentPath: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val selectedFiles: List<SelectedFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val viewMode: FileBrowserViewMode = FileBrowserViewMode.GRID,
    val isMultiSelectEnabled: Boolean = true,
    val thumbnailCache: Map<String, ByteArray> = emptyMap(),
    val hideHiddenFiles: Boolean = true,
)

enum class FileBrowserViewMode {
    GRID,
    LIST,
}

/**
 * Events that can be sent to the FileBrowserViewModel.
 */
sealed class FileBrowserEvent {
    data class Initialize(
        val mode: FileBrowserMode,
    ) : FileBrowserEvent()

    data class NavigateToPath(
        val path: String,
    ) : FileBrowserEvent()

    data object NavigateToParent : FileBrowserEvent()

    data class ToggleSelection(
        val entry: FileEntry,
    ) : FileBrowserEvent()

    data class HandleEntryDoubleClick(
        val entry: FileEntry,
    ) : FileBrowserEvent()

    data class SetSortOrder(
        val order: SortOrder,
    ) : FileBrowserEvent()

    data class SetViewMode(
        val mode: FileBrowserViewMode,
    ) : FileBrowserEvent()

    data object ClearSelection : FileBrowserEvent()

    data class ConfirmSelection(
        val onResult: (List<SelectedFile>) -> Unit,
    ) : FileBrowserEvent()

    data object ToggleHiddenFiles : FileBrowserEvent()
}

/**
 * ViewModel for the custom file browser dialog.
 *
 * Manages file system browsing with:
 * - Directory navigation (into folders, up to parent)
 * - File/folder listing with thumbnails
 * - Multi-select support
 * - Sorting and view mode options
 */
class FileBrowserViewModel : ViewModel() {
    private val logger = Logger.withTag("FileBrowserViewModel")

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    fun onEvent(event: FileBrowserEvent) {
        when (event) {
            is FileBrowserEvent.Initialize -> initialize(event.mode)
            is FileBrowserEvent.NavigateToPath -> loadDirectory(event.path)
            is FileBrowserEvent.NavigateToParent -> navigateToParent()
            is FileBrowserEvent.ToggleSelection -> toggleSelection(event.entry)
            is FileBrowserEvent.HandleEntryDoubleClick -> handleEntryDoubleClick(event.entry)
            is FileBrowserEvent.SetSortOrder -> setSortOrder(event.order)
            is FileBrowserEvent.SetViewMode -> setViewMode(event.mode)
            is FileBrowserEvent.ClearSelection -> clearSelection()
            is FileBrowserEvent.ConfirmSelection -> confirmSelection(event.onResult)
            is FileBrowserEvent.ToggleHiddenFiles -> toggleHiddenFiles()
        }
    }

    private fun initialize(mode: FileBrowserMode) {
        logger.i { "FileBrowserViewModel.initialize: mode=$mode" }
        _uiState.update { it.copy(mode = mode, selectedFiles = emptyList(), error = null) }

        val startPath =
            when (mode) {
                FileBrowserMode.FILE_SYSTEM -> FileSystemBrowser.getHomeDirectory()
                FileBrowserMode.IMAGE_LIBRARY ->
                    FileSystemBrowser.getPictureDirectories().firstOrNull()
                        ?: FileSystemBrowser.getHomeDirectory()
            }
        loadDirectory(startPath)
    }

    private fun loadDirectory(path: String) {
        logger.i { "FileBrowserViewModel.loadDirectory: path=$path" }
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                val entries = FileSystemBrowser.listDirectory(path)
                val filteredEntries =
                    if (_uiState.value.hideHiddenFiles) {
                        entries.filter { !it.name.startsWith(".") }
                    } else {
                        entries
                    }
                val sortedEntries = sortEntries(filteredEntries, _uiState.value.sortOrder)

                _uiState.update {
                    it.copy(
                        currentPath = path,
                        parentPath = deriveParentPath(path),
                        entries = sortedEntries,
                        isLoading = false,
                    )
                }

                // Preload thumbnails for visible items
                preloadThumbnails(sortedEntries.take(20))
            } catch (e: Exception) {
                logger.e(e) { "FileBrowserViewModel.loadDirectory: failed" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load directory",
                    )
                }
            }
        }
    }

    private fun navigateToParent() {
        val parentPath = _uiState.value.parentPath ?: return
        loadDirectory(parentPath)
    }

    private fun toggleSelection(entry: FileEntry) {
        if (entry.isDirectory) {
            // Double-click behavior for directories
            loadDirectory(entry.path)
            return
        }

        val currentSelected = _uiState.value.selectedFiles
        val existingIndex = currentSelected.indexOfFirst { it.path == entry.path }

        if (existingIndex >= 0) {
            // Deselect
            _uiState.update {
                it.copy(selectedFiles = currentSelected.toMutableList().apply { removeAt(existingIndex) })
            }
        } else {
            // Select (single or multi based on setting)
            if (_uiState.value.isMultiSelectEnabled) {
                viewModelScope.launch {
                    val bytes = FileSystemBrowser.readFileBytes(entry.path)
                    val selectedFile =
                        SelectedFile(
                            path = entry.path,
                            name = entry.name,
                            bytes = bytes,
                            mimeType = entry.mimeType,
                        )
                    _uiState.update {
                        it.copy(selectedFiles = currentSelected + selectedFile)
                    }
                }
            } else {
                // Single select - replace selection
                viewModelScope.launch {
                    val bytes = FileSystemBrowser.readFileBytes(entry.path)
                    val selectedFile =
                        SelectedFile(
                            path = entry.path,
                            name = entry.name,
                            bytes = bytes,
                            mimeType = entry.mimeType,
                        )
                    _uiState.update {
                        it.copy(selectedFiles = listOf(selectedFile))
                    }
                }
            }
        }
    }

    private fun handleEntryDoubleClick(entry: FileEntry) {
        if (entry.isDirectory) {
            loadDirectory(entry.path)
        } else {
            // For single-click select mode, double-click confirms selection
            if (!_uiState.value.isMultiSelectEnabled) {
                toggleSelection(entry)
            }
        }
    }

    private fun setSortOrder(order: SortOrder) {
        logger.i { "FileBrowserViewModel.setSortOrder: order=$order" }
        val sortedEntries = sortEntries(_uiState.value.entries, order)
        _uiState.update {
            it.copy(sortOrder = order, entries = sortedEntries)
        }
    }

    private fun setViewMode(mode: FileBrowserViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedFiles = emptyList()) }
    }

    private fun confirmSelection(onResult: (List<SelectedFile>) -> Unit) {
        val selectedFiles = _uiState.value.selectedFiles
        logger.i { "FileBrowserViewModel.confirmSelection: ${selectedFiles.size} files selected" }
        onResult(selectedFiles)
    }

    private fun toggleHiddenFiles() {
        _uiState.update { it.copy(hideHiddenFiles = !it.hideHiddenFiles) }
        // Reload current directory with new filter
        loadDirectory(_uiState.value.currentPath)
    }

    private suspend fun preloadThumbnails(entries: List<FileEntry>) {
        entries.filter { !it.isDirectory && isPreviewable(it) }.forEach { entry ->
            try {
                val thumbnail = FileSystemBrowser.getThumbnail(entry.path, THUMBNAIL_SIZE)
                if (thumbnail != null) {
                    _uiState.update {
                        it.copy(thumbnailCache = it.thumbnailCache + (entry.path to thumbnail))
                    }
                }
            } catch (_: Exception) {
                // Skip failed thumbnails
            }
        }
    }

    private fun sortEntries(
        entries: List<FileEntry>,
        sortOrder: SortOrder,
    ): List<FileEntry> {
        val directories = entries.filter { it.isDirectory }
        val files = entries.filter { !it.isDirectory }

        val sortedDirectories =
            when (sortOrder) {
                SortOrder.NAME_ASC -> directories.sortedBy { it.name.lowercase() }
                SortOrder.NAME_DESC -> directories.sortedByDescending { it.name.lowercase() }
                SortOrder.DATE_ASC -> directories.sortedBy { it.lastModified }
                SortOrder.DATE_DESC -> directories.sortedByDescending { it.lastModified }
                SortOrder.SIZE_ASC -> directories.sortedBy { it.size }
                SortOrder.SIZE_DESC -> directories.sortedByDescending { it.size }
            }

        val sortedFiles =
            when (sortOrder) {
                SortOrder.NAME_ASC -> files.sortedBy { it.name.lowercase() }
                SortOrder.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
                SortOrder.DATE_ASC -> files.sortedBy { it.lastModified }
                SortOrder.DATE_DESC -> files.sortedByDescending { it.lastModified }
                SortOrder.SIZE_ASC -> files.sortedBy { it.size }
                SortOrder.SIZE_DESC -> files.sortedByDescending { it.size }
            }

        return sortedDirectories + sortedFiles
    }

    private fun deriveParentPath(path: String): String? {
        val normalizedPath = path.trimEnd('/')
        val lastSeparator = normalizedPath.lastIndexOf('/')
        return if (lastSeparator > 0) {
            normalizedPath.substring(0, lastSeparator)
        } else {
            null
        }
    }

    private fun isPreviewable(entry: FileEntry): Boolean {
        val mimeType = entry.mimeType ?: return false
        return mimeType.startsWith("image/") || mimeType == "application/pdf"
    }

    companion object {
        private const val THUMBNAIL_SIZE = 150
    }
}
