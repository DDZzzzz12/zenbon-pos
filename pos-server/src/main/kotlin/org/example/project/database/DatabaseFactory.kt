/**
 * Database factory responsible for managing database connections and initialization.
 *
 * This factory handles PostgreSQL database connections using Exposed ORM framework
 * and provides methods for database initialization, migration, and connection management.
 * Supports both development and production configurations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.infrastructure.plugins.createDataSource
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Singleton factory for managing database connections and operations.
 *
 * Provides centralized database connection management using HikariCP connection pooling
 * and Exposed ORM for database operations. Handles connection lifecycle and provides
 * utility methods for database transactions.
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private lateinit var database: Database
    private lateinit var dataSource: HikariDataSource

    /**
     * Initializes the database connection with the provided configuration.
     *
     * Sets up HikariCP connection pool with PostgreSQL driver and creates
     * the database connection using Exposed ORM. Runs Flyway migrations
     * before Exposed schema initialization to ensure database is up-to-date.
     *
     * @param hikariConfig HikariCP configuration for database connection
     * @param runMigrations Whether to run Flyway migrations (default: true)
     */
    fun init(
        hikariConfig: HikariConfig? = null,
        runMigrations: Boolean = true,
    ) {
        try {
            // Close existing connection if already initialized
            if (::dataSource.isInitialized && !dataSource.isClosed) {
                logger.info("Closing existing database connection before re-initialization")
                dataSource.close()
            }

            if (hikariConfig == null) {
                dataSource = HikariDataSource(createDataSource())
            } else {
                // Create HikariCP data source
                dataSource = HikariDataSource(hikariConfig)
            }

            // Run Flyway migrations BEFORE Exposed schema initialization
            if (runMigrations) {
                logger.info("Running Flyway database migrations...")
                FlywayConfig.migrate(dataSource)
            }

            // Create Exposed database connection
            database = Database.connect(datasource = dataSource)

            // Register with platform/server-common's shared transaction manager
            // so feature modules can use dbQuery() without depending on pos-server
            DatabaseTransactionManager.register(database)

            logger.info("Database connection initialized successfully")

            // Test the connection and configure SQL logging
            transaction(database) {
                // Enable SQL logging for debugging
                addLogger(StdOutSqlLogger)

                exec("SELECT 1") { rs ->
                    if (rs.next()) {
                        logger.info("Database connection test successful")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize database connection", e)
            throw e
        }
    }

    /**
     * Executes a database query within a suspended transaction context.
     *
     * Provides coroutine-safe database operations using Exposed's suspended
     * transaction support. All database operations should use this method
     * to ensure proper transaction management and context switching.
     *
     * Isolation Levels (from weakest to strongest):
     * - TRANSACTION_READ_COMMITTED (2) - Default. Prevents dirty reads.
     * - TRANSACTION_REPEATABLE_READ (4) - Also prevents non-repeatable reads.
     * - TRANSACTION_SERIALIZABLE (8) - Full isolation. Use for critical operations like bill/order creation.
     *
     * @param T Return type of the database operation
     * @param transactionIsolation Transaction isolation level. Defaults to READ_COMMITTED (2).
     *        Use SERIALIZABLE (8) for critical financial operations to prevent race conditions.
     * @param block Suspend function containing database operations
     * @return Result of the database operation
     * @throws TransactionRollbackException if transaction fails and is rolled back
     *
     * @see java.sql.Connection.TRANSACTION_READ_COMMITTED
     * @see java.sql.Connection.TRANSACTION_REPEATABLE_READ
     * @see java.sql.Connection.TRANSACTION_SERIALIZABLE
     */
    suspend fun <T> dbQuery(
        transactionIsolation: Int = Connection.TRANSACTION_READ_COMMITTED,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, transactionIsolation = transactionIsolation) {
                block()
            }
        }

    /**
     * Executes a blocking database operation within a transaction.
     *
     * For non-suspend operations that need database access. Use dbQuery()
     * for coroutine-based operations when possible.
     *
     * @param T Return type of the database operation
     * @param block Function containing database operations
     * @return Result of the database operation
     */
    fun <T> blockingQuery(block: () -> T): T = transaction(database) { block() }

    /**
     * Gets the current database instance.
     *
     * @return Exposed Database instance
     * @throws IllegalStateException if database is not initialized
     */
    fun getDatabase(): Database {
        if (!::database.isInitialized) {
            throw IllegalStateException("Database not initialized. Call init() first.")
        }
        return database
    }

    /**
     * Closes the database connection and releases all resources.
     *
     * Should be called during application shutdown to properly clean up
     * database connections and prevent resource leaks. After calling this
     * method, the database will need to be re-initialized.
     */
    fun close() {
        if (::dataSource.isInitialized) {
            logger.info("Closing database connection pool")
            dataSource.close()
            logger.info("Database connection pool closed")
        }
    }

    /**
     * Checks if the database connection is currently initialized and active.
     *
     * @return true if database is initialized and connection pool is not closed
     */
    fun isInitialized(): Boolean = ::database.isInitialized && !dataSource.isClosed

    /**
     * Returns the underlying HikariCP data source.
     *
     * Used by FlywayConfig to run migrations on the same connection pool.
     *
     * @return The active [HikariDataSource]
     * @throws IllegalStateException if the data source is not initialized
     */
    fun getDataSource(): HikariDataSource {
        if (!::dataSource.isInitialized) {
            throw IllegalStateException("DataSource not initialized. Call init() first.")
        }
        return dataSource
    }

    /**
     * Gets current connection pool statistics for monitoring.
     *
     * Provides insights into connection pool usage for performance monitoring
     * and debugging purposes.
     *
     * @return Map containing pool statistics (active connections, idle connections, etc.)
     */
    fun getConnectionPoolStats(): Map<String, Any> {
        if (!::dataSource.isInitialized) {
            return emptyMap()
        }

        return mapOf(
            "activeConnections" to dataSource.hikariPoolMXBean.activeConnections,
            "idleConnections" to dataSource.hikariPoolMXBean.idleConnections,
            "totalConnections" to dataSource.hikariPoolMXBean.totalConnections,
            "threadsAwaitingConnection" to dataSource.hikariPoolMXBean.threadsAwaitingConnection,
            "maximumPoolSize" to dataSource.maximumPoolSize,
            "poolName" to dataSource.poolName,
        )
    }
}
