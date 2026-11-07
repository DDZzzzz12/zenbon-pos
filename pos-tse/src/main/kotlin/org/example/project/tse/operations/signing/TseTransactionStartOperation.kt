package org.example.project.tse.operations.signing

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.signing.BaseTseSignOperation
import java.util.Base64

/**
 * TSE transaction start command implementation.
 *
 * Starts a new transaction on the TSE hardware without immediately finishing it.
 * This allows for long-running transactions where data is collected over time
 * before being finalized.
 *
 * IMPORTANT - Login NOT Required:
 * Transaction operations do NOT require admin login. The TSE only requires:
 * - The TSE is INITIALIZED
 * - The client ID is registered
 * - The CTSS interface is enabled
 * This was verified by TseSignLoginRequirementTest on 2025-01-30.
 *
 * Workflow:
 * 1. Start transaction -> Receive transaction number (NO LOGIN NEEDED)
 * 2. (Optional) Collect data, add items, etc.
 * 3. Finish transaction -> Provide final data and receive signature
 *
 * WormAPI Method: transaction_start() - line 1048
 *
 * @param command The sign command identifier (e.g., "signorders", "signbill")
 *
 * @see TseTransactionFinishOperation
 * @see BaseTseSignOperation
 */
class TseTransactionStartOperation(
    command: String,
    val clientId: String
) : BaseTseSignOperation<TseDto.TransactionStartResultDto>(command) {

    /**
     * Start a simulated transaction for development/testing.
     *
     * Uses Unix time in seconds to match real TSE behavior.
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type being started
     * @return Result.Success with simulated transaction start result
     */
    override fun performSimulated(
        tseStatus: TseStatus,
        processType: String
    ): TseDto.TransactionStartResultDto {
        logger.info("Starting SIMULATION transaction for process type: $processType")

        val timestampSeconds = System.currentTimeMillis() / 1000
        val result = TseDto.TransactionStartResultDto(
            success = true,
            processType = processType,
            transactionNumber = timestampSeconds,
            timestamp = timestampSeconds,
            // The operation's own client, not TseStatus's. It is non-null by construction,
            // and it is the register this transaction is actually for — simulation should
            // report what the real path would sign for, not a global fallback.
            clientId = clientId,
            serialNumber = "SIM-TSE-001"
        )

        logger.info("SIMULATION transaction started. TxNum: ${result.transactionNumber}")
        return result
    }

    /**
     * Start a real transaction using TSE hardware via WormAPI.
     *
     * WormAPI Flow (NO LOGIN REQUIRED):
     * 1. Start transaction (line 1048: transaction_start)
     * 2. Return transaction number for later finish
     *
     * IMPORTANT: Uses singleton WormStore instance (does NOT close it).
     *
     * @param tseStatus Current TSE status and configuration
     * @param processType The process type to start
     * @return Result.Success with transaction start result or Result.Error
     */
    override fun performReal(
        tseStatus: TseStatus,
        processType: String
    ): TseDto.TransactionStartResultDto {
        logger.info("Starting real TSE transaction. Process type: $processType")

        val store = getWormStore(tseStatus)

        // Anhang I: „processType und processData für die StartTransaction-Operation immer
        // leer" (audit TSER-10) — the type is supplied only at Finish/Update.
        return store.transaction_start(clientId, ByteArray(0), "").use { response ->
            val txNumber = response.transactionNumber()
            val result = TseDto.TransactionStartResultDto(
                success = true,
                processType = processType,
                transactionNumber = txNumber,
                timestamp = response.logTime(),
                clientId = clientId,
                serialNumber = Base64.getEncoder().encodeToString(response.serialNumber())
            )

            logger.info("Transaction started successfully. TxNum: $txNumber, Process type: $processType")
            result
        }
    }
}
