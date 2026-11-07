package org.example.project.admin.features.documents.presentation.scanner

import org.example.project.admin.core.util.camera.CameraSupport
import org.example.project.admin.features.documents.presentation.scanner.imaging.Quad
import org.example.project.core.presentation.error.UiError

data class ScannerState(
    val phase: ScannerPhase = ScannerPhase.CAPTURE,
    val support: CameraSupport = CameraSupport.Unsupported,
    val pages: List<ScannerPage> = emptyList(),
    val draft: ScannerDraft? = null,
    val liveQuad: Quad? = null,
    val settled: Boolean = false,
    val autoCapture: Boolean = false,
    val torchOn: Boolean = false,
    val torchSupported: Boolean = false,
    val cameraFailed: Boolean = false,
    val cameraAttempt: Int = 0,
    val busy: Boolean = false,
    val notice: Notice? = null,
    val error: UiError? = null,
) {
    /** Non-blocking messages shown inline. */
    enum class Notice {
        NOTHING_DETECTED,
    }
}
