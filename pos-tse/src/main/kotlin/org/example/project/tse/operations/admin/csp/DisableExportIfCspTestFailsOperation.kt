/**
 * Disable export if CSP test fails operation.
 *
 * Configures the TSE to disable data export when the Crypto Service Provider
 * (CSP) self-test fails. This is a security hardening measure.
 *
 * Endpoint: POST /admin/csp-export/disable
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.1.0
 */
package org.example.project.tse.operations.admin.csp

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation

class DisableExportIfCspTestFailsOperation(
    adminPin: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Disable TSE export when CSP self-test fails.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Operation result on success.
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        if (tseStatus.isTseV2()) {
            // TSE 2.0 removed this feature and the SDK call became a silent no-op, so a
            // success response would tell the operator something was applied when nothing
            // was. Report it honestly instead.
            logger.info("Not applicable on TSE 2.0 — CSP-export toggle was removed in that generation")
            return TseDto.AdminResultDto(
                success = false,
                message = "Not applicable on TSE 2.0: the CSP-export toggle was removed in that generation.",
                clientId = tseStatus.getClientId(),
            )
        }

        logger.info("Disabling export if CSP test fails")

        val store = getWormStore(tseStatus)
        loginAsAdmin(store)

        store.tse_disableExportIfCspTestFails()

        logger.info("Export disabled if CSP test fails - security hardening applied")

        return TseDto.AdminResultDto(
            success = true,
            message = "Export will be disabled if CSP self-test fails. Security hardening applied."
        )
    }
}
