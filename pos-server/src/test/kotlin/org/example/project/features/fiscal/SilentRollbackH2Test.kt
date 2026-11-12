package org.example.project.features.fiscal

import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [SilentRollbackScenarios] on H2, so the default suite keeps the guard. H2 does not abort a
 * transaction on a failed statement, so it cannot show the silent rollback itself — only the
 * ordering and the visible-failure halves of each invariant. [SilentRollbackPostgresTest] is the
 * real reproduction.
 */
@DisplayName("Swallowed SQL failures on fiscal paths do not silently roll back (H2)")
class SilentRollbackH2Test {
    private val database: () -> Unit = TestDatabase::setup

    @Test
    fun `a failing favourites recalculation does not lose the closing`() =
        SilentRollbackScenarios.`a failing favourites recalculation does not lose the closing`(database)

    @Test
    fun `a failing Z-Bon PDF does not lose the closing`() = SilentRollbackScenarios.`a failing Z-Bon PDF does not lose the closing`(database)

    @Test
    fun `the transfer target is read before the closing transaction opens`() =
        SilentRollbackScenarios.`the transfer target is read before the closing transaction opens`(database)

    @Test
    fun `a failing audit insert fails the cancel visibly`() = SilentRollbackScenarios.`a failing audit insert fails the cancel visibly`(database)

    @Test
    fun `one failing enqueue does not undo the rest of the sweep`() =
        SilentRollbackScenarios.`one failing enqueue does not undo the rest of the sweep`(database)
}
