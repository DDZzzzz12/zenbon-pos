package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaintainerPasswordHashCliTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    private fun run(vararg answers: String?): Int {
        val queue = answers.toMutableList()
        return MaintainerPasswordHashCli.run(
            prompt = { queue.removeFirstOrNull()?.toCharArray() },
            out = PrintStream(out),
            err = PrintStream(err),
        )
    }

    @Test
    fun `two matching long passwords print a single-quoted env line that verifies`() {
        assertEquals(0, run("a long enough password", "a long enough password"))
        val line = out.toString().trim()
        assertTrue(line.startsWith("MAINTAINER_PASSWORD_HASH='\$2a\$12\$") && line.endsWith("'"), line)
        val hash = line.removePrefix("MAINTAINER_PASSWORD_HASH='").removeSuffix("'")
        assertTrue(BCrypt.verifyer().verify("a long enough password".toCharArray(), hash).verified)
        MaintenanceConfig.of("maint", hash, null) // and the config accepts exactly what the CLI prints
    }

    @Test
    fun `a short password is refused and nothing is printed`() {
        assertEquals(1, run("short", "short"))
        assertEquals("", out.toString())
        assertTrue("at least 12" in err.toString(), err.toString())
    }

    @Test
    fun `entries that differ are refused`() {
        assertEquals(1, run("a long enough password", "a different long password"))
        assertEquals("", out.toString())
    }

    @Test
    fun `no input at all is refused`() {
        assertEquals(1, run())
        assertEquals("", out.toString())
    }

    @Test
    fun `the first entry is wiped when the second prompt gets no input`() {
        val first = "a long enough password".toCharArray()
        val answers = mutableListOf<CharArray?>(first, null)
        val code = MaintainerPasswordHashCli.run(prompt = { answers.removeAt(0) }, out = PrintStream(out), err = PrintStream(err))
        assertEquals(1, code)
        assertTrue(first.all { it == '\u0000' }, "the first password was left in memory")
    }

    @Test
    fun `a password of 72 bytes is accepted`() {
        val password = "x".repeat(72)
        assertEquals(0, run(password, password))
        assertTrue(out.toString().startsWith("MAINTAINER_PASSWORD_HASH='"), out.toString())
    }

    @Test
    fun `a password longer than 72 bytes is refused and nothing is printed`() {
        val password = "x".repeat(73)
        assertEquals(1, run(password, password))
        assertEquals("", out.toString())
        assertTrue("72 bytes" in err.toString(), err.toString())
    }

    @Test
    fun `a short multi-byte password over 72 bytes is refused by byte length not character count`() {
        val password = "ä".repeat(40) // 40 characters, 80 UTF-8 bytes
        assertTrue(password.length < 72 && password.toByteArray(Charsets.UTF_8).size > 72)
        assertEquals(1, run(password, password))
        assertEquals("", out.toString())
        assertTrue("72 bytes" in err.toString(), err.toString())
    }
}
