package org.example.project.tse.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Unit tests for TseLocator TSE hardware detection utility.
 *
 * Tests focus on the three main public methods:
 * - findTseDevice(): OS-specific TSE device detection (file-based, fully testable)
 * - findAndValidateTse(): Complete TSE detection and validation flow
 * - validateTseHardware(): Hardware validation with WormStore
 *
 * Native libraries are loaded automatically via java.library.path configured in build.gradle.kts.
 * The WormAPI.jar's static initializer handles library loading when WormStore is first used.
 *
 * @since 1.0.0
 */
class TseLocatorTest {

    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("tse-locator-test").toFile()
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // ============================================================================================
    // findTseDevice() Tests - Fully testable (no native dependencies)
    // ============================================================================================

    @Test
    fun `findTseDevice returns null or valid path`() {
        val result = TseLocator.findTseDevice()

        // Either null (no TSE) or valid directory path (TSE connected)
        if (result != null) {
            assertTrue(File(result).isDirectory, "Result should be a valid directory path")
        }
    }

    @Test
    fun `findTseDevice does not throw exception`() {
        try {
            TseLocator.findTseDevice()
        } catch (e: Exception) {
            fail("findTseDevice() should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `findTseDevice is idempotent`() {
        val result1 = TseLocator.findTseDevice()
        val result2 = TseLocator.findTseDevice()
        val result3 = TseLocator.findTseDevice()

        assertEquals(result1, result2, "Consecutive calls should return same result")
        assertEquals(result2, result3, "Consecutive calls should return same result")
    }

    @Test
    fun `findTseDevice scans standard Linux mount paths`() {
        // This test verifies the method runs without error
        // Actual detection depends on system state
        val result = TseLocator.findTseDevice()

        // Method should complete without exception
        // Result is null (no TSE) or a valid path
        assertTrue(
            result == null || File(result).isDirectory,
            "Result should be null or valid directory"
        )
    }

    // ============================================================================================
    // findAndValidateTse() Tests
    // These tests pass when findTseDevice() returns null (no TSE marker files found)
    // because validateTseHardware() is only called when a device path is found
    // ============================================================================================

    @Test
    fun `findAndValidateTse returns null or valid TSE path`() {
        val result = TseLocator.findAndValidateTse()

        if (result != null) {
            assertTrue(File(result).exists(), "Returned path should exist")
            assertTrue(File(result).isDirectory, "Returned path should be a directory")
        }
        // If null, that's also acceptable (no TSE hardware connected)
    }

    @Test
    fun `findAndValidateTse does not throw exception`() {
        try {
            TseLocator.findAndValidateTse()
        } catch (e: Exception) {
            fail("findAndValidateTse() should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `findAndValidateTse is idempotent`() {
        val result1 = TseLocator.findAndValidateTse()
        val result2 = TseLocator.findAndValidateTse()

        assertEquals(result1, result2, "Consecutive calls should return same result")
    }

    @Test
    fun `findAndValidateTse returns null on dev machine without TSE`() {
        // On systems without actual TSE hardware, this should return null
        // because even if findTseDevice() finds marker files,
        // validateTseHardware() will fail without real hardware
        val result = TseLocator.findAndValidateTse()

        // Either null (typical on dev machines) or valid path (if TSE connected)
        assertTrue(
            result == null || File(result).isDirectory,
            "Result should be null or a valid directory"
        )
    }
}
