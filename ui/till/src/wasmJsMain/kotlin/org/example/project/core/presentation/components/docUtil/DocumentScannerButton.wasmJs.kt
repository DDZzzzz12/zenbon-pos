package org.example.project.core.presentation.components.docUtil

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders nothing in the browser preview — document scanning requires a camera and is only
 * available on Android. Callers already gate on `isDocumentScannerAvailable()`.
 */
@Composable
actual fun DocumentScannerButton(
    onScanResult: (DocumentScanResult) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    buttonContent: @Composable () -> Unit,
) {
    // Intentionally empty — see the KDoc above.
}
