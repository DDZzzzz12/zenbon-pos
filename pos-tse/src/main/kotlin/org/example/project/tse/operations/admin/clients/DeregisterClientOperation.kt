package org.example.project.tse.operations.admin.clients

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Deregister a client ID from the TSE.
 *
 * Removes a client from the list of registered clients.
 * All transactions started by this client must be finished before deregistration.
 *
 * Prerequisites:
 * - Client must be registered
 * - No open transactions for this client
 * - Admin authentication required
 *
 * Effects:
 * - Client can no longer start new transactions
 * - Existing finished transactions remain in TSE storage
 *
 * WormAPI Method: tse_deregisterClient() - line 903
 *
 * Endpoint: DELETE /admin/clients/{id}
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param clientId Client ID to deregister
 * @since 2.0.0
 */
class DeregisterClientOperation(
    adminPin: String,
    private val clientId: String
) : BaseTseOperation<TseDto.AdminResultDto>(adminPin) {

    /**
     * Deregister the client ID from TSE.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with deregistration confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.AdminResultDto {
        logger.info("Deregistering client ID: $clientId")

        val store = getWormStore(tseStatus)

        // Login as admin
        loginAsAdmin(store)

        // Deregister client
        store.tse_deregisterClient(clientId)

        logger.info("Client deregistered successfully: $clientId")

        return TseDto.AdminResultDto(
            success = true,
            message = "Client '$clientId' deregistered successfully"
        )
    }
}
