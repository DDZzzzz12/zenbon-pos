/**
 * Platform-specific document scanner button.
 *
 * On Android, launches Google ML Kit's Document Scanner which provides camera-based
 * document detection, cropping, and PDF generation. After scanning, ML Kit Text
 * Recognition performs OCR on page images to extract metadata (document type, date,
 * amount, IBAN, account number) and returns a [DocumentScanResult].
 *
 * On desktop, renders nothing since camera-based scanning is not available.
 *
 * @since 1.0.0
 */
package org.example.project.core.presentation.components.docUtil

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A button that launches a platform-specific document scanner.
 *
 * On Android, this uses ML Kit Document Scanner to capture documents via camera
 * and return them as a [DocumentScanResult] containing PDF bytes and optional
 * OCR-extracted metadata. On desktop, this composable renders nothing.
 *
 * @param onScanResult Callback with [DocumentScanResult] containing PDF bytes and OCR metadata
 * @param onError Callback when scanning fails (non-critical; user can still pick files manually)
 * @param modifier Modifier for the button layout
 * @param buttonContent Composable content for the button label (icon + text)
 */
@Composable
expect fun DocumentScannerButton(
    onScanResult: (DocumentScanResult) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    buttonContent: @Composable () -> Unit,
)
