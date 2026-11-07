package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.runtime.Composable
import org.example.project.core.domain.fileBrowser.models.SelectedFile

@Composable
actual fun rememberDocumentPickerLauncher(
    mimeTypes: List<String>,
    onPicked: (SelectedFile) -> Unit,
    onFailed: (String) -> Unit,
): DocumentPickerLauncher? = null
