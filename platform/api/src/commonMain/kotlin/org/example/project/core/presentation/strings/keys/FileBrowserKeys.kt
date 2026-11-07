package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object FileBrowserKeys {
    // Dialog titles
    val fileBrowserTitle = StringKey("file_browser.file_browser_title")
    val imageLibraryTitle = StringKey("file_browser.image_library_title")

    // Actions
    val close = StringKey("file_browser.close")
    val goUp = StringKey("file_browser.go_up")
    val cancel = StringKey("file_browser.cancel")
    val select = StringKey("file_browser.select")
    val clearSelection = StringKey("file_browser.clear_selection")

    // Select button with count
    val selectCount = StringKey("file_browser.select_count")

    // Empty and error states
    val noFilesFound = StringKey("file_browser.no_files_found")
    val errorOccurred = StringKey("file_browser.error_occurred")

    // Top bar controls
    val sort = StringKey("file_browser.sort")
    val toggleViewMode = StringKey("file_browser.toggle_view_mode")

    // Sort options
    val nameAsc = StringKey("file_browser.name_asc")
    val nameDesc = StringKey("file_browser.name_desc")
    val dateAsc = StringKey("file_browser.date_asc")
    val dateDesc = StringKey("file_browser.date_desc")
    val sizeAsc = StringKey("file_browser.size_asc")
    val sizeDesc = StringKey("file_browser.size_desc")

    // Selection indicator
    val selected = StringKey("file_browser.selected")

    // Hidden files toggle
    val showHiddenFiles = StringKey("file_browser.show_hidden_files")
    val hideHiddenFiles = StringKey("file_browser.hide_hidden_files")
}
