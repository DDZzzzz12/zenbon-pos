package org.example.project.admin.core.util.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraErrorTest {
    @Test
    fun mapsTheDomExceptionNames() {
        assertEquals(CameraError.PermissionDenied, CameraError.fromName("NotAllowedError"))
        assertEquals(CameraError.PermissionDenied, CameraError.fromName("SecurityError"))
        assertEquals(CameraError.NoCamera, CameraError.fromName("NotFoundError"))
        assertEquals(CameraError.NoCamera, CameraError.fromName("OverconstrainedError"))
        assertEquals(CameraError.Busy, CameraError.fromName("NotReadableError"))
        assertEquals(CameraError.Busy, CameraError.fromName("AbortError"))
        assertEquals(CameraError.Unknown, CameraError.fromName("Whatever"))
        assertEquals(CameraError.Unknown, CameraError.fromName(null))
    }
}
