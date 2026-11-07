package org.example.project.tse.operations.admin.transactions

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus

/**
 * List all currently started (unfinished) transactions.
 *
 * Retrieves the list of transaction numbers for transactions that have been
 * started but not yet finished. This is useful for:
 * - Monitoring open transactions
 * - Identifying stuck transactions
 * - Cleanup and recovery operations
 *
 * Filtering:
 * - If clientId is provided, only transactions for that client are returned
 * - If clientId is null, all started transactions across all clients are returned
 *
 * WormAPI Method: transaction_listStartedTransactions() - line 1144/1153
 *
 * Endpoint: GET /admin/transactions
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param clientId Optional client ID to filter transactions
 * @since 2.0.0
 */
class ListTransactionsOperation(
    adminPin: String,
    private val clientId: String? = null
) : BaseTseOperation<TseDto.TransactionListResultDto>(adminPin) {

    /**
     * List started (unfinished) transactions.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with list of transaction numbers or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.TransactionListResultDto {
        logger.info("Listing started transactions${if (clientId != null) " for client: $clientId" else ""}")

        val store = getWormStore(tseStatus)

        val transactions = if (clientId != null) {
            store.transaction_listStartedTransactions(clientId)
        } else {
            store.transaction_listStartedTransactions()
        }

        logger.info("Found ${transactions.size} started transactions")
        transactions.forEachIndexed { index, txNum ->
            logger.debug("  [$index] Transaction #$txNum")
        }

        return TseDto.TransactionListResultDto(
            success = true,
            transactionNumbers = transactions.toList(),
            count = transactions.size,
            clientId = clientId,
            message = "Found ${transactions.size} started transactions"
        )
    }
}
