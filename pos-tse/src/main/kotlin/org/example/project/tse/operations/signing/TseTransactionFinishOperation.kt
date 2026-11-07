package org.example.project.tse.operations.signing

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.BaseTseSignOperation

/**
 * TSE transaction finish command implementation.
 *
 * Completes a previously started transaction by providing the final process data
 * and receiving the cryptographic signature from the TSE.
 *
 * IMPORTANT - Login NOT Required:
 * Transaction operations do NOT require admin login. The TSE only requires:
 * - The TSE is INITIALIZED
 * - The client ID is registered
 * - The CTSS interface is enabled
 * This was verified by TseSignLoginRequirementTest on 2025-01-30.
 *
 * Workflow:
 * 1. Transaction was started -> Transaction number received
 * 2. Data collected/order completed
 * 3. Finish transaction -> Provide final data and receive signature (NO LOGIN NEEDED)
 *
 * WormAPI Method: transaction_finish() - line 1097
 *
 * @param command The sign command identifier (e.g., "signorders", "signbill")
 * @param transactionNumber The transaction number from transaction_start
 * @param value The final process data to sign (JSON or string format)
 *
 * @see TseTransactionStartOperation
 * @see BaseTseSignOperation
 */
class TseTransactionFinishOperation(
    command: String,
    val transactionNumber: Long,
    val value: String,
    val clientId: String,
) : BaseTseSignOperation<TseDto.SignResultDto>(command) {

    /**
     * Finish a simulated transaction for development/testing.
     *
     * Uses Unix time in seconds to match real TSE behavior.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being finished
     * @return Result.Success with simulated signature result
     */
    override fun performSimulated(tseStatus: TseStatus, processType: String): TseDto.SignResultDto {
        logger.info("Finishing SIMULATION transaction. TxNum: $transactionNumber")
        val result = generateSimulatedSignResult(processType, transactionNumber)
        logger.info("SIMULATION transaction finished. TxNum: $transactionNumber")
        return result
    }

    /**
     * Finish a real transaction using TSE hardware via WormAPI.
     *
     * WormAPI Flow (NO LOGIN REQUIRED):
     * 1. Validate process data (DSFinV-K format)
     * 2. Finish transaction (line 1097: transaction_finish)
     * 3. Get signature algorithm and certificate
     *
     * IMPORTANT: Uses singleton WormStore instance (does NOT close it).
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being finished
     * @return Result.Success with signature result or Result.Error
     */
    override fun performReal(tseStatus: TseStatus, processType: String): TseDto.SignResultDto {
        logger.info("Finishing real TSE transaction. TxNum: $transactionNumber")

        val store = getWormStore(tseStatus)

        // Validate process data (DSFinV-K format) — throws PosException on failure.
        validateProcessData(value, processType)

        // Finish transaction with validated data
        val processData = value.toByteArray(Charsets.UTF_8)
        return store.transaction_finish(clientId, transactionNumber, processData, processType).use { resp ->
            val result = buildRealSignResult(
                processType, resp.transactionNumber(), resp.signature(),
                resp.logTime(), resp.serialNumber(), resp.signatureCounter(), store
            )
            logger.info(
                "Transaction finished successfully. TxNum: ${resp.transactionNumber()}, " +
                        "SigCounter: ${resp.signatureCounter()}"
            )
            result
        }
    }
}
