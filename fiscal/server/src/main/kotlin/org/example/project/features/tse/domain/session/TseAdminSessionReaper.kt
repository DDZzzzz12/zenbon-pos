package org.example.project.features.tse.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Logs the device Admin out when the in-memory admin sitting lapses on its own.
 *
 * The explicit Lock button logs out immediately, but an operator who simply walks away lets
 * the sitting expire silently after its idle window. Left there, the TSE keeps Admin logged
 * in — permanently on 1.x, and on 2.0 for as long as the maintenance task keeps reusing the
 * Admin login (each reuse resets the device's 40-minute timer, so it never self-clears).
 *
 * So this watches [TseAdminSession] and, the moment it observes the sitting go from unlocked
 * to locked without an explicit lock having handled it, ends the device sitting too. Polling
 * rather than a per-session timer keeps [TseAdminSession] a pure in-memory value with no
 * coroutine machinery, and the logout it calls is idempotent, so a redundant call after an
 * explicit lock is harmless.
 */
class TseAdminSessionReaper(
    private val scope: CoroutineScope,
    private val logoutAdmin: suspend () -> Unit,
    private val pollIntervalMs: Long = 60_000L,
    private val isUnlocked: () -> Boolean = { TseAdminSession.isUnlocked() },
) {
    private val logger = LoggerFactory.getLogger(TseAdminSessionReaper::class.java)

    @Volatile
    private var wasUnlocked = false
    private var job: Job? = null

    fun start() {
        job =
            scope.launch {
                while (isActive) {
                    delay(pollIntervalMs)
                    reconcileOnce()
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One reconcile pass. Logs out only on the unlocked → locked edge, so a sitting that is
     * still live (or was never unlocked) costs nothing, and a lapse triggers exactly one logout.
     *
     * Visible for tests so the edge behaviour can be checked without waiting on the poll clock.
     */
    suspend fun reconcileOnce() {
        val nowUnlocked = isUnlocked()
        if (wasUnlocked && !nowUnlocked) {
            logger.info("TSE admin sitting lapsed — logging the device Admin out")
            runCatching { logoutAdmin() }
                .onFailure { logger.warn("Device Admin logout after sitting lapse failed: ${it.message}") }
        }
        wasUnlocked = nowUnlocked
    }
}
