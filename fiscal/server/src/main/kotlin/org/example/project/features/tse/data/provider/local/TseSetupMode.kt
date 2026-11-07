package org.example.project.features.tse.data.provider.local

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/**
 * The mode a TSE setup commissions the device in.
 *
 * Found on a real Swissbit TSE: a fresh install is configured DISABLED, setup passed that stored
 * mode to `SetupOperation`, and `SetupOperation` commissions in NORMAL only — so commissioning from
 * the setup dialog failed on every fresh install, the device stayed UNINITIALIZED, and the only way
 * through was to switch the mode to NORMAL in the configuration form first, which nothing said.
 *
 * Commissioning is the operator asking to run this TSE for real, so setup commissions in NORMAL:
 * - **DISABLED** (the fresh-install value) and **NORMAL** commission in NORMAL. The mode is not
 *   defaulted — it is what the setup act means, decided here per configured mode.
 * - **SIMULATION** is refused before the TSE is touched. It is a mode an operator chooses
 *   deliberately (signatures are mocked), and setup must not silently end it; nor could setup ever
 *   run in it — `SetupOperation` refuses every mode but NORMAL, and the refusal used to drop the
 *   shop to DISABLED on the way out.
 */
object TseSetupMode {
    /**
     * @param configured the persisted TSE mode.
     * @return the mode to commission the device in — always NORMAL.
     * @throws PosException 409 for a configured SIMULATION mode; nothing is written.
     */
    fun commissioningMode(configured: TseMode): TseMode =
        when (configured) {
            TseMode.NORMAL, TseMode.DISABLED -> TseMode.NORMAL
            TseMode.SIMULATION ->
                throw PosException(
                    code = PosErrorCode.TSE_INVALID_PARAMETERS,
                    httpStatus = HttpStatusCode.Conflict,
                    message =
                        "The TSE is configured for SIMULATION mode. A TSE is commissioned in NORMAL mode: switch the " +
                            "mode to NORMAL or DISABLED first. Nothing has been written to the device.",
                )
        }
}
