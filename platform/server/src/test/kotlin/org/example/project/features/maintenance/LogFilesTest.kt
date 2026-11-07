package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.LogFiles
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFilesTest {
    @TempDir
    lateinit var dir: File

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun touch(vararg names: String) = names.forEach { File(dir, it).writeText("") }

    @Test
    fun `the current file comes first, then rotated files newest day and highest index first`() {
        touch("error.log", "error.2026-10-07.0.log.gz", "error.2026-10-08.0.log.gz", "error.2026-10-08.1.log.gz")
        val names = LogFiles.list(dir, LogSourceKind.ERROR).map { it.file.name }
        assertEquals(
            listOf("error.log", "error.2026-10-08.1.log.gz", "error.2026-10-08.0.log.gz", "error.2026-10-07.0.log.gz"),
            names,
        )
    }

    @Test
    fun `error does not pick up client-error files, nor lookalikes`() {
        touch("error.log", "client-error.log", "client-error.2026-10-08.0.log.gz", "errorx.log", "error.txt", "notes.log")
        assertEquals(listOf("error.log"), LogFiles.list(dir, LogSourceKind.ERROR).map { it.file.name })
        assertEquals(
            listOf("client-error.log", "client-error.2026-10-08.0.log.gz"),
            LogFiles.list(dir, LogSourceKind.CLIENT_ERROR).map { it.file.name },
        )
    }

    @Test
    fun `application rotated files have no index`() {
        touch("application.log", "application.2026-10-08.log", "application.2026-10-07.log")
        val files = LogFiles.list(dir, LogSourceKind.APPLICATION)
        assertEquals(listOf("application.log", "application.2026-10-08.log", "application.2026-10-07.log"), files.map { it.file.name })
        assertEquals(LocalDate.parse("2026-10-08"), files[1].date)
    }

    @Test
    fun `a rotated file wholly before the range is skipped, the current file never is`() {
        touch("error.log", "error.2026-10-08.0.log.gz")
        val files = LogFiles.list(dir, LogSourceKind.ERROR)
        // Midnight Berlin on 9 Oct is 22:00Z on 8 Oct; the 8 Oct file ends exactly there.
        val midnightBerlin = LocalDate.parse("2026-10-09").atStartOfDay(berlin).toInstant()
        assertTrue(files.first { it.file.name == "error.log" }.mayContain(midnightBerlin, null, berlin))
        assertFalse(files.first { it.file.name.startsWith("error.2026") }.mayContain(midnightBerlin, null, berlin))
        assertTrue(files.first { it.file.name.startsWith("error.2026") }.mayContain(midnightBerlin.minusSeconds(1), null, berlin))
    }

    @Test
    fun `a rotated file wholly after the range end is skipped`() {
        touch("error.2026-10-08.0.log.gz")
        val file = LogFiles.list(dir, LogSourceKind.ERROR).single()
        val to = LocalDate.parse("2026-10-07").atStartOfDay(berlin).toInstant()
        assertFalse(file.mayContain(null, to, berlin))
    }

    @Test
    fun `a rotated name with an impossible date is skipped, not an error`() {
        touch("error.log", "error.2026-13-40.0.log.gz", "error.2026-10-08.0.log.gz")
        assertEquals(listOf("error.log", "error.2026-10-08.0.log.gz"), LogFiles.list(dir, LogSourceKind.ERROR).map { it.file.name })
    }

    @Test
    fun `a missing directory lists nothing instead of throwing`() {
        assertEquals(emptyList(), LogFiles.list(File(dir, "nope"), LogSourceKind.ERROR))
    }
}
