package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.runtime.Composable
import org.example.project.core.domain.fileBrowser.models.SelectedFile

/**
 * The platform document picker, or null where the in-app [FileBrowserDialog] is the picker.
 *
 * Android returns a Storage Access Framework launcher: under scoped storage (targetSdk ≥ 30) the
 * in-app browser can list folders but never other apps' files (E2E-2026-09-18 #7), and SAF needs
 * no storage permission. Desktop and wasm return null.
 *
 * @param mimeTypes what the picker offers, e.g. `application/pdf`, `image/`*.
 * @param onPicked the chosen file, already read into memory.
 * @param onFailed the file could not be read (message for the log; show a generic error).
 */
@Composable
expect fun rememberDocumentPickerLauncher(
    mimeTypes: List<String>,
    onPicked: (SelectedFile) -> Unit,
    onFailed: (String) -> Unit,
): DocumentPickerLauncher?
