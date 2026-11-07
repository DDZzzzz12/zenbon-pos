/**
 * Implementation of [KassenbuchService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from `Result<T, Error>` in Stage B Batch 3.
 *
 * Integrates with TSE (Technical Security Element) for German KassenSichV
 * fiscal compliance, signing each Kassenbuch entry. The TSE module remains
 * Result-based; FAIL-mode branches throw [PosException], QUEUE-mode falls through.
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.export.dsfinvk.builder.KassenbuchTaxClassifier
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.kassenbuch.mapper.toGroupedDetailDto
import org.example.project.features.kassenbuch.mapper.toGroupedSummaryDto
import org.example.project.features.kassenbuch.mapper.toKassenbuchEntryData
import org.example.project.features.kassenbuch.mapper.toKassenbuchEntryResponseDto
import org.example.project.features.kassenbuch.mapper.toLineItemData
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.resolveSigningClientId
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.example.project.features.tse.domain.service.TseSigningQueueService
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.DrawerCashDerivation
import org.example.project.features.zBon.domain.repository.ZBonRepository
import org.example.project.features.zBon.mapper.zBonDisplayName
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.errorHandling.RetryConfig
import org.example.project.utils.errorHandling.withRetryThrows
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.formatGerman
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.log.AuditEntityRef
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Implementation of KassenbuchService with business logic.
 */
class KassenbuchServiceImpl(
    private val kassenbuchRepository: KassenbuchRepository,
    private val zBonRepository: ZBonRepository,
    private val employeeRepository: EmployeeRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val tseServiceFactory: TseServiceFactory,
    private val tseSigningQueueService: TseSigningQueueService,
    private val terminalRepository: TerminalRepository,
    private val documents: KassenbuchDocuments,
    private val configManager: IShopConfigurationManager,
    private val lateSigningRepository: TseLateSigningRepository = TseLateSigningRepositoryImpl(),
) : KassenbuchService {
    private val logger = LoggerFactory.getLogger(KassenbuchServiceImpl::class.java)

    /**
     * Resolves "no cash book given" to the shop's fiscal Ladenkasse for the operator guards.
     * Stateless, so a field rather than a constructor arg — same rationale as [tseSigner].
     */
    private val cashBookRepository = CashBookRepositoryImpl()

    /**
     * The shared TSE-signing path, also used by [CashBookTransferServiceImpl].
     *
     * Built here from this service's own collaborators rather than taken as a constructor
     * parameter, so the ~10 call sites that construct this service by named argument stay
     * untouched by the extraction.
     */
    private val tseSigner = KassenbuchTseSigner(configManager, tseServiceFactory, tseSigningQueueService)

    override suspend fun createEntry(
        employee: String,
        deviceId: String,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
    ): KassenbuchDto.CreateKassenbuchEntryGroupResponseDto = createAuditedEntry(employee, deviceId, request, approvedLateBookingReason = null)

    override suspend fun needsLateBookingOverride(request: KassenbuchDto.CreateKassenbuchEntryRequestDto): Boolean {
        val belegDate = KassenbuchEntryGuards.parseBelegDatum(request.belegDatum) ?: return false
        return KassenbuchBelegDateRule.needsOverride(belegDate, currentTimeStamp().date, configManager.getKassenbuchMaxBelegAgeDays())
    }

    override suspend fun createBackdatedEntry(
        employee: String,
        deviceId: String,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
    ): KassenbuchDto.CreateKassenbuchEntryGroupResponseDto =
        createAuditedEntry(employee, deviceId, request, approvedLateBookingReason = request.lateBookingReason)

    /**
     * The audited operator create. [approvedLateBookingReason] is non-null only on the authorised
     * backdating path ([createBackdatedEntry]); it lets a Beleg date past the shop's limit through
     * and is stored on the entry.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun createAuditedEntry(
        employee: String,
        deviceId: String,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
        approvedLateBookingReason: String?,
    ): KassenbuchDto.CreateKassenbuchEntryGroupResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createCashBookEntry(employee=$employee)",
            employeeId = employee,
            operation = "CREATE_CASH_BOOK_ENTRY",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            // `entries` is the single header projection createSignedEntry builds, and its `id` IS the
            // kassenbuch_header primary key — the same identifier the three document-link sites below
            // write, so a create and the later link/replace/unlink of the same entry share one
            // entity_id. `belegNr` is the Beleg number an auditor reads off the DATEV export; it also
            // stands in as the id if that projection is ever empty, because it names the SAME entry by
            // its other key rather than naming some other entity.
            auditEntity = { group ->
                val projection = group.entries.firstOrNull()
                AuditEntityRef(id = projection?.id?.toString() ?: group.belegNr, name = group.belegNr)
            },
        ) {
            createSignedEntry(
                employee,
                deviceId,
                request,
                enforceOperatorGuards = true,
                approvedLateBookingReason = approvedLateBookingReason,
            ).second
        }

    /**
     * Shared two-phase create + TSE-sign path used by [createEntry] and `createCorrection`.
     *
     * Phase 1 (retryable, committed in a single [dbQuery]): validates the request, creates the
     * header + line items, links the mandatory document, and collects the TSE signing data.
     * The optional [correctsHeaderId] / [correctionRole] are stamped onto the created entry.
     *
     * Phase 2 (not retried — prevents orphaned TSE records on rollback): signs with the TSE via
     * [KassenbuchTseSigner.sign].
     *
     * @return the created header id paired with the grouped create response.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun createSignedEntry(
        employee: String,
        deviceId: String,
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
        correctsHeaderId: Long? = null,
        correctionRole: CorrectionRole? = null,
        enforceOperatorGuards: Boolean = false,
        approvedLateBookingReason: String? = null,
        systemLineKinds: Set<KassenbuchLineKind> = emptySet(),
        bookingHook: KassenbuchBookingHook? = null,
        recordsCountedCash: Boolean = false,
    ): Pair<Long, KassenbuchDto.CreateKassenbuchEntryGroupResponseDto> {
        // Data needed for TSE signing after the DB transaction commits
        data class TseSigningData(
            val processData: String,
            val processType: String,
            val clientId: String,
            val headerId: Long,
            val belegNr: String,
        )

        data class CreationResult(
            val headerId: Long,
            val response: KassenbuchDto.CreateKassenbuchEntryGroupResponseDto,
            // Null when the entry's cash book is non-fiscal: Phase 2 (TSE signing) is skipped
            // entirely for those entries — see the isFiscal check below.
            val tseSigningData: TseSigningData?,
        )

        // Read the TSE mode the signing guard needs BEFORE opening the transaction: on a cache
        // miss getTseMode falls through to its own dbQuery, and nesting one inside Phase 1 would be
        // a nested transaction. It also takes `configMutex` on a cache miss, and this transaction
        // holds the shared advisory lock. Hoisting it above the retry also means a retried
        // transaction sees one consistent value rather than re-reading mid-flight.
        val tseMode = configManager.getTseMode()

        // The shop's Beleg-age limit, read here for the same reason — and only on the operator path,
        // because corrections and the Z-Bon sweep are exempt from the date guards. Null = no check.
        val belegAgeLimit = if (enforceOperatorGuards) configManager.getKassenbuchMaxBelegAgeDays() else null

        // Phase 1: DB transaction (retryable) — create entry, link document, collect TSE signing data
        val phase1: CreationResult =
            withRetryThrows(
                operationName = "createSignedEntry",
                config = RetryConfig.AGGRESSIVE,
            ) {
                dbQuery {
                    // Round-8 R7-D9: serialize Kassenbuch creation against ZBonServiceImpl.createReport,
                    // which holds pg_advisory_xact_lock(1) for the whole of its closing transaction.
                    // Taking the SAME lock as the FIRST statement here makes a KB booking either commit
                    // fully BEFORE a concurrent closing pins its REPEATABLE_READ snapshot (so the closing
                    // sweeps it) or serialize AFTER that closing commits (so it is correctly fresh open
                    // business) — never commit invisibly INSIDE the snapshot, which is what left the entry
                    // stranded is_locked=false. The heal-forward sweep recovers a stranded row; this stops
                    // it being stranded at all, and keeps the entry in its own period's closing. Held until
                    // this transaction commits. Postgres-only: H2 aliases the function to a no-op (see
                    // TestDatabase), and the lock is re-acquired on each AGGRESSIVE retry (released on
                    // rollback), so a retry cannot bypass the serialization.
                    TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")
                    logger.info(
                        "KassenbuchServiceImpl.createSignedEntry: creating kassenbuch entry for default shop, employee={}, lines={}",
                        employee,
                        request.lines.size,
                    )

                    // Validate the request data. `systemLineKinds` is non-empty only on a server-
                    // internal booking (the tip payout); every client request arrives with none.
                    val validationErrors = request.validate(systemLineKinds)
                    if (validationErrors.isNotEmpty()) {
                        logger.warn(
                            "KassenbuchServiceImpl.createSignedEntry: validation failed for kassenbuch entry: {}",
                            validationErrors.joinToString(", "),
                        )
                        throw validationFailed("Request validation failed: ${validationErrors.first()}")
                    }

                    val shop =
                        shopInfoRepository.findById(1L)
                            ?: throw PosException(
                                PosErrorCode.RESOURCE_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Default shop not found",
                            )

                    // Round-3 KABS-3 (Anhang C p. 56): balance-snapshot entry types are guarded at
                    // creation. CLOSING_BALANCE is rejected outright — a count snapshot is the
                    // Kassensturz/DifferenzSollIst flow's job, and letting it chain into currentBalance
                    // as if cash moved breaks Kassensturzfähigkeit. OPENING_BALANCE is only a genuine
                    // Anfangsbestand (BON_TYP „Beleg"/Bar) when the book is virgin: running balance is
                    // zero AND no prior ZBon closing exists for the shop.
                    guardBalanceSnapshotEntry(request.entryType, shop)

                    // Round-7 C1: a CORRECTION_*'s GV_TYP — and therefore its USt container — is the
                    // CORRECTED entry's. Nothing nets across containers (Anhang I p. 118), so a
                    // reversal landing anywhere else could never „die Beträge des ersten Datensatzes
                    // rechnerisch wieder ausgleichen" (BON_STORNO p. 85). Resolved once here, inside
                    // the same transaction, exactly as the DSFinV-K exporter resolves `correctsHeader`.
                    val correctedEntryType =
                        correctsHeaderId
                            ?.let { KassenbuchHeader.findById(it)?.entryType }

                    // Round-7 D1 (CLAUDE.md rule 8 — guard at BOTH levels; the client filters the VAT
                    // control by entry type). `getGvTyp`'s per-line PFAND/GUTSCHEIN override is gated
                    // on the header GV_TYP being „Umsatz", so a GUTSCHEIN line inside e.g. a Barausgabe
                    // is NOT a voucher redemption at all — it is part of that `Auszahlung` and takes
                    // the declared rate. Anhang I has no voucher-inside-payout example and Anhang C
                    // p. 54/55 defines the Gutschein GV_TYPs as customer-facing, so the combination is
                    // meaningless rather than merely unusual. The shared classifier stays TOTAL (it
                    // returns the Auszahlung rule with no special-casing — an undefined cell on a
                    // SIGNING path is strictly worse than a determinate one); this guard makes the
                    // cell unreachable so the question never has to be answered in production data.
                    if (request.lines.any { it.kind == KassenbuchLineKind.GUTSCHEIN }) {
                        val headerGvTyp =
                            KassenbuchDsfinvkMapping.getGvTyp(request.entryType, correctedEntryType)
                        if (headerGvTyp != DsfinvkExportDto.BusinessCaseTypes.UMSATZ) {
                            throw validationFailed(
                                "Gutschein lines are only valid on an Umsatz Vorgang " +
                                    "(Barverkauf/Barzahlung/Rückerstattung); ${request.entryType.name} " +
                                    "maps to GV_TYP $headerGvTyp",
                            )
                        }
                    }

                    // Under the advisory lock taken above, so a guard re-read here sees every
                    // booking committed before this one (KassenbuchBookingHook).
                    bookingHook?.beforeCreate()

                    val employeeUuid = UUID.fromString(employee)
                    val employeeEntity =
                        employeeRepository.getEmployeeById(employeeUuid)
                            ?: throw PosException(
                                PosErrorCode.EMPLOYEE_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Employee not found: $employee",
                            )

                    // The number must come from the TARGET book's own series. `request.cashBookId`
                    // is null for the overwhelmingly common fiscal case, and the repository
                    // resolves that to the shop's Ladenkasse — passing it through to a global max
                    // would gap every book's Belegnummernkreis.
                    val belegNr = kassenbuchRepository.getNextBelegNr(shop, request.cashBookId)

                    // Only a FISCAL entry is TSE-signed, and only signing needs a terminal. Demanding
                    // one unconditionally locked adminApp out of the non-fiscal books entirely — its
                    // synthetic `admin-web-*` id can never be a terminal by design — over a clientId
                    // those NOT_APPLICABLE entries never use.
                    //
                    // The resolution mirrors KassenbuchRepositoryImpl.createEntry exactly (same
                    // `request.cashBookId`, same "null means the shop's fiscal Ladenkasse" rule), so
                    // it can only agree with the authoritative `header.cashBook.isFiscal` read below;
                    // the fiscal branch there re-asserts that agreement rather than trusting it. An
                    // id resolving to no book stays on the GUARDED path — createEntry rejects it
                    // moments later, and "unknown book" must never become the cheap way past a
                    // fiscal guard.
                    val targetBookIsFiscal = request.cashBookId?.let { CashBook.findById(it)?.isFiscal } ?: true
                    //
                    // Stage 4b: the fiscal branch signs under the CALLING station's own TSE client,
                    // so it must pass the same guard as an order or a bill — `requireSigningTerminal`
                    // now asserts that itself. The NON-fiscal branch stays entirely ungated, and
                    // deliberately: a Tresor or Nebenkasse is not an elektronisches
                    // Aufzeichnungssystem, §146a AO does not reach it, and nothing is signed.
                    //
                    // This is the third gate kind the parent spec §2.1 names — a FISCAL PRECONDITION,
                    // decided by the ROW being written (`targetBookIsFiscal`) rather than by the
                    // route. It is why this route cannot live in `TransactionalRoutes`: a static
                    // route set cannot express "gated only when the target cash book is fiscal".
                    val clientId =
                        if (targetBookIsFiscal) {
                            resolveSigningClientId(terminalRepository, deviceId, tseMode)
                        } else {
                            null
                        }

                    // Rule 8, server leg: a GV_TYP DSFinV-K pins to the non-taxable class discards the
                    // declared rate in the signed payload and the CSV; persist the effective rate too, so
                    // a client that still sends the "19" default (or an older build) cannot leave a 19 %
                    // line on a Geldtransit/Privateinlage/… behind. Normalised, not rejected: the value is
                    // meaningless for these types, not malicious, and rejecting would break old clients.
                    val headerGvTyp = KassenbuchDsfinvkMapping.getGvTyp(request.entryType, correctedEntryType)
                    val headerData =
                        request
                            .toKassenbuchEntryData(request.lines.first(), belegNr)
                            .let { it.copy(uStSatz = KassenbuchTaxClassifier.storedRate(headerGvTyp, it.uStSatz)) }
                            .copy(correctsHeaderId = correctsHeaderId, correctionRole = correctionRole)
                    val lineItemsData =
                        request.lines.map { line ->
                            request.toLineItemData(line).let { it.copy(ustSatz = KassenbuchTaxClassifier.storedRate(headerGvTyp, it.ustSatz)) }
                        }

                    // Round-4 TSER-1 (Anhang I p.119/120): the Bar tender is the SIGNED cash flow, not
                    // the document magnitude. `request.lines` is unsigned by construction —
                    // KassenbuchDto.validate() forces the Beleg total positive because the entry type
                    // carries the direction — while the buckets come from lineItemsData, whose
                    // vorzBetrag toLineItemData() has already sign-corrected. KassenbuchSigningPayload
                    // sums the SAME signed line data the buckets use:
                    //   Barausgabe 50 € @19 % -> Beleg^-50.00_0.00_0.00_0.00_0.00^-50.00:Bar
                    //   Privatentnahme 100 €  -> Beleg^0.00_0.00_0.00_0.00_-100.00^-100.00:Bar
                    // NON_CASH_PAYMENT lines are unreachable here (allowedInManualEntry = false).

                    // Operator-facing guards only — see KassenbuchEntryGuards for why corrections,
                    // the Z-Bon sweep and transfers are exempt. The Beleg date no longer decides the
                    // period (the booking time does), so the check needs neither the target book
                    // nor its last close.
                    val lateBookingReason =
                        if (belegAgeLimit == null) {
                            null
                        } else {
                            KassenbuchEntryGuards.parseBelegDatum(headerData.belegDatum)?.let { belegDate ->
                                KassenbuchEntryGuards.requireAcceptableBelegDatum(belegDate, belegAgeLimit, approvedLateBookingReason)
                            }
                        }

                    // F59 — the Kassenminus guard, under the advisory lock taken above, so the balance
                    // it reads includes every booking committed before this one and two concurrent
                    // withdrawals cannot both pass on it. Exempt: a Storno (REVERSAL) and the cash
                    // count's Kassendifferenz — see KassenminusGuard for why.
                    if (correctionRole != CorrectionRole.REVERSAL && !recordsCountedCash) {
                        requireCashCovers(shop, request.cashBookId, lineItemsData)
                    }

                    val header =
                        kassenbuchRepository.createEntry(
                            shop,
                            employeeEntity,
                            headerData.copy(lateBookingReason = lateBookingReason),
                            lineItemsData,
                        )
                    bookingHook?.afterCreate(header.id.value)
                    // A non-fiscal book (Tresor, Nebenkasse) is not an elektronisches
                    // Aufzeichnungssystem, so §146a does not reach it: no signature, and the
                    // row says so rather than sitting at PENDING forever.
                    val isFiscal = header.cashBook.isFiscal
                    header.tseSigningStatus =
                        if (isFiscal) TseSigningStatus.PENDING else TseSigningStatus.NOT_APPLICABLE

                    // Round-3 TSER-2 / round-7 C1 / D4: Kassenbeleg-V1 for a cash-moving entry,
                    // SonstigerVorgang for a balance snapshot — one definition, shared with the
                    // in-closing tip payout (F58) so both sign byte-identically.
                    val payload = KassenbuchSigningPayload.of(headerData, correctedEntryType, lineItemsData)

                    logger.info(
                        "KassenbuchServiceImpl.createSignedEntry: successfully created kassenbuch header {} with {} line items",
                        header.id.value,
                        header.lineItems.count(),
                    )

                    val responseDto = header.toKassenbuchEntryResponseDto()
                    val totalAmount = request.calculateTotalAmount()

                    // Mandatory: link the already-uploaded document to this header
                    val documentId =
                        request.documentId
                            ?: throw validationFailed("documentId is required")
                    if (!documents.documentExists(documentId)) {
                        throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            "Document not found: $documentId",
                        )
                    }
                    kassenbuchRepository.linkDocument(header.id.value, documentId)
                    logger.info(
                        "KassenbuchServiceImpl.createSignedEntry: linked uploaded document {} to KB header {}",
                        documentId,
                        header.id.value,
                    )

                    CreationResult(
                        headerId = header.id.value,
                        response =
                            KassenbuchDto.CreateKassenbuchEntryGroupResponseDto(
                                belegNr = belegNr,
                                totalGrossAmount = totalAmount,
                                entries = listOf(responseDto),
                                createdAt = Clock.System.now().toString(),
                            ),
                        tseSigningData =
                            if (isFiscal) {
                                TseSigningData(
                                    processData = payload.processData,
                                    processType = payload.processType,
                                    // Assert, never default: the terminal guard was skipped only
                                    // because the target book resolved non-fiscal. If the book the
                                    // repository actually wrote to is fiscal, the two resolutions
                                    // disagree and this entry would sign with no terminal behind it
                                    // — fail inside the transaction so nothing is committed.
                                    clientId =
                                        checkNotNull(clientId) {
                                            "Fiscal entry has no signing terminal clientId — cash book " +
                                                "${header.cashBook.id.value} resolved fiscal after the " +
                                                "terminal guard was skipped for it"
                                        },
                                    headerId = header.id.value,
                                    belegNr = belegNr,
                                )
                            } else {
                                null
                            },
                    )
                }
            }

        // Phase 2: TSE signing (not retried — prevents orphaned TSE records on rollback).
        // Skipped entirely for a non-fiscal book's entry — tseSigningData is null and the
        // header was already stamped NOT_APPLICABLE in Phase 1.
        val signingData = phase1.tseSigningData
        if (signingData != null) {
            logger.info(
                "KassenbuchServiceImpl.createSignedEntry: signing Kassenbuch entry with TSE, headerId={}, belegNr={}",
                signingData.headerId,
                signingData.belegNr,
            )
            tseSigner.sign(
                headerId = signingData.headerId,
                belegNr = signingData.belegNr,
                processData = signingData.processData,
                processType = signingData.processType,
                clientId = signingData.clientId,
            )
        }
        return phase1.headerId to phase1.response
    }

    /**
     * Phase 2 of [createSignedEntry]: signs the already-committed entry with the TSE.
     *
     * On TSE failure the configured [TseFailureBehavior] decides: FAIL throws
     * [PosErrorCode.TSE_SIGNING_FAILED]; QUEUE enqueues the entry for later signing
     * (Nachsignieren). Not retried — the entry is already committed.
     *
     * `internal` (not `private`): [org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl]
     * reuses this exact signing path for a transfer's fiscal leg rather than duplicating the
     * TSE-calling logic — same failure-mode handling (FAIL/QUEUE), same PENDING-strand-on-
     * queue-failure fallback.
     */
    override suspend fun getCurrentBalance(cashBookId: Long?): String =
        dbQuery {
            val shop =
                shopInfoRepository.findById(1L)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Default shop not found",
                    )
            requireBookOfShop(shop, cashBookId)
            kassenbuchRepository.getCurrentBalance(shop, cashBookId).toDecimalString()
        }

    /**
     * The Kassenminus guard of an ordinary booking: when the signed cash flow of [lineItems] is an
     * outflow, the target book (null = the shop's fiscal Ladenkasse, exactly as the repository
     * resolves it) must hold at least that much — see [KassenminusGuard]. An id naming no book is
     * left to `createEntry`, which rejects it moments later with its own message.
     */
    private fun requireCashCovers(
        shop: ShopInfo,
        cashBookId: Long?,
        lineItems: List<KassenbuchDto.LineItemData>,
    ) {
        val outflow = signedCashFlow(lineItems).negate()
        if (outflow.signum() <= 0) return
        val book = (if (cashBookId == null) cashBookRepository.fiscalBookFor(shop) else CashBook.findById(cashBookId)) ?: return
        KassenminusGuard.requireCovered(kassenbuchRepository, book, outflow, KassenminusGuard.Takings.UNBOOKED_IN_DRAWER)
    }

    /**
     * Pre-check of a correction against the Kassenminus guard: after the Storno of [original] gives
     * back (or takes away) its cash flow, the re-entry's outflow must still be covered. Read-only, no
     * lock — the binding check runs when the re-entry is created. Needs an ambient transaction.
     */
    private fun requireCorrectionCovers(
        original: KassenbuchHeader,
        correctedEntry: KassenbuchDto.CreateKassenbuchEntryRequestDto,
    ) {
        val documentTotal =
            correctedEntry.lines
                .mapNotNull { it.amount.toBigDecimalOrNull() }
                .fold(BigDecimal.ZERO, BigDecimal::add)
        val reEntryFlow = if (correctedEntry.entryType.isIncoming) documentTotal else documentTotal.negate()
        if (reEntryFlow.signum() >= 0) return
        val book = original.cashBook
        val afterStorno =
            KassenminusGuard.available(kassenbuchRepository, book, KassenminusGuard.Takings.UNBOOKED_IN_DRAWER).total -
                original.totalAmount
        if (afterStorno + reEntryFlow < BigDecimal.ZERO) {
            throw KassenminusGuard.insufficient(book, afterStorno, reEntryFlow.negate())
        }
    }

    /** Σ of the lines' signed cash flow (`vorzBetrag`, already sign-corrected by `toLineItemData`). */
    private fun signedCashFlow(lineItems: List<KassenbuchDto.LineItemData>): BigDecimal =
        lineItems.fold(BigDecimal.ZERO) { acc, line -> acc + BigDecimal(line.vorzBetrag) }

    override suspend fun getAvailableCash(cashBookId: Long?): KassenbuchDto.AvailableCashDto =
        dbQuery {
            val shop =
                shopInfoRepository.findById(1L)
                    ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Default shop not found")
            requireBookOfShop(shop, cashBookId)
            val book = if (cashBookId == null) cashBookRepository.fiscalBookFor(shop) else cashBookRepository.findById(cashBookId)!!
            val available = KassenminusGuard.available(kassenbuchRepository, book, KassenminusGuard.Takings.UNBOOKED_IN_DRAWER)
            KassenbuchDto.AvailableCashDto(
                cashBookId = book.id.value,
                bookBalance = available.bookBalance.toDecimalString(),
                unbookedCashTakings = available.unbookedCashTakings.toDecimalString(),
                availableCash = available.total.toDecimalString(),
            )
        }

    /**
     * Refuses a [cashBookId] that names no cash book of [shop] (404). `null` is the fiscal-book
     * default and always passes.
     *
     * Without it an unknown id read as a real, empty book: the repository filters by shop + book,
     * matches no rows and answers ZERO — "0,00" for a book that does not exist (checklist 21.2). A
     * foreign shop's book is refused the same way rather than reported as empty, so the id alone can
     * no longer probe which books exist elsewhere.
     */
    private fun requireBookOfShop(
        shop: ShopInfo,
        cashBookId: Long?,
    ) {
        if (cashBookId == null) return
        val book = cashBookRepository.findById(cashBookId)
        if (book == null || book.shop.id.value != shop.id.value) {
            throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Cash book with id=$cashBookId not found",
            )
        }
    }

    override suspend fun getEntryHistory(
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean,
        cashBookId: Long?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val shop =
                shopInfoRepository.findById(1L)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Default shop not found",
                    )
            val headers =
                kassenbuchRepository.getEntriesByDateRange(
                    shop,
                    startDate,
                    endDate,
                    unlinkedOnly,
                    cashBookIds = cashBookId?.let { listOf(it) },
                )
            // Keep the repository's order (created_at DESC): Beleg-Nr repeat per cash book, so
            // sorting by it scrambled the date order whenever two books were listed together (FO-43).
            headers.map { it.toGroupedSummaryDto(currency) }
        }
    }

    override suspend fun getRecentEntries(
        limit: Int,
        cashBookId: Long?,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val shop =
                shopInfoRepository.findById(1L)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Default shop not found",
                    )
            kassenbuchRepository
                .getRecentEntries(shop, limit, cashBookId)
                .map { it.toGroupedSummaryDto(currency) }
        }
    }

    override suspend fun searchEntries(
        query: String,
        cashBookIds: List<Long>?,
        limit: Int,
    ): List<KassenbuchDto.KassenbuchGroupedSummaryDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val shop =
                shopInfoRepository.findById(1L)
                    ?: throw PosException(
                        PosErrorCode.RESOURCE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Default shop not found",
                    )
            kassenbuchRepository
                .searchEntries(shop, query, cashBookIds, limit)
                .map { it.toGroupedSummaryDto(currency) }
        }
    }

    override suspend fun getEntryById(entryId: Long): KassenbuchDto.KassenbuchEntryResponseDto =
        dbQuery {
            val header = kassenbuchRepository.getEntryById(entryId) ?: throw entryNotFound(entryId)
            header.toKassenbuchEntryResponseDto()
        }

    override suspend fun getGroupedEntryDetail(headerId: Long): KassenbuchDto.KassenbuchGroupedDetailDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val header = kassenbuchRepository.getEntryById(headerId) ?: throw entryNotFound(headerId)
            header
                .toGroupedDetailDto(
                    currency = currency,
                    linkedDocument = documents.linkedDocumentOf(header),
                    alreadyReversed = kassenbuchRepository.hasReversal(headerId),
                ).copy(tseSigningNote = lateSigningRepository.noteFor(TseEntityType.KASSENBUCH, headerId.toString()))
        }
    }

    override suspend fun linkDocument(
        employeeId: String,
        deviceId: String,
        headerId: Long,
        documentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        ExceptionConverter.catchingThrows(
            context = "linkDocument(headerId=$headerId, documentId=$documentId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "LINK_CASH_BOOK_DOCUMENT",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = ::namesTheEntry,
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val header =
                    try {
                        kassenbuchRepository.linkDocument(headerId, documentId)
                    } catch (e: IllegalArgumentException) {
                        throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            e.message ?: "Entity not found while linking document",
                        )
                    }
                logger.info(
                    "KassenbuchServiceImpl.linkDocument: successfully linked document {} to kassenbuch header {}",
                    documentId,
                    headerId,
                )
                header.toGroupedDetailDto(currency, documents.linkedDocumentOf(header))
            }
        }

    override suspend fun replaceDocument(
        employeeId: String,
        deviceId: String,
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        ExceptionConverter.catchingThrows(
            context = "replaceDocument(headerId=$headerId, newDocumentId=$newDocumentId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "REPLACE_CASH_BOOK_DOCUMENT",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = ::namesTheEntry,
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val existingHeader =
                    kassenbuchRepository.getEntryById(headerId)
                        ?: throw entryNotFound(headerId)

                if (existingHeader.isLocked) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.UnprocessableEntity,
                        "Cannot replace document on locked kassenbuch entry $headerId",
                    )
                }

                if (existingHeader.correctionRole != null) {
                    throw correctionDocumentImmutable(headerId)
                }

                val updatedHeader =
                    try {
                        kassenbuchRepository.replaceDocument(headerId, newDocumentId)
                    } catch (e: IllegalStateException) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            e.message ?: "replaceDocument rejected for kassenbuch entry $headerId",
                        )
                    } catch (e: IllegalArgumentException) {
                        throw PosException(
                            PosErrorCode.RESOURCE_NOT_FOUND,
                            HttpStatusCode.NotFound,
                            e.message ?: "Document not found",
                        )
                    }
                logger.info(
                    "KassenbuchServiceImpl.replaceDocument: replaced document on header {} with document {}",
                    headerId,
                    newDocumentId,
                )
                updatedHeader.toGroupedDetailDto(currency, documents.linkedDocumentOf(updatedHeader))
            }
        }

    @OptIn(ExperimentalTime::class)
    override suspend fun createCorrection(
        employeeId: String,
        deviceId: String,
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        ExceptionConverter.catchingThrows(
            context = "createCorrection(headerId=$headerId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "CORRECT_CASH_BOOK_ENTRY",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = ::namesTheEntry,
        ) {
            if (request.reason.isBlank()) {
                throw validationFailed("A reason is required for a correction")
            }

            // Snapshot original + guards (read-only transaction).
            data class OriginalSnapshot(
                val belegNr: String,
                val belegDatum: String,
                val entryTypeIsIncoming: Boolean,
                val waehrung: String,
                val lines: List<KassenbuchDto.EntryLineDto>,
                val linkedDocumentId: Long?,
                /**
                 * The book the original sits in — carried so BOTH correction legs land there.
                 *
                 * Omitting it is not neutral: `createSignedEntry` reads "null cashBookId" as the
                 * shop's fiscal Ladenkasse, so a correction of a non-fiscal (Tresor) entry booked
                 * its reversal into the FISCAL book while the original stayed un-reversed in the
                 * Tresor — both books wrong, and a phantom credit in the one that feeds DSFinV-K.
                 */
                val cashBookId: Long,
            )
            val original =
                dbQuery {
                    val h = kassenbuchRepository.getEntryById(headerId) ?: throw entryNotFound(headerId)
                    // A transfer is ONE physical cash movement recorded in two books. Stornoing a
                    // single leg would leave the two books permanently disagreeing about cash that
                    // actually moved, with no repair path — the whole pair must be reversed together
                    // via CashBookTransferService.reverseTransfer instead.
                    if (h.transferId != null) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "Entry $headerId is one leg of transfer ${h.transferId}. " +
                                "Use POST /api/v1/kassenbuch/transfers/{transferId}/reverse instead.",
                        )
                    }
                    // A pure Storno (REVERSAL) is the mechanical negation of its target and is never
                    // corrected on its own. A corrected re-entry (RE_ENTRY), however, is a real booking
                    // that can itself be wrong — correcting it chains another Storno + re-entry.
                    if (h.correctionRole == CorrectionRole.REVERSAL) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "Entry $headerId is a Storno (reversal) and cannot be corrected",
                        )
                    }
                    if (kassenbuchRepository.hasReversal(headerId)) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "Entry $headerId has already been reversed",
                        )
                    }
                    if (h.isZbonAggregate || h.lineItems.any { it.kind == KassenbuchLineKind.NON_CASH_PAYMENT }) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "Entry $headerId is a Z-Bon aggregate and cannot be corrected",
                        )
                    }
                    // A tip payout is stornoed through its OWN action, not here. This route is Storno
                    // PLUS a mandatory free-form re-entry; a tip payout's re-entry would have to pass
                    // the payout guards (remaining amount, the employee link in employee_tip_payout),
                    // which an operator-typed CreateKassenbuchEntryRequestDto cannot express — and
                    // without the link row the freed amount would stay consumed. The dedicated
                    // action books the SAME mechanism (CORRECTION_IN, correctsHeader, REVERSAL) and
                    // the operator pays again through the payout. Refused up front, before an
                    // Eigenbeleg is generated, rather than half-done.
                    if (h.lineItems.any { it.kind == KassenbuchLineKind.EMPLOYEE_TIP }) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "Entry $headerId is an employee-tip payout. Storno it from the Z-Bon's tip section " +
                                "(POST /api/z-bon/{id}/tip-payouts/$headerId/reverse) and pay the correct amount again.",
                        )
                    }
                    // F59 — fail fast on the Kassenminus guard BEFORE the Storno is booked. The Storno
                    // itself is exempt, but the re-entry is an ordinary booking and is guarded under the
                    // lock when it is created; refusing it only then would leave the Storno booked and
                    // the correction half-done. The binding check stays the one under the lock.
                    requireCorrectionCovers(h, request.correctedEntry)
                    // Reconstruct DOCUMENT-sign lines (spec §1.3): the reversal request goes
                    // back through createSignedEntry, whose opposite entry type flips them
                    // to inverted cash-flow signs — amounts are never negated here.
                    OriginalSnapshot(
                        belegNr = h.belegNr,
                        belegDatum = h.belegDatum,
                        entryTypeIsIncoming = h.entryType.isIncoming,
                        waehrung = h.waehrung,
                        lines =
                            h.lineItems.sortedBy { it.lineNumber }.map { li ->
                                KassenbuchDto.EntryLineDto(
                                    kind = li.kind,
                                    vatRate = li.ustSatz,
                                    // Document sign = stored cash-flow sign flipped back for non-incoming, exact.
                                    amount = (if (h.entryType.isIncoming) li.vorzBetrag else li.vorzBetrag.negate()).toPlainString(),
                                    belegtext = li.belegtext,
                                )
                            },
                        linkedDocumentId = h.linkedDocumentId,
                        cashBookId = h.cashBook.id.value,
                    )
                }

            val employeeUuid = UUID.fromString(employeeId)

            // 1) Generate + persist the Storno-Eigenbeleg, get its documentId.
            val reversalType =
                if (original.entryTypeIsIncoming) {
                    KassenbuchEntryType.CORRECTION_OUT
                } else {
                    KassenbuchEntryType.CORRECTION_IN
                }
            val reversalGross =
                original.lines
                    .mapNotNull { it.amount.toBigDecimalOrNull() }
                    .fold(BigDecimal.ZERO, BigDecimal::add)
            val signPrefix = if (original.entryTypeIsIncoming) "-" else "+"
            // createEigenbeleg persists a Document row + maps it to a DTO, so it must run
            // inside a transaction (the caller provides it, same convention as the Z-Bon PDF path).
            val eigenbeleg =
                dbQuery {
                    documents.createEigenbeleg(
                        belegNr = "STORNO-${original.belegNr}",
                        date = original.belegDatum,
                        entryType = reversalType.displayName,
                        amount = "$signPrefix${formatGerman(reversalGross)} ${original.waehrung}",
                        description = "Storno zu Beleg-Nr. ${original.belegNr}",
                        employeeId = employeeUuid,
                        correctsBelegNr = original.belegNr,
                        correctsDate = original.belegDatum,
                        reason = request.reason,
                    )
                }

            // 2) Create the reversal entry (REVERSAL) linked to the Storno-Eigenbeleg.
            val reversalRequest =
                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                    entryType = reversalType,
                    lines = original.lines,
                    waehrung = original.waehrung,
                    belegtext = "Storno zu ${original.belegNr}".take(60),
                    nachricht = request.reason.take(120),
                    documentId = eigenbeleg,
                    // The book the original sits in, never the default. `createSignedEntry` reads a
                    // null cashBookId as the shop's fiscal Ladenkasse, so leaving it out sent the
                    // reversal of a Tresor entry into the fiscal book.
                    cashBookId = original.cashBookId,
                )
            createSignedEntry(employeeId, deviceId, reversalRequest, headerId, CorrectionRole.REVERSAL)

            // 3) Resolve the corrected entry's document: replacement or a copy of the original's.
            val correctedDocumentId =
                request.replacementDocumentId
                    ?: original.linkedDocumentId?.let { documents.copyDocument(it, employeeUuid) }
                    ?: throw validationFailed("Original has no document to copy and no replacement was provided")

            // 4) Create the corrected re-entry (RE_ENTRY).
            //
            // Pinned to the original's book too. A correction restates one booking in the book it
            // was made in; it is not a way to move money between books, and a caller that omitted
            // the id (or named a different one) would otherwise split the pair across two books —
            // the same defect the reversal leg had, from the other side.
            val correctedRequest =
                request.correctedEntry.copy(
                    documentId = correctedDocumentId,
                    cashBookId = original.cashBookId,
                )
            val (correctedHeaderId, _) =
                createSignedEntry(
                    employeeId,
                    deviceId,
                    correctedRequest,
                    headerId,
                    CorrectionRole.RE_ENTRY,
                )

            val currency = configManager.getDefaultCurrency()
            dbQuery {
                kassenbuchRepository.getEntryById(correctedHeaderId)!!.let { corrected ->
                    corrected.toGroupedDetailDto(currency, documents.linkedDocumentOf(corrected))
                }
            }
        }

    override suspend fun recordCashCount(
        employeeId: String,
        deviceId: String,
        request: KassenbuchDto.CashCountRequestDto,
    ): KassenbuchDto.CashCountResponseDto {
        val counted = CashCountDifference.parseCounted(request.countedAmount)

        // cashBookId = null means the shop's fiscal Ladenkasse throughout: getCurrentBalance,
        // getNextBelegNr and createSignedEntry all resolve null the same way, so the balance, the
        // Beleg number and the entry can only ever refer to the same book.
        val shop =
            dbQuery { shopInfoRepository.findById(1L) }
                ?: throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "Default shop not found",
                )
        // The drawer holds the book balance PLUS the cash of every bill the book does not show
        // yet — cash sales reach the Kassenbuch only as the closing's CASH_SALE aggregate, booked
        // AFTER this count. Comparing against the bare book signed the day's takings as a phantom
        // CASH_SURPLUS on every honest closing (2026-09-11 rehearsal), and the closing's Geldtransit
        // then carried takings plus phantom to the safe. Same derivation as the closing's aggregate
        // — DrawerCashDerivation — so count and closing cannot disagree about the drawer.
        // (The count taken IN the closing dialog is booked inside the closing instead — see
        // ClosingCashCountBooker.)
        val expectation = dbQuery { cashCountExpectation(shop) }
        val bookBalance = expectation.bookBalance
        val expectedCash = expectation.expectedCash
        val difference = counted.subtract(expectedCash)

        // A matching drawer is the normal case: record nothing and consume no belegNr.
        if (difference.signum() == 0) {
            return KassenbuchDto.CashCountResponseDto(
                bookBalance = bookBalance.toDecimalString(),
                countedAmount = counted.toDecimalString(),
                difference = BigDecimal.ZERO.toDecimalString(),
                unbookedCashTakings = expectation.unbookedCashTakings.toDecimalString(),
                expectedCash = expectedCash.toDecimalString(),
            )
        }

        // Non-consuming max+1 read, so createSignedEntry derives the same number moments later and
        // the Eigenbeleg carries the Beleg number the entry actually gets.
        val belegNr = dbQuery { kassenbuchRepository.getNextBelegNr(shop, null) }
        val belegDatum = formatBelegDatum(currentTimeStamp().date)
        val description =
            "Kassenzählung zum $belegDatum: gezählt ${formatGerman(counted)} EUR, " +
                "Soll ${formatGerman(expectedCash)} EUR (Buch ${formatGerman(bookBalance)} + " +
                "unverbuchte Bareinnahmen ${formatGerman(expectation.unbookedCashTakings)})"

        // createEigenbeleg persists a Document row, so it needs an ambient transaction —
        // same convention as createCorrection.
        val eigenbeleg =
            dbQuery {
                CashCountDifference.eigenbeleg(
                    documents = documents,
                    belegNr = belegNr,
                    belegDatum = belegDatum,
                    difference = difference,
                    description = description,
                    operatorId = UUID.fromString(employeeId),
                )
            }
        val entryRequest = CashCountDifference.request(difference, belegDatum, description, eigenbeleg)
        // A Kassendifferenz records what was counted — reality — so it is exempt from the Kassenminus guard.
        val (headerId, response) = createSignedEntry(employeeId, deviceId, entryRequest, recordsCountedCash = true)

        return KassenbuchDto.CashCountResponseDto(
            bookBalance = bookBalance.toDecimalString(),
            countedAmount = counted.toDecimalString(),
            difference = difference.toDecimalString(),
            entryId = headerId,
            belegNr = response.belegNr,
            unbookedCashTakings = expectation.unbookedCashTakings.toDecimalString(),
            expectedCash = expectedCash.toDecimalString(),
        )
    }

    override suspend fun payOutEmployeeTip(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        when (request.method) {
            TipPayoutMethod.CASH -> payOutEmployeeTipInCash(employeeId, deviceId, zBonId, request)
            TipPayoutMethod.BANK_PAYROLL -> settleEmployeeTipViaBankPayroll(employeeId, deviceId, zBonId, request)
        }

    /**
     * F59, option C: the tip was paid by bank transfer or with the payroll. Only the
     * `employee_tip_payout` row is written — no Kassenbuch entry, so no Eigenbeleg, no TSE signature,
     * no DSFinV-K row and no DATEV booking (the tax adviser books the bank/payroll payment against
     * the transit account the closing credited). Taken under `pg_advisory_xact_lock(1)`, the lock a
     * cash payout takes, so the two methods cannot both consume the same remainder. Audited.
     */
    private suspend fun settleEmployeeTipViaBankPayroll(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        ExceptionConverter.catchingThrows(
            context = "settleEmployeeTipViaBankPayroll(zBonId=$zBonId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "SETTLE_EMPLOYEE_TIP_BANK_PAYROLL",
            entityType = "EMPLOYEE_TIP_PAYOUT",
            deviceId = deviceId,
            auditEntity = ::tipSettlementAuditEntity,
            auditState = { it.toString() },
        ) {
            val payout = EmployeeTipPayoutBooking.parse(zBonId, request)
            dbQuery {
                TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")
                val payoutId = payout.recordBankPayrollSettlement(createdBy = UUID.fromString(employeeId))
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = null,
                    belegNr = null,
                    status = payout.currentStatus(),
                    payoutId = payoutId,
                    method = TipPayoutMethod.BANK_PAYROLL,
                )
            }
        }

    override suspend fun reverseEmployeeTipSettlement(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        payoutId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        ExceptionConverter.catchingThrows(
            context = "reverseEmployeeTipSettlement(zBonId=$zBonId, payoutId=$payoutId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "REVERSE_EMPLOYEE_TIP_SETTLEMENT",
            entityType = "EMPLOYEE_TIP_PAYOUT",
            deviceId = deviceId,
            auditEntity = ::tipSettlementAuditEntity,
            failedEntityId = payoutId.toString(),
            auditState = { it.toString() },
        ) {
            if (request.reason.isBlank()) throw validationFailed("A reason is required to storno a tip settlement")
            val reversal = EmployeeTipSettlementReversal(zBonId, payoutId)
            dbQuery {
                TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")
                val (reversalId, settlement) = reversal.reverse(createdBy = UUID.fromString(employeeId))
                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = null,
                    belegNr = null,
                    status = reversal.currentStatus(settlement.employeeId),
                    payoutId = reversalId,
                    method = TipPayoutMethod.BANK_PAYROLL,
                )
            }
        }

    /** The audit row of a bank/payroll settlement or its storno: the `employee_tip_payout` row. */
    private fun tipSettlementAuditEntity(response: ZBonDto.TipPayoutResponseDto): AuditEntityRef =
        AuditEntityRef(
            id = response.payoutId.toString(),
            name = "Trinkgeld Bank/Lohn ${response.status.employeeName}",
        )

    private suspend fun payOutEmployeeTipInCash(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        ExceptionConverter
            .catchingThrows(
                context = "payOutEmployeeTip(zBonId=$zBonId, employeeId=$employeeId)",
                employeeId = employeeId,
                operation = "PAY_OUT_EMPLOYEE_TIP",
                entityType = "KASSENBUCH",
                deviceId = deviceId,
                auditEntity = ::tipPayoutAuditEntity,
                auditState = { (response, _) -> response.toString() },
            ) {
                val payout = EmployeeTipPayoutBooking.parse(zBonId, request)

                // Checked before anything is generated, so a refused payout leaves no orphan Eigenbeleg.
                // Not the binding check — that one runs again under the lock inside createSignedEntry.
                val recipientName =
                    dbQuery {
                        payout.requirePayable()
                        Employee.findById(payout.employeeId)!!.zBonDisplayName()
                    }
                val shop =
                    dbQuery { shopInfoRepository.findById(1L) }
                        ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Default shop not found")

                val belegNr = dbQuery { kassenbuchRepository.getNextBelegNr(shop, null) }
                val belegDatum = formatBelegDatum(currentTimeStamp().date)
                val description = "Auszahlung Trinkgeld (Arbeitnehmer) an $recipientName aus Z-Bon-ID $zBonId"
                val eigenbeleg =
                    dbQuery {
                        EmployeeTipPayoutEntry.eigenbeleg(
                            documents = documents,
                            belegNr = belegNr,
                            belegDatum = belegDatum,
                            amount = payout.amount,
                            description = description,
                            operatorId = UUID.fromString(employeeId),
                        )
                    }

                // The same entry the in-closing payout books (F58) — see EmployeeTipPayoutEntry.
                val entryRequest =
                    EmployeeTipPayoutEntry.request(
                        amount = payout.amount,
                        recipientName = recipientName,
                        belegDatum = belegDatum,
                        description = description,
                        documentId = eigenbeleg,
                    )
                val (headerId, response) =
                    createSignedEntry(
                        employeeId,
                        deviceId,
                        entryRequest,
                        systemLineKinds = EmployeeTipPayoutEntry.systemLineKinds,
                        bookingHook = payout.hook(createdBy = UUID.fromString(employeeId)),
                    )

                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = headerId,
                    belegNr = response.belegNr,
                    status = dbQuery { payout.currentStatus() },
                    payoutId = dbQuery { payoutIdOf(headerId) },
                ) to signedTseTransactionId(headerId)
            }.first

    override suspend fun reverseEmployeeTipPayout(
        employeeId: String,
        deviceId: String,
        zBonId: Long,
        payoutHeaderId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto =
        ExceptionConverter
            .catchingThrows(
                context = "reverseEmployeeTipPayout(zBonId=$zBonId, payoutHeaderId=$payoutHeaderId, employeeId=$employeeId)",
                employeeId = employeeId,
                operation = "REVERSE_EMPLOYEE_TIP_PAYOUT",
                entityType = "KASSENBUCH",
                deviceId = deviceId,
                auditEntity = ::tipPayoutAuditEntity,
                auditState = { (response, _) -> response.toString() },
            ) {
                if (request.reason.isBlank()) throw validationFailed("A reason is required to storno a tip payout")
                val reversal = EmployeeTipPayoutReversal(zBonId, payoutHeaderId)

                // Checked before the Eigenbeleg is generated; re-checked under the lock in the hook.
                data class Original(
                    val payout: EmployeeTipPayoutReversal.Payout,
                    val belegNr: String,
                    val belegDatum: String,
                    val waehrung: String,
                    val recipientName: String,
                )
                val original =
                    dbQuery {
                        val payout = reversal.requirePayout()
                        val header = KassenbuchHeader.findById(payoutHeaderId) ?: throw entryNotFound(payoutHeaderId)
                        Original(
                            payout = payout,
                            belegNr = header.belegNr,
                            belegDatum = header.belegDatum,
                            waehrung = header.waehrung,
                            recipientName = Employee.findById(payout.employeeId)!!.zBonDisplayName(),
                        )
                    }

                val eigenbeleg =
                    dbQuery {
                        documents.createEigenbeleg(
                            belegNr = "STORNO-${original.belegNr}",
                            date = original.belegDatum,
                            entryType = KassenbuchEntryType.CORRECTION_IN.displayName,
                            amount = "+${formatGerman(original.payout.amount)} ${original.waehrung}",
                            description = "Storno Trinkgeld-Auszahlung an ${original.recipientName}",
                            employeeId = UUID.fromString(employeeId),
                            correctsBelegNr = original.belegNr,
                            correctsDate = original.belegDatum,
                            reason = request.reason,
                        )
                    }

                // The same mechanism createCorrection uses for its Storno leg: the opposite entry type of
                // the withdrawal (CORRECTION_IN, cash back IN), linked to the payout as REVERSAL, in the
                // fiscal book, signed. Its one line keeps the kind EMPLOYEE_TIP, so DSFinV-K reads it as
                // TrinkgeldAN (+amount, key 5) and DATEV books it on the employee-tip account again.
                val entryRequest =
                    KassenbuchDto.CreateKassenbuchEntryRequestDto(
                        entryType = KassenbuchEntryType.CORRECTION_IN,
                        lines =
                            listOf(
                                KassenbuchDto.EntryLineDto(
                                    kind = KassenbuchLineKind.EMPLOYEE_TIP,
                                    vatRate = null,
                                    amount = original.payout.amount.toPlainString(),
                                    belegtext = null,
                                ),
                            ),
                        waehrung = original.waehrung,
                        belegtext = "Storno zu ${original.belegNr}".take(60),
                        nachricht = request.reason.take(120),
                        documentId = eigenbeleg,
                    )
                val (headerId, response) =
                    createSignedEntry(
                        employeeId,
                        deviceId,
                        entryRequest,
                        correctsHeaderId = payoutHeaderId,
                        correctionRole = CorrectionRole.REVERSAL,
                        systemLineKinds = setOf(KassenbuchLineKind.EMPLOYEE_TIP),
                        bookingHook = reversal.hook(createdBy = UUID.fromString(employeeId)),
                    )

                ZBonDto.TipPayoutResponseDto(
                    kassenbuchHeaderId = headerId,
                    belegNr = response.belegNr,
                    status = dbQuery { reversal.currentStatus(original.payout.employeeId) },
                    payoutId = dbQuery { payoutIdOf(headerId) },
                ) to signedTseTransactionId(headerId)
            }.first

    /**
     * The audit row of a tip payout or its storno: the Kassenbuch header, its Beleg number, and —
     * F54 #4 — the `tse_transaction` that signed it as `fiscal_record_id`, the same reference the
     * TSE's own audit rows (`TSE_QUEUE_SIGNED`, `TSE_TRANSACTION_SUPERSEDED`) carry. Null while the
     * entry is still queued for Nachsignieren; `TSE_QUEUE_SIGNED` then records the reference.
     */
    private fun tipPayoutAuditEntity(booked: Pair<ZBonDto.TipPayoutResponseDto, String?>): AuditEntityRef =
        AuditEntityRef(
            id = booked.first.kassenbuchHeaderId.toString(),
            name = booked.first.belegNr,
            fiscalRecordId = booked.second,
        )

    /** The `employee_tip_payout` row linked to Kassenbuch entry [headerId]. Needs a transaction. */
    private fun payoutIdOf(headerId: Long): Long? =
        EmployeeTipPayoutTable
            .selectAll()
            .where { EmployeeTipPayoutTable.kassenbuchHeaderId eq headerId }
            .firstOrNull()
            ?.get(EmployeeTipPayoutTable.id)
            ?.value

    /** The id of the `tse_transaction` linked to [headerId] once signed, or null while unsigned. */
    private suspend fun signedTseTransactionId(headerId: Long): String? =
        dbQuery {
            KassenbuchHeader
                .findById(headerId)
                ?.tseTransaction
                ?.id
                ?.value
                ?.toString()
        }

    override suspend fun getCashCountExpectation(): KassenbuchDto.CashCountExpectationDto {
        val shop =
            dbQuery { shopInfoRepository.findById(1L) }
                ?: throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "Default shop not found",
                )
        val expectation = dbQuery { cashCountExpectation(shop) }
        return KassenbuchDto.CashCountExpectationDto(
            bookBalance = expectation.bookBalance.toDecimalString(),
            unbookedCashTakings = expectation.unbookedCashTakings.toDecimalString(),
            expectedCash = expectation.expectedCash.toDecimalString(),
        )
    }

    /** What the fiscal Ladenkasse should physically hold right now. Requires an ambient transaction. */
    private class CashCountExpectation(
        val bookBalance: BigDecimal,
        val unbookedCashTakings: BigDecimal,
    ) {
        val expectedCash: BigDecimal get() = bookBalance + unbookedCashTakings
    }

    /**
     * Book balance and unbooked cash takings read in ONE transaction, so a bill committing between
     * the two reads cannot land in neither or both figures.
     */
    private fun cashCountExpectation(shop: ShopInfo): CashCountExpectation =
        CashCountExpectation(
            bookBalance = kassenbuchRepository.getCurrentBalance(shop, null),
            unbookedCashTakings = DrawerCashDerivation.unbookedCashTakings(),
        )

    @Suppress("DefaultLocale")
    private fun formatBelegDatum(date: kotlinx.datetime.LocalDate): String = String.format("%02d.%02d.%04d", date.dayOfMonth, date.monthNumber, date.year)

    override suspend fun getFiscalBookBalanceAt(at: kotlinx.datetime.LocalDateTime): java.math.BigDecimal =
        dbQuery {
            kassenbuchRepository.getBalanceAt(at, fiscalBookId())
        }

    override suspend fun findFiscalBookEntriesInWindow(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
    ): List<KassenbuchHeader> =
        dbQuery {
            kassenbuchRepository.findEntriesInWindow(start, end, fiscalBookId())
        }

    /** The default shop's fiscal Ladenkasse. Needs an ambient transaction. */
    private fun fiscalBookId(): Long {
        val shop =
            shopInfoRepository.findById(1L)
                ?: throw PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Default shop not found")
        return cashBookRepository.fiscalBookFor(shop).id.value
    }

    override suspend fun unlinkDocument(
        employeeId: String,
        deviceId: String,
        headerId: Long,
    ): KassenbuchDto.KassenbuchGroupedDetailDto =
        ExceptionConverter.catchingThrows(
            context = "unlinkDocument(headerId=$headerId, employeeId=$employeeId)",
            employeeId = employeeId,
            operation = "UNLINK_CASH_BOOK_DOCUMENT",
            entityType = "KASSENBUCH",
            deviceId = deviceId,
            auditEntity = ::namesTheEntry,
        ) {
            val currency = configManager.getDefaultCurrency()
            dbQuery {
                val existingHeader =
                    kassenbuchRepository.getEntryById(headerId)
                        ?: throw entryNotFound(headerId)
                if (existingHeader.correctionRole != null) {
                    throw correctionDocumentImmutable(headerId)
                }

                val header =
                    try {
                        kassenbuchRepository.unlinkDocument(headerId)
                    } catch (e: IllegalArgumentException) {
                        throw entryNotFound(headerId)
                    }
                logger.info(
                    "KassenbuchServiceImpl.unlinkDocument: successfully unlinked document from kassenbuch header {}",
                    headerId,
                )
                header.toGroupedDetailDto(currency, documents.linkedDocumentOf(header))
            }
        }

    /**
     * Names the cash-book ENTRY a document-link operation acted on — not the document.
     *
     * `entityType` on all three of `linkDocument`, `replaceDocument` and `unlinkDocument` says
     * KASSENBUCH, and the document id is already carried in each call's `context`. Both fields are
     * non-null on the grouped detail DTO, so this never declines. Shared rather than repeated so the
     * reasoning sits once instead of three times, hundreds of lines apart.
     */
    private fun namesTheEntry(detail: KassenbuchDto.KassenbuchGroupedDetailDto) = AuditEntityRef(id = detail.headerId.toString(), name = detail.belegNr)

    private fun correctionDocumentImmutable(id: Long) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.UnprocessableEntity,
            "Cannot unlink or replace the document of correction entry $id",
        )

    private fun entryNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Kassenbuch header with id=$id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    /**
     * Round-3 KABS-3 (Anhang C p. 56) balance-snapshot creation guard. Must be called INSIDE the
     * creation transaction so the balance / prior-closing reads are consistent with the insert.
     *
     * - CLOSING_BALANCE is rejected outright: a count snapshot is the Kassensturz/DifferenzSollIst
     *   flow's job. Booking it here chains its amount into currentBalance as if cash moved, which
     *   breaks Kassensturzfähigkeit and exports a phantom second "Anfangsbestand".
     * - OPENING_BALANCE is only a genuine Anfangsbestand (booked as BON_TYP „Beleg"/Bar per Anhang
     *   C p. 56) on a virgin book: the running cash balance is exactly zero AND no prior ZBon
     *   closing exists for the shop. Any later "opening" is a Wechselgeld top-up (Geldtransit) or a
     *   count, not the initial fill.
     */
    private fun guardBalanceSnapshotEntry(
        entryType: KassenbuchEntryType,
        shop: ShopInfo,
    ) {
        when (entryType) {
            KassenbuchEntryType.CLOSING_BALANCE -> throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.Conflict,
                "CLOSING_BALANCE cannot be booked as a Kassenbuch entry — record a cash count " +
                    "via the Kassensturz/DifferenzSollIst flow instead (Anhang C p. 56, KABS-3).",
            )

            KassenbuchEntryType.OPENING_BALANCE -> {
                val currentBalance = kassenbuchRepository.getCurrentBalance(shop)
                val priorClosingExists = zBonRepository.getLatestReportByShop() != null
                if (currentBalance.signum() != 0 || priorClosingExists) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.Conflict,
                        "OPENING_BALANCE (Anfangsbestand) is only allowed on a virgin cash book " +
                            "(zero balance, no prior Z-Bon closing). Current balance=${currentBalance.toDecimalString()}, " +
                            "priorClosingExists=$priorClosingExists — use Geldtransit for a " +
                            "Wechselgeld top-up (Anhang C p. 56, KABS-3).",
                    )
                }
            }

            else -> Unit
        }
    }
}
