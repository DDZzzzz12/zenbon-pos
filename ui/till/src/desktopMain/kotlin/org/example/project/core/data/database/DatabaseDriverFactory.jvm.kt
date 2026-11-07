package org.example.project.core.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.example.project.database.PosDatabase
import java.io.File

/**
 * Desktop JVM database driver factory.
 *
 * For the desktop app, this maintains SQLite for local caching while the main
 * business logic uses PostgreSQL through the server module's DatabaseFactory.
 * This separation allows:
 * - PostgreSQL for business data (shared with server module)
 * - SQLite for UI state, preferences, and offline caching
 */
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val databasePath = File(System.getProperty("user.home"), ".pos/${DatabaseConfig.DATABASE_NAME}")
        databasePath.parentFile?.mkdirs()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}")

        val schemaVersion = PosDatabase.Schema.version
        val currentVersion = getCurrentVersion(driver)
        val hasExistingTables = hasExistingTables(driver)

        when {
            !hasExistingTables -> {
                // Fresh database — create schema from scratch
                PosDatabase.Schema.create(driver)
                setVersion(driver, schemaVersion)
            }
            currentVersion < schemaVersion -> {
                // Existing database needing migration.
                // Legacy databases (version 0) are treated as version 1.
                val fromVersion = if (currentVersion == 0L) 1L else currentVersion
                migrateIdempotent(driver, fromVersion, schemaVersion)
                setVersion(driver, schemaVersion)
            }
        }

        return driver
    }

    /**
     * Runs migrations with idempotent error handling.
     *
     * Intermediate builds may have applied schema changes via CREATE TABLE
     * before migration files existed, so ALTER TABLE ADD COLUMN may fail
     * with "duplicate column". This is safe to ignore.
     */
    private fun migrateIdempotent(
        driver: SqlDriver,
        fromVersion: Long,
        toVersion: Long,
    ) {
        // V1 -> V2: Add is_training column
        if (fromVersion < 2) {
            try {
                driver.execute(
                    null,
                    "ALTER TABLE local_rtables ADD COLUMN is_training INTEGER NOT NULL DEFAULT 0",
                    0,
                )
            } catch (_: Exception) {
                // Column already exists — safe to ignore
            }
        }
        // Future migrations (version 2+) use standard SQLDelight migrations
        if (fromVersion >= 2 && toVersion > fromVersion) {
            PosDatabase.Schema.migrate(driver, fromVersion, toVersion)
        }
    }

    /**
     * Checks if the database already has user tables.
     */
    private fun hasExistingTables(driver: SqlDriver): Boolean {
        val count =
            driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                    mapper = { cursor ->
                        cursor.next()
                        app.cash.sqldelight.db.QueryResult
                            .Value(cursor.getLong(0) ?: 0L)
                    },
                    parameters = 0,
                ).value
        return count > 0L
    }

    /**
     * Reads the current user_version pragma from the SQLite database.
     */
    private fun getCurrentVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult
                        .Value(cursor.getLong(0) ?: 0L)
                },
                parameters = 0,
            ).value

    /**
     * Sets the user_version pragma on the SQLite database.
     */
    private fun setVersion(
        driver: SqlDriver,
        version: Long,
    ) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
