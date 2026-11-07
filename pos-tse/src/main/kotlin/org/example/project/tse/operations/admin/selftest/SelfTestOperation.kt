package org.example.project.tse.operations.admin.selftest

import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Perform TSE self-test.
 *
 * The TSE performs periodic self-tests to verify hardware integrity.
 * Self-tests can be triggered manually or forced.
 *
 * Self-Test Behavior:
 * - Verifies cryptographic hardware integrity
 * - Checks signature generation capability
 * - Validates internal state consistency
 * - Required by German fiscal regulations (KassenSichV)
 *
 * Post Self-Test Time Sync:
 * - Running a self-test invalidates the TSE's internal clock, after which the TSE
 *   reports an invalid time and rejects signing until re-synchronized.
 * - This operation therefore re-synchronizes the time (tse_updateTime) immediately
 *   after the self-test, within the same admin session.
 *
 * Forcing Self-Test:
 * - Normal self-test may skip if recently executed
 * - Force mode always runs the test regardless of timing
 *
 * WormAPI Method: tse_runSelfTest() - line 873
 *
 * Endpoints:
 * - POST /admin/selftest - Normal self-test
 * - POST /admin/selftest/force - Forced self-test
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param clientId Client ID for self-test execution
 * @param force If true, force self-test even if not due
 * @since 2.0.0
 */
class SelfTestOperation(
    adminPin: String,
    private val clientId: String?,
    private val force: Boolean = false
) : BaseTseOperation<TseDto.SelfTestResultDto>(adminPin) {

    /**
     * Perform TSE self-test.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with test result or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.SelfTestResultDto {
        logger.info("Performing TSE self-test. Force: $force")

        val store = getWormStore(tseStatus)

        // Login as admin
        // No login: the self test requires none on TSE 1.x, and on TSE 2.0 it only excludes
        // TimeAdmin -- whom the startup sequence never leaves logged in. Requiring Admin here
        // was part of what forced the Admin PIN to be stored.

        // Determine client ID to use
        val testClientId = clientId ?: tseStatus.getClientId()

        // Run self-test
        val startTime = System.currentTimeMillis()
        tseStatus.runSelfTestOnDevice(testClientId)
        val durationMs = System.currentTimeMillis() - startTime

        logger.info("Self-test completed successfully in ${durationMs}ms")

        // A self-test invalidates the TSE clock, so the time MUST be re-synchronized
        // immediately or the TSE reports an invalid time and rejects signing. Let failures
        // propagate — a self-test that leaves the clock invalid is not a healthy outcome.
        //
        // TimeAdmin, not Admin: tse_updateTime accepts either, and the TimeAdmin PIN is the
        // one credential the guidance manual (2.5) permits the ERS to store. The self test
        // itself needs no login at all.
        val currentTime = System.currentTimeMillis() / 1000
        loginAsTimeAdmin(store, tseStatus.getTimeAdminPin())
        try {
            store.tse_updateTime(currentTime)
            logger.info("TSE time re-synchronized after self-test to: $currentTime")
        } finally {
            // Mandatory on TSE 2.0: a resting TimeAdmin may neither export nor read entries.
            runCatching { store.user_logout(WormUserId.WORM_USER_TIME_ADMIN) }
        }

        return TseDto.SelfTestResultDto(
            success = true,
            message = "Self-test completed successfully",
            forced = force,
            durationMs = durationMs
        )
    }
}
