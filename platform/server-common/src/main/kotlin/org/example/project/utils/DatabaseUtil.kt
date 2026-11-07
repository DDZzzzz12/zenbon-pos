package org.example.project.utils

import org.example.project.database.DatabaseTransactionManager

/**
 * Executes a database query within a suspend transaction context.
 *
 * This is the preferred method for most database operations in services,
 * particularly when converting entities to DTOs that may access related properties.
 *
 * @param transactionIsolation Transaction isolation level (defaults to READ_COMMITTED).
 *        Use TRANSACTION_SERIALIZABLE for critical financial operations.
 * @param block The database operation to execute
 * @return The result of the database operation
 */
suspend fun <T> dbQuery(
    transactionIsolation: Int = java.sql.Connection.TRANSACTION_READ_COMMITTED,
    block: suspend () -> T,
): T =
    DatabaseTransactionManager.dbQuery(
        transactionIsolation = transactionIsolation,
        block = block,
    )

/**
 * Executes a blocking database transaction.
 *
 * Used primarily for database setup, migrations, and operations that need
 * to run synchronously without suspend context.
 *
 * @param block The database operation to execute
 * @return The result of the database operation
 */
fun <T> dbBlocking(block: () -> T): T = DatabaseTransactionManager.dbBlocking { block() }
