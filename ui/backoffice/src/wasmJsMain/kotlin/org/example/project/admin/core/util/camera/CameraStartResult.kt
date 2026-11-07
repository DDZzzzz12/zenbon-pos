package org.example.project.admin.core.util.camera

/** Outcome of [CameraSession.start]. */
sealed interface CameraStartResult {
    data object Started : CameraStartResult

    /** [CameraSession.stop] (or a newer start) overtook this one; any stream that arrived late was already released. */
    data object Cancelled : CameraStartResult

    data class Failed(
        val error: CameraError,
    ) : CameraStartResult
}
