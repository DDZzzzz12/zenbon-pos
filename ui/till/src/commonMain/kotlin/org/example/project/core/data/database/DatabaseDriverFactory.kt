package org.example.project.core.data.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific database driver factory.
 *
 * Each platform implements this interface to provide the appropriate SQLite driver:
 * - Android: Uses AndroidSqliteDriver for local caching and offline data
 * - Desktop (JVM): Uses JdbcSqliteDriver for UI state and preferences
 *   (Main business data handled by server module's PostgreSQL)
 * - iOS: Uses NativeSqliteDriver for local caching and offline data
 *
 * Note: Desktop apps use PostgreSQL for business logic through server module services,
 * while SQLite is used only for local UI state and caching.
 */
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

/**
 * Database configuration constants.
 */
object DatabaseConfig {
    const val DATABASE_NAME = "pos_database.db"
    const val DATABASE_VERSION = 2
}
