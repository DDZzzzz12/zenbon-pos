package org.example.project.service

import org.example.project.features.terminal.data.service.DeviceTokenGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceTokenGeneratorTest {
    @Test fun `generates distinct high-entropy tokens`() {
        val a = DeviceTokenGenerator.newToken()
        val b = DeviceTokenGenerator.newToken()
        assertNotEquals(a, b)
        assertTrue(a.length >= 32)
    }

    @Test fun `hash is stable per token and differs across tokens`() {
        val t = DeviceTokenGenerator.newToken()
        assertEquals(DeviceTokenGenerator.hash(t), DeviceTokenGenerator.hash(t))
        assertNotEquals(DeviceTokenGenerator.hash(t), DeviceTokenGenerator.hash(DeviceTokenGenerator.newToken()))
    }
}
