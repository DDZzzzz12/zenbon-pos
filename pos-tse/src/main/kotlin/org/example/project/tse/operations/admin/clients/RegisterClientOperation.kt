package org.example.project.tse.operations.admin.clients

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * Register a client ID with the TSE.
 *
 * Client IDs must be registered before they can perform transactions.
 * Each POS terminal should have a unique client ID.
 *
 * Registration Process:
 * 1. Admin login required
 * 2. Client ID is registered with TSE
 * 3. Verification by checking registered clients list
 *
 * Constraints:
 * - Maximum number of clients is limited (see maxRegisteredClients)
 * - Client ID must be unique
 * - Cannot register if TSE is not initialized
 *
 * WormAPI Method: tse_registerClient() - line 889
 *
 * Endpoint: POST /admin/clients
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param clientId Client ID to register
 * @since 2.0.0
 */
class RegisterClientOperation(
    adminPin: String,
    private val clientId: String
) : BaseTseOperation<TseDto.ClientRegistrationResultDto>(adminPin) {

    /**
     * Register the client ID with TSE.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with registration confirmation or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.ClientRegistrationResultDto {
        logger.info("Registering client ID: $clientId")

        val store = getWormStore(tseStatus)

        // Login as admin
        loginAsAdmin(store)
        val id = clientId

        logger.debug("Listing registered clients...")
        val registered = store.tse_listRegisteredClients()
        logger.debug("Currently registered clients: $registered")

        if (registered.contains(id)) {
            logger.info("Client already registered: $id")
            return TseDto.ClientRegistrationResultDto(
                success = true,
                clientId = id,
                message = "Client already registered: $id"
            )
        }

        // Register client
        logger.debug("Registering client with TSE...")
        store.tse_registerClient(id)
        logger.debug("Client registration call completed")

        logger.info("Client registered and verified successfully: $id")

        return TseDto.ClientRegistrationResultDto(
            success = true,
            clientId = id,
            message = "Client registered successfully"
        )
    }
}
