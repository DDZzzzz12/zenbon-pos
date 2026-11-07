package org.example.project.features.scheduler.data.service.executors

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.audit.domain.service.TransactionAuditService
import org.example.project.features.matching.data.service.ReversalProcessor
import org.example.project.features.matching.data.service.VoidedAllocation
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Sweeps returned direct debits once a night, so an invoice the bank un-paid stops reading as paid.
 *
 * The count reported is **allocations voided**, not reversals looked at: that is the number an
 * operator would want explained the morning after, because each one is an invoice that went back to
 * open. A night with nothing to undo reports zero, which is the ordinary case.
 *
 * The whole sweep runs in one transaction — the voids, the residuals they restore and the markers
 * that stop them repeating have to commit together, or a crash halfway would leave reversals stamped
 * with their allocations still standing.
 *
 * Every voided allocation then gets a `VOID_RETURNED_DEBIT_ALLOCATION` audit row, written after that
 * transaction commits because the audit writer opens its own. Before this the only trace of a
 * Rücklastschrift re-opening an invoice was the run's one-line result: an allocation an operator had
 * made simply vanished. A failed audit write is logged and does not fail the job — the voids have
 * already committed, and reporting the run as failed would invite a re-run that finds nothing to do.
 */
class ReversalJobExecutor(
    private val processor: ReversalProcessor,
    private val auditService: TransactionAuditService,
) : ScheduledJobExecutor {
    private val logger = LoggerFactory.getLogger(ReversalJobExecutor::class.java)

    override val type = ScheduledJobType.REVERSAL_SWEEP
    override val historyRetention = 30
    override val labelKey = "SCHEDULER_JOB_TYPE_REVERSAL_SWEEP"

    override suspend fun execute(parametersJson: String): String {
        val voided = dbQuery { processor.sweepAndReport() }
        voided.forEach { record(it) }
        return "voided ${voided.size} allocations of returned debits"
    }

    override fun validateParameters(parametersJson: String) { /* no params */ }

    override fun parameterSchema(): JsonElement = JsonObject(emptyMap())

    private suspend fun record(voided: VoidedAllocation) {
        try {
            auditService.logTransaction(
                employeeId = SYSTEM_EMPLOYEE_ID,
                employeeNumber = SYSTEM_EMPLOYEE_ID,
                authorizationType = AUTOMATIC_AUTHORIZATION,
                authorizingManagerId = SYSTEM_EMPLOYEE_ID,
                operation = TransactionAuditDto.TransactionOperation.VOID_RETURNED_DEBIT_ALLOCATION.name,
                entityType = "payment_allocation",
                entityId = voided.allocationId.toString(),
                entityName = listOfNotNull(voided.targetKind, voided.targetId?.toString()).joinToString(" "),
                beforeState =
                    mapOf(
                        "bankTransactionId" to voided.reversedTransactionId.toString(),
                        "targetKind" to voided.targetKind,
                        "targetId" to voided.targetId?.toString().orEmpty(),
                        "amount" to voided.amount.toPlainString(),
                    ),
                reason =
                    "Returned direct debit (Ruecklastschrift): bank transaction ${voided.reversalTransactionId} " +
                        "reverses ${voided.reversedTransactionId}",
            )
        } catch (e: Exception) {
            logger.error("Reversal sweep voided allocation {} but its audit row could not be written", voided.allocationId, e)
        }
    }

    private companion object {
        /** How the other unattended writers (TSE queue worker, partner merge) attribute a system action. */
        const val SYSTEM_EMPLOYEE_ID = "SYSTEM"
        const val AUTOMATIC_AUTHORIZATION = "AUTOMATIC"
    }
}
