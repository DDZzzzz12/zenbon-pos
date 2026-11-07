/**
 * Thread-safe tracker for pending bill numbers to prevent duplicates during concurrent transactions.
 *
 * **Not the uniqueness mechanism any more.** Bill numbers come from the month's counter row in
 * `bill_number_sequence`, locked `FOR UPDATE` inside the bill's transaction (real-TSE QA rt12: this
 * tracker, released before commit, let two parallel bills share `RE-260506-39`). The tracker stays
 * as an in-process second guard; a number is drawn once and a refused reservation aborts.
 *
 * This singleton maintains an in-memory map of bill numbers with reservation timestamps
 * to prevent race conditions where multiple concurrent requests could generate the same
 * bill number before the transaction commits.
 *
 * **How it works:**
 * 1. Before generating a bill number, reserve it atomically with a timestamp
 * 2. If another request tries to reserve the same number, it will be rejected
 * 3. After transaction commits (or rolls back), release the number from the tracker
 * 4. Reservations automatically expire after 30 seconds (auto-release)
 *
 * **Thread Safety:**
 * - Uses ConcurrentHashMap for thread-safe operations
 * - Atomic reserve/release operations ensure no duplicates
 * - No locks required - leverages ConcurrentHashMap's internal synchronization
 *
 * **Memory Management:**
 * - Automatic cleanup on transaction commit/rollback
 * - Auto-release after 30 seconds to prevent memory leaks
 * - Background cleanup task runs every 10 seconds
 * - Manual cleanup available for testing/shutdown
 *
 * **Auto-Release Mechanism:**
 * - Reservations timeout after 30 seconds (RESERVATION_TIMEOUT_MS)
 * - Background daemon thread cleans up expired reservations every 10 seconds
 * - Prevents memory leaks from forgotten releases or failed transactions
 * - Immediate cleanup when expired reservations are detected via isReserved()
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.bill.data.service

import org.example.project.core.data.service.NumberTrackerCleanupService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton for tracking pending bill numbers with automatic timeout.
 *
 * Prevents race conditions during concurrent bill creation by maintaining
 * an in-memory map of bill numbers with reservation timestamps.
 *
 * **Auto-Release:** Reservations automatically expire after 30 seconds to prevent
 * memory leaks from failed transactions or forgotten releases.
 */
object BillNumberTracker : NumberTrackerCleanupService.CleanupTask {
    override val trackerName: String = "BillNumberTracker"

    private val logger = LoggerFactory.getLogger(BillNumberTracker::class.java)

    /**
     * Thread-safe map of bill numbers with their reservation timestamps.
     *
     * - Key: bill number (e.g., "B2025-0001")
     * - Value: timestamp in milliseconds when the number was reserved
     */
    private val pendingNumbers: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    init {
        // Register with shared cleanup service (accessed via DI singleton)
        NumberTrackerCleanupService.getInstance().registerCleanupTask(this)
        logger.info("BillNumberTracker initialized with ${NumberTrackerCleanupService.RESERVATION_TIMEOUT_MS}ms timeout")
    }

    /**
     * Attempts to reserve a bill number for the current transaction.
     *
     * If the number is already reserved by another transaction, this will return false.
     * The caller should retry generation or wait before attempting again.
     *
     * **Thread Safety:** This operation is atomic - if it returns true, the number
     * is guaranteed to be reserved exclusively for this transaction.
     *
     * **Auto-Release:** The reservation will automatically expire after 30 seconds.
     *
     * @param billNumber The bill number to reserve
     * @return true if successfully reserved, false if already reserved by another transaction
     */
    fun reserve(billNumber: String): Boolean {
        val now = System.currentTimeMillis()
        val previousValue = pendingNumbers.putIfAbsent(billNumber, now)

        val wasAdded = previousValue == null
        if (wasAdded) {
            logger.debug("Reserved bill number: $billNumber (Pending: ${pendingNumbers.size})")
        } else {
            logger.warn("Bill number already reserved: $billNumber")
        }
        return wasAdded
    }

    /**
     * Releases a bill number after transaction completion.
     *
     * Should be called in transaction commit/rollback hooks to cleanup the tracker.
     *
     * **Usage:**
     * - Call in transaction commit hook after successful save
     * - Call in transaction rollback hook after failure
     * - Call in finally block for guaranteed cleanup
     *
     * @param billNumber The bill number to release
     */
    fun release(billNumber: String) {
        val wasRemoved = pendingNumbers.remove(billNumber) != null
        if (wasRemoved) {
            logger.debug("Released bill number: $billNumber (Pending: ${pendingNumbers.size})")
        } else {
            logger.warn("Attempted to release non-reserved bill number: $billNumber")
        }
    }

    /**
     * Checks if a bill number is currently reserved by a pending transaction.
     *
     * Also checks if the reservation has expired. Expired reservations are treated
     * as not reserved and will be cleaned up by the background task.
     *
     * @param billNumber The bill number to check
     * @return true if reserved and not expired, false otherwise
     */
    fun isReserved(billNumber: String): Boolean {
        val reservationTime = pendingNumbers[billNumber] ?: return false
        val isExpired = System.currentTimeMillis() - reservationTime > NumberTrackerCleanupService.RESERVATION_TIMEOUT_MS

        if (isExpired) {
            // Clean up expired reservation immediately when detected
            pendingNumbers.remove(billNumber)
            return false
        }

        return true
    }

    /**
     * Gets the current count of pending bill numbers.
     *
     * Useful for monitoring and debugging purposes.
     *
     * @return Number of bill numbers currently reserved
     */
    fun getPendingCount(): Int = pendingNumbers.size

    /**
     * Clears all pending reservations.
     *
     * **⚠️ USE WITH CAUTION:** This should only be called in exceptional circumstances
     * such as application shutdown or during testing.
     *
     * Under normal operations, bill numbers should be released through the
     * transaction lifecycle hooks.
     */
    fun clearAll() {
        val count = pendingNumbers.size
        pendingNumbers.clear()
        logger.warn("Cleared all pending bill numbers (count: $count)")
    }

    /**
     * Gets a defensive copy of all currently pending bill numbers.
     *
     * Useful for debugging and monitoring purposes.
     *
     * @return Immutable set of pending bill numbers
     */
    fun getPendingNumbers(): Set<String> = pendingNumbers.keys.toSet()

    /**
     * Cleanup implementation for the shared cleanup service.
     *
     * Removes expired bill number reservations and returns the count
     * of items removed. Called periodically by NumberTrackerCleanupService.
     *
     * @return Number of expired reservations removed
     */
    override fun cleanup(): Int {
        val now = System.currentTimeMillis()
        val expiredNumbers = mutableListOf<String>()

        // Find expired reservations
        pendingNumbers.forEach { (billNumber, reservationTime) ->
            if (now - reservationTime > NumberTrackerCleanupService.RESERVATION_TIMEOUT_MS) {
                expiredNumbers.add(billNumber)
            }
        }

        // Remove expired reservations
        expiredNumbers.forEach { billNumber ->
            pendingNumbers.remove(billNumber)
        }

        if (expiredNumbers.isNotEmpty()) {
            logger.debug("Expired bills: ${expiredNumbers.joinToString(", ")} (Remaining: ${pendingNumbers.size})")
        }

        return expiredNumbers.size
    }
}
