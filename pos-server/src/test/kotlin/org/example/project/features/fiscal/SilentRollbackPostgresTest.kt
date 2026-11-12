package org.example.project.features.fiscal

import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [SilentRollbackScenarios] on real Postgres — the engine that turns a swallowed SQL failure into a
 * silent rollback of the whole transaction. Every test here failed on v1.2.2. Each test gets its
 * own database on one embedded instance.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Swallowed SQL failures on fiscal paths do not silently roll back (Postgres)")
class SilentRollbackPostgresTest {
    private lateinit var pg: PostgresTestDatabase

    private val freshDatabase: () -> Unit = { TestDatabase.setupPostgres(pg.freshDatabaseConfig()) }

    @BeforeAll
    fun startPostgres() {
        pg = PostgresTestDatabase.start()
    }

    @AfterAll
    fun stopPostgres() = pg.close()

    @Test
    fun `a failing favourites recalculation does not lose the closing`() =
        SilentRollbackScenarios.`a failing favourites recalculation does not lose the closing`(freshDatabase)

    @Test
    fun `a failing Z-Bon PDF does not lose the closing`() = SilentRollbackScenarios.`a failing Z-Bon PDF does not lose the closing`(freshDatabase)

    @Test
    fun `the transfer target is read before the closing transaction opens`() =
        SilentRollbackScenarios.`the transfer target is read before the closing transaction opens`(freshDatabase)

    @Test
    fun `a failing audit insert fails the cancel visibly`() = SilentRollbackScenarios.`a failing audit insert fails the cancel visibly`(freshDatabase)

    @Test
    fun `one failing enqueue does not undo the rest of the sweep`() =
        SilentRollbackScenarios.`one failing enqueue does not undo the rest of the sweep`(freshDatabase)
}
