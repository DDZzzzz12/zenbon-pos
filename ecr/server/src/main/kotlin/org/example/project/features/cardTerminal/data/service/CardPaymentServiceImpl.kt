/**
 * Implementation of CardPaymentService for card payment authorization.
 *
 * Handles authorizing card payments through a ZVT-compatible terminal,
 * managing terminal busy state, storing transaction records, and
 * providing real-time intermediate status streaming.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 9.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.example.project.core.domain.dto.cardTerminal.CardTransactionDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.cardTerminal.domain.models.CardTransaction
import org.example.project.features.cardTerminal.domain.repository.CardTerminalRepository
import org.example.project.features.cardTerminal.domain.repository.CardTransactionRepository
import org.example.project.features.cardTerminal.domain.service.CardPaymentService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory

/**
 * Implementation of [CardPaymentService].
 *
 * Delegates ZVT network calls to the ZvtClient obtained from [CardTerminalServiceImpl].
 * Stores approved transactions in the database via [CardTransactionRepository].
 *
 * @property terminalService Provides access to active ZvtClient and ZvtMessageHandler instances
 * @property terminalRepository For reading and updating terminal busy state
 * @property transactionRepository For persisting card transaction records
 */
class CardPaymentServiceImpl(
    private val terminalService: CardTerminalServiceImpl,
    private val terminalRepository: CardTerminalRepository,
    private val transactionRepository: CardTransactionRepository,
) : CardPaymentService {
    private val logger = LoggerFactory.getLogger(CardPaymentServiceImpl::class.java)

    override suspend fun authorizePayment(
        terminalId: Long,
        amountCents: Long,
        currency: String,
    ): CardTransactionDto.CardTransactionResponseDto {
        val client = terminalService.getClient(terminalId) ?: throw cardTerminalDisconnected(terminalId)

        // Check terminal not busy
        val isBusy = dbQuery { terminalRepository.getById(terminalId)?.isBusy ?: false }
        if (isBusy) throw cardTerminalBusy(terminalId)

        try {
            dbQuery { terminalRepository.updateBusyState(terminalId, true) }

            val r =
                try {
                    client.authorize(amountCents, currencyCode(currency))
                } catch (e: PosException) {
                    throw cardTerminalUnreachable(terminalId, e.message ?: "protocol error", cause = e)
                } catch (e: java.io.IOException) {
                    throw cardTerminalUnreachable(terminalId, e.message ?: "I/O", cause = e)
                } catch (e: Exception) {
                    logger.error("Authorize failed for terminal $terminalId: ${e.message}", e)
                    throw cardTerminalUnreachable(terminalId, e.message ?: "unknown", cause = e)
                }

            if (!r.approved) {
                logger.info("Payment declined for terminal $terminalId: ${r.errorDescription}")
                throw cardTransactionDeclined(r.errorDescription ?: "declined")
            }

            return dbQuery {
                val transaction =
                    transactionRepository.create(
                        cardTerminalId = terminalId,
                        traceNumber = r.traceNumber?.toString() ?: "0",
                        receiptNumber = r.receiptNumber?.toString(),
                        amountCents = r.amountCents,
                        currency = currency.uppercase(),
                        cardType = r.cardType,
                        cardTypeName = r.cardTypeName,
                        panLast4 = r.pan?.takeLast(4),
                        expiryDate = r.expiryDate,
                        aid = r.aid,
                        paymentType = r.paymentTypeName ?: "CARD",
                        resultCode = r.resultCode,
                        receiptTextMerchant = r.receiptText.joinToString("\n").takeIf { it.isNotBlank() },
                        receiptTextCustomer = null,
                        transactionTime = currentTimeStamp(),
                    )
                transaction.toResponse()
            }
        } finally {
            try {
                dbQuery { terminalRepository.updateBusyState(terminalId, false) }
            } catch (e: Exception) {
                logger.warn("Failed to clear busy state for terminal $terminalId: ${e.message}")
            }
        }
    }

    override suspend fun linkTransactionToPayment(
        cardTransactionId: Long,
        paymentId: Long,
    ) {
        try {
            dbQuery { transactionRepository.linkToPayment(cardTransactionId, paymentId) }
        } catch (e: NoSuchElementException) {
            throw cardTransactionNotFound(cardTransactionId)
        }
    }

    override fun getIntermediateStatusFlow(terminalId: Long): Flow<Pair<Int, String>> {
        val handler = terminalService.getMessageHandler(terminalId) ?: return emptyFlow()
        return handler.intermediateStatusFlow.map { status -> Pair(status.code, status.description) }
    }

    override suspend fun abortPayment(terminalId: Long) {
        val client = terminalService.getClient(terminalId) ?: throw cardTerminalDisconnected(terminalId)
        try {
            client.abort()
        } catch (e: PosException) {
            throw cardTransactionAborted(e.message ?: "abort failed")
        } catch (e: Exception) {
            logger.error("Failed to abort payment on terminal $terminalId: ${e.message}", e)
            throw cardTerminalUnreachable(terminalId, e.message ?: "I/O", cause = e)
        }
    }

    // ===== Helpers =====

    private fun currencyCode(currency: String): Int =
        when (currency.uppercase()) {
            "EUR" -> 978
            "USD" -> 840
            "GBP" -> 826
            "CHF" -> 756
            else -> 978
        }

    // ===== Mapping =====

    private fun CardTransaction.toResponse(): CardTransactionDto.CardTransactionResponseDto =
        CardTransactionDto.CardTransactionResponseDto(
            id = id.value,
            paymentId = payment?.id?.value,
            cardTerminalId = cardTerminal.id.value,
            traceNumber = traceNumber,
            receiptNumber = receiptNumber,
            amountCents = amountCents,
            currency = currency,
            cardType = cardType,
            cardTypeName = cardTypeName,
            panLast4 = panLast4,
            expiryDate = expiryDate,
            aid = aid,
            paymentType = paymentType,
            resultCode = resultCode,
            receiptTextMerchant = receiptTextMerchant,
            receiptTextCustomer = receiptTextCustomer,
            transactionTime = transactionTime.toString(),
            createdAt = createdAt.toString(),
        )

    // ===== PosException helpers =====

    private fun cardTerminalDisconnected(id: Long) =
        PosException(
            PosErrorCode.CARD_TERMINAL_DISCONNECTED,
            HttpStatusCode.ServiceUnavailable,
            "Card terminal $id disconnected",
        )

    private fun cardTerminalBusy(id: Long) =
        PosException(
            PosErrorCode.CARD_TERMINAL_BUSY,
            HttpStatusCode.Conflict,
            "Card terminal $id is busy",
        )

    private fun cardTerminalUnreachable(
        id: Long,
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.CARD_TERMINAL_UNREACHABLE,
        HttpStatusCode.ServiceUnavailable,
        "Card terminal $id unreachable: $msg",
        cause = cause,
    )

    private fun cardTransactionDeclined(reason: String) =
        PosException(
            PosErrorCode.CARD_TRANSACTION_DECLINED,
            HttpStatusCode.PaymentRequired,
            "Card transaction declined: $reason",
        )

    private fun cardTransactionAborted(reason: String) =
        PosException(
            PosErrorCode.CARD_TRANSACTION_ABORTED,
            HttpStatusCode.Conflict,
            "Card transaction aborted: $reason",
        )

    private fun cardTransactionNotFound(id: Long) =
        PosException(
            PosErrorCode.CARD_TRANSACTION_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Card transaction $id not found",
        )
}
