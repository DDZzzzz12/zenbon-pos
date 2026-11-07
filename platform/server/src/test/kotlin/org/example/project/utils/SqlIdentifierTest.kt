package org.example.project.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The backup restore path builds DDL/DML around identifiers taken from the CSV header inside the
 * archive being restored. Column names that are not in the current schema are, by definition,
 * arbitrary text from that archive, and they reach `ALTER TABLE ... ADD COLUMN "<name>" TEXT`.
 *
 * PostgreSQL escapes a `"` inside a quoted identifier by doubling it; anything less lets the name
 * terminate the identifier and continue as SQL.
 */
@DisplayName("quoteSqlIdentifier")
class SqlIdentifierTest {
    @Test
    fun `wraps a plain identifier in double quotes`() {
        assertEquals("\"employees\"", quoteSqlIdentifier("employees"))
    }

    @Test
    fun `doubles an embedded quote so a crafted column cannot terminate the identifier`() {
        // The shape a malicious backup would use to escape ADD COLUMN "<name>" TEXT.
        val payload = "x\" TEXT); CREATE TABLE pwned(a int); --"
        assertEquals(
            "\"x\"\" TEXT); CREATE TABLE pwned(a int); --\"",
            quoteSqlIdentifier(payload),
            "the embedded quote must be doubled, leaving one single quoted identifier",
        )
    }

    @Test
    fun `doubles every quote, not just the first`() {
        assertEquals("\"a\"\"b\"\"c\"", quoteSqlIdentifier("a\"b\"c"))
    }

    @Test
    fun `leaves a name that merely contains SQL keywords intact`() {
        // No quote to escape — a name like this is already inert once quoted.
        assertEquals("\"drop table x\"", quoteSqlIdentifier("drop table x"))
    }
}
