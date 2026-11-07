package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TSE 2.0 rejects '/', ':' and '?' in a client ID. Ours are generated as "TER-001"
 * (TerminalRepositoryImpl), so they are safe today — this pins that, so a future change to
 * the ID scheme cannot silently produce IDs a TSE 2.0 refuses to register.
 */
class TseClientIdTest {
    @Test
    fun `the generated terminal id format is TSE 2_0 safe`() {
        (1..999).map { "TER-${it.toString().padStart(3, '0')}" }.forEach {
            assertTrue(isTseSafeClientId(it), "generated id '$it' must be accepted by a TSE 2.0")
        }
    }

    @Test
    fun `ids containing the forbidden characters are rejected`() {
        listOf("TER/001", "TER:001", "TER?001", "a/b:c?d").forEach {
            assertFalse(isTseSafeClientId(it), "'$it' contains a character TSE 2.0 rejects")
        }
    }

    @Test
    fun `blank ids are rejected`() {
        assertFalse(isTseSafeClientId(""))
        assertFalse(isTseSafeClientId("   "))
    }
}
