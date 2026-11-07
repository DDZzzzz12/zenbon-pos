package org.example.project.tse.operations

import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus

/**
 * Typed interface for TSE administrative operations.
 *
 * All TSE admin operations must implement this interface to ensure:
 * - Type-safe return values (no more Any)
 * - Consistent operation configuration (lock requirements, connection requirements)
 * - Thread-safe execution through TseStatus mutex mechanism
 *
 * The Swissbit TSE hardware only allows one command to be active at any given time.
 * Operations that require lock access will be queued and executed sequentially.
 *
 * @param T The return type of the operation result
 * @see org.example.project.tse.operations.admin.BaseTseOperation
 * @see org.example.project.tse.models.TseStatus.executeOnTseThread
 * @since 2.0.0
 */
interface ITseOperation<T> {

    /**
     * Whether this operation requires an active TSE hardware connection.
     *
     * Operations like STATUS don't require connection and can run in any mode.
     * Most admin operations require connection to the TSE hardware.
     */
    val requiresConnection: Boolean

    /**
     * Whether this operation requires exclusive lock on the TSE hardware.
     *
     * Most operations require lock to prevent concurrent hardware access.
     * Read-only status queries may not need exclusive lock.
     */
    val requiresLock: Boolean

    /**
     * Execute the TSE admin operation.
     *
     * This method is called by the controller after validating request parameters.
     * Implementations must handle:
     * - Connection validation (if requiresConnection is true)
     * - Lock acquisition (if requiresLock is true)
     * - Admin authentication if needed
     * - The actual operation execution
     * - Throwing [PosException] (per the B1 cross-walk) for failure modes
     *
     * Thread Safety Guarantee:
     * - Operations with requiresLock=true will use executeOnTseThread
     * - Only one locked operation executes at a time across all threads
     * - Concurrent requests are queued in FIFO order
     *
     * @param tseStatus The current TSE status containing connection state, configuration,
     *                  and the mutex for thread-safe hardware access
     * @return Operation-specific typed data on success.
     * @throws PosException if the operation fails (mapped to RFC 7807 ProblemDetail by
     *         the global StatusPages handler).
     */
    @Throws(PosException::class)
    fun execute(tseStatus: TseStatus): T
}
