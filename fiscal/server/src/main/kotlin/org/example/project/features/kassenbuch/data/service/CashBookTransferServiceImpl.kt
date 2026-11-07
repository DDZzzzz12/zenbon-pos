/**
 * Implementation of [CashBookTransferService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Follows the same two-phase create + TSE-sign shape as
 * [KassenbuchServiceImpl.createSignedEntry], delegating the fiscal leg to the shared
 * [KassenbuchTseSigner] rather than duplicating the TSE-calling logic.
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.kassenbuch.domain.service.CashBookTransferService
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.resolveSigningClientId
import org.example.project.features.tse.domain.service.TseTransactionService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.builder.tse.KassenbuchProcessDataBuilder
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.formatGerman
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

class CashBookTransferServiceImpl(
    private val cashBookRepository: CashBookRepository,
    private val kassenbuchRepository: KassenbuchRepositoryImpl,
    private val employeeRepository: EmployeeRepository,
    private val terminalRepository: TerminalRepository,
    private val documents: KassenbuchDocuments,
    private val tseSigner: KassenbuchTseSigner,
    private val configManager: IShopConfigurationManager,
) : CashBookTransferService {
    private val logger = LoggerFactory.getLogger(CashBookTransferServiceImpl::class.java)

    /** What Phase 1 hands to Phase 2 for the fiscal leg's TSE signing — null when neither leg is fiscal. */
    internal data class LegSigningData(
        val headerId: Long,
        val belegNr: String,
        val processData: String,
        val processType: String,
        val clientId: String,
    )

    internal data class TransferLegs(
        val fromHeaderId: Long,
        val toHeaderId: Long,
        val fromBelegNr: String,
        val toBelegNr: String,
        val amount: BigDecimal,
        val signing: LegSigningData?,
    )

    /**
     * Audited as `CREATE_CASH_TRANSFER` (admin20 NOTE-C: a Geldtransit moved cash between books and
     * left no `transaction_audit_log` row, unlike every other Kassenbuch booking). The row names the
     * transfer id — the key both legs share — and their two Belegnummern.
     */
    override suspend fun createTransfer(
        employeeId: String,
        deviceId: String?,
        request: CashBookDto.CreateTransferRequestDto,
    ): CashBookDto.TransferResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createTransfer(from=${request.fromBookId}, to=${request.toBookId}, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "CREATE_CASH_TRANSFER",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = ::namesTheTransfer,
            auditState = ::transferStateJson,
        ) { bookTransfer(employeeId, deviceId, request) }

    /**
     * Audited as `REVERSE_CASH_TRANSFER`. A refusal names the transfer it tried to reverse, so it
     * shows up in that transfer's audit trail; a success names the fresh reversal pair.
     */
    override suspend fun reverseTransfer(
        employeeId: String,
        deviceId: String?,
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto =
        ExceptionConverter.catchingThrows(
            context = "reverseTransfer(transferId=$transferId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "REVERSE_CASH_TRANSFER",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = ::namesTheTransfer,
            failedEntityId = transferId.takeIf { it.isNotBlank() && it.length <= 50 },
            auditState = ::transferStateJson,
        ) { bookReversal(employeeId, deviceId, transferId, reason) }

    /** The audit row's `after_state` as JSON, not the DTO's Kotlin `toString()` (emu21 NOTE-u). */
    private fun transferStateJson(response: CashBookDto.TransferResponseDto): String = Json.encodeToString(response)

    /** The audit row's entity: the pair's shared transfer id, named by both Belegnummern. */
    private fun namesTheTransfer(response: CashBookDto.TransferResponseDto): AuditEntityRef =
        AuditEntityRef(id = response.transferId, name = "${response.fromBelegNr} / ${response.toBelegNr}")

    private suspend fun bookTransfer(
        employeeId: String,
        deviceId: String?,
        request: CashBookDto.CreateTransferRequestDto,
    ): CashBookDto.TransferResponseDto {
        request.validate().firstOrNull()?.let {
            throw validationFailed(it)
        }
        // CLAUDE.md rule 8: the DTO validates shape, but a fiscal path re-checks server-side.
        val amount =
            request.amount.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                ?: throw validationFailed("amount must be a positive decimal string")

        val transferId = UUID.randomUUID().toString()
        val employeeUuid = UUID.fromString(employeeId)

        // Read the TSE mode BEFORE opening the transaction, for the same reason the ERP config is
        // read early in KassenbuchServiceImpl.createSignedEntry: on a cache miss `getTseMode()`
        // takes `configMutex` and falls through to its own `dbQuery`, and nesting one transaction in
        // another is forbidden here. Acquiring a mutex while holding a DB connection is also a
        // lock-ordering hazard — and this transaction holds `pg_advisory_xact_lock(1)`, so stalling
        // inside it would block every Z-Bon and Kassenbuch write shop-wide.
        //
        // Hoisting above the retry also means a retried transaction sees ONE consistent mode rather
        // than re-reading mid-flight.
        val tseMode = configManager.getTseMode()

        // Phase 1: both legs or neither, in ONE transaction — the point of atomicity here.
        val legs: TransferLegs =
            dbQuery {
                // Round-8 R7-D9, the SAME lock and the SAME reason as
                // KassenbuchServiceImpl.createSignedEntry: serialize Kassenbuch creation against
                // ZBonServiceImpl.createReport, which holds pg_advisory_xact_lock(1) for its whole
                // closing transaction. Without it a transfer leg can commit invisibly INSIDE the
                // closing's REPEATABLE_READ snapshot and be stranded is_locked=false — and there is
                // no unlock/delete/repair path for a stranded entry.
                //
                // It also serializes the Kassenminus guard below: two concurrent transfers out of the
                // same book would otherwise both read the pre-transfer balance, both pass
                // KassenminusGuard.requireCovered, and together overdraw it.
                //
                // Must be the FIRST statement. Postgres-only; H2 aliases it to a no-op (TestDatabase).
                TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")

                val from = cashBookRepository.findById(request.fromBookId) ?: throw bookNotFound(request.fromBookId)
                val to = cashBookRepository.findById(request.toBookId) ?: throw bookNotFound(request.toBookId)
                // Checked here too (not just inside createTransferLegsInTransaction below) to keep
                // this exact error priority: originally these ran before the employee lookup, and
                // employee now has to be resolved up front to satisfy createTransferLegsInTransaction's
                // signature. Cheap and side-effect-free, so re-running them there is harmless.
                requireCompatibleBooks(from, to)
                KassenminusGuard.requireCovered(kassenbuchRepository, from, amount, KassenminusGuard.Takings.UNBOOKED_IN_DRAWER)

                val employee =
                    employeeRepository.getEmployeeById(employeeUuid)
                        ?: throw PosException(
                            PosErrorCode.EMPLOYEE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Employee not found: $employeeId",
                        )

                val built =
                    createTransferLegsInTransaction(
                        fromBook = from,
                        toBook = to,
                        amount = amount,
                        belegDatum = request.belegDatum,
                        employee = employee,
                        transferId = transferId,
                        note = request.note,
                        takings = KassenminusGuard.Takings.UNBOOKED_IN_DRAWER,
                    )

                // Signing stays here rather than in createTransferLegsInTransaction: it needs
                // THIS request's deviceId, which the shared leg-builder does not take — the Z-Bon
                // caller (ZBonServiceImpl.createReport) signs the fiscal leg through its own
                // terminal context instead, so deviceId-based signing is createTransfer-specific.
                val signing =
                    when {
                        from.isFiscal ->
                            buildSigningData(
                                built.fromHeaderId,
                                built.fromBelegNr,
                                amount.negate(),
                                KassenbuchEntryType.CASH_TRANSFER_OUT,
                                deviceId,
                                tseMode,
                            )
                        to.isFiscal ->
                            buildSigningData(
                                built.toHeaderId,
                                built.toBelegNr,
                                amount,
                                KassenbuchEntryType.CASH_TRANSFER_IN,
                                deviceId,
                                tseMode,
                            )
                        else -> null
                    }

                logger.info(
                    "CashBookTransferServiceImpl.createTransfer: transferId={} from={}({}) to={}({}) amount={}",
                    transferId,
                    from.id.value,
                    built.fromBelegNr,
                    to.id.value,
                    built.toBelegNr,
                    amount,
                )

                built.copy(signing = signing)
            }

        // Phase 2: sign the fiscal leg only, not retried — the entry is already committed.
        // Delegates to the shared KassenbuchTseSigner rather than duplicating the TSE logic;
        // a signing failure strands that leg PENDING for TsePendingSweeper, same as any ordinary entry.
        legs.signing?.let {
            tseSigner.sign(
                headerId = it.headerId,
                belegNr = it.belegNr,
                processData = it.processData,
                processType = it.processType,
                clientId = it.clientId,
            )
        }

        return CashBookDto.TransferResponseDto(
            transferId = transferId,
            fromLegHeaderId = legs.fromHeaderId,
            toLegHeaderId = legs.toHeaderId,
            fromBelegNr = legs.fromBelegNr,
            toBelegNr = legs.toBelegNr,
            amount = amount.toPlainString(),
        )
    }

    /**
     * Builds both legs of a transfer **inside the caller's transaction**.
     *
     * Split out of [createTransfer] because `ZBonServiceImpl.createReport` books the daily
     * Geldtransit from inside its own `dbQuery`, and this project forbids nesting one transaction
     * in another. [createTransfer] keeps the `dbQuery` + Phase-2 signing around this; the Z-Bon
     * path supplies its own transaction and signs the fiscal leg through the closing's own
     * terminal context.
     *
     * Requires an ambient Exposed transaction and the R7-D9 advisory lock to already be held.
     *
     * `suspend`, not plain `fun`: it calls [KassenbuchDocuments.createEigenbeleg] (itself
     * `suspend`) directly rather than through another `dbQuery`, exactly as the code it was
     * extracted from did inside `createTransfer`'s `dbQuery { }` lambda (whose block type is
     * `suspend () -> T`).
     *
     * [belegtextForToLeg] overrides the `toBook` leg's default `"Geldtransit von ${fromBook.name}"`
     * text — the Z-Bon path's daily takings transfer reads "Tageseinnahmen" instead, since it isn't
     * an operator-initiated Geldtransit. Null (the default) preserves [createTransfer]'s existing
     * text exactly.
     *
     * [takings] decides what the source book may pay out (the Kassenminus guard): an operator
     * transfer counts the fiscal drawer's not-yet-closed takings ([KassenminusGuard.Takings.UNBOOKED_IN_DRAWER]);
     * a closing has booked them already ([KassenminusGuard.Takings.BOOKED_BY_CLOSING]). Required,
     * not defaulted, so no caller picks the wrong one by omission.
     */
    internal suspend fun createTransferLegsInTransaction(
        fromBook: CashBook,
        toBook: CashBook,
        amount: BigDecimal,
        belegDatum: String,
        employee: Employee,
        transferId: String,
        note: String?,
        documentIdForFromLeg: Long? = null,
        documentIdForToLeg: Long? = null,
        belegtextForToLeg: String? = null,
        takings: KassenminusGuard.Takings,
    ): TransferLegs {
        requireCompatibleBooks(fromBook, toBook)
        KassenminusGuard.requireCovered(kassenbuchRepository, fromBook, amount, takings)

        val outBelegNr = kassenbuchRepository.nextBelegNrBlocking(fromBook.id.value)
        val inBelegNr = kassenbuchRepository.nextBelegNrBlocking(toBook.id.value)

        // One Eigenbeleg PER LEG unless the caller already has a document to link (the Z-Bon path
        // passes the Z-Bon PDF and a copy of it): linkedDocumentId is uniqueIndex, so the two legs
        // cannot share one document either way.
        val outDocumentId =
            documentIdForFromLeg
                ?: documents.createEigenbeleg(
                    belegNr = outBelegNr,
                    date = belegDatum,
                    entryType = KassenbuchEntryType.CASH_TRANSFER_OUT.displayName,
                    amount = "-${formatGerman(amount)} EUR",
                    description = transferDescription("an", toBook.name, inBelegNr, note),
                    employeeId = employee.id.value,
                )
        val inDocumentId =
            documentIdForToLeg
                ?: documents.createEigenbeleg(
                    belegNr = inBelegNr,
                    date = belegDatum,
                    entryType = KassenbuchEntryType.CASH_TRANSFER_IN.displayName,
                    amount = "+${formatGerman(amount)} EUR",
                    description = transferDescription("von", fromBook.name, outBelegNr, note),
                    employeeId = employee.id.value,
                )

        val outHeader =
            createLeg(
                book = fromBook,
                counterparty = toBook,
                entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                signedAmount = amount.negate(),
                belegNr = outBelegNr,
                belegDatum = belegDatum,
                belegtext = "Geldtransit an ${toBook.name}",
                nachricht = note,
                transferId = transferId,
                employee = employee,
                documentId = outDocumentId,
            )
        val inHeader =
            createLeg(
                book = toBook,
                counterparty = fromBook,
                entryType = KassenbuchEntryType.CASH_TRANSFER_IN,
                signedAmount = amount,
                belegNr = inBelegNr,
                belegDatum = belegDatum,
                belegtext = belegtextForToLeg ?: "Geldtransit von ${fromBook.name}",
                nachricht = note,
                transferId = transferId,
                employee = employee,
                documentId = inDocumentId,
            )

        // A non-fiscal book (Tresor, Nebenkasse) is not an elektronisches
        // Aufzeichnungssystem — §146a does not reach it. At most ONE of the two books is
        // the shop's fiscal Ladenkasse, so at most one leg is PENDING; the caller decides whether
        // and how to sign it (see the KDoc above).
        outHeader.tseSigningStatus = if (fromBook.isFiscal) TseSigningStatus.PENDING else TseSigningStatus.NOT_APPLICABLE
        inHeader.tseSigningStatus = if (toBook.isFiscal) TseSigningStatus.PENDING else TseSigningStatus.NOT_APPLICABLE

        return TransferLegs(
            fromHeaderId = outHeader.id.value,
            toHeaderId = inHeader.id.value,
            fromBelegNr = outBelegNr,
            toBelegNr = inBelegNr,
            amount = amount,
            signing = null,
        )
    }

    /**
     * Reverses a whole transfer (both legs) as a Storno pair. See the interface doc for why a
     * transfer is never corrected leg-by-leg — [KassenbuchServiceImpl.createCorrection] refuses
     * any header with a non-null `transferId` and points the caller here.
     *
     * Mirrors [createTransfer]'s two-phase shape and reuses [createLeg]/[buildSigningData]: the
     * only differences are that the amounts/entry types are the mirror image of the originals,
     * each new leg carries `correctionRole = REVERSAL` + `correctsHeaderId`, and the pair shares a
     * FRESH `transferId` rather than the original's.
     */
    private suspend fun bookReversal(
        employeeId: String,
        deviceId: String?,
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto {
        if (reason.isBlank()) {
            throw validationFailed("A reason is required to reverse a transfer")
        }

        val employeeUuid = UUID.fromString(employeeId)
        val reversalTransferId = UUID.randomUUID().toString()

        // Hoisted above the transaction for the same reason as in createTransfer — see there. This
        // one holds the same shared advisory lock.
        val tseMode = configManager.getTseMode()

        // Phase 1: both reversal legs or neither, in ONE transaction — same atomicity contract as createTransfer.
        val legs: TransferLegs =
            dbQuery {
                // Same lock, same reason as createTransfer / KassenbuchServiceImpl.createSignedEntry:
                // serializes against ZBonServiceImpl.createReport's closing transaction so a reversal
                // leg cannot commit invisibly inside a closing's REPEATABLE_READ snapshot and be
                // stranded is_locked=false. Must be the FIRST statement.
                TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")

                val originalLegs = KassenbuchHeader.find { KassenbuchHeaderTable.transferId eq transferId }.toList()
                val originalOut = originalLegs.firstOrNull { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }
                val originalIn = originalLegs.firstOrNull { it.entryType == KassenbuchEntryType.CASH_TRANSFER_IN }
                if (originalOut == null || originalIn == null) {
                    throw transferNotFound(transferId)
                }

                // A locked leg (already swept into a closed Z-Bon) MUST still be reversible — locking
                // freezes document attachment, not correction. No isLocked check here, deliberately.

                // Double-storno guard: hasReversal finds a REVERSAL whose correctsHeaderId ==
                // headerId, which is exactly the shape this method creates below — so a second call
                // for the same transferId is caught by the very rows the first call wrote.
                if (kassenbuchRepository.hasReversal(originalOut.id.value) ||
                    kassenbuchRepository.hasReversal(originalIn.id.value)
                ) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.UnprocessableEntity,
                        "Transfer $transferId has already been reversed",
                    )
                }

                val fromBook = originalOut.cashBook
                val toBook = originalIn.cashBook
                // The magnitude that physically moved — the IN leg's stored amount is already
                // positive, so it needs no sign massaging.
                val amount = originalIn.totalAmount

                val employee =
                    employeeRepository.getEmployeeById(employeeUuid)
                        ?: throw PosException(
                            PosErrorCode.EMPLOYEE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Employee not found: $employeeId",
                        )

                val outReversalBelegNr = kassenbuchRepository.nextBelegNrBlocking(fromBook.id.value)
                val inReversalBelegNr = kassenbuchRepository.nextBelegNrBlocking(toBook.id.value)

                // Each reversal leg needs its OWN Storno-Eigenbeleg: linkedDocumentId is uniqueIndex,
                // so the two legs cannot share one document, same as the original pair.
                val outReversalDoc =
                    documents.createEigenbeleg(
                        belegNr = outReversalBelegNr,
                        date = originalOut.belegDatum,
                        entryType = KassenbuchEntryType.CASH_TRANSFER_IN.displayName,
                        amount = "+${formatGerman(amount)} EUR",
                        description = "Storno zu Beleg-Nr. ${originalOut.belegNr}",
                        employeeId = employeeUuid,
                        correctsBelegNr = originalOut.belegNr,
                        correctsDate = originalOut.belegDatum,
                        reason = reason,
                    )
                val inReversalDoc =
                    documents.createEigenbeleg(
                        belegNr = inReversalBelegNr,
                        date = originalIn.belegDatum,
                        entryType = KassenbuchEntryType.CASH_TRANSFER_OUT.displayName,
                        amount = "-${formatGerman(amount)} EUR",
                        description = "Storno zu Beleg-Nr. ${originalIn.belegNr}",
                        employeeId = employeeUuid,
                        correctsBelegNr = originalIn.belegNr,
                        correctsDate = originalIn.belegDatum,
                        reason = reason,
                    )

                // Reversal of the OUT leg: same book (fromBook), opposite entry type/sign, cash
                // comes BACK in.
                val reversalOfOut =
                    createLeg(
                        book = fromBook,
                        counterparty = toBook,
                        entryType = KassenbuchEntryType.CASH_TRANSFER_IN,
                        signedAmount = amount,
                        belegNr = outReversalBelegNr,
                        belegDatum = originalOut.belegDatum,
                        belegtext = "Storno Geldtransit von ${toBook.name}",
                        nachricht = reason,
                        transferId = reversalTransferId,
                        employee = employee,
                        documentId = outReversalDoc,
                        correctsHeaderId = originalOut.id.value,
                        correctionRole = CorrectionRole.REVERSAL,
                    )
                // Reversal of the IN leg: same book (toBook), opposite entry type/sign, cash goes
                // back OUT.
                val reversalOfIn =
                    createLeg(
                        book = toBook,
                        counterparty = fromBook,
                        entryType = KassenbuchEntryType.CASH_TRANSFER_OUT,
                        signedAmount = amount.negate(),
                        belegNr = inReversalBelegNr,
                        belegDatum = originalIn.belegDatum,
                        belegtext = "Storno Geldtransit an ${fromBook.name}",
                        nachricht = reason,
                        transferId = reversalTransferId,
                        employee = employee,
                        documentId = inReversalDoc,
                        correctsHeaderId = originalIn.id.value,
                        correctionRole = CorrectionRole.REVERSAL,
                    )

                // Same fiscal/non-fiscal split as the original pair — the fiscal book didn't change.
                reversalOfOut.tseSigningStatus =
                    if (fromBook.isFiscal) TseSigningStatus.PENDING else TseSigningStatus.NOT_APPLICABLE
                reversalOfIn.tseSigningStatus =
                    if (toBook.isFiscal) TseSigningStatus.PENDING else TseSigningStatus.NOT_APPLICABLE

                val signing =
                    when {
                        fromBook.isFiscal ->
                            buildSigningData(
                                reversalOfOut.id.value,
                                outReversalBelegNr,
                                amount,
                                KassenbuchEntryType.CASH_TRANSFER_IN,
                                deviceId,
                                tseMode,
                            )
                        toBook.isFiscal ->
                            buildSigningData(
                                reversalOfIn.id.value,
                                inReversalBelegNr,
                                amount.negate(),
                                KassenbuchEntryType.CASH_TRANSFER_OUT,
                                deviceId,
                                tseMode,
                            )
                        else -> null
                    }

                logger.info(
                    "CashBookTransferServiceImpl.reverseTransfer: originalTransferId={} reversalTransferId={} from={}({}) to={}({}) amount={}",
                    transferId,
                    reversalTransferId,
                    fromBook.id.value,
                    outReversalBelegNr,
                    toBook.id.value,
                    inReversalBelegNr,
                    amount,
                )

                TransferLegs(
                    fromHeaderId = reversalOfOut.id.value,
                    toHeaderId = reversalOfIn.id.value,
                    fromBelegNr = outReversalBelegNr,
                    toBelegNr = inReversalBelegNr,
                    amount = amount,
                    signing = signing,
                )
            }

        // Phase 2: sign the fiscal leg only, not retried — same contract as createTransfer.
        legs.signing?.let {
            tseSigner.sign(
                headerId = it.headerId,
                belegNr = it.belegNr,
                processData = it.processData,
                processType = it.processType,
                clientId = it.clientId,
            )
        }

        return CashBookDto.TransferResponseDto(
            transferId = reversalTransferId,
            fromLegHeaderId = legs.fromHeaderId,
            toLegHeaderId = legs.toHeaderId,
            fromBelegNr = legs.fromBelegNr,
            toBelegNr = legs.toBelegNr,
            amount = legs.amount.toPlainString(),
        )
    }

    private fun requireCompatibleBooks(
        from: CashBook,
        to: CashBook,
    ) {
        if (from.shop.id.value != to.shop.id.value) {
            throw validationFailed("Cash books belong to different shops")
        }
        if (!from.isActive || !to.isActive) {
            throw validationFailed("Both cash books must be active")
        }
    }

    /**
     * Creates one leg's header + single line item; no VAT (a Geldtransit is not a
     * Geschäftsvorfall). [correctsHeaderId]/[correctionRole] are non-null only when this leg is a
     * reversal — [reverseTransfer] reuses this same helper for its Storno pair rather than
     * duplicating the header/line-item shape.
     */
    private fun createLeg(
        book: CashBook,
        counterparty: CashBook,
        entryType: KassenbuchEntryType,
        signedAmount: BigDecimal,
        belegNr: String,
        belegDatum: String,
        belegtext: String,
        nachricht: String?,
        transferId: String,
        employee: org.example.project.features.employee.domain.models.Employee,
        documentId: Long,
        correctsHeaderId: Long? = null,
        correctionRole: CorrectionRole? = null,
    ): KassenbuchHeader {
        val header =
            kassenbuchRepository.createEntry(
                shop = book.shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = entryType,
                        belegNr = belegNr,
                        vorzBetrag = signedAmount.toPlainString(),
                        belegDatum = belegDatum,
                        belegtext = belegtext.take(60),
                        nachricht = nachricht?.take(120),
                        cashBookId = book.id.value,
                        counterpartyBookId = counterparty.id.value,
                        transferId = transferId,
                        correctsHeaderId = correctsHeaderId,
                        correctionRole = correctionRole,
                    ),
                lineItemsData =
                    listOf(
                        KassenbuchDto.LineItemData(vorzBetrag = signedAmount.toPlainString(), ustSatz = null),
                    ),
            )
        kassenbuchRepository.linkDocument(header.id.value, documentId)
        return header
    }

    /**
     * Builds the Kassenbeleg-V1 TSE payload for the fiscal leg, mirroring
     * [KassenbuchServiceImpl.createSignedEntry]'s Kassenbeleg branch: a Geldtransit's BON_TYP is
     * always „Beleg" (only CLOSING_BALANCE maps to AVSonstige), so it signs as a cash receipt.
     *
     * Takes the header's id rather than the entity: since the
     * [createTransferLegsInTransaction] extraction, [createTransfer]'s only header handle after
     * Phase 1 is the id carried on the returned [TransferLegs].
     */
    private fun buildSigningData(
        headerId: Long,
        belegNr: String,
        netCashAmount: BigDecimal,
        entryType: KassenbuchEntryType,
        deviceId: String?,
        mode: TseMode,
    ): LegSigningData {
        val dev =
            deviceId ?: throw validationFailed("deviceId is required to sign the fiscal leg of a transfer")
        // Stage 4b: the fiscal leg signs under the CALLING station's own TSE client, so it must pass
        // the same guard as an order or a bill. `requireSigningTerminal` now asserts that itself,
        // which is why the mode has to be threaded down here — see TerminalSigningGuard.
        val clientId = resolveSigningClientId(terminalRepository, dev, mode)
        return buildLegSigningData(headerId, belegNr, netCashAmount, entryType, clientId)
    }

    /**
     * The Kassenbeleg-V1 payload for a fiscal transfer leg under an ALREADY-resolved TSE client.
     *
     * Split from [buildSigningData] for `ZBonServiceImpl.createReport`, which signs its daily
     * Geldtransit's Ladenkasse leg under the closing's own client — resolved once, for the
     * aggregate — rather than re-running the terminal guard. One payload definition for the
     * manual and the automatic Geldtransit, so the two cannot drift.
     */
    internal fun buildLegSigningData(
        headerId: Long,
        belegNr: String,
        netCashAmount: BigDecimal,
        entryType: KassenbuchEntryType,
        clientId: String,
    ): LegSigningData {
        val vorgangstyp = KassenbuchDsfinvkMapping.getBonTyp(entryType)
        val lineItems = listOf(KassenbuchDto.LineItemData(vorzBetrag = netCashAmount.toPlainString(), ustSatz = null))
        val processData =
            KassenbuchProcessDataBuilder.buildKassenbelegV1(
                vorgangstyp = vorgangstyp,
                entryType = entryType,
                correctedEntryType = null,
                lineItems = lineItems,
                netCashAmount = netCashAmount,
                totalNonCashWithdrawals = null,
            )

        return LegSigningData(
            headerId = headerId,
            belegNr = belegNr,
            processData = processData,
            processType = TseTransactionService.PROCESS_TYPE_RECEIPT,
            clientId = clientId,
        )
    }

    private fun transferDescription(
        preposition: String,
        counterpartyName: String,
        counterpartyBelegNr: String,
        note: String?,
    ): String =
        buildString {
            append("Geldtransit $preposition $counterpartyName (Beleg $counterpartyBelegNr)")
            if (!note.isNullOrBlank()) append(". $note")
        }

    private fun bookNotFound(id: Long) = PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Cash book with id=$id not found")

    private fun transferNotFound(transferId: String) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Transfer with id=$transferId not found (both legs required)",
        )

    private fun validationFailed(msg: String) = PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, msg)
}
