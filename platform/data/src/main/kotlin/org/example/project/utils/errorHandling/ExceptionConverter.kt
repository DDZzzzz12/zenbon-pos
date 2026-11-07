/**
 * Exception conversion utilities — audit-log side effects only.
 *
 * Stage C Phase 3: this object used to convert Throwable → DataError.
 * After all services migrated to throws-style PosException, the typed
 * DataError return became unused. The remaining logic is the audit /
 * entry-log side effect, retained as a wrapper around suspend blocks
 * via [catchingThrows].
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.errorHandling

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.auth.data.ApprovalAuthorization
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.log.AuditEntityRef
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogHelper
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.util.UUID

object ExceptionConverter {
    private val logger: Logger = LoggerFactory.getLogger(ExceptionConverter::class.java)

    /** Mirrors `TRACE_ID_MDC_KEY` in platform-server (TraceIdMdcKey.kt); platform-data must not depend on Ktor. */
    private const val TRACE_ID_MDC_KEY = "traceId"

    /**
     * The `entityId` of a failed audit row whose call site cannot name the entity it acted on.
     * Mirrored in shared-ui's `AuditRowHeadline`, which cannot depend on platform-data.
     */
    const val FAILED_ENTITY_ID = "FAILED"

    /**
     * The `entityName` of EVERY failed audit row — the failure marker that holds even when the row
     * names its entity (see `catchingThrows(failedEntityId = ...)`). Mirrored in `AuditRowHeadline`.
     */
    const val FAILED_ENTITY_NAME = "Operation failed due to error"

    /** Append one CRUD change-feed row in the CURRENT transaction. Must be called inside dbQuery. */
    fun recordCrudLog(
        type: ChangeEntityType,
        operation: LogOperation,
        entityId: String,
        entityName: String,
        message: String? = null,
        employeeId: String? = null,
    ) = EntryLogHelper.logOperation(type, operation, entityId, entityName, message, employeeId)

    /**
     * Logs the exception (audit + slf4j) and returns. Used as a side-effect
     * helper inside [catchingThrows]; the original exception is rethrown by
     * the caller.
     */
    private fun logException(
        throwable: Throwable,
        context: String? = null,
        employeeId: String? = null,
        operation: String? = null,
        entityType: String? = null,
        deviceId: String? = null,
        traceId: String? = null,
        approval: ApprovalAuthorization? = null,
        failedEntityId: String? = null,
    ) {
        logger.debug("Exception in operation=$operation: ${throwable::class.simpleName}", throwable)
        if (shouldAuditError(operation)) {
            logErrorToAudit(
                throwable = throwable,
                context = context,
                employeeId = employeeId,
                operation = operation,
                entityType = entityType,
                deviceId = deviceId,
                traceId = traceId,
                approval = approval,
                failedEntityId = failedEntityId,
            )
        }
        when (throwable) {
            // Same policy as StatusPages.logOutcome (E2E-2026-09-15 N12): a 4xx is an expected,
            // client-correctable outcome. Logging it at ERROR with a stack trace put ordinary
            // business-rule refusals (occupied table, reused pairing code) into error.log
            // (E2E-2026-09-18 #2). INFO keeps the trace id and code in the main log without a trace.
            is PosException ->
                if (throwable.httpStatus.value < 500) {
                    logger.info(
                        "Rejected{}: {} {} — {}",
                        contextSuffix(context),
                        throwable.code.name,
                        throwable.httpStatus.value,
                        throwable.message,
                    )
                } else {
                    logger.error("Exception${contextSuffix(context)}", throwable)
                }
            is IllegalArgumentException -> logger.warn("Illegal argument${contextSuffix(context)}: ${throwable.message}", throwable)
            is IllegalStateException -> logger.warn("Illegal state${contextSuffix(context)}: ${throwable.message}", throwable)
            else -> logger.error("Exception${contextSuffix(context)}", throwable)
        }
    }

    /**
     * Throwing variant — wraps a suspend block with the audit-log + entry-log
     * + undoBlock side effects but lets exceptions propagate. Successful
     * results are still recorded for audit/entry logging.
     *
     * @param auditEntity Names the entity this operation acted on, for the audit row. Its return is
     *   **nullable**, and that is how a call site says "I cannot name this": returning null degrades
     *   the row to `UNKNOWN` quietly, because [resolveAuditEntity] warns only when the lambda
     *   THROWS. Use null on a normal success path whose result carries no identifier —
     *   `transferTable`'s item-transfer branch, where the items moved into the target's session and
     *   the response never carries its id. Keep throwing for genuine failure: that is logged at WARN
     *   with the stack trace, and a routine outcome routed through it makes the warning meaningless.
     * @param failedEntityId The id of the entity the operation acts on, when it is known BEFORE the
     *   block runs (a cancel names the invoice it cancels). A failed row then records it as its
     *   `entityId` instead of the [FAILED_ENTITY_ID] placeholder, so a refused attempt shows up in
     *   that entity's audit trail. The row still reads as a failure through [FAILED_ENTITY_NAME] and
     *   its `ERROR:` reason. Leave it null where no id exists before the operation (a create).
     * @param auditState Renders the success row's `after_state` as JSON. Without it the column holds
     *   the result's `toString()`, which for an internal carrier class is a Kotlin dump, not JSON. A
     *   throwing renderer degrades to that fallback rather than losing the row.
     */
    suspend fun <T> catchingThrows(
        context: String? = null,
        employeeId: String? = null,
        operation: String? = null,
        entityType: String? = null,
        deviceId: String? = null,
        entryLogConfig: EntryLogConfig<T>? = null,
        auditEntity: ((T) -> AuditEntityRef?)? = null,
        undoBlock: (suspend () -> Unit)? = null,
        failedEntityId: String? = null,
        auditState: ((T) -> String)? = null,
        block: suspend () -> T,
    ): T {
        // Captured HERE, on the request thread. MDC is thread-local and the audit write re-enters
        // through `runBlocking { dbQuery { … } }`, which may dispatch elsewhere and lose it.
        val traceId = MDC.get(TRACE_ID_MDC_KEY)
        // Captured here for the same reason: the audit write's own runBlocking does not inherit
        // this coroutine's context, and a remote approval is visible nowhere else.
        val approval = currentCoroutineContext()[ApprovalAuthorization]
        try {
            // When an entry-log config is present, run the mutation AND the
            // change-feed write inside one ambient transaction so the entry_log
            // row is atomic with the mutation. Synced services wrap their body in
            // `dbQuery { ... }`; that inner dbQuery joins this ambient transaction
            // (Exposed reuses the transaction already on the coroutine context),
            // so a throw inside the block rolls back the log too.
            val value =
                if (entryLogConfig != null) {
                    dbQuery {
                        val result = block()
                        logEntryOperation(result, employeeId, entryLogConfig)
                        result
                    }
                } else {
                    block()
                }
            if (shouldAuditOperation(operation)) {
                // Resolved outside logSuccessToAudit so a throwing lambda degrades this row to
                // UNKNOWN rather than losing the audit row to that method's catch-all.
                val resolved = resolveAuditEntity(value, auditEntity, entryLogConfig, operation)
                // Audit log intentionally writes in its OWN transaction (outside
                // the ambient one) so it survives even if a later step rolls back.
                logSuccessToAudit(
                    value = value,
                    context = context,
                    employeeId = employeeId,
                    operation = operation,
                    entityType = entityType,
                    deviceId = deviceId,
                    entity = resolved,
                    traceId = traceId,
                    approval = approval,
                    stateJson = renderAuditState(value, auditState, operation),
                )
            }
            return value
        } catch (e: Exception) {
            undoBlock?.invoke()
            // Run side-effect logging but rethrow the original exception
            logException(
                throwable = e,
                context = context,
                employeeId = employeeId,
                operation = operation,
                entityType = entityType,
                deviceId = deviceId,
                traceId = traceId,
                approval = approval,
                failedEntityId = failedEntityId,
            )
            if (entryLogConfig != null) {
                logEntryOperationError(e, employeeId, entryLogConfig, context)
            }
            throw e
        }
    }

    /**
     * Creates context suffix for logging.
     *
     * @param context Optional context string
     * @return Formatted context suffix or empty string
     */
    private fun contextSuffix(context: String?): String = if (context != null) " in $context" else ""

    /**
     * Determines if an operation should be audited.
     *
     * Audits critical financial operations and sequence-consuming operations
     * for complete transaction trail (both success and failure).
     *
     * @param operation Operation name
     * @return true if operation should be audited
     */
    private fun shouldAuditOperation(operation: String?): Boolean {
        if (operation == null) return false

        val criticalOperations =
            setOf(
                "CREATE_BILL",
                "DELETE_BILL",
                // Problem reports leave the building: who published what to GitHub, and the issue number.
                "CREATE_ISSUE_REPORT",
                // Zahlartwechsel: Storno + Neubuchung in one step; one row names both new bills.
                "REBOOK_BILL",
                "CREATE_ORDER",
                "DELETE_ORDER",
                "CANCEL_ORDER_ITEMS",
                "TRANSFER_ORDER_ITEMS",
                "TRANSFER_TABLE",
                "DELETE_TABLE_SESSION",
                "CREATE_VOUCHER",
                "REDEEM_VOUCHER",
                "CREATE_PAYMENT",
                "REFUND_PAYMENT",
                "CREATE_REPORT",
                "CREATE_CASH_BOOK_ENTRY",
                "CORRECT_CASH_BOOK_ENTRY",
                "REPLACE_CASH_BOOK_DOCUMENT",
                "LINK_CASH_BOOK_DOCUMENT",
                "UNLINK_CASH_BOOK_DOCUMENT",
                // admin20 NOTE-C: a Geldtransit moves cash between two books (two signed legs) and
                // left no row. The row names the pair's shared transfer id.
                "CREATE_CASH_TRANSFER",
                // Which till signs the closing's cash movements; decides whose TSE client carries them.
                "SET_CASH_BOOK_SIGNING_TERMINAL",
                "REVERSE_CASH_TRANSFER",
                // F51: cash handed from the drawer to an employee as their tip, after the closing —
                // a signed Kassenbuch withdrawal. The row names the withdrawal's header and Beleg.
                "PAY_OUT_EMPLOYEE_TIP",
                "REVERSE_EMPLOYEE_TIP_PAYOUT",
                // F59 option C: a tip settled by bank transfer / payroll. No cash moves and no
                // Kassenbuch entry exists, so this row is the only trace outside the payout table.
                "SETTLE_EMPLOYEE_TIP_BANK_PAYROLL",
                "REVERSE_EMPLOYEE_TIP_SETTLEMENT",
                // T9-12: cancelling an issued outgoing invoice (§ 14 Gutschrift) and undoing a
                // Zuordnung. The allocation row is hard-deleted, so this is its only trace.
                "CANCEL_SALES_INVOICE",
                "REVERSE_ALLOCATION",
                // T9-19: a Beleg's incoming invoice is never deleted, it is cancelled by a
                // correction (Gegenbuchung). The row names who did it, when, and why.
                "CANCEL_INCOMING_INVOICE",
                // emu20 NOTE-p: staff changes decide who may do what at the till (role, permission
                // group, deactivation). Audited on success and on refusal (a 403 for conferring
                // capabilities the actor lacks writes a FAILED row naming the target employee).
                "CREATE_EMPLOYEE",
                "UPDATE_EMPLOYEE",
                "ASSIGN_PERMISSION_GROUP",
                "DEACTIVATE_EMPLOYEE",
                // F68: a retired terminal's client ID removed from the TSE. Permanent for that client
                // until it is registered again, and invisible in the TSE's own log as a receipt event.
                "DEREGISTER_TSE_CLIENT",
                // QA rt14: taking a terminal out of service and back decides which till may sign;
                // neither left a row. The row names the terminal and its client id.
                "DEACTIVATE_TERMINAL",
                "REACTIVATE_TERMINAL",
                // Browser stations: this setting decides whether a browser tab may act as a till
                // (pair, sign in, sell). The row names the shop; the state holds the new roles.
                "UPDATE_BROWSER_STATIONS",
                // Web access: the API-documentation switch and the default page for /. The row names
                // the shop; the state holds both values.
                "UPDATE_WEB_ACCESS",
                // QA rt14: the TSE archive register decides which archives a DSFinV-K package ships for
                // a period erased from the TSE. The row names the register entry, or the file on refusal.
                "REGISTER_TSE_ARCHIVE",
                "CLOCK_IN",
                "CLOCK_OUT",
                "START_BREAK",
                "END_BREAK",
                "CREATE_MANUAL_TIME_ENTRY",
                "DELETE_TIME_ENTRY",
            )

        return operation.uppercase() in criticalOperations
    }

    /**
     * Determines if an error should be audited.
     *
     * Delegates to shouldAuditOperation for consistency.
     *
     * @param operation Operation name
     * @return true if error should be audited
     */
    private fun shouldAuditError(operation: String?): Boolean = shouldAuditOperation(operation)

    /**
     * Stamps the three authorization columns on an audit row.
     *
     * Without this the row always said `DIRECT` with a NULL manager, which per the column's own
     * documentation asserts that the actor held the authority themselves and no manager was
     * involved — the opposite of what happened when a manager lent a cashier `BILL_VOID` and the
     * cashier then voided the bill.
     *
     * **The actor is deliberately unchanged.** A step-up is a union, not a substitution: the cashier
     * stays the actor (see `TokenClaimsElevation`, which explains why a Kassennachschau must see that
     * a cashier voided the bill with a manager beside them). This only adds the second name.
     *
     * **What the manager columns mean here, exactly.** [ExceptionConverter] does not know which
     * [org.example.project.core.domain.enums.auth.Capability] the operation required, so what is
     * recorded is *"an elevation approved by this manager was active for this actor on this device
     * at this moment"* — NOT *"this elevation authorised this specific action"*. The cashier may
     * have held the authority for this particular action all along and never needed the step-up.
     * Do not read the column as proof that the manager approved this operation.
     *
     * **A remote approval is different, and outranks an elevation.** [ApprovalAuthorization] is
     * installed by `DeferredActionExecutor` around the one action a manager approved, so for
     * `APPROVAL_REQUEST` the manager columns DO mean *"this manager approved this operation"*.
     * Before it was read here, a manager-approved Storno was written as the cashier's own `DIRECT`
     * authority (E2E-2026-09-20 round 2, R2-1).
     *
     * @param elevation The step-up in force, from [TemporarySessionStore.activeElevationFor], or null
     * @param approval The remote approval this action runs under, or null
     * @param approverName The approving manager's name, resolved by [approverNameFor] before the row
     *   is created
     */
    private fun TransactionAuditLog.recordAuthorization(
        elevation: TemporarySessionStore.SessionInfo?,
        approval: ApprovalAuthorization?,
        approverName: String?,
    ) {
        if (approval != null) {
            this.authorizationType = "APPROVAL_REQUEST"
            this.authorizingManagerId = approval.managerId
            this.authorizingManagerName = approverName
            return
        }
        this.authorizationType = if (elevation == null) "DIRECT" else "TEMPORARY_SESSION"
        this.authorizingManagerId = elevation?.managerId
        this.authorizingManagerName = elevation?.managerName
    }

    /**
     * The approving manager's name, as a temporary session would record it ([Employee.fullName]).
     * The approval route claims a request with the manager's id only, so the name is usually
     * resolved here. Must run inside a transaction, and before `TransactionAuditLog.new`.
     */
    private fun approverNameFor(approval: ApprovalAuthorization?): String? {
        approval ?: return null
        approval.managerName?.let { return it }
        val id = runCatching { UUID.fromString(approval.managerId) }.getOrNull() ?: return null
        return Employee.findById(id)?.fullName
    }

    /**
     * Logs error to transaction audit log.
     *
     * Creates an audit entry for failed operations to maintain complete
     * transaction history and explain sequence gaps in auto-increment fields.
     *
     * **Wrapped in dbQuery** to ensure transactional consistency.
     *
     * @param throwable Exception that occurred
     * @param context Operation context
     * @param employeeId Employee performing the operation
     * @param operation Operation name
     * @param entityType Entity type affected
     * @param traceId The request's `X-Request-ID`, captured on the request thread by
     *   [catchingThrows]; null for work with no request behind it. This is the row an operator
     *   actually traces — the failure they were shown carries the same id.
     */
    private fun logErrorToAudit(
        throwable: Throwable,
        context: String?,
        employeeId: String?,
        operation: String?,
        entityType: String?,
        deviceId: String? = null,
        traceId: String? = null,
        approval: ApprovalAuthorization? = null,
        failedEntityId: String? = null,
    ) {
        try {
            // Run in separate transaction to ensure audit log is saved
            // even if parent transaction rolls back
            runBlocking {
                dbQuery {
                    val employee =
                        employeeId?.let {
                            try {
                                Employee.findById(UUID.fromString(it))
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                    val terminal = deviceId?.let { Terminal.find { TerminalTable.deviceId eq it } }?.firstOrNull()
                    // A FAILED action under an elevation is still something the manager's approval
                    // was standing behind — an auditor asking "what did this manager authorise"
                    // must see the attempts, not only the successes.
                    val elevation =
                        TemporarySessionStore.activeElevationFor(employeeId = employeeId, deviceId = deviceId)
                            ?: TemporarySessionStore.stationOverrideActedBy(employeeId = employeeId, deviceId = deviceId)
                    val approverName = approverNameFor(approval)

                    TransactionAuditLog.new {
                        this.transactionId = UUID.randomUUID().toString()
                        this.employeeId = employee?.id?.value.toString()
                        this.employeeNumber = employee?.employeeNumber ?: "UNKNOWN"
                        this.recordAuthorization(elevation, approval, approverName)
                        this.operation = operation ?: "UNKNOWN"
                        this.entityType = entityType ?: "UNKNOWN"
                        this.entityId = failedEntityId ?: FAILED_ENTITY_ID
                        this.entityName = FAILED_ENTITY_NAME

                        // Store error details
                        this.beforeState = null
                        this.afterState = null
                        this.changesSummary = buildErrorSummary(throwable, context)

                        // Context information
                        this.deviceId = terminal?.clientId ?: deviceId
                        this.ipAddress = null
                        this.reason = "ERROR: ${throwable::class.simpleName}: ${throwable.message}"

                        // No financial impact for failed operations
                        this.amountImpact = null
                        this.currencyCode = "EUR"

                        // Store full exception details in metadata
                        this.metadata = buildErrorMetadata(throwable, context)
                        this.timestamp = currentTimeStamp().toString()
                        this.fiscalRecordId = null
                        this.traceId = traceId
                        this.createdAt = currentTimeStamp().toString()
                    }
                }
            }
            logger.info("Error audit log created for failed operation: $operation")
        } catch (auditError: Exception) {
            // Don't let audit logging failures break the application
            logger.error("Failed to create error audit log", auditError)
        }
    }

    /**
     * Builds human-readable error summary.
     *
     * @param throwable Exception
     * @param context Operation context
     * @return Error summary string
     */
    private fun buildErrorSummary(
        throwable: Throwable,
        context: String?,
    ): String =
        buildString {
            append("FAILED: ${throwable::class.simpleName}")
            if (context != null) {
                append(" in $context")
            }
            append("\nMessage: ${throwable.message}")
            append("\nThis explains a potential gap in auto-increment sequences (bill_sequence, order_sequence, etc.)")
        }

    /**
     * Builds JSON metadata for error details.
     *
     * @param throwable Exception
     * @param context Operation context
     * @return JSON string with error metadata
     */
    private fun buildErrorMetadata(
        throwable: Throwable,
        context: String?,
    ): String =
        """
        {
          "error_class": "${throwable::class.qualifiedName}",
          "error_message": "${throwable.message?.replace("\"", "\\\"")}",
          "context": "${context ?: "null"}",
          "stack_trace_summary": "${throwable.stackTraceToString().take(500).replace("\"", "\\\"").replace("\n", "\\n")}",
          "note": "This audit entry explains why a sequence value was consumed but no entity was created"
        }
        """.trimIndent()

    /**
     * Logs successful operation to transaction audit log.
     *
     * Creates an audit entry for successful critical operations to maintain
     * complete transaction history. Useful for compliance, debugging, and
     * understanding the full lifecycle of financial operations.
     *
     * **Wrapped in dbQuery** to ensure transactional consistency.
     *
     * @param result Success result containing the operation data
     * @param context Operation context
     * @param employeeId Employee performing the operation
     * @param operation Operation name
     * @param entityType Entity type affected
     * @param entity The entity named by the call site, from [resolveAuditEntity]; null writes
     *   `UNKNOWN`, which is honestly missing rather than a reflected guess
     * @param traceId The request's `X-Request-ID`, captured on the request thread by
     *   [catchingThrows]; null for work with no request behind it
     */
    private fun <T> logSuccessToAudit(
        value: T,
        context: String?,
        employeeId: String?,
        operation: String?,
        entityType: String?,
        deviceId: String? = null,
        entity: AuditEntityRef? = null,
        traceId: String? = null,
        approval: ApprovalAuthorization? = null,
        stateJson: String? = null,
    ) {
        try {
            // Extract entity information from the value if it's a DTO with toResponseDto pattern
            val entityInfo = extractEntityInfo(value).let { if (stateJson != null) it.copy(stateJson = stateJson) else it }

            // Run in separate transaction for audit independence
            runBlocking {
                dbQuery {
                    val employeeID =
                        try {
                            UUID.fromString(employeeId)
                        } catch (e: Exception) {
                            null
                        }
                    val employee =
                        employeeId
                            ?.let { Employee.find { EmployeeTable.id eq employeeID or (EmployeeTable.employeeNumber eq it) } }
                            ?.firstOrNull()
                    val terminal = deviceId?.let { Terminal.find { TerminalTable.deviceId eq it } }?.firstOrNull()
                    val elevation =
                        TemporarySessionStore.activeElevationFor(employeeId = employeeId, deviceId = deviceId)
                            ?: TemporarySessionStore.stationOverrideActedBy(employeeId = employeeId, deviceId = deviceId)
                    val approverName = approverNameFor(approval)

                    TransactionAuditLog.new {
                        this.transactionId = UUID.randomUUID().toString()
                        this.employeeId = employee?.id?.value.toString()
                        this.employeeNumber = employee?.employeeNumber ?: "UNKNOWN"
                        this.recordAuthorization(elevation, approval, approverName)
                        this.operation = operation ?: "UNKNOWN"
                        this.entityType = entityType ?: "UNKNOWN"
                        this.entityId = entity?.id ?: "UNKNOWN"
                        // Truncated, not validated: entity_name is varchar(255), and an overflow
                        // would throw into this method's catch-all and lose the row entirely. A
                        // clipped name still identifies it.
                        this.entityName = entity?.name?.take(255) ?: "Successful operation"

                        // Store success state
                        this.beforeState = null
                        this.afterState = entityInfo.stateJson
                        this.changesSummary = "SUCCESS: ${operation ?: "Operation"} completed successfully"

                        // Context information
                        this.deviceId = terminal?.clientId ?: deviceId
                        this.ipAddress = null
                        this.reason = "Success"

                        // Financial impact if available
                        this.amountImpact = entityInfo.amount
                        this.currencyCode = "EUR"

                        // Store metadata
                        this.metadata = buildSuccessMetadata(context)
                        this.timestamp = currentTimeStamp().toString()
                        // Clipped like entity_name: an overflow would lose the whole row.
                        this.fiscalRecordId = entity?.fiscalRecordId?.take(100)
                        this.traceId = traceId
                        this.createdAt = currentTimeStamp().toString()
                    }
                }
            }
            logger.debug("Success audit log created for operation: $operation")
        } catch (auditError: Exception) {
            // Don't let audit logging failures break the application
            logger.error("Failed to create success audit log", auditError)
        }
    }

    /**
     * Logs a successful entity operation to EntryLog.
     *
     * Creates an entry log record for entity CRUD operations (product, category, etc.)
     * using the provided EntryLogConfig. Writes in the CALLER's current transaction
     * (the ambient [dbQuery] established by [catchingThrows]) so the change-feed row
     * is ATOMIC with the mutation: it commits only if the mutation commits, and is
     * rolled back if the mutation rolls back.
     *
     * Unlike the error path, failures here are NOT swallowed — a failed log write
     * must roll back the whole transaction, otherwise we could commit a mutation
     * with no corresponding change-feed row (a permanently missed sync).
     *
     * @param data The successful operation result data
     * @param employeeId Employee who performed the operation
     * @param config Entry log configuration with type, operation, and data extraction
     */
    private fun <T> logEntryOperation(
        data: T,
        employeeId: String?,
        config: EntryLogConfig<T>,
    ) {
        val logData = config.extractLogData(data)
        EntryLogHelper.logOperation(
            type = config.logType,
            operation = config.logOperation,
            entityId = logData.entityId,
            entityName = logData.entityName,
            message = logData.message,
            userId = employeeId,
        )
        logger.debug("Entry log created: ${config.logType} ${config.logOperation}")
    }

    /**
     * Logs a failed entity operation to EntryLog.
     *
     * Creates an entry log record for failed CRUD operations using [LogOperation.ERROR].
     * The original intended operation and error details are captured in the message field.
     * Runs in a separate transaction to ensure log failures never break error handling.
     *
     * @param cause The exception that caused the failure
     * @param employeeId Employee who attempted the operation
     * @param config Entry log configuration with type and original operation
     * @param context Optional context string (may contain entity ID)
     */
    private fun <T> logEntryOperationError(
        cause: Throwable,
        employeeId: String?,
        config: EntryLogConfig<T>,
        context: String?,
    ) {
        try {
            val message = cause.message ?: cause::class.simpleName ?: "unknown error"
            runBlocking {
                dbQuery {
                    EntryLogHelper.logOperation(
                        type = config.logType,
                        operation = LogOperation.ERROR,
                        entityId = extractEntityIdFromContext(context) ?: "FAILED",
                        entityName = "FAILED ${config.logOperation.name}",
                        message = "${config.logOperation.name} failed: $message",
                        userId = employeeId,
                    )
                }
            }
            logger.debug("Error entry log created: {} {} failed", config.logType, config.logOperation)
        } catch (e: Exception) {
            logger.error("Failed to create error entry log for ${config.logType} ${config.logOperation}", e)
        }
    }

    /**
     * Extracts a meaningful entity ID from a context string.
     *
     * Parses context strings like "createProduct(employeeId=abc)" or
     * "deleteVatRate(vatId=V1, employeeId=abc)" to find the first
     * non-employeeId parameter value.
     *
     * @param context Context string from catching() call
     * @return Extracted entity ID or null if not found
     */
    private fun extractEntityIdFromContext(context: String?): String? {
        if (context == null) return null
        val paramPattern = """(\w+)=([^,)]+)""".toRegex()
        return paramPattern
            .findAll(context)
            .firstOrNull { it.groupValues[1] !in setOf("employeeId", "userId") }
            ?.groupValues
            ?.get(2)
    }

    /**
     * Who names the entity, in order: the call site's own lambda, then the `EntryLogConfig` it
     * already supplies, then nothing.
     *
     * Reflection is deliberately NOT a tier. [extractEntityInfo] runs TWO independent member scans
     * — the id from a member named `id`, `billNumber` or `orderId`, the name from one named
     * `billNumber`, `orderNumber` or `name` — and calls whichever it finds first. On a DTO whose
     * `id` belongs to something else that writes a confidently wrong entity id, and because the two
     * scans are separate they can even name different entities in one row. In an audit trail a
     * wrong identifier is worse than a missing one: `UNKNOWN` is visibly absent, a wrong id
     * silently points at another record.
     *
     * @param value The successful operation result
     * @param auditEntity The call site's own naming lambda, if it declared one. Its return type is
     *   nullable so a call with nothing to name can say so: a null falls through to the next tier
     *   silently, since `onFailure` fires only on a genuine failure, while a throw is warned about.
     *   Before the type allowed null, such a call site had to throw — and wrote a stack trace on a
     *   routine success path every time it ran.
     * @param entryLogConfig The entry-log config the call site already supplies, if any
     * @param operation The audited operation name, for the log line when a source throws
     * @return The resolved entity, or null when no source names one
     */
    private fun <T> resolveAuditEntity(
        value: T,
        auditEntity: ((T) -> AuditEntityRef?)?,
        entryLogConfig: EntryLogConfig<T>?,
        operation: String?,
    ): AuditEntityRef? {
        auditEntity?.let { extract ->
            runCatching { extract(value) }
                .onFailure {
                    logger.warn(
                        "auditEntity lambda threw for operation=$operation; " +
                            "falling back to the entry-log config if present, otherwise UNKNOWN",
                        it,
                    )
                }.getOrNull()
                ?.let { return it }
        }
        entryLogConfig?.let { config ->
            // NOTE: this runs extractLogData a SECOND time, and outside the caller's dbQuery — the
            // first run is logEntryOperation's, inside it. A lambda touching a lazy Exposed DAO
            // would therefore resolve there and throw here. Left as-is deliberately; threading the
            // first result through restructures entry-log ordering and is its own change.
            // mapCatching, NOT map: the ref is built here rather than inside a call-site lambda,
            // so unlike tier 1 it has no runCatching of its own around it. Under `map` the init
            // guard's IllegalArgumentException escapes into catchingThrows' catch, which fires
            // undoBlock and rethrows — failing an operation that has already committed, over a
            // guard whose entire purpose is to degrade a ROW. One warn covers both failure modes;
            // the row's fate is identical either way.
            runCatching { config.extractLogData(value) }
                .mapCatching { logData -> AuditEntityRef(id = logData.entityId, name = logData.entityName) }
                .onFailure {
                    logger.warn(
                        "the entry-log config could not name the audit entity for " +
                            "operation=$operation; the audit row will say UNKNOWN",
                        it,
                    )
                }.getOrNull()
                ?.let { return it }
        }
        return null
    }

    /** The call site's JSON `after_state` for [value], or null (→ `toString()`) when absent or when it throws. */
    private fun <T> renderAuditState(
        value: T,
        auditState: ((T) -> String)?,
        operation: String?,
    ): String? =
        auditState?.let { render ->
            runCatching { render(value) }
                .onFailure { logger.warn("the audit state of operation=$operation could not be rendered; after_state falls back to toString()", it) }
                .getOrNull()
        }

    /**
     * Extracts entity information from result data.
     *
     * Attempts to extract ID, name, and other relevant information from
     * the success result data for audit logging.
     *
     * @param data Result data
     * @return EntityInfo containing extracted data
     */
    private fun <T> extractEntityInfo(data: T): EntityInfo {
        // Use reflection to extract common DTO fields
        return try {
            val dataClass = data!!::class
            val id =
                dataClass.members
                    .firstOrNull { it.name == "id" || it.name == "billNumber" || it.name == "orderId" }
                    ?.call(data)
                    ?.toString()

            val name =
                dataClass.members
                    .firstOrNull { it.name == "billNumber" || it.name == "orderNumber" || it.name == "name" }
                    ?.call(data)
                    ?.toString()

            val amount =
                dataClass.members
                    .firstOrNull { it.name == "billAmount" || it.name == "totalAmount" || it.name == "amount" || it.name == "amountImpact" }
                    ?.call(data)
                    ?.let {
                        when (it) {
                            is Double -> BigDecimal.valueOf(it)
                            is BigDecimal -> it
                            else -> null
                        }
                    }

            EntityInfo(
                id = id,
                name = name,
                amount = amount,
                // A Unit result has no state; its toString() ("kotlin.Unit") is not one (emu21 NOTE-u).
                stateJson = if (data is Unit) null else data.toString(),
            )
        } catch (e: Exception) {
            logger.warn("Failed to extract entity info from result data", e)
            EntityInfo(null, null, null, null)
        }
    }

    /**
     * Builds JSON metadata for success details.
     *
     * **It must not restate the entity's id or name.** It used to emit `entity_id`/`entity_name`
     * from the REFLECTED [EntityInfo] — the very source the call-site `auditEntity` lambda replaced
     * — while the columns beside it carried the call site's declared answer. The damaging shape is
     * the inverse of the obvious one: at a site with no lambda whose DTO happens to expose a member
     * named `id`, the column honestly said `UNKNOWN` while this JSON said `4711`, so the value the
     * design forbids was still recorded, one field over. `AuditLogScreen` renders the id column and
     * the raw metadata inside the same expanded row, so the two disagreed on screen.
     *
     * The typed, indexed `entity_id`/`entity_name` columns carry both. This copy was only ever a
     * duplicate, and a duplicate that can disagree is worse than no copy at all.
     *
     * **Scope, precisely.** This removes the reflected id from the field that *named* the entity.
     * `after_state` still holds `data.toString()`, so the same value can appear there — but as a
     * snapshot of what the operation returned, not as an answer to "which entity is this row
     * about". That is deliberately left alone: a snapshot is not an identity claim, and no field
     * now contradicts the `entity_id` column.
     *
     * @param context Operation context
     * @return JSON string with success metadata
     */
    private fun buildSuccessMetadata(context: String?): String =
        """
        {
          "context": "${context ?: "null"}",
          "note": "Successful operation - confirms sequence usage and entity creation"
        }
        """.trimIndent()

    /**
     * Data class for holding extracted entity information.
     */
    private data class EntityInfo(
        val id: String?,
        val name: String?,
        val amount: BigDecimal?,
        val stateJson: String?,
    )
}
