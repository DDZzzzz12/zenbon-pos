package org.example.project.admin.core.util.camera

/** The ways the camera request fails, collapsed to what the operator can act on. */
enum class CameraError {
    PermissionDenied,
    NoCamera,
    Busy,
    Unknown,
    ;

    companion object {
        fun fromName(name: String?): CameraError =
            when (name) {
                "NotAllowedError", "SecurityError" -> PermissionDenied
                "NotFoundError", "OverconstrainedError" -> NoCamera
                "NotReadableError", "AbortError" -> Busy
                else -> Unknown
            }
    }
}
