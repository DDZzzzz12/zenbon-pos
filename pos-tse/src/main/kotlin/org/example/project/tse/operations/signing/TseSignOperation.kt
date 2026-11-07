package org.example.project.tse.operations.signing

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.BaseTseSignOperation

/**
 * TSE sign command implementation for one-shot transaction signing.
 *
 * Performs a complete transaction lifecycle (start + finish) in a single call.
 * This is the "quick mode" for signing where no intermediate updates are needed.
 *
 * IMPORTANT - Login NOT Required:
 * Transaction signing does NOT require admin login. The TSE only requires:
 * - The TSE is INITIALIZED
 * - The client ID is registered
 * - The CTSS interface is enabled
 * This was verified by TseSignLoginRequirementTest on 2025-01-30.
 *
 * WormAPI Transaction Flow (WormStore.java):
 * 1. Start transaction (line 1048: transaction_start) - NO LOGIN NEEDED
 * 2. Finish transaction (line 1097: transaction_finish) - NO LOGIN NEEDED
 * 3. Get certificate (line 1224: logMessageCertificate)
 * 4. Return signature with metadata
 *
 * Supported Process Types:
 * - "Bestellung-V1" (Order/Draft bill)
 * - "Kassenbeleg-V1" (Final receipt)
 * - "Kassenbeleg-Storno" (Cancellation)
 * - "AVSonstige" (Other processes)
 *
 * @param command The sign command identifier (e.g., "signorders", "signbill")
 * @param value The process data to sign (JSON or string format)
 *
 * @see BaseTseSignOperation
 * @see TseTransactionStartOperation
 * @see TseTransactionFinishOperation
 */
class TseSignOperation(
    command: String,
    val value: String? = null,
    val clientId: String
) : BaseTseSignOperation<TseDto.SignResultDto>(command) {

    /**
     * Generate a simulated signature for development/testing.
     *
     * Creates a mock signature result without requiring actual TSE hardware.
     * Transaction number is generated from current timestamp.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being signed
     * @return Result.Success with simulated SignResultDto
     */
    override fun performSimulated(tseStatus: TseStatus, processType: String): TseDto.SignResultDto {
        logger.info("Generating SIMULATION signature for process type: $processType")
        val txNumber = System.currentTimeMillis()
        val result = generateSimulatedSignResult(processType, txNumber)
        logger.info("SIMULATION signature generated successfully. TxNum: $txNumber")
        return result
    }

    /**
     * Sign transaction using real TSE hardware via WormAPI.
     *
     * Implements the full TSE signing workflow (NO LOGIN REQUIRED):
     * 1. Start transaction with empty start data
     * 2. Finish transaction with actual process data
     * 3. Retrieve metadata (algorithm, certificate, public key)
     *
     * All binary data (signature, serial number, public key, certificate) is Base64-encoded
     * for JSON serialization.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type to sign (e.g., "Bestellung-V1")
     * @return Result.Success with SignResultDto or Result.Error on failure
     */
    override fun performReal(tseStatus: TseStatus, processType: String): TseDto.SignResultDto {
        logger.info("Signing with WormAPI. Process type: $processType")

        val store = getWormStore(tseStatus)

        // Step 1: Start transaction — Anhang I: „processType und processData für die
        // StartTransaction-Operation immer leer" (audit TSER-10); both are supplied only
        // at FinishTransaction.
        return store.transaction_start(clientId, ByteArray(0), "").use { startResp ->
            val txNumber = startResp.transactionNumber()
            val startLogTime = startResp.logTime()
            logger.debug("Transaction started. TxNum: $txNumber, startLogTime: $startLogTime")

            // Step 2: Finish transaction with actual data
            val processData = value?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            store.transaction_finish(clientId, txNumber, processData, processType).use { finishResp ->
                val result = buildRealSignResult(
                    processType, finishResp.transactionNumber(), finishResp.signature(),
                    finishResp.logTime(), finishResp.serialNumber(), finishResp.signatureCounter(), store,
                    startLogTime = startLogTime,
                )
                logger.info(
                    "Transaction signed successfully. TxNum: ${finishResp.transactionNumber()}, " +
                            "SigCounter: ${finishResp.signatureCounter()}"
                )
                result
            }
        }
    }
}
