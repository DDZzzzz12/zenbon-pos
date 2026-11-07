package org.example.project.admin.core.util.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraSupportTest {
    @Test
    fun liveCameraWhenGetUserMediaExists() {
        assertEquals(CameraSupport.Available, CameraSupport.classify(isSecureContext = true, hasGetUserMedia = true))
    }

    @Test
    fun plainHttpOverALanIpIsReportedAsInsecure() {
        // navigator.mediaDevices is undefined there, which is exactly the case operators will hit.
        assertEquals(CameraSupport.InsecureContext, CameraSupport.classify(isSecureContext = false, hasGetUserMedia = false))
    }

    @Test
    fun secureButNoApiIsUnsupported() {
        assertEquals(CameraSupport.Unsupported, CameraSupport.classify(isSecureContext = true, hasGetUserMedia = false))
    }
}
