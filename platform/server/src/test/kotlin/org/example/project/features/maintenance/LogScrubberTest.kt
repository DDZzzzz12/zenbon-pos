package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.LogScrubber
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrubberTest {
    private data class Case(
        val logger: String?,
        val raw: String,
        val mustNotContain: List<String>,
        val mustContain: List<String> = emptyList(),
    )

    /**
     * Real shapes from this codebase's log calls and exception messages. A leak found later is added
     * HERE first (red), then the scrubber is fixed.
     */
    private val corpus =
        listOf(
            // AuthRoutes logs the identifier/username on every login/refresh/token generation. Its logger is
            // the PLAIN name "AuthRoutes" (AuthRoutes.kt: getLogger("AuthRoutes")), not the package.
            Case("AuthRoutes", "Login attempt for identifier: anna.mueller", listOf("anna.mueller")),
            Case("AuthRoutes", "Failed login attempt for identifier: anna.mueller", listOf("anna.mueller")),
            Case("AuthRoutes", "Using shop for user: anna.mueller", listOf("anna.mueller")),
            Case(
                "AuthRoutes",
                "Token refresh for user: anna.mueller on device: till-7f3a",
                listOf("anna.mueller", "till-7f3a"),
            ),
            Case(
                "AuthRoutes",
                "Generating tokens for admin with device ID: abc-123",
                listOf("admin", "abc-123"),
            ),
            Case("EmployeeRoutes", "Creating new employee with username: anna.mueller", listOf("anna.mueller")),
            // The same login shapes from a logger that is NOT withheld: the `identifier` keyword must do it.
            Case(
                "rejected",
                "Failed login attempt for identifier: anna.mueller",
                listOf("anna.mueller"),
                listOf("Failed login attempt for identifier"),
            ),
            // TableSessionRepositoryImpl.kt: a lowercase username after a plain space.
            Case(
                "org.example.project.features.table.data.repository.TableSessionRepositoryImpl",
                "Created new session: 77 for table T5 by employee anna.mueller",
                listOf("anna.mueller"),
                listOf("Created new session", "by employee"),
            ),
            // A username that starts like a prose word ("a") is still a value.
            Case("rejected", "Password reset requested by user a.mueller", listOf("a.mueller"), listOf("Password reset requested by user")),
            // Postgres detail lines inside an exception summary.
            Case(
                "StatusPages",
                "org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint " +
                    "\"employees_username_key\"\n  Detail: Key (username)=(anna.mueller) already exists.",
                listOf("anna.mueller"),
                listOf("Key (username)=(<redacted>) already exists"),
            ),
            Case(
                "StatusPages",
                "ERROR: null value in column \"pin\" violates not-null constraint\n  Detail: Failing row contains (17, Anna, Mueller, null).",
                listOf("Anna", "Mueller"),
                listOf("Failing row contains (<redacted>)"),
            ),
            // CashBookRoutes.kt: the book's name follows its id.
            Case("CashBookRoutes", "Created cash book 12: Kasse Anna Mueller", listOf("Anna", "Mueller"), listOf("Created cash book")),
            Case("CashBookRoutes", "Updated cash book 12: Kasse Anna Mueller", listOf("Anna", "Mueller"), listOf("Updated cash book")),
            // BackupRestoreRoutes.kt keeps the uploaded file's original name.
            Case(
                "BackupRestoreRoutes",
                "Restoring from backup: anna-mueller-privat.zip (12345 bytes)",
                listOf("anna-mueller"),
                listOf("Restoring from backup"),
            ),
            Case("rejected", "Upload rejected: filename=anna_mueller_cv.pdf", listOf("anna_mueller")),
            Case("rejected", "Could not read file name: Anna Mueller.pdf", listOf("Anna", "Mueller")),
            Case("BackupRestoreRoutes", "Restoring from server file: /home/anna/backups/x.zip", listOf("anna")),
            // The same shape from a logger that is NOT an identity logger: the keyword rule must do it.
            Case(
                "rejected",
                "Login failed for user: anna.mueller on device: till-7f3a",
                listOf("anna.mueller", "till-7f3a"),
                listOf("Login failed for user:"),
            ),
            // OrderServiceImpl / permission refusals interpolate the employee number.
            Case(
                "rejected",
                "Employee 1042 does not have permission to apply discounts greater than 20%",
                listOf("1042"),
                listOf("does not have permission to apply discounts"),
            ),
            // BillServiceImpl
            Case(
                "rejected",
                "Cannot cancel bill 20260915-0042: invoice RE-2026-0007 was issued for it.",
                listOf("20260915", "0042", "2026", "0007"),
                listOf("Cannot cancel bill"),
            ),
            // OrderServiceImpl choice group
            Case("rejected", "Group 'Pizza Toppings' requires 1..3 selections, got 5", listOf("Pizza", "Toppings")),
            Case("rejected", "Customer anna@example.com not found", listOf("anna", "example.com")),
            Case("rejected", "IBAN DE89370400440532013000 is invalid", listOf("DE89", "0532")),
            Case("rejected", "Employee 123e4567-e89b-12d3-a456-426614174000 not found", listOf("123e4567", "426614174000")),
            Case("org.example.project.features.order.OrderServiceImpl", "Product 4711 is a voucher; sell it through the voucher sale", listOf("4711")),
        )

    @Test
    fun `no planted identifier survives and the legible part of each message does`() {
        corpus.forEach { case ->
            val scrubbed = LogScrubber.scrubMessage(case.logger, case.raw)
            case.mustNotContain.forEach { secret ->
                assertFalse(secret in scrubbed, "'$secret' leaked: [$scrubbed] from [${case.raw}]")
            }
            case.mustContain.forEach { phrase ->
                assertTrue(phrase in scrubbed, "'$phrase' was destroyed: [$scrubbed] from [${case.raw}]")
            }
        }
    }

    @Test
    fun `an identity logger's message is withheld entirely`() {
        assertEquals(
            "<redacted: identity log>",
            LogScrubber.scrubMessage("org.example.project.features.employee.data.service.EmployeeServiceImpl", "Created employee anna"),
        )
    }

    @Test
    fun `the plain-named auth and employee route loggers are withheld`() {
        listOf("AuthRoutes", "EmployeeRoutes", "ApprovalRoutes", "TemporarySessionRoutes", "Security", "AuthorizationExtensions").forEach { name ->
            assertEquals("<redacted: identity log>", LogScrubber.scrubMessage(name, "Login attempt for identifier: anna"), name)
        }
        // An unrelated logger is scrubbed, not withheld.
        assertEquals("Order saved", LogScrubber.scrubMessage("OrderRoutes", "Order saved"))
    }

    @Test
    fun `prose after a person keyword keeps its function words`() {
        assertEquals("Employee does not have permission", LogScrubber.scrub("Employee does not have permission"))
        assertEquals("Customer not found", LogScrubber.scrub("Customer not found"))
    }

    @Test
    fun `a frame is scrubbed of quoted text, emails, uuids and ibans but keeps its line numbers`() {
        assertEquals("at org.example.Foo.bar(Foo.kt:12)", LogScrubber.scrubFrame("at org.example.Foo.bar(Foo.kt:12)"))
        val scrubbed = LogScrubber.scrubFrame("at fn ('Anna Mueller' anna@example.com 123e4567-e89b-12d3-a456-426614174000 DE89370400440532013000:3)")
        listOf("Anna", "anna@", "123e4567", "DE8937").forEach { secret -> assertFalse(secret in scrubbed, "'$secret' leaked: $scrubbed") }
        assertTrue(":3)" in scrubbed, scrubbed)
    }

    @Test
    fun `an unclosed quote at the very end is blanked - a truncated message may end inside one`() {
        assertEquals("Group '…'", LogScrubber.scrub("Group 'Anna Muel"))
    }

    @Test
    fun `a long run without an at sign is scrubbed in linear time`() {
        // The old EMAIL pattern retried the run from every start: 40,000 characters took ~6 s.
        val run = "a".repeat(40_000)
        val scrubbed = assertTimeoutPreemptively(Duration.ofSeconds(2), ThrowingSupplier { LogScrubber.scrub(run) })
        assertEquals(run, scrubbed)
        val framed = assertTimeoutPreemptively(Duration.ofSeconds(2), ThrowingSupplier { LogScrubber.scrubFrame(run) })
        assertEquals(run, framed)
    }

    @Test
    fun `digit runs become a hash sign`() {
        assertEquals("took #ms for # items", LogScrubber.scrub("took 250ms for 12 items"))
    }

    @Test
    fun `a possessive apostrophe is not mistaken for a quote`() {
        assertEquals("the server can't reach the printer", LogScrubber.scrub("the server can't reach the printer"))
    }

    @Test
    fun `quoted text is blanked`() {
        assertEquals("Group '…' is full", LogScrubber.scrub("Group 'Pizza Toppings' is full"))
        assertEquals("""Field '…' is invalid""", LogScrubber.scrub("""Field "customerName" is invalid"""))
    }

    @Test
    fun `scrubbing is idempotent`() {
        val once = LogScrubber.scrub("Employee 1042 saw 'x' at user: bob")
        assertEquals(once, LogScrubber.scrub(once))
    }

    @Test
    fun `scrubbing is idempotent across the whole corpus`() {
        corpus.forEach { case ->
            val once = LogScrubber.scrub(case.raw)
            assertEquals(once, LogScrubber.scrub(once), "not idempotent for [${case.raw}]")
        }
    }
}
