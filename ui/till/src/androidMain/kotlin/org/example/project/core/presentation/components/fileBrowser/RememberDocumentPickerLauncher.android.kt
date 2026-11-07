package org.example.project.core.presentation.components.fileBrowser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.core.domain.fileBrowser.models.SelectedFile

@Composable
actual fun rememberDocumentPickerLauncher(
    mimeTypes: List<String>,
    onPicked: (SelectedFile) -> Unit,
    onFailed: (String) -> Unit,
): DocumentPickerLauncher? {
    val context = LocalContext.current
    val reader = remember(context) { ContentUriReader(context.contentResolver) }
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult // cancelled — not an error
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { reader.read(uri) } }
                    .onSuccess { picked(it) }
                    .onFailure { failed(it.message ?: "read failed") }
            }
        }
    return remember(launcher, mimeTypes) { DocumentPickerLauncher { launcher.launch(mimeTypes.toTypedArray()) } }
}
