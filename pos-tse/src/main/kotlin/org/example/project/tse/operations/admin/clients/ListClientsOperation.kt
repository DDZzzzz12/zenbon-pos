package org.example.project.tse.operations.admin.clients

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * List all registered clients on the TSE.
 *
 * Retrieves the list of all client IDs that have been registered with the TSE.
 * This is useful for:
 * - Auditing registered POS terminals
 * - Verifying client registration
 * - Checking available registration slots
 *
 * WormAPI Method: tse_listRegisteredClients() - line 915
 *
 * Endpoint: GET /admin/clients
 *
 * @param adminPin Admin PIN for TSE authentication
 * @since 2.0.0
 */
class ListClientsOperation(
    adminPin: String
) : BaseTseOperation<TseDto.ClientListResultDto>(adminPin) {

    /**
     * List all registered clients.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with list of registered clients or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.ClientListResultDto {
        logger.info("Listing registered clients")

        val store = getWormStore(tseStatus)
        // tse_listRegisteredClients requires Admin on TSE 1.x (v2 allows any user except
        // TimeAdmin). This operation performed no login at all and worked only while the
        // startup sequence left an Admin session behind — which it no longer does. It is
        // operator-triggered, so the session PIN is available.
        loginAsAdmin(store)

        val clients = store.tse_listRegisteredClients()

        logger.info("Found ${clients.size} registered clients")
        clients.forEachIndexed { index, client ->
            logger.debug("  [$index] $client")
        }

        return TseDto.ClientListResultDto(
            success = true,
            clients = clients.toList(),
            count = clients.size,
            message = "Found ${clients.size} registered clients"
        )
    }
}
