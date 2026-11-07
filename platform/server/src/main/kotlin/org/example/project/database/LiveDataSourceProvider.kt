package org.example.project.database

import com.zaxxer.hikari.HikariDataSource

/**
 * The live database connection pool, as a port.
 *
 * Backup and restore need the pool the server is running on; `DatabaseFactory` (host wiring) owns it,
 * so platform code asks for it here instead of naming the factory. Resolved on each call, so a pool
 * that is not initialised yet still fails at the same point it did before.
 */
fun interface LiveDataSourceProvider {
    fun liveDataSource(): HikariDataSource
}
