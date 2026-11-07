/**
 * Desktop implementation of [DocumentScannerButton].
 *
 * No-op on desktop since camera-based document scanning is not available.
 *
 * @since 1.0.0
 */
package org.example.project.core.presentation.components.docUtil

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * No-op on desktop — document scanning requires a camera and is only available on Android.
 */
@Composable
actual fun DocumentScannerButton(
    onScanResult: (DocumentScanResult) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    buttonContent: @Composable () -> Unit,
) {
    // Not available on desktop — intentionally empty
}
