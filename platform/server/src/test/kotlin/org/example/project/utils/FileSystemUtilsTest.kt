package org.example.project.utils

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the failure contract every DATEV export service relies on.
 *
 * `ensureDirectoryWritable` used to throw a raw `IOException`, which escaped the export services
 * as an opaque **500**. All four callers (`DatevXmlExportServiceImpl`, `DatevApExportServiceImpl`,
 * `DatevBuchungsstapelExportServiceImpl`, `DatevBuchungsstapelBelegeExportServiceImpl`) reach it
 * with an operator-chosen export path out of the DATEV configuration, so an unusable path is bad
 * input — **400**, naming the path — not a server fault. Restoring a production archive onto a
 * different machine reproduces it: the stored Linux export path does not exist there.
 */
@DisplayName("FileSystemUtils.ensureDirectoryWritable / ensureAbsoluteWritableDirectory")
class FileSystemUtilsTest {
    @Test
    fun `creates a missing directory`(
        @TempDir tmp: File,
    ) {
        val target = File(tmp, "exports/datev")
        FileSystemUtils.ensureDirectoryWritable(target)
        assertTrue(target.isDirectory)
    }

    @Test
    fun `accepts an existing writable directory`(
        @TempDir tmp: File,
    ) {
        FileSystemUtils.ensureDirectoryWritable(tmp)
        assertTrue(tmp.isDirectory)
    }

    @Test
    fun `rejects a path that is a file with 400 naming the path`(
        @TempDir tmp: File,
    ) {
        val file = File(tmp, "not-a-directory").apply { writeText("x") }

        val e = assertFailsWith<PosException> { FileSystemUtils.ensureDirectoryWritable(file) }

        assertEquals(PosErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(HttpStatusCode.BadRequest, e.httpStatus)
        assertContains(e.message.orEmpty(), file.absolutePath)
    }

    @Test
    fun `rejects an un-creatable directory with 400 naming the path`(
        @TempDir tmp: File,
    ) {
        // A regular file as the parent makes the directory un-creatable on every OS, without
        // depending on POSIX permission bits (which root and some CI filesystems ignore).
        val blocker = File(tmp, "blocker").apply { writeText("x") }
        val target = File(blocker, "exports")

        val e = assertFailsWith<PosException> { FileSystemUtils.ensureDirectoryWritable(target) }

        assertEquals(PosErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(HttpStatusCode.BadRequest, e.httpStatus)
        assertContains(e.message.orEmpty(), target.absolutePath)
    }

    // ---- ensureAbsoluteWritableDirectory: the DATEV export path and backup path settings (SET-05) ----

    @Test
    fun `absolute - accepts an existing writable directory`(
        @TempDir tmp: File,
    ) {
        FileSystemUtils.ensureAbsoluteWritableDirectory(tmp.absolutePath)
        assertTrue(tmp.isDirectory)
    }

    @Test
    fun `absolute - creates a missing absolute directory`(
        @TempDir tmp: File,
    ) {
        val target = File(tmp, "backups/nightly")
        FileSystemUtils.ensureAbsoluteWritableDirectory(target.absolutePath)
        assertTrue(target.isDirectory)
    }

    @Test
    fun `absolute - rejects a relative path with 400 and creates nothing`() {
        val relative = "relative-qa-path-${System.nanoTime()}/dir"

        val e = assertFailsWith<PosException> { FileSystemUtils.ensureAbsoluteWritableDirectory(relative) }

        assertEquals(PosErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(HttpStatusCode.BadRequest, e.httpStatus)
        assertContains(e.message.orEmpty(), "not an absolute path")
        assertTrue(!File(relative).exists(), "a refused relative path must not be created under the working directory")
    }

    @Test
    fun `absolute - rejects an existing file with 400 naming the path`(
        @TempDir tmp: File,
    ) {
        val file = File(tmp, "backup.zip").apply { writeText("x") }

        val e = assertFailsWith<PosException> { FileSystemUtils.ensureAbsoluteWritableDirectory(file.absolutePath) }

        assertEquals(HttpStatusCode.BadRequest, e.httpStatus)
        assertContains(e.message.orEmpty(), "not a directory")
    }
}
