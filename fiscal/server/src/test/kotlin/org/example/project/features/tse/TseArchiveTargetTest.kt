package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import org.example.project.features.tse.verification.requireTseArchiveRoom
import org.example.project.features.tse.verification.requireTseArchiveWritable
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** F68: the export-and-delete checks its archive folder on the server, before the TSE is asked. */
@DisplayName("TSE export-and-delete — the archive folder is checked before the device")
class TseArchiveTargetTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a writable folder with room passes and leaves no probe file behind`() {
        requireTseArchiveWritable(dir)
        requireTseArchiveRoom(dir, exportBytes = 1024)
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun `a path that is not a directory is refused with 409`() {
        val file = File(dir, "plain").apply { writeText("x") }
        val e = assertFailsWith<PosException> { requireTseArchiveWritable(File(file, "tse")) }
        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
    }

    @Test
    fun `a read-only folder is refused`() {
        val readOnly = File(dir, "ro").apply { mkdirs() }
        readOnly.setWritable(false)
        try {
            assertFailsWith<PosException> { requireTseArchiveWritable(readOnly) }
        } finally {
            readOnly.setWritable(true)
        }
        Unit
    }

    @Test
    fun `too little free space is refused with 507`() {
        val e = assertFailsWith<PosException> { requireTseArchiveRoom(dir, exportBytes = Long.MAX_VALUE / 2) }
        assertEquals(HttpStatusCode.InsufficientStorage, e.httpStatus)
    }
}
