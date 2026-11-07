package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.terminal.domain.service.TerminalFiscalGateway
import org.example.project.features.terminal.domain.service.TseClientOpResult
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.features.zBon.domain.KassenStammdatenSnapshot
import org.example.project.infrastructure.errors.PosException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq

/**
 * The fiscal side of terminal commissioning: [TerminalFiscalGateway] over [TseAdminProvider],
 * [TseAdminSession], [KassenStammdatenSnapshot] and the ecr bill/order signing state. Each member
 * is the call `TerminalServiceImpl` made inline before the extraction (ZenBon B2-1a Task 11),
 * moved verbatim; nothing here catches or re-wraps a [PosException] the device raises.
 */
class TseTerminalFiscalGateway(
    private val tseAdminClient: TseAdminProvider,
) : TerminalFiscalGateway {
    override suspend fun isTseInitialized(): Boolean = tseAdminClient.isInitialized()

    override fun isAdminUnlocked(): Boolean = TseAdminSession.isUnlocked()

    override fun currentAdminEpochIfUnlocked(): Long? = TseAdminSession.currentEpochIfUnlocked()

    override suspend fun registerClient(clientId: String): TseClientOpResult {
        val result = tseAdminClient.registerClient(clientId)
        return TseClientOpResult(success = result.success, message = result.message)
    }

    override suspend fun deregisterClient(clientId: String): TseClientOpResult {
        val result = tseAdminClient.deregisterClient(clientId)
        return TseClientOpResult(success = result.success, message = result.message)
    }

    /**
     * Fetches the set of client IDs currently registered in the TSE device.
     *
     * The provider's own `PosException` propagates unchanged — the same rule
     * `TerminalServiceImpl.registerTerminalTse` states: "let it propagate unchanged so the client
     * can prompt for the Admin PIN. Do NOT wrap into 500." This method used to re-wrap every
     * failure as `INTERNAL_ERROR` / 500 and quote the real code into the message text, so a locked
     * Admin sitting reached the operator as a 500 whose body read `code=TSE_ADMIN_LOCKED` — a
     * state the UI can act on, reported as a state it cannot (E2E-2026-09-22 R3-6). The re-wrap
     * also discarded the deliberate `423 Locked` (see `LocalTseAdminProvider.getAdminPin`: not a
     * 401, because the client's Auth plugin would refresh the JWT and silently retry against a
     * locked TSE).
     *
     * The other codes now say what actually happened. `listClients` runs through `pos-tse`'s
     * *admin* `BaseTseOperation`, which since `400af5597` sorts a `WormException` through the same
     * `WormErrorClass` as the signing path: a host or connection fault still resets the
     * connection, while an answer from the card keeps it and arrives as `TSE_NOT_OPERATIONAL`,
     * `TSE_OPERATION_REJECTED` or `TSE_TRANSACTION_NOT_OPEN`. So an uncommissioned device or a
     * refused PIN reaches the caller as its own state rather than as "cannot connect" — which is
     * only useful because this method stopped flattening everything into a 500.
     */
    override suspend fun listRegisteredClientIds(): Set<String> {
        val tseResult = tseAdminClient.listClients()
        return if (tseResult.success) {
            tseResult.clients.toSet()
        } else {
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "TSE listClients returned unsuccessful: ${tseResult.message}",
            )
        }
    }

    override suspend fun startedTransactionCount(clientId: String): Int = tseAdminClient.listTransactions(clientId).count

    /**
     * True if the terminal owns any PENDING-signed bill OR order. Runs inside the caller's
     * dbQuery. Reused by revokeBinding today; a future Phase-C fiscal-demotion path (removing
     * fiscal capability from a terminal's role/fiscal flag) must gate on this too — a terminal
     * mid-signature must not be stripped of its TSE identity while a signature is outstanding.
     */
    override fun hasPendingSigning(terminalId: EntityID<Long>): Boolean {
        val openBills =
            Bill
                .find {
                    (BillTable.terminalId eq terminalId) and (BillTable.tseSigningStatus eq TseSigningStatus.PENDING)
                }.any()
        val openOrders =
            Orders
                .find {
                    (OrdersTable.terminalId eq terminalId) and (OrdersTable.tseSigningStatus eq TseSigningStatus.PENDING)
                }.any()
        return openBills || openOrders
    }

    override fun freezeStammdatenBeforeTerminalRewrite() {
        KassenStammdatenSnapshot.freezeUnsnapshottedClosingsBeforeTerminalRewrite()
    }
}
