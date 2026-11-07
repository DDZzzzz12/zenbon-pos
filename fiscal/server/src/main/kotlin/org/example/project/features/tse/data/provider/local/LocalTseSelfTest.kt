package org.example.project.features.tse.data.provider.local

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.selftest.SelfTestOperation

/**
 * Runs the in-process TSE's self test for the register the driver works for — the one path used by
 * the power-loss recovery ([TsePowerLossRecovery]) and the manual "Selbsttest"
 * (`LocalTseAdminProvider.selfTest`).
 *
 * Both used to pass no client and rely on `TseStatus.clientId`, which nothing set when the TSE was
 * plugged in while the server was running. On a real Swissbit TSE 1.x (2026-10-10) every attempt
 * then failed with "no client ID is configured" until the server was restarted. The client is now
 * resolved per run through [TseDriverClient], so a terminal registered after the TSE came back is
 * picked up by the next attempt.
 *
 * TSE 1.x needs a registered client; with none, the run is refused *before* any device call
 * ([noRegisteredClient]). TSE 2.0 ignores the client, so null is passed and `pos-tse` uses the
 * no-argument SDK overload. Needs no Admin PIN: the self test logs in nobody, and the clock
 * re-sync `SelfTestOperation` runs after it logs in as TimeAdmin.
 *
 * @param resolveClient the registered client ([LocalTseLifecycleManager.signingClientId]).
 * @param isTseV2 whether the attached device is a TSE 2.0, read from the cached device info.
 * @param execute runs the device self test for a client (null only on TSE 2.0) and a force flag.
 */
class LocalTseSelfTest(
    private val resolveClient: suspend () -> String?,
    private val isTseV2: () -> Boolean,
    private val execute: suspend (clientId: String?, force: Boolean) -> TseDto.SelfTestResultDto,
) {
    /**
     * @param force run even when the device does not consider a self test due.
     * @param explicitClient a client the caller names; null resolves the registered one.
     * @throws PosException `TSE_NOT_OPERATIONAL` (409) on TSE 1.x with no registered terminal.
     */
    suspend fun run(
        force: Boolean,
        explicitClient: String? = null,
    ): TseDto.SelfTestResultDto {
        val clientId = explicitClient ?: resolveClient()
        if (clientId == null && !isTseV2()) throw noRegisteredClient()
        return execute(clientId, force)
    }

    companion object {
        /** Unattended and operator paths alike: the self test needs no Admin PIN. */
        private const val NO_ADMIN_PIN = ""

        /**
         * The self test of the TSE [lifecycleManager] owns, under a maintenance mark named
         * [maintenanceOp] and serialized on the TSE thread by the operation itself.
         */
        fun forLocalTse(
            lifecycleManager: LocalTseLifecycleManager,
            maintenanceOp: String,
        ): LocalTseSelfTest =
            LocalTseSelfTest(
                resolveClient = lifecycleManager::signingClientId,
                isTseV2 = { lifecycleManager.getTseStatus().isTseV2() },
                execute = { clientId, force ->
                    val status = lifecycleManager.getTseStatus()
                    status.withMaintenanceSuspend(maintenanceOp) {
                        SelfTestOperation(adminPin = NO_ADMIN_PIN, clientId = clientId, force = force).execute(status)
                    }
                },
            )

        private fun noRegisteredClient() =
            PosException(
                code = PosErrorCode.TSE_NOT_OPERATIONAL,
                httpStatus = HttpStatusCode.Conflict,
                message =
                    "Cannot run the TSE self test: no active terminal is registered on this TSE, and a " +
                        "TSE 1.x self test needs a registered register. Register a terminal's TSE client under " +
                        "Devices (Terminals), then run the self test again.",
            )
    }
}
