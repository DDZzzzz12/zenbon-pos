package org.example.project.testutils

import org.example.project.database.DatabaseFactory
import org.example.project.infrastructure.plugins.initDB
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the one property every other `@Tag("postgres")` class depends on and none of them can check:
 * closing the harness leaves no global database behind.
 *
 * [org.example.project.infrastructure.plugins.initDB] opens with
 * `if (!DatabaseFactory.isInitialized())` and does nothing when that is false. So a class that
 * leaves the global looking initialised does not fail — it silently disables schema setup for
 * whichever class runs next, which then fails somewhere unrelated. That is what happened: one class
 * closed its embedded PostgreSQL without closing the pool in front of it, `isInitialized()` kept
 * reporting true against a dead server, and the next class's database was never built.
 *
 * Ordinary tests cannot catch this. Each one passes alone; only a particular order fails, and
 * Gradle reorders previously-failed tests first, so the order changes exactly when something else
 * breaks. This test asserts the contract directly instead.
 */
@Tag("postgres")
@DisplayName("Postgres harness lifecycle")
class PostgresTestDatabaseLifecycleTest {
    @Test
    fun `closing the harness leaves no initialised DatabaseFactory behind`() {
        val pg = PostgresTestDatabase.start()
        try {
            initDB(pg.hikariConfig(), runMigrations = false)
            assertTrue(
                DatabaseFactory.isInitialized(),
                "precondition: initDB must have initialised the global, or this test proves nothing",
            )
        } finally {
            pg.close()
        }

        assertFalse(
            DatabaseFactory.isInitialized(),
            "the harness must release the global on close; leaving it makes the NEXT class's " +
                "initDB a silent no-op against an unschema'd database",
        )
    }
}
