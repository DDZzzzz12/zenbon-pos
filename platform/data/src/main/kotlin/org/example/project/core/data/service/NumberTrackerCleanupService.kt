/**
 * Shared cleanup service for number trackers (Bill and Voucher).
 *
 * Provides a single background executor thread that periodically cleans up
 * expired reservations from all registered number trackers. This is more
 * efficient than having separate executors for each tracker.
 *
 * **How it works:**
 * - Single daemon thread runs every 10 seconds
 * - Trackers register cleanup tasks that get executed periodically
 * - Expired reservations (older than timeout) are automatically removed
 * - Prevents memory leaks from forgotten releases or failed transactions
 *
 * **Thread Safety:**
 * - Uses ScheduledExecutorService for thread-safe task scheduling
 * - Daemon thread won't prevent JVM shutdown
 * - Graceful shutdown with timeout
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.service

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service that manages periodic cleanup for number trackers.
 *
 * Provides a shared executor for both BillNumberTracker and VoucherNumberTracker
 * to efficiently clean up expired reservations without needing multiple threads.
 *
 * **Lifecycle**: Managed by Koin DI - starts on initialization, shuts down with app.
 */
class NumberTrackerCleanupService {
    private val logger = LoggerFactory.getLogger(NumberTrackerCleanupService::class.java)

    /**
     * Background executor for periodic cleanup of expired reservations.
     */
    private val cleanupExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "NumberTrackerCleanup").apply {
                isDaemon = true // Daemon thread won't prevent JVM shutdown
            }
        }

    /**
     * Registered cleanup tasks from number trackers.
     */
    private val cleanupTasks = mutableListOf<CleanupTask>()

    companion object {
        /**
         * Cleanup task execution interval in seconds.
         */
        private const val CLEANUP_INTERVAL_SECONDS = 10L

        /**
         * Reservation timeout in milliseconds (30 seconds).
         */
        const val RESERVATION_TIMEOUT_MS = 30_000L

        /**
         * Singleton instance set by DI container.
         * Allows tracker objects to access the service without DI injection.
         */
        @Volatile
        private var instance: NumberTrackerCleanupService? = null

        /**
         * Gets the singleton instance (must be initialized by DI first).
         */
        fun getInstance(): NumberTrackerCleanupService =
            instance ?: throw IllegalStateException(
                "NumberTrackerCleanupService not initialized. Ensure it's registered in Koin.",
            )

        /**
         * Sets the singleton instance (called by DI container).
         */
        internal fun setInstance(service: NumberTrackerCleanupService) {
            instance = service
        }
    }

    init {
        // Set singleton instance for tracker objects to access
        setInstance(this)

        // Start periodic cleanup task
        cleanupExecutor.scheduleAtFixedRate(
            { executeCleanupTasks() },
            CLEANUP_INTERVAL_SECONDS,
            CLEANUP_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        logger.info(
            "NumberTrackerCleanupService initialized (interval: ${CLEANUP_INTERVAL_SECONDS}s, timeout: ${RESERVATION_TIMEOUT_MS}ms)",
        )
    }

    /**
     * Interface for cleanup tasks that trackers must implement.
     */
    interface CleanupTask {
        /**
         * Name of the tracker (for logging).
         */
        val trackerName: String

        /**
         * Executes the cleanup operation, returning the number of expired items removed.
         */
        fun cleanup(): Int
    }

    /**
     * Registers a cleanup task with the service.
     *
     * @param task The cleanup task to register
     */
    fun registerCleanupTask(task: CleanupTask) {
        synchronized(cleanupTasks) {
            cleanupTasks.add(task)
            logger.info("Registered cleanup task: ${task.trackerName}")
        }
    }

    /**
     * Executes all registered cleanup tasks.
     *
     * Runs periodically in the background to clean up expired reservations
     * from all registered trackers.
     */
    private fun executeCleanupTasks() {
        try {
            val tasks = synchronized(cleanupTasks) { cleanupTasks.toList() }

            tasks.forEach { task ->
                try {
                    val cleanedCount = task.cleanup()
                    if (cleanedCount > 0) {
                        logger.info(
                            "${task.trackerName}: Auto-released $cleanedCount expired reservations",
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error during cleanup for ${task.trackerName}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error executing cleanup tasks", e)
        }
    }

    /**
     * Shuts down the cleanup executor gracefully.
     *
     * Should be called during application shutdown to ensure proper cleanup.
     */
    fun shutdown() {
        try {
            cleanupExecutor.shutdown()
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow()
            }
            logger.info("NumberTrackerCleanupService shut down")
        } catch (e: InterruptedException) {
            cleanupExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
