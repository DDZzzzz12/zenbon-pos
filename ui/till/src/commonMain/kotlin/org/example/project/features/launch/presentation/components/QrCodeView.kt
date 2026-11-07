package org.example.project.features.launch.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.str
import qrgenerator.qrkitpainter.rememberQrKitPainter

/**
 * Renders [data] as a QR code using qr-kit.
 */
@Composable
fun QrCodeView(
    data: String,
    modifier: Modifier = Modifier,
) {
    val painter: Painter = rememberQrKitPainter(data = data)
    Image(
        painter = painter,
        contentDescription = str(AccessibilityKeys.qrCode),
        modifier = modifier,
    )
}
