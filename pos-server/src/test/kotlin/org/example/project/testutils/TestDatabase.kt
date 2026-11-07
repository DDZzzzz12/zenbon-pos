package org.example.project.testutils

import com.zaxxer.hikari.HikariConfig
import org.example.project.database.DatabaseFactory
import org.example.project.infrastructure.plugins.initDB
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.stopKoin

object TestDatabase {
    /**
     * H2 stand-in for the Postgres advisory lock that `ZBonServiceImpl.createReport` AND
     * `KassenbuchServiceImpl.createSignedEntry` take (`pg_advisory_xact_lock(1)`). H2 has no such
     * function; aliasing it to `return key;` makes it a no-op so the H2 suite runs. Defined ONCE
     * here so every test reaching either signing path gets it (the real serialization is exercised
     * against Postgres by `KassenbuchCreateSignedEntryAdvisoryLockTest`, `@Tag("postgres")`).
     */
    private const val ADVISORY_LOCK_ALIAS =
        "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
            "'long pgAdvisoryXactLock(long key) { return key; }'"

    fun setup() {
        stopKoin()
        // Close any pool still open from a previous test class BEFORE calling initDB().
        //
        // initDB() wraps its whole body in `if (!DatabaseFactory.isInitialized())`, so while a pool is
        // open it silently no-ops — the unique jdbcUrl minted below is then never connected and every
        // `setupData` block runs against the previous class's database instead (a duplicate-key crash
        // when the fixtures collide, silently shared state when they don't).
        //
        // `testApp` now calls `startApplication()` before its trailing block, so the ordering that used
        // to strand a pool — `testApplication` starting the `application { }` module lazily, so that a
        // trailing block issuing no HTTP request ran the module (and hence this setup()) only after
        // `finally { teardown() }` had already fired — can no longer happen there. This close() stays as
        // a safety net for the paths that bypass `testApp`: tests driving `testApplication` directly
        // (e.g. RoutePermissionsCoverageTest), and any module that throws after initDB() opened a pool.
        DatabaseFactory.close()
        val config =
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
                maximumPoolSize = 10
            }
        initDB(config, runMigrations = false)
        transaction { exec(ADVISORY_LOCK_ALIAS) }
    }

    fun teardown() {
        DatabaseFactory.close()
        stopKoin()
    }
}
