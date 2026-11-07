package org.example.project.tse.operations.signing

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.BaseTseSignOperation

/**
 * TSE transaction update command implementation.
 *
 * Updates a previously started transaction with new process data.
 * Each update creates a new signature, allowing for incremental transaction building.
 *
 * IMPORTANT - Login NOT Required:
 * Transaction operations do NOT require admin login. The TSE only requires:
 * - The TSE is INITIALIZED
 * - The client ID is registered
 * - The CTSS interface is enabled
 * This was verified by TseSignLoginRequirementTest on 2025-01-30.
 *
 * Workflow:
 * 1. Transaction started -> Transaction number received
 * 2. Update transaction (add items, modify data) -> New signature (NO LOGIN NEEDED)
 * 3. Update again (more items) -> Another new signature
 * 4. Finish transaction -> Final signature
 *
 * WormAPI Method: transaction_update() - line 1051
 *
 * Important Notes:
 * - Each update creates a NEW signature (not replacing previous ones)
 * - Requires active CTSS interface and valid TSE time
 * - Same client that started the transaction must perform the update
 * - Can call update multiple times before finishing
 *
 * @param command The sign command identifier (e.g., "signorders", "signbill")
 * @param transactionNumber The transaction number from transaction_start
 * @param value The process data to add/update (JSON or string format)
 *
 * @see TseTransactionStartOperation
 * @see TseTransactionFinishOperation
 * @see BaseTseSignOperation
 */
class TseTransactionUpdateOperation(
    command: String,
    val transactionNumber: Long,
    val value: String,
    val clientId: String
) : BaseTseSignOperation<TseDto.SignResultDto>(command) {

    /**
     * Update a simulated transaction for development/testing.
     *
     * Generates a new simulated signature for the update operation.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being updated
     * @return Result.Success with simulated signature result
     */
    override fun performSimulated(tseStatus: TseStatus, processType: String): TseDto.SignResultDto {
        logger.info("Updating SIMULATION transaction. TxNum: $transactionNumber")
        val result = generateSimulatedSignResult(processType, transactionNumber, "SIM-UPDATE")
        logger.info("SIMULATION transaction updated. TxNum: $transactionNumber")
        return result
    }

    /**
     * Update a real transaction using TSE hardware via WormAPI.
     *
     * WormAPI Flow (NO LOGIN REQUIRED):
     * 1. Validate process data (DSFinV-K format)
     * 2. Update transaction (line 1051: transaction_update)
     * 3. Get signature algorithm and certificate
     *
     * IMPORTANT: Uses singleton WormStore instance (does NOT close it).
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being updated
     * @return Result.Success with signature result or Result.Error
     */
    override fun performReal(tseStatus: TseStatus, processType: String): TseDto.SignResultDto {
        logger.info("Updating real TSE transaction. TxNum: $transactionNumber")

        val store = getWormStore(tseStatus)

        // Validate process data (DSFinV-K format) — throws PosException on failure.
        validateProcessData(value, processType)

        // Update transaction with validated data
        val processData = value.toByteArray(Charsets.UTF_8)
        return store.transaction_update(clientId, transactionNumber, processData, processType).use { resp ->
            val result = buildRealSignResult(
                processType, resp.transactionNumber(), resp.signature(),
                resp.logTime(), resp.serialNumber(), resp.signatureCounter(), store
            )
            logger.info(
                "Transaction updated successfully. TxNum: ${resp.transactionNumber()}, " +
                        "SigCounter: ${resp.signatureCounter()}"
            )
            result
        }
    }
}
