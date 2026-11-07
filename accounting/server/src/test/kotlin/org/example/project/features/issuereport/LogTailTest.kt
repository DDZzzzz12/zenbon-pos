package org.example.project.features.issuereport

import org.example.project.features.issuereport.data.service.LogTail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("LogTail")
class LogTailTest {
    /** One line as logback's LogstashEncoder writes it: `message` precedes `level`. */
    private fun line(
        level: String,
        message: String,
    ): String =
        """{"@timestamp":"2026-10-11T10:00:00.000+02:00","@version":"1","message":"$message",""" +
            """"logger_name":"org.example.project.features.auth","thread_name":"main","level":"$level","level_value":20000}"""

    private fun write(
        dir: File,
        lines: List<String>,
    ) = File(dir, "application.log").writeText(lines.joinToString("\n", postfix = "\n"))

    @Test
    fun `returns the last N WARN and ERROR lines`(
        @TempDir dir: File,
    ) {
        write(dir, (1..200).map { line(if (it % 2 == 0) "ERROR" else "WARN", "problem $it") })
        val tail = LogTail(logDir = { dir }, maxLines = 50).read()!!.lines()
        assertEquals(50, tail.size)
        assertTrue("problem 151" in tail.first())
        assertTrue("problem 200" in tail.last())
    }

    @Test
    fun `excludes INFO lines such as login attempts naming staff`(
        @TempDir dir: File,
    ) {
        write(
            dir,
            listOf(
                line("INFO", "Login attempt for identifier: anna.mueller"),
                line("WARN", "printer slow"),
                line("DEBUG", "noise"),
                line("ERROR", "receipt print failed"),
                line("INFO", "Login attempt for identifier: bert"),
            ),
        )
        val tail = LogTail(logDir = { dir }).read()!!
        assertEquals(2, tail.lines().size)
        assertTrue("printer slow" in tail)
        assertTrue("receipt print failed" in tail)
        assertFalse("anna.mueller" in tail)
        assertFalse("bert" in tail)
    }

    @Test
    fun `the word ERROR inside an INFO message is not matched`(
        @TempDir dir: File,
    ) {
        write(
            dir,
            listOf(
                line("INFO", "ERROR count is 0"),
                line("INFO", """quoted \"level\":\"ERROR\" in a message"""),
            ),
        )
        assertNull(LogTail(logDir = { dir }).read())
    }

    @Test
    fun `null when the log holds no WARN or ERROR line`(
        @TempDir dir: File,
    ) {
        write(dir, (1..20).map { line("INFO", "routine $it") })
        assertNull(LogTail(logDir = { dir }).read())
    }

    @Test
    fun `non-JSON lines are dropped`(
        @TempDir dir: File,
    ) {
        write(dir, listOf("ERROR plain text line", "line 2"))
        assertNull(LogTail(logDir = { dir }).read())
    }

    @Test
    fun `truncates an over-long kept line after deciding its level`(
        @TempDir dir: File,
    ) {
        // The level field sits past the cut, so the filter must read the whole line first.
        write(dir, listOf(line("ERROR", "x".repeat(10_000))))
        val kept = LogTail(logDir = { dir }, maxLines = 50, maxLineChars = 500).read()!!
        assertEquals(500, kept.length)
    }

    @Test
    fun `a partial first line in the scan window is dropped`(
        @TempDir dir: File,
    ) {
        val first = line("ERROR", "first")
        val second = line("WARN", "second")
        write(dir, listOf(first, second))
        // Window starts inside the first line: only the second, complete one may come back.
        val tail = LogTail(logDir = { dir }, scanBytes = second.length + 10L).read()!!
        assertEquals(1, tail.lines().size)
        assertTrue("second" in tail)
    }

    @Test
    fun `null when there is no log file`(
        @TempDir dir: File,
    ) {
        assertNull(LogTail(logDir = { dir }, maxLines = 50).read())
    }
}
