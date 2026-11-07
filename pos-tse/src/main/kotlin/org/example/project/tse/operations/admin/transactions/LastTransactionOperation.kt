package org.example.project.tse.operations.admin.transactions

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus
import java.util.*

/**
 * Get the last transaction response.
 *
 * Retrieves information about the most recent transaction, including:
 * - Transaction number
 * - Signature counter
 * - Log timestamp
 * - Serial number
 * - Signature (Base64 encoded)
 *
 * Filtering:
 * - If clientId is provided, returns last transaction for that specific client
 * - If clientId is null, returns the globally last transaction
 *
 * WormAPI Method: transaction_lastResponse() - line 1114/1128
 *
 * Endpoint: GET /admin/transactions/last
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param clientId Optional client ID to filter by
 * @since 2.0.0
 */
class LastTransactionOperation(
    adminPin: String,
    private val clientId: String? = null
) : BaseTseOperation<TseDto.LastTransactionResultDto>(adminPin) {

    /**
     * Get the last transaction response.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with last transaction details or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.LastTransactionResultDto {
        logger.info("Getting last transaction response${if (clientId != null) " for client: $clientId" else ""}")

        val store = getWormStore(tseStatus)

        val response = try {
            if (clientId != null) {
                store.transaction_lastResponse(clientId)
            } else {
                store.transaction_lastResponse()
            }
        } catch (e: com.secureflashcard.wormapi.WormException) {
            if (e.message?.contains("WORM_ERROR_NO_LAST_TRANSACTION") == true) {
                logger.info("No last transaction found on TSE")
                return TseDto.LastTransactionResultDto(
                    success = true,
                    transactionNumber = 0,
                    signatureCounter = 0,
                    logTime = 0,
                    serialNumberHex = "",
                    signatureBase64 = "",
                    message = "No transactions recorded on TSE"
                )
            }
            throw e
        }

        return response.use { resp ->
            val result = TseDto.LastTransactionResultDto(
                success = true,
                transactionNumber = resp.transactionNumber(),
                signatureCounter = resp.signatureCounter(),
                logTime = resp.logTime(),
                serialNumberHex = resp.serialNumber().joinToString("") { "%02x".format(it) },
                signatureBase64 = Base64.getEncoder().encodeToString(resp.signature()),
                message = "Last transaction retrieved successfully"
            )

            logger.info("Last transaction: #${resp.transactionNumber()}, signature counter: ${resp.signatureCounter()}")

            result
        }
    }
}
