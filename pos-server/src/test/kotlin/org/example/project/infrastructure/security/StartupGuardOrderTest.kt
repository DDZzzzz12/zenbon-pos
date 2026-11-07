package org.example.project.infrastructure.security

import org.example.project.testutils.SourceRoots
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checklist 1.10 / e2e backlog: the guard that refuses to boot without `JWT_SECRET` (or with
 * development mode on a real deployment) ran inside `Application.module` — AFTER `main` had already
 * started the embedded PostgreSQL and run Flyway. A refused boot therefore still migrated the
 * database. The guard must be the first thing `main` does after reading its configuration.
 *
 * **Source-scanned, not executed.** `main` starts a database, Koin and a Netty server and ends in
 * `exitProcess`; there is no seam to run it in a test. What matters is call order inside that one
 * function, which the text states exactly.
 */
class StartupGuardOrderTest {
    private val mainBody: String by lazy {
        // Exactly one file is scanned; fileUnder fails on none (a moved root) and on two (a stray copy).
        val source = SourceRoots.fileUnder(SourceRoots.serverMainRoots(), "org/example/project/Application.kt")
        val text = source.readText()
        val start = text.indexOf("fun main(")
        assertTrue(start >= 0, "Application.kt must declare fun main(")
        text.substring(start)
    }

    private fun indexOfCall(call: String): Int {
        val index = mainBody.indexOf(call)
        assertTrue(index >= 0, "main must call $call")
        return index
    }

    @Test
    fun `main checks the auth configuration before touching the database`() {
        val guard = indexOfCall("assertSecureAuthConfig()")
        listOf("embeddedManager.start()", "initDB(").forEach { dbStep ->
            assertTrue(
                guard < indexOfCall(dbStep),
                "assertSecureAuthConfig() must run before $dbStep — a refused boot must not have migrated the database",
            )
        }
    }
}
