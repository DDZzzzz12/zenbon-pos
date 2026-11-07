/**
 * Thread-safe tracker for pending voucher numbers to prevent duplicates during concurrent transactions.
 *
 * This singleton maintains an in-memory map of voucher numbers with reservation timestamps
 * to prevent race conditions where multiple concurrent requests could generate the same
 * voucher number before the transaction commits.
 *
 * **How it works:**
 * 1. Before generating a number, reserve it atomically with a timestamp
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
package org.example.project.features.voucher.data.service

import org.example.project.core.data.service.NumberTrackerCleanupService
import org.jetbrains.exposed.v1.core.exposedLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton for tracking pending voucher numbers with automatic timeout.
 *
 * Prevents race conditions during concurrent voucher creation by maintaining
 * an in-memory map of voucher numbers with reservation timestamps.
 *
 * **Auto-Release:** Reservations automatically expire after 30 seconds to prevent
 * memory leaks from failed transactions or forgotten releases.
 */
object VoucherNumberTracker : NumberTrackerCleanupService.CleanupTask {
    override val trackerName: String = "VoucherNumberTracker"

    /**
     * Thread-safe map of voucher numbers with their reservation timestamps.
     *
     * - Key: voucher number (e.g., "G2025-0001")
     * - Value: timestamp in milliseconds when the number was reserved
     */
    private val pendingNumbers: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    init {
        // Register with shared cleanup service (accessed via DI singleton)
        NumberTrackerCleanupService.getInstance().registerCleanupTask(this)
        exposedLogger.info("VoucherNumberTracker initialized with ${NumberTrackerCleanupService.RESERVATION_TIMEOUT_MS}ms timeout")
    }

    /**
     * Attempts to reserve a voucher number for the current transaction.
     *
     * If the number is already reserved by another transaction, this will return false.
     * The caller should retry generation or wait before attempting again.
     *
     * **Thread Safety:** This operation is atomic - if it returns true, the number
     * is guaranteed to be reserved exclusively for this transaction.
     *
     * **Auto-Release:** The reservation will automatically expire after 30 seconds.
     *
     * @param voucherNumber The voucher number to reserve
     * @return true if successfully reserved, false if already reserved by another transaction
     */
    fun reserve(voucherNumber: String): Boolean {
        val now = System.currentTimeMillis()
        val previousValue = pendingNumbers.putIfAbsent(voucherNumber, now)

        val wasAdded = previousValue == null
        if (wasAdded) {
            exposedLogger.debug("Reserved voucher number: $voucherNumber (Pending: ${pendingNumbers.size})")
        } else {
            exposedLogger.warn("Voucher number already reserved: $voucherNumber")
        }
        return wasAdded
    }

    /**
     * Releases a voucher number after transaction completion.
     *
     * Should be called in transaction commit/rollback hooks to cleanup the tracker.
     *
     * **Usage:**
     * - Call in transaction commit hook after successful save
     * - Call in transaction rollback hook after failure
     * - Call in finally block for guaranteed cleanup
     *
     * @param voucherNumber The voucher number to release
     */
    fun release(voucherNumber: String) {
        val wasRemoved = pendingNumbers.remove(voucherNumber) != null
        if (wasRemoved) {
            exposedLogger.debug("Released voucher number: $voucherNumber (Pending: ${pendingNumbers.size})")
        } else {
            exposedLogger.warn("Attempted to release non-reserved voucher number: $voucherNumber")
        }
    }

    /**
     * Checks if a voucher number is currently reserved by a pending transaction.
     *
     * Also checks if the reservation has expired. Expired reservations are treated
     * as not reserved and will be cleaned up by the background task.
     *
     * @param voucherNumber The voucher number to check
     * @return true if reserved and not expired, false otherwise
     */
    fun isReserved(voucherNumber: String): Boolean {
        val reservationTime = pendingNumbers[voucherNumber] ?: return false
        val isExpired = System.currentTimeMillis() - reservationTime > NumberTrackerCleanupService.RESERVATION_TIMEOUT_MS

        if (isExpired) {
            // Clean up expired reservation immediately when detected
            pendingNumbers.remove(voucherNumber)
            return false
        }

        return true
    }

    /**
     * Gets the current count of pending voucher numbers.
     *
     * Useful for monitoring and debugging purposes.
     *
     * @return Number of voucher numbers currently reserved
     */
    fun getPendingCount(): Int = pendingNumbers.size

    /**
     * Clears all pending reservations.
     *
     * **⚠️ USE WITH CAUTION:** This should only be called in exceptional circumstances
     * such as application shutdown or during testing.
     *
     * Under normal operations, voucher numbers should be released through the
     * transaction lifecycle hooks.
     */
    fun clearAll() {
        val count = pendingNumbers.size
        pendingNumbers.clear()
        exposedLogger.warn("Cleared all pending voucher numbers (count: $count)")
    }

    /**
     * Gets a defensive copy of all currently pending voucher numbers.
     *
     * Useful for debugging and monitoring purposes.
     *
     * @return Immutable set of pending voucher numbers
     */
    fun getPendingNumbers(): Set<String> = pendingNumbers.keys.toSet()

    /**
     * Cleanup implementation for the shared cleanup service.
     *
     * Removes expired voucher number reservations and returns the count
     * of items removed. Called periodically by NumberTrackerCleanupService.
     *
     * @return Number of expired reservations removed
     */
    override fun cleanup(): Int {
        val now = System.currentTimeMillis()
        val expiredNumbers = mutableListOf<String>()

        // Find expired reservations
        pendingNumbers.forEach { (voucherNumber, reservationTime) ->
            if (now - reservationTime > NumberTrackerCleanupService.RESERVATION_TIMEOUT_MS) {
                expiredNumbers.add(voucherNumber)
            }
        }

        // Remove expired reservations
        expiredNumbers.forEach { voucherNumber ->
            pendingNumbers.remove(voucherNumber)
        }

        if (expiredNumbers.isNotEmpty()) {
            exposedLogger.debug("Expired vouchers: ${expiredNumbers.joinToString(", ")} (Remaining: ${pendingNumbers.size})")
        }

        return expiredNumbers.size
    }
}
