/**
 * Background service for retrying TSE signing operations.
 *
 * Runs periodically to process queued TSE signing requests that failed
 * due to TSE unavailability. Implements "Nachsignieren" (late signing)
 * for German KassenSichV compliance.
 *
 * Features:
 * - Periodic execution every 30 seconds
 * - Pre-check: Only processes if TSE is available
 * - Batch processing: Up to 50 entries per cycle
 * - Exponential backoff: 1min → 2min → 4min → 5min (max)
 * - Graceful shutdown with timeout
 * - Pause/resume capability for maintenance
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.tse.data.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseSigningQueueDto.TriggerRetryResponseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background service that periodically retries failed TSE signing operations.
 *
 * Lifecycle: Managed by Koin DI - starts on initialization, shuts down with app.
 *
 * @property queueService Service for queue operations
 */
class TseSigningRetryService(
    private val queueService: TseSigningQueueService,
) {
    private val logger = LoggerFactory.getLogger(TseSigningRetryService::class.java)

    /**
     * Background executor for periodic retry operations.
     */
    private val retryExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TseSigningRetry").apply {
                isDaemon = true // Daemon thread won't prevent JVM shutdown
            }
        }

    /**
     * Coroutine scope for async operations within scheduled task.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Handle for the scheduled task (for cancellation).
     */
    private var scheduledTask: ScheduledFuture<*>? = null

    /**
     * Flag indicating if service is paused.
     */
    private val isPaused = AtomicBoolean(false)

    /**
     * Flag indicating if service is running.
     */
    private val isServiceRunning = AtomicBoolean(false)

    /**
     * Last processing result summary for monitoring.
     */
    @Volatile
    private var lastProcessingResult: ProcessingResult? = null

    companion object {
        /**
         * Retry task execution interval in seconds.
         */
        const val RETRY_INTERVAL_SECONDS = 60L

        const val INITIAL_DELAY_SECONDS = 20L

        /**
         * Maximum entries to process per cycle.
         */
        const val BATCH_SIZE = 50

        /**
         * Singleton instance set by DI container.
         */
        @Volatile
        private var instance: TseSigningRetryService? = null

        /**
         * Gets the singleton instance.
         */
        fun getInstance(): TseSigningRetryService? = instance

        private fun register(service: TseSigningRetryService) {
            instance = service
        }

        /**
         * Checks if the retry service is currently running.
         */
        fun isRunning(): Boolean = instance?.isServiceRunning?.get() ?: false
    }

    init {
        register(this)
        logger.info("TseSigningRetryService initialized (interval: ${RETRY_INTERVAL_SECONDS}s, batchSize: $BATCH_SIZE)")
    }

    /**
     * Starts the periodic retry task.
     *
     * Should be called after DI initialization is complete.
     */
    fun start() {
        if (isServiceRunning.get()) {
            logger.warn("TseSigningRetryService already running, ignoring start request")
            return
        }

        scheduledTask =
            retryExecutor.scheduleAtFixedRate(
                { executeRetryTask() },
                INITIAL_DELAY_SECONDS, // Initial delay - give system time to start
                RETRY_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )

        isServiceRunning.set(true)
        logger.info("TseSigningRetryService started")
    }

    /**
     * Pauses the retry service.
     *
     * The scheduled task continues to run but skips processing.
     * Use for maintenance windows or controlled debugging.
     */
    fun pause() {
        isPaused.set(true)
        logger.info("TseSigningRetryService paused")
    }

    /**
     * Resumes the retry service after pause.
     */
    fun resume() {
        isPaused.set(false)
        logger.info("TseSigningRetryService resumed")
    }

    /**
     * Checks if the service is currently paused.
     */
    fun isPaused(): Boolean = isPaused.get()

    /**
     * Gets the last processing result for monitoring.
     */
    fun getLastProcessingResult(): ProcessingResult? = lastProcessingResult

    /**
     * Executes a single retry cycle.
     *
     * Called periodically by the scheduled executor.
     * Handles all errors internally to prevent task termination.
     */
    private fun executeRetryTask() {
        if (isPaused.get()) {
            logger.debug("TseSigningRetryService is paused, skipping cycle")
            return
        }

        try {
            serviceScope.launch {
                try {
                    if (ShopConfigurationManager.getTseConfig().mode == TseMode.DISABLED) {
                        logger.debug("TSE mode is DISABLED, skipping retry cycle")
                        lastProcessingResult =
                            ProcessingResult(
                                timestamp = System.currentTimeMillis(),
                                processed = 0,
                                succeeded = 0,
                                failed = 0,
                                queued = 0,
                                tseAvailable = false,
                                skippedReason = "TSE disabled",
                            )
                        return@launch
                    }

                    // Check TSE availability first
                    val tseAvailable =
                        try {
                            queueService.isTseAvailable()
                        } catch (e: PosException) {
                            logger.debug("TSE availability check failed: ${e.message}")
                            false
                        }

                    if (!tseAvailable) {
                        logger.debug("TSE not available, skipping retry cycle")
                        lastProcessingResult =
                            ProcessingResult(
                                timestamp = System.currentTimeMillis(),
                                processed = 0,
                                succeeded = 0,
                                failed = 0,
                                queued = 0,
                                tseAvailable = false,
                                skippedReason = "TSE unavailable",
                            )
                        return@launch
                    }

                    // Process batch
                    try {
                        val batchResult = queueService.processBatch(BATCH_SIZE)
                        lastProcessingResult =
                            ProcessingResult(
                                timestamp = System.currentTimeMillis(),
                                processed = batchResult.processedCount,
                                succeeded = batchResult.successCount,
                                failed = batchResult.failureCount,
                                queued = batchResult.queuedForRetryCount,
                                tseAvailable = true,
                                processingTimeMs = batchResult.processingTimeMs,
                                skippedReason = null,
                            )

                        if (batchResult.processedCount > 0) {
                            logger.info(
                                "TSE signing retry cycle complete: " +
                                    "processed=${batchResult.processedCount}, " +
                                    "success=${batchResult.successCount}, " +
                                    "failed=${batchResult.failureCount}, " +
                                    "queued=${batchResult.queuedForRetryCount}, " +
                                    "time=${batchResult.processingTimeMs}ms",
                            )
                        } else {
                            logger.debug("TSE signing retry cycle: no entries to process")
                        }
                    } catch (e: PosException) {
                        logger.error("TSE signing retry batch failed: ${e.message}")
                        lastProcessingResult =
                            ProcessingResult(
                                timestamp = System.currentTimeMillis(),
                                processed = 0,
                                succeeded = 0,
                                failed = 0,
                                queued = 0,
                                tseAvailable = true,
                                skippedReason = "Batch processing error: ${e.message}",
                            )
                    }
                } catch (e: Exception) {
                    logger.error("Error in TSE signing retry cycle", e)
                    lastProcessingResult =
                        ProcessingResult(
                            timestamp = System.currentTimeMillis(),
                            processed = 0,
                            succeeded = 0,
                            failed = 0,
                            queued = 0,
                            tseAvailable = false,
                            skippedReason = "Exception: ${e.message}",
                        )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to launch TSE signing retry task", e)
        }
    }

    /**
     * Manually triggers a retry cycle.
     *
     * Can be called from API endpoint for on-demand processing.
     *
     * @return Result of the processing cycle
     */
    suspend fun triggerManualRetry(): ProcessingResult {
        val tseAvailable =
            try {
                queueService.isTseAvailable()
            } catch (e: PosException) {
                false
            }

        if (!tseAvailable) {
            return ProcessingResult(
                timestamp = System.currentTimeMillis(),
                processed = 0,
                succeeded = 0,
                failed = 0,
                queued = 0,
                tseAvailable = false,
                skippedReason = "TSE unavailable",
            )
        }

        // processBatch throws PosException on failure; let it propagate. A manual trigger does not
        // wait out the back-off: the operator presses it having just removed the cause (registered
        // the station, reconnected the TSE), and a "retry now" that processed 0 entries until the
        // next scheduled cycle read as broken (E2E round 4, R4-8).
        val batchResult = queueService.processBatch(BATCH_SIZE, ignoreBackoff = true)
        val processingResult =
            ProcessingResult(
                timestamp = System.currentTimeMillis(),
                processed = batchResult.processedCount,
                succeeded = batchResult.successCount,
                failed = batchResult.failureCount,
                queued = batchResult.queuedForRetryCount,
                tseAvailable = true,
                processingTimeMs = batchResult.processingTimeMs,
                skippedReason = null,
            )
        lastProcessingResult = processingResult
        return processingResult
    }

    /**
     * Shuts down the retry executor gracefully.
     *
     * Should be called during application shutdown.
     */
    fun shutdown() {
        try {
            isServiceRunning.set(false)
            scheduledTask?.cancel(false)
            retryExecutor.shutdown()
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow()
            }
            logger.info("TseSigningRetryService shut down")
        } catch (e: InterruptedException) {
            retryExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Data class for processing result summary.
     *
     * Internal to the service — it is not `@Serializable` and must not be responded with. The wire
     * contract of `POST /api/tse/queue/trigger` is [TriggerRetryResponseDto], which is what
     * `KtorTseQueueApiClient.triggerRetry()` decodes; responding with this class instead answered
     * 400 *"Serializer for class 'ProcessingResult' is not found"* after the batch had already run.
     */
    data class ProcessingResult(
        val timestamp: Long,
        val processed: Int,
        val succeeded: Int,
        val failed: Int,
        val queued: Int,
        val tseAvailable: Boolean,
        val processingTimeMs: Long = 0,
        val skippedReason: String? = null,
    ) {
        /**
         * The operator-facing report of one manual cycle.
         *
         * A skipped cycle is **not** a success: nothing was signed, and the button must not read as
         * though the backlog had been worked off. The reason is carried into the message, because
         * "TSE unavailable" is the whole answer the operator needs.
         */
        fun toResponseDto(): TriggerRetryResponseDto =
            TriggerRetryResponseDto(
                success = skippedReason == null && failed == 0,
                processedCount = processed,
                successCount = succeeded,
                failureCount = failed,
                message =
                    skippedReason?.let { "Retry cycle skipped: $it" }
                        ?: "Processed $processed entries: $succeeded signed, $failed failed, $queued queued for retry",
            )
    }
}
