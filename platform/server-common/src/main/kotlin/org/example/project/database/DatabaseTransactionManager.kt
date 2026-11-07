package org.example.project.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

/**
 * Centralized transaction manager for database operations.
 *
 * Holds a reference to the active [Database] instance, set once during
 * server startup by [DatabaseFactory.init]. Feature modules use [dbQuery]
 * and [dbBlocking] to run operations within this database context.
 */
object DatabaseTransactionManager {
    private var database: Database? = null

    /**
     * Registers the active database connection.
     *
     * Called once by pos-server's DatabaseFactory after connecting.
     * Must be called before any [dbQuery] or [dbBlocking] usage.
     */
    fun register(db: Database) {
        database = db
    }

    /**
     * Returns the registered database, or throws if not yet registered.
     */
    fun getDatabase(): Database =
        database ?: throw IllegalStateException(
            "DatabaseTransactionManager not initialized. Call register() first.",
        )

    /**
     * Executes a suspending database operation within a transaction.
     *
     * @param transactionIsolation JDBC transaction isolation level (default: READ_COMMITTED)
     * @param block Suspending block to execute inside the transaction
     * @return Result of the block
     */
    suspend fun <T> dbQuery(
        transactionIsolation: Int = Connection.TRANSACTION_READ_COMMITTED,
        block: suspend () -> T,
    ): T {
        val db = getDatabase()
        return withContext(Dispatchers.IO) {
            suspendTransaction(db = db, transactionIsolation = transactionIsolation) {
                block()
            }
        }
    }

    /**
     * Executes a blocking database operation within a transaction.
     *
     * Used for synchronous operations (setup, migrations) that don't
     * need suspend context.
     *
     * @param block Block to execute inside the transaction
     * @return Result of the block
     */
    fun <T> dbBlocking(block: () -> T): T {
        val db = getDatabase()
        return transaction(db) { block() }
    }
}
