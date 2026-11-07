package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSizeFormatterTest {
    @Test
    fun `zero and negative sizes read as zero bytes`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("0 B", formatFileSize(-1))
    }

    @Test
    fun `bytes below a kilobyte keep their unit`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun `a whole unit prints without a decimal point`() {
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("1 MB", formatFileSize(1024L * 1024))
        assertEquals("1 GB", formatFileSize(1024L * 1024 * 1024))
    }

    @Test
    fun `a partial unit keeps one decimal`() {
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("2.5 MB", formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `the largest unit clamps rather than running off the end of the table`() {
        assertEquals("5120 TB", formatFileSize(5L * 1024 * 1024 * 1024 * 1024 * 1024))
    }
}
