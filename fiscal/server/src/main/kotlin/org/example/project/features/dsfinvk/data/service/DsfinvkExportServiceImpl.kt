/**
 * Implementation of DsfinvkExportService for German fiscal compliance CSV export.
 *
 * Handles business logic for DSFinV-K export generation including data extraction,
 * validation, transformation, and ZIP packaging. Coordinates between multiple
 * repositories and generates all required CSV files according to DSFinV-K specification.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dsfinvk.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.DsfinvkFieldLimits
import org.example.project.core.utils.lateSigningReasonLabelDe
import org.example.project.export.dsfinvk.builder.BonIdNamespace
import org.example.project.export.dsfinvk.builder.DsfinvkExportBuilder
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.export.dsfinvk.builder.KassenbuchTaxClassifier
import org.example.project.export.dsfinvk.model.StammTseCertificate
import org.example.project.export.dsfinvk.model.TseTarFile
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.bill.mapper.RebilledOriginals
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.repository.TseExportArchiveRepository
import org.example.project.features.tse.domain.repository.TseLateSigningRepository
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.KassenStammdatenSnapshot
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.BillVatRowExpectation
import org.example.project.utils.NonTaxableTipCarrier
import org.example.project.utils.OwnerTipApportionment
import org.example.project.utils.OwnerTipBasis
import org.example.project.utils.PromotionApportionment
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.builder.tse.resolvedStartLogTimeSeconds
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.getTimeZone
import org.example.project.utils.formatter.serialHex
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

/** DSFinV-K 2.4, TSE_Transaktionen: TSE_TA_FEHLER is at most 200 characters. */
private const val TSE_TA_FEHLER_MAX_LENGTH = 200

/** `yyyy-MM-dd` — the date part of an ISO date-time such as a transaction's `BON_START`. */
private const val DATE_PREFIX_LENGTH = 10

/**
 * Exposed-based implementation of DsfinvkExportService.
 *
 * Provides business logic for DSFinV-K export with comprehensive data extraction,
 * validation, and transformation to comply with German fiscal requirements.
 *
 * @param json JSON serializer for encoding/decoding (injected from DI)
 */
class DsfinvkExportServiceImpl(
    private val json: Json,
    private val configRepository: ConfigurationRepository,
    /** Names the original bill behind a till clearing's invoice, for its header reference (accounting port). */
    private val invoices: BillInvoiceLink,
    /** Mode-aware TSE admin access for the TAR export (H10). Null only in tests that don't exercise TAR. */
    private val tseAdminProvider: org.example.project.features.tse.domain.provider.TseAdminProvider? = null,
    private val lateSigningRepository: TseLateSigningRepository = TseLateSigningRepositoryImpl(),
    /** The TSE archive register: export-and-delete archives that cover periods erased from the device (rt14). */
    exportArchives: TseExportArchiveRepository = TseExportArchiveRepositoryImpl(),
) : DsfinvkExportService {
    private val tseTarAssembly = TseTarAssembly(exportArchives)

    private val logger = LoggerFactory.getLogger(DsfinvkExportServiceImpl::class.java)

    private companion object {
        /**
         * KABS-6 tax-id requirement — shared by the export gate and the /validate error entry.
         *
         * Round-7 C4: the requirement is a property of the EXPORTED CLOSING, not of the shop's
         * current configuration, so the message names the offending Z_NR. Editing the shop
         * settings cannot repair a closing that already carries a snapshot (round-5 KABS-2) — an
         * operator who is not told that will "fix" the settings and re-run into the same 422.
         */
        fun missingTaxIdMessage(zNrs: List<Long>): String =
            "DSFinV-K export requires Steuernummer or USt-IdNr (§ 14 Abs. 4 Nr. 2 UStG) — " +
                "neither is present for Z_NR ${zNrs.joinToString(", ")}. A Kassenabschluss carries " +
                "the Stammdaten snapshot taken when it was closed; configuring the shop settings " +
                "now fixes only closings taken afterwards"
    }

    // The BuildConfig software brand/version fallbacks that used to live here now belong to
    // KassenStammdatenSnapshot.resolveLive (round-7 C3): they are Stamm_Kassen values, and
    // resolving them at the emit site meant a closing's KASSE_SW_BRAND/KASSE_SW_VERSION tracked
    // the RUNNING binary instead of the one it was closed under.

    /** Date formatter for DSFinV-K date format (YYYY-MM-DD) */
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** DateTime formatter for DSFinV-K timestamp format */
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    @OptIn(TestOnlyApi::class)
    override suspend fun generateExport(request: DsfinvkExportDto.ExportRequestDto): ByteArray {
        logger.info(
            "DsfinvkExportServiceImpl.generateExport: generating DSFinV-K export for period: {} to {}",
            request.startDate,
            request.endDate,
        )

        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            logger.warn("DsfinvkExportServiceImpl.generateExport: invalid export request: {}", e.message)
            throw invalidDateRange(e.message ?: "Invalid export request")
        }

        return try {
            dbQuery {
                // Parse dates
                val startDate = LocalDate.parse(request.startDate)
                val endDate = LocalDate.parse(request.endDate)
                val startDateTime = startDate.atTime(0, 0, 0)
                // Exclusive upper bound: atTime(23,59,59) silently dropped closings created in
                // the final sub-second of the range (audit H4).
                val endDateTimeExclusive = endDate.plus(1, DateTimeUnit.DAY).atTime(0, 0, 0)

                // Get shop info
                val shopInfo =
                    ShopInfo.all().firstOrNull()
                        ?: throw invalidCashRegisterConfig("No shop info found")

                // Load Kassenmeldung config for serial number and stamm_kassen
                val kassenConfigJson =
                    configRepository.getSettingAsString(
                        shopInfo.id.value,
                        ConfigKey.KASSEN_MELDUNG_CONFIG.key,
                    )
                val kassenConfig =
                    if (kassenConfigJson == null) {
                        // ABSENT config row: no Kassenmeldung has been captured yet. Falling back to
                        // the "SN-<shopId>" serial below is register-sanctioned behavior (round-3
                        // STAM-5) — keep it, but leave a trail.
                        logger.warn(
                            "generateExport: no KASSEN_MELDUNG_CONFIG for shopId={} — falling back to SN-<shopId> as KASSE_SERIENNR",
                            shopInfo.id.value,
                        )
                        ShopConfigurationDto.KassenMeldungConfigDto()
                    } else {
                        // PRESENT but undecodable config: silently swapping in a fabricated
                        // "SN-<shopId>" serial would corrupt the § 146a-Meldung identity and every
                        // file's Z_KASSE_ID join key. Fail the export loudly instead (round-3 STAM-5).
                        try {
                            json.decodeFromString<ShopConfigurationDto.KassenMeldungConfigDto>(kassenConfigJson)
                        } catch (e: SerializationException) {
                            throw fileGenerationFailed(
                                "KASSEN_MELDUNG_CONFIG exists but cannot be decoded — refusing to export a " +
                                    "fabricated KASSE_SERIENNR (round-3 STAM-5)",
                                e,
                            )
                        }
                    }

                // Use Kassen-Seriennummer as Z_KASSE_ID (stable, never changes)
                val cashRegisterId = kassenConfig.kasseSeriennr.ifBlank { "SN-${shopInfo.id.value}" }

                // m13: Anhang E p. 71 — „Aus technischen Gründen dürfen weder Slash („/") noch
                // Unterstriche („_") in der Seriennummer der Kasse verwendet werden." This value is
                // emitted as both Z_KASSE_ID (the join key across EVERY file) and KASSE_SERIENNR, so
                // one banned character breaks every join in the package. The default generator is
                // safe by construction; this catches a serial that arrived any other way (restored
                // hand-edited backup, direct DB write, a future edit endpoint) at the one point it
                // must pass through — the same fail-loud-on-bad-master-data stance as the country
                // (STAM-6) and fabricated-serial (STAM-5) guards.
                if (cashRegisterId.contains('/') || cashRegisterId.contains('_')) {
                    throw fileGenerationFailed(
                        "KASSE_SERIENNR '$cashRegisterId' contains a '/' or '_', which Anhang E p. 71 " +
                            "forbids in the register serial — fix the Kassenmeldung master data",
                    )
                }

                // The Kassenabschluss (created inside the range) is the unit of export —
                // DSFinV-K is closing-oriented, „auch kalendertagübergreifend" (audit H4).
                //
                // ORDER BY zbon_nummer is load-bearing, not cosmetic: [closingFor] resolves a
                // Vorgang stamped exactly on the instant two consecutive closings share
                // (startTime == previous endTime, so BOTH match its inclusive window) by taking the
                // FIRST covering closing in this list. Without an ORDER BY the database may return
                // the rows in any order it likes — seq scan, index scan on created_at and parallel
                // scan need not agree, and ZBon rows are updated after insert, so page reuse can
                // reorder them — which would make the tie-break, and therefore the Z_NR a boundary
                // Vorgang is exported under, depend on the query plan of the day.
                // Sorted by zbonNummer rather than startTime because the fiscal sequence is unique:
                // two closings CAN share a boundary instant, so a time sort could still tie and
                // hand the decision back to the database. ASC pins the earlier closing as the
                // winner, which is what every realistic plan already produced — this makes the
                // existing behaviour guaranteed, it does not change it (already-delivered packages
                // must keep their bytes).
                val zBons =
                    ZBon
                        .find {
                            (ZBonTable.createdAt greaterEq startDateTime) and
                                (ZBonTable.createdAt less endDateTimeExclusive)
                        }.orderBy(ZBonTable.zbonNummer to SortOrder.ASC)
                        .toList()

                if (zBons.isEmpty()) {
                    throw noDataInRange(request.startDate, request.endDate)
                }

                // Anhang E p. 68: „Es muss entweder die Steuernummer oder die USt-IdNr … angegeben
                // werden (§ 14 Abs. 4 Nr. 2 UStG)." A cashpointclosing.csv row with both empty is
                // formally invalid (round-3 KABS-6) — hard-gate it before any CSV is produced.
                //
                // Round-7 C4: the gate must be evaluated PER EXPORTED CLOSING and through
                // [stammdatenFor] — the same resolution the emitter uses — not against the live
                // shop_info row. Round-5's KABS-2 made the emitter ship the per-closing snapshot
                // while this gate kept reading the live row, so both directions were wrong: a live
                // row with a tax id let a closing whose SNAPSHOT has neither through (the real
                // harm — an invalid package shipped past a green gate), and a cleared live row
                // blocked closings whose snapshot was perfectly fine. The obligation attaches to
                // the bytes that leave the building.
                //
                // This necessarily runs after the closings are resolved, so a range with no
                // closings now reports "no data" rather than the tax-id error — correct, since
                // there is no closing for the requirement to attach to.
                val untaxedClosings = closingsMissingTaxId(zBons, shopInfo)
                if (untaxedClosings.isNotEmpty()) {
                    throw missingTaxId(untaxedClosings)
                }

                val zBonIds = zBons.map { it.id.value }

                // Query bills for these Z-reports
                val bills =
                    Bill
                        .find {
                            BillTable.zBonId inList zBonIds
                        }.toList()

                // Kassenbuch entries and orders belong to a closing via its window — select by
                // the exported closings' windows, NOT by the calendar range. Vorgänge no exported
                // closing covers are excluded: they belong to a future closing (audit H3/H4).
                val closingWindowStart = zBons.minOf { it.startTime }
                val closingWindowEnd = zBons.maxOf { it.endTime }
                // Round-4 KABS-1 rescue: entries written before the createdAt fix carry one frozen
                // table-init instant, which can sit outside the closing window even though the row
                // is explicitly linked to an exported closing. Admit those by zbon_id so their cash
                // cannot vanish from the closing while remaining in cash_per_currency
                // (Kassensturzfähigkeit, Tz 3.3.3 p.30).
                //
                // Round-5 follow-up: admission must follow ATTRIBUTION, not merely overlap it. An
                // entry that CARRIES a zbon_id is attributable only to that closing — if the linked
                // closing is not in this export, the row belongs to a different package and must be
                // excluded here even when its (frozen) createdAt happens to fall inside this
                // export's window. Admitting it by window would emit it under this export's Z_NR
                // while the linked closing's own export emits it again under the true Z_NR: two
                // packages, two different Z_NR, and BON_ID/BON_NR are closing-independent
                // ("KB-$id", BonNrRanges.KASSENBUCH + id), so no cross-package dedup can collapse
                // the duplicate. A day-by-day Kassennachschau export set would double-count cash.
                // Only an UNLINKED entry may be attributed by its covering window.
                val kassenbuchEntries =
                    KassenbuchHeader
                        .find {
                            (KassenbuchHeaderTable.zBonId inList zBonIds) or
                                (
                                    (KassenbuchHeaderTable.createdAt greaterEq closingWindowStart) and
                                        (KassenbuchHeaderTable.createdAt lessEq closingWindowEnd)
                                )
                        }.toList()
                        .filter { entry ->
                            // Mirrors kassenbuchClosingFor exactly: linked ⇒ only its own closing counts.
                            entry.zBon
                                ?.id
                                ?.value
                                ?.let { it in zBonIds }
                                ?: (closingFor(zBons, entry.createdAt) != null)
                        }

                // Spec 2026-07-09: the Z-Bon aggregate entry is bookkeeping over sales the export
                // already carries from the bills — including it double-counts revenue. Filter it
                // out of ALL FIVE kassenbuch consumers below (drop as a unit → no orphaned KB- ids).
                val exportableKassenbuchEntries = filterExportableKassenbuchEntries(kassenbuchEntries)

                logger.info(
                    "DsfinvkExportServiceImpl.generateExport: found {} Kassenbuch entries for DSFinV-K export",
                    kassenbuchEntries.size,
                )

                // Select by the RECORDED closing first, exactly as bills, cancellations and
                // Kassenbuch entries are — an order that records its Z-Bon can no longer be lost to
                // window arithmetic. Unlinked legacy rows still come through by window so historical
                // exports are unchanged.
                val orders =
                    Orders
                        .find {
                            (OrdersTable.zBonId inList zBonIds) or
                                (
                                    (OrdersTable.zBonId eq null) and
                                        (OrdersTable.orderTime greaterEq closingWindowStart) and
                                        (OrdersTable.orderTime lessEq closingWindowEnd)
                                )
                        }.toList()
                        .filter { order -> orderClosingFor(order, zBons) != null }

                // Query Cancellations by Z-Bon association
                val cancellations =
                    Cancellation
                        .find {
                            CancellationTable.zBonId inList zBonIds
                        }.toList()

                logger.info(
                    "DsfinvkExportServiceImpl.generateExport: found {} Orders and {} Cancellations for DSFinV-K export",
                    orders.size,
                    cancellations.size,
                )

                // Generate all CSV row DTOs (including Kassenbuch, Order, and Cancellation entries)
                // BON_NR must be unique (and non-zero) within a single Kassenabschluss (M6) — see
                // assignRunningBonNr() below, applied after all four sources are concatenated.
                val unattributedTransactions =
                    sortTransactionsForClosing(
                        generateTransactions(bills, zBons, cashRegisterId) +
                            generateKassenbuchTransactions(exportableKassenbuchEntries, zBons, cashRegisterId) +
                            generateOrderTransactions(orders, zBons, cashRegisterId) +
                            generateCancellationTransactions(cancellations, zBons, cashRegisterId),
                    )
                // TERMINAL_ID / TERMINAL_SERIENNR (Anhang E p. 85, BKOPF-7/STAM-10): both are
                // derived together from the signing ClientID of each Vorgang, so a Bonkopf
                // TERMINAL_ID can only ever name a slaves.csv row whose TERMINAL_SERIENNR is the
                // ClientID that signed it.
                val terminalAttribution =
                    DsfinvkTerminalAttribution.attribute(
                        transactions = unattributedTransactions,
                        stammTerminals = generateStammTerminals(zBons, cashRegisterId),
                        vorgaenge = terminalEvidence(bills, exportableKassenbuchEntries, orders, cancellations),
                    )
                val transactions = terminalAttribution.transactions
                // Round-3 STAM-1: one TSE_ID per physical device that signed ANY exported Vorgang
                // (bills, kassenbuch, orders, cancellations). Keyed by hex-normalized serial so a
                // TSE swap yields two devices instead of old-device rows claiming id 1.
                val linkedTseTransactions =
                    bills.mapNotNull { it.tseTransaction } +
                        exportableKassenbuchEntries.mapNotNull { it.tseTransaction } +
                        orders.mapNotNull { it.tseTransaction } +
                        cancellations.mapNotNull { it.tseTransaction }
                // The SUPERSEDED transactions of the same Vorgänge are exported too — as an extra,
                // unsigned transactions_tse.csv row each — so they must be part of the device set.
                // Built from the entities' LINKED transactions alone, a superseded TANR from a
                // since-replaced TSE got no TSE_ID and no tse.csv entry: a dangling reference.
                val exportedTseTransactions =
                    linkedTseTransactions +
                        supersededTransactionsForExport(
                            linkedTseTransactions,
                            listOf(
                                setOf(TseEntityType.BILL) to bills.map { it.id.value.toString() },
                                setOf(TseEntityType.KASSENBUCH) to exportableKassenbuchEntries.map { it.id.value.toString() },
                                setOf(TseEntityType.ORDER) to orders.map { it.id.value.toString() },
                                // Cancellations sign per BATCH, so the batch key is their reference
                                // — not the row id. Without this they would depend on the
                                // superseded row's replacement link, which is missing in exactly
                                // the case this collection exists for.
                                setOf(TseEntityType.CANCELLATION) to cancellations.map(::cancellationBatchKey).distinct(),
                            ),
                        )
                val tseIdBySerial = buildTseIdBySerial(exportedTseTransactions)
                val transactionsTse =
                    generateTransactionsTse(bills, zBons, cashRegisterId, tseIdBySerial) +
                        generateKassenbuchTransactionsTse(exportableKassenbuchEntries, zBons, cashRegisterId, tseIdBySerial) +
                        generateOrderTransactionsTse(orders, zBons, cashRegisterId, tseIdBySerial) +
                        generateCancellationTransactionsTse(cancellations, zBons, cashRegisterId, tseIdBySerial)
                val bonposPlans = mutableMapOf<Long, org.example.project.export.dsfinvk.builder.BonposLinePlan>()
                // GV and Kassenbuch lines name the terminal their Bon was settled on — the Bon's
                // attributed TERMINAL_ID, so a line never names a terminal its Bonkopf does not.
                val bonTerminalIds = DsfinvkTerminalAttribution.terminalIdsByBonId(transactions)
                // Item lines name the till the item was entered on: the order's signing client, else
                // its recorded terminal — one resolution per order, shared by every line of its items.
                val orderLineTerminals = OrderLineTerminals(orders)
                val lineItems =
                    DsfinvkTerminalAttribution.attributeLines(
                        lines =
                            generateLineItems(bills, zBons, cashRegisterId, bonposPlans, bonTerminalIds, orderLineTerminals) +
                                generateKassenbuchLineItems(exportableKassenbuchEntries, zBons, cashRegisterId, bonTerminalIds) +
                                generateOrderLineItems(orders, zBons, cashRegisterId, orderLineTerminals) +
                                generateCancellationLineItems(cancellations, zBons, cashRegisterId, orderLineTerminals, bonTerminalIds),
                        stammTerminals = terminalAttribution.stammTerminals,
                    )
                val lineVats =
                    generateLineVats(bills, zBons, cashRegisterId, bonposPlans) +
                        generateAvLineVats(orders, cancellations, exportableKassenbuchEntries, zBons, cashRegisterId)
                val dataPayments =
                    generateDataPayments(bills, zBons, cashRegisterId) +
                        generateAvPayments(orders, cancellations, exportableKassenbuchEntries, zBons, cashRegisterId)
                val businessCases =
                    generateBusinessCases(zBons, cashRegisterId) +
                        aggregateKassenbuchBusinessCases(exportableKassenbuchEntries, zBons, cashRegisterId)
                // payment.csv and Z_SE_* derive from the SAME datapayment rows the
                // Einzelaufzeichnung carries — closing identity by construction (KABS-2/4).
                val trainingBonIds =
                    bills
                        .asSequence()
                        .filter { it.isTraining }
                        .map { bonIdForBill(it.id.value) }
                        .toSet()
                val paymentSummaries = generatePaymentSummaries(zBons, cashRegisterId, dataPayments, trainingBonIds)
                val cashPerCurrency = generateCashPerCurrency(zBons, cashRegisterId)

                // Generate Phase 1 & 2 new CSV files
                val allocationGroups = generateAllocationGroups(bills, zBons, cashRegisterId, orders, cancellations)
                val references =
                    generateReferences(
                        bills,
                        zBons,
                        cashRegisterId,
                        bonposPlans,
                        exportableKassenbuchEntries,
                        cancellations,
                    )
                val itemAmounts = generateItemAmounts(bills, zBons, cashRegisterId, orders, cancellations)
                val subItems = generateSubItems(bills, zBons, cashRegisterId, bonposPlans)

                // Generate new master data files (HIGH priority compliance)
                val bonkopfUst = generateBonkopfUst(bills, zBons, cashRegisterId, exportableKassenbuchEntries)
                val stammAbschluss = generateStammAbschluss(zBons, shopInfo, cashRegisterId, transactions, paymentSummaries)
                val stammOrte = generateStammOrte(zBons, shopInfo, cashRegisterId)
                // Round-3 STAM-3: KEINE_UST_ZUORDNUNG is a PER-Kassenabschluss flag (Tz. 3.2.6) —
                // "1" only for the closings whose OWN rows actually used key 7, not blanket-stamped
                // across every register row because one closing in the range used it.
                val key7ZNrs: Set<Long> =
                    (
                        businessCases
                            .filter { it.ustSchluessel == DsfinvkExportDto.VatKeyMapping.NICHT_ERMITTELBAR }
                            .map { it.zNr } +
                            lineVats
                                .filter { it.ustSchluessel == DsfinvkExportDto.VatKeyMapping.NICHT_ERMITTELBAR }
                                .map { it.zNr }
                    ).toSet()
                val stammKassen =
                    generateStammKassen(
                        zBons,
                        shopInfo,
                        cashRegisterId,
                        kassenConfig,
                        keineUstZuordnungZNrs = key7ZNrs,
                    )
                // m12: every file that carries a UST_SCHLUESSEL contributes, so the declared
                // catalogue is exactly the used one — same shape as the key7ZNrs scan above.
                val usedVatKeysByZNr: Map<Long, Set<Int>> =
                    (
                        lineVats.map { it.zNr to it.ustSchluessel } +
                            bonkopfUst.map { it.zNr to it.ustSchluessel } +
                            businessCases.map { it.zNr to it.ustSchluessel } +
                            itemAmounts.map { it.zNr to it.ustSchluessel }
                    ).groupBy({ it.first }, { it.second }).mapValues { (_, keys) -> keys.toSet() }
                val stammUst = generateStammUst(zBons, cashRegisterId, usedVatKeysByZNr)
                val stammTse =
                    generateStammTse(
                        zBons,
                        cashRegisterId,
                        tseIdBySerial,
                        exportedTseTransactions,
                        tseIdsByZNr = usedTseIdsByZNr(transactionsTse),
                    )
                val stammTerminals = terminalAttribution.stammTerminals

                // Fetch TSE TAR export if requested
                var tseTarData: ByteArray? = null
                var tseTarFileName: String? = null
                var archiveTars: List<TseTarFile> = emptyList()

                if (request.includeTseTar) {
                    logger.info("DsfinvkExportServiceImpl.generateExport: TSE TAR export requested")
                    try {
                        val (data, fileName) = fetchTseTarExport()
                        // Checklist 28.4: a reachable but wrong or empty device answers with a valid
                        // TAR that verifies clean and covers nothing. Refuse it like an unreachable
                        // one — including the allowMissingTseTar opt-out below, which then ships the
                        // package WITHOUT a TAR rather than with a misleading one. Entries an
                        // export-and-delete erased are covered by the registered archive (rt14).
                        val tars = tarsCoveringPeriod(TseTarFile(fileName, data), exportedTseTransactions)
                        tseTarData = data
                        tseTarFileName = fileName
                        archiveTars = tars.drop(1)
                        logger.info(
                            "DsfinvkExportServiceImpl.generateExport: TSE TAR export retrieved successfully: {} ({} bytes)",
                            tseTarFileName,
                            tseTarData?.size ?: 0,
                        )
                    } catch (e: PosException) {
                        if (request.allowMissingTseTar) {
                            // Explicitly requested: operator takes responsibility for shipping
                            // WITHOUT the TSE data (must deliver the TAR separately).
                            logger.warn(
                                "DsfinvkExportServiceImpl.generateExport: TSE TAR export failed ({}) — continuing WITHOUT TSE data as explicitly allowed",
                                e.message,
                            )
                        } else {
                            // H10: silently shipping a DSFinV-K package without the requested TSE
                            // data made operators believe the export was complete.
                            throw e
                        }
                    }
                }

                // Build ZIP package
                val zipContent =
                    DsfinvkExportBuilder.buildExportPackage(
                        transactions = transactions,
                        transactionsTse = transactionsTse,
                        lineItems = lineItems,
                        lineVats = lineVats,
                        dataPayments = dataPayments,
                        businessCases = businessCases,
                        paymentSummaries = paymentSummaries,
                        cashPerCurrency = cashPerCurrency,
                        allocationGroups = allocationGroups,
                        references = references,
                        itemAmounts = itemAmounts,
                        subItems = subItems,
                        bonkopfUst = bonkopfUst,
                        stammAbschluss = stammAbschluss,
                        stammOrte = stammOrte,
                        stammKassen = stammKassen,
                        stammUst = stammUst,
                        stammTse = stammTse,
                        stammTerminals = stammTerminals,
                        shopName = shopInfo.shopName,
                        startDate = request.startDate,
                        endDate = request.endDate,
                        tseTarData = tseTarData,
                        tseTarFileName = tseTarFileName,
                        additionalTseTars = archiveTars,
                    )

                val tseStatus = if (tseTarData != null) "with TSE TAR" else "without TSE TAR"
                logger.info(
                    "DsfinvkExportServiceImpl.generateExport: successfully generated DSFinV-K export {} with {} transactions",
                    tseStatus,
                    transactions.size,
                )
                zipContent
            }
        } catch (e: PosException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.warn("DsfinvkExportServiceImpl.generateExport: invalid export request: {}", e.message)
            throw invalidDateRange(e.message ?: "Invalid export request")
        } catch (e: Exception) {
            logger.error("DsfinvkExportServiceImpl.generateExport: failed to generate DSFinV-K export", e)
            throw fileGenerationFailed(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Round-3 CONT-2: the half-open `[startDateTime, endDateTimeExclusive)` window used to
     * select closings. `/export`, `/validate` and `/statistics` MUST all use the same bound so
     * validate/statistics report exactly the closings export would ship — the old inclusive
     * `atTime(23,59,59)` bound silently dropped closings created in the final sub-second of the
     * range (audit H4).
     */
    private fun closingWindowFor(
        startDate: String,
        endDate: String,
    ): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)
        return start.atTime(0, 0, 0) to end.plus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    }

    @OptIn(TestOnlyApi::class)
    override suspend fun validateExportData(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ValidationResultDto {
        logger.info("DsfinvkExportServiceImpl.validateExportData: validating DSFinV-K export data for period: {} to {}", startDate, endDate)

        return try {
            dbQuery {
                // Parse dates — same half-open window as generateExport (round-3 CONT-2).
                val (startDateTime, endDateTimeExclusive) = closingWindowFor(startDate, endDate)

                val errors = mutableListOf<String>()
                val warnings = mutableListOf<String>()

                val shopInfo = ShopInfo.all().firstOrNull()

                // Check for Z-reports in range
                val zBons =
                    ZBon
                        .find {
                            (ZBonTable.createdAt greaterEq startDateTime) and
                                (ZBonTable.createdAt less endDateTimeExclusive)
                        }.toList()

                if (zBons.isEmpty()) {
                    errors.add("No Z-reports found in the specified date range")
                }

                // KABS-6: pre-export mirror of the generateExport hard gate — a Kassenabschluss
                // must carry either a Steuernummer or a USt-IdNr (§ 14 Abs. 4 Nr. 2 UStG). Surface
                // it here so operators see it before attempting an export that would 422.
                //
                // Round-7 C4 (rule R2): this mirror is evaluated exactly like the gate — per
                // exported closing, via [stammdatenFor] — and must stay that way. A pre-flight
                // check that disagrees with the export it precedes is worse than none: it either
                // green-lights an invalid package or blocks a valid one, and in both cases the
                // operator learns the truth only from the 422.
                if (shopInfo != null) {
                    val untaxedClosings = closingsMissingTaxId(zBons, shopInfo)
                    if (untaxedClosings.isNotEmpty()) {
                        errors.add(missingTaxIdMessage(untaxedClosings))
                    }
                }

                val zBonIds = zBons.map { it.id.value }

                // Check for bills
                val bills =
                    if (zBonIds.isNotEmpty()) {
                        Bill.find { BillTable.zBonId inList zBonIds }.toList()
                    } else {
                        emptyList()
                    }

                // Count missing TSE signatures across EVERY kind the export carries, not just
                // bills: over a real period this reported 5 while transactions_tse.csv came out
                // with 15 non-empty TSE_TA_FEHLER rows — 5 bills, 5 Kassenbuch entries, 2 orders
                // and 3 cancellations (E2E-2026-09-20 #11). Selected by the RECORDED closing, the
                // primary selector the export itself uses for each kind; an unlinked row that the
                // export would still pick up by window is not counted here, so the tally never
                // over-reports a period the export would ship clean.
                val missingSignatures =
                    MissingSignatureTally(
                        bills = bills.count { it.tseTransaction == null },
                        orders =
                            if (zBonIds.isEmpty()) {
                                0
                            } else {
                                Orders.find { OrdersTable.zBonId inList zBonIds }.count { it.tseTransaction == null }
                            },
                        // Only the rows the export itself carries: the Z-Bon's aggregate entry and the
                        // entries of a non-fiscal book are dropped from the CSV, and the aggregate has no
                        // TSE transaction by design (NOT_APPLICABLE) — counting it warned about a
                        // signature that never existed on every closing (QA finding NF-3).
                        kassenbuchEntries =
                            if (zBonIds.isEmpty()) {
                                0
                            } else {
                                filterExportableKassenbuchEntries(
                                    KassenbuchHeader.find { KassenbuchHeaderTable.zBonId inList zBonIds }.toList(),
                                ).count { it.tseTransaction == null }
                            },
                        cancellations =
                            if (zBonIds.isEmpty()) {
                                0
                            } else {
                                Cancellation
                                    .find { CancellationTable.zBonId inList zBonIds }
                                    .count { it.tseTransaction == null }
                            },
                    )
                val missingTseCount = missingSignatures.total
                missingSignatures.warning()?.let { warnings.add(it) }

                // Count bills that should carry VAT details but don't. A Forderungsausgleich and a
                // storno of such a sale carry none BY DESIGN; counting them warned "N bills are missing VAT breakdown details"
                // on a package that reconciles (E2E 2026-09-20 observation). Which bills must carry
                // rows is defined once, in BillVatRowExpectation.
                val billsWithoutVat = bills.count { BillVatRowExpectation.requiresVatRows(it) && it.vatDetails.empty() }
                if (billsWithoutVat > 0) {
                    warnings.add("$billsWithoutVat bills are missing VAT breakdown details")
                }

                // Check for unclosed Z-reports (this would be unusual, but check anyway)
                val unclosedCount = zBons.count { it.endTime == it.startTime }

                val isValid = errors.isEmpty()
                // Round-7 D1: only a HARD error blocks the export; warnings never do. The old
                // `errors.isEmpty() || warnings.isNotEmpty()` reported canExport = true whenever ANY
                // warning was present — so a package with a missing tax id or a missing TSE
                // signature (hard errors) plus one unrelated warning green-lit an invalid export,
                // and an operator trusting the pre-flight shipped it. `canExport` is exactly „no hard
                // error", i.e. errors.isEmpty(); the „export despite warnings" UX is the
                // `canExport && !isValid` case (warnings present, no errors), which still holds.
                val canExport = errors.isEmpty()

                DsfinvkExportDto.ValidationResultDto(
                    isValid = isValid,
                    canExport = canExport,
                    errors = errors,
                    warnings = warnings,
                    missingTseCount = missingTseCount,
                    unclosedZBonCount = unclosedCount,
                    dateRange = "$startDate to $endDate",
                )
            }
        } catch (e: PosException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.warn("DsfinvkExportServiceImpl.validateExportData: invalid date format: {}", e.message)
            throw invalidDateRange(e.message ?: "Invalid date format")
        } catch (e: Exception) {
            logger.error("DsfinvkExportServiceImpl.validateExportData: failed to validate DSFinV-K export data", e)
            throw exportInternal("validate failed: ${e.message}", e)
        }
    }

    override suspend fun getExportStatistics(
        startDate: String,
        endDate: String,
    ): DsfinvkExportDto.ExportStatisticsDto {
        logger.info(
            "DsfinvkExportServiceImpl.getExportStatistics: getting DSFinV-K export statistics for period: {} to {}",
            startDate,
            endDate,
        )

        return try {
            dbQuery {
                // Parse dates — same half-open window as generateExport (round-3 CONT-2).
                val (startDateTime, endDateTimeExclusive) = closingWindowFor(startDate, endDate)

                // Count Z-reports
                val zBons =
                    ZBon
                        .find {
                            (ZBonTable.createdAt greaterEq startDateTime) and
                                (ZBonTable.createdAt less endDateTimeExclusive)
                        }.toList()

                val zBonIds = zBons.map { it.id.value }

                // Count bills/transactions
                val bills =
                    if (zBonIds.isNotEmpty()) {
                        Bill.find { BillTable.zBonId inList zBonIds }.toList()
                    } else {
                        emptyList()
                    }

                val billIds = bills.map { it.id.value }

                // Count line items
                val lineItemCount =
                    if (billIds.isNotEmpty()) {
                        OrderItem.find { OrderItemTable.billId inList billIds }.count().toInt()
                    } else {
                        0
                    }

                // Count payments
                val payments =
                    if (billIds.isNotEmpty()) {
                        Payment.find { PaymentTable.billId inList billIds }.toList()
                    } else {
                        emptyList()
                    }

                // Count TSE transactions
                val tseCount = bills.count { it.tseTransaction != null }

                // The closings' own figures, summed — never re-derived from the bills. The preview is
                // labelled "Gesamtumsatz" beside the Z-Bons it counts, so it must read what those
                // Z-Bons say: Umsatz after promotions incl. the owner tip, without Stornos, cancelled
                // originals, training bills or receivable clearings. Σ billAmount + owner tips over
                // every bill showed 287,90 against the Z-Bon's 258,52 (admin17 NOTE-12): before the
                // Rabatte, and with a cancelled sale's own figure riding on its Storno. Display only:
                // nothing here is written into the export files.
                val totalRevenue = zBons.sumOf { it.totalPosRevenue }
                val totalVat = zBons.flatMap { it.vatGroups }.sumOf { it.vatAmount }

                // Get currencies
                val currencies = payments.map { it.currency }.distinct()

                DsfinvkExportDto.ExportStatisticsDto(
                    totalZBons = zBons.size,
                    totalTransactions = bills.size,
                    totalLineItems = lineItemCount,
                    totalPayments = payments.size,
                    totalTseTransactions = tseCount,
                    totalRevenue = totalRevenue.toDecimalString(),
                    totalVatAmount = totalVat.toDecimalString(),
                    currenciesIncluded = currencies,
                )
            }
        } catch (e: PosException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.warn("DsfinvkExportServiceImpl.getExportStatistics: invalid date format: {}", e.message)
            throw invalidDateRange(e.message ?: "Invalid date format")
        } catch (e: Exception) {
            logger.error("DsfinvkExportServiceImpl.getExportStatistics: failed to get DSFinV-K export statistics", e)
            throw exportInternal("statistics failed: ${e.message}", e)
        }
    }

    // ============================================
    // Private helper methods for data transformation
    // ============================================

    /**
     * m11: the ARTIKELTEXT of a percentage Rabatt line.
     *
     * This was `((1 - factor) * 100).toInt()`, which TRUNCATES: a 7,5 % discount printed
     * „Rabatt 7%" while the Beleg handed to the customer said 7,5 %. The *amount* in STK_BR was
     * always right, so nothing reconciled wrong — but ARTIKELTEXT is what a Prüfer reads beside
     * the printed receipt, and the two disagreed on a fiscal document.
     *
     * Rendered with the minimum decimals that stay truthful (7 → „7%", 7,5 → „7,5%") and a German
     * decimal comma, matching the printed Beleg rather than the CSV's own numeric convention —
     * ARTIKELTEXT is free text (Alpha), not a Numeric column, so `formatDecimal` never touches it.
     */
    @TestOnlyApi
    fun rabattLabel(discountFactor: BigDecimal): String {
        val percent =
            (BigDecimal.ONE - discountFactor)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
        return "Rabatt ${percent.toPlainString().replace('.', ',')}%"
    }

    /**
     * m7: TSE_VORGANGSDATEN — the signed processData, for the subset that can be emitted faithfully.
     *
     * Anhang E p. 105 makes this optional („Optional können hier die Daten des Vorgangs
     * (processData) … dargestellt werden. Dies erleichtert die Fehlersuche für die … Finanz-
     * verwaltung") and forbids surrounding spaces („Vorgehende und/oder nachfolgende Leerzeichen
     * sind nicht zulässig"). The field was hardcoded null on a FALSE premise — that pos-tse does
     * not surface the payload — but `processDataHash` holds exactly the signed processData (the QR
     * reads it back too).
     *
     * Only NORMAL-mode rows: a simulation payload is not genuine Absicherung, and an unsigned row
     * has none. Trimmed per the space rule.
     *
     * A payload containing CR/LF (a Bestellung-V1 row-separated by „\r") is OMITTED, not emitted:
     * `escapeString` collapses CR/LF to a space (CONT-7), which would produce a payload that no
     * longer verifies against the signature while looking like it should. An honestly-absent
     * optional field beats a silently-corrupted one. Kassenbeleg-V1 and SonstigerVorgang payloads
     * use `^`/`_`/`:` separators with no CR/LF, so the fiscally-central receipts are covered.
     */
    private fun tseVorgangsdatenFor(real: TseTransaction?): String? {
        val payload = real?.processDataHash?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (payload.any { it == '\r' || it == '\n' }) return null
        return payload
    }

    /**
     * Earliest `orderTime` per table session, for the sessions these [bills] settle.
     *
     * BON_START must be „der Zeitpunkt der ersten Erfassung innerhalb eines Vorgangs" — and Anhang E
     * p. 85 spells out that this „entspricht nicht TSE_TA_START". So it is the till's own capture
     * time of the first order line: not a TSE timestamp, and not the moment the table was opened.
     *
     * Resolved in ONE query rather than by walking `bill.tableSession.orders` per bill — an export
     * legitimately spans a year of Vorgänge, and this runs once per Bonkopf row.
     *
     * Deliberately keyed on the SESSION, not the bill: under Tz. 2.7.1 „können Bestellungen auch auf
     * mehrere Rechnungen verteilt sein", so every split bill of one session shares the moment that
     * session's ordering began. The tie back to the individual orders is ABRECHNUNGSKREIS.
     *
     * Not filtered to the export window: an order placed before the window still began this
     * Vorgang, and reporting the second-earliest order because the first fell outside a date range
     * would defeat the point of the field.
     */
    private fun firstOrderTimeBySession(bills: List<Bill>): Map<Long, LocalDateTime> {
        val sessionIds = bills.map { it.tableSession.id }.distinct()
        if (sessionIds.isEmpty()) return emptyMap()

        val earliest = mutableMapOf<Long, LocalDateTime>()
        OrdersTable
            .select(OrdersTable.tableSessionId, OrdersTable.orderTime)
            .where { OrdersTable.tableSessionId inList sessionIds }
            .forEach { row ->
                val sessionId = row[OrdersTable.tableSessionId].value
                val orderTime = row[OrdersTable.orderTime]
                val current = earliest[sessionId]
                if (current == null || orderTime < current) earliest[sessionId] = orderTime
            }
        return earliest
    }

    /**
     * Generates transactions.csv row DTOs from bills using BonkopfRowDto.
     */
    private fun generateTransactions(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonkopfRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val firstOrderBySession = firstOrderTimeBySession(bills)

        return bills.map { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val isStorno = bill.deletedBill != null
            val voucherTender = voucherTenderTotal(bill)

            // Determine BON_TYP based on training mode and storno status
            val bonTyp = determineBonTyp(bill, isStorno)

            DsfinvkExportDto.BonkopfRowDto(
                zKasseId = cashRegisterId,
                zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                zNr = zBon?.zbonNummer ?: 0L,
                bonId = bonIdForBill(bill.id.value),
                bonNr = bonNrForBill(bill.id.value, bill.billNumber),
                bonTyp = bonTyp,
                bonName =
                    when {
                        bill.isTraining -> "Training"
                        isStorno -> "Storno"
                        else -> null
                    },
                terminalId = null, // filled by DsfinvkTerminalAttribution from the signing ClientID (Anhang E p. 85)
                bonStorno = if (isStorno) "1" else "0",
                // BON_START is the first ERFASSUNG in the Vorgang (Anhang E p. 85), which is the
                // first order — not `bill.startTime`, which mirrors `tableSession.startTime`, i.e.
                // the moment a waiter tapped the table with nothing yet captured.
                bonStart = formatDateTime(firstOrderBySession[bill.tableSession.id.value] ?: bill.startTime),
                bonEnde = formatDateTime(bill.endTime),
                bedienerId = bill.employeeId.employeeNumber,
                bedienerName = employeeDisplayName(bill.employeeId),
                // UMS_BRUTTO is the DSFinV-K receipt gross: the sum of ALL Bonpos on the Bon, which
                // must reconcile with Σ(lines_vat.POS_BRUTTO) for this BON_ID (H3). Compose it the
                // same way the paid total is validated in BillServiceImpl (billAmount −
                // promotionAmount + tips):
                //  - billAmount already reflects the discounted item finalPrices (Σ finalPrice), so
                //    line discounts must NOT be netted here — they're inside billAmount.
                //  - promotions are NOT in billAmount and lines_vat emits a negative promotion row,
                //    so they must be subtracted to match.
                //  - the tip is a separate Bonpos that lines_vat/lines already emit, so it must be
                //    added. Its VAT treatment depends on the RECIPIENT: an EMPLOYEE tip
                //    (TrinkgeldAN) is non-taxable and books at UST_SCHLUESSEL=5 "Nicht steuerbar"
                //    (NOT "steuerfrei"/key 6), whereas an OWNER tip (TrinkgeldAG) is TAXABLE and is
                //    apportioned across the item VAT rates by apportionOwnerTip — it reaches key 5
                //    only on an ITEM-LESS bill, where no supply exists whose rate it could follow.
                //    UMS_BRUTTO is the same either way; only the per-key split differs.
                //  - a Mehrzweckgutschein redemption is a NEGATIVE key-5 Geschäftsvorfall, not a
                //    tender (C1, official example V_2_3_02 Bsp. 2.2): the voucher-paid share is
                //    netted out, matching the negative redemption line lines.csv already emits.
                // 4c: a receivable is netted out for the same reason the voucher share is — the
                // header reports money that moved, and none did for the deferred part. A mixed
                // tender therefore reports its CASH share, never 0 (spec §5.2a).
                umsBrutto =
                    (
                        bill.billAmount - bill.promotionAmount + bill.tips -
                            voucherTender - receivableTenderTotal(bill)
                    ).toDecimalString(),
                // Round-3 BKOPF-5: export the customer's full name and number when one is
                // attached (was firstName only / null id). Address fields stay null — a
                // separate scope (would need the CustomerAddress join).
                kundeName = bill.tableSession.customer?.fullName,
                kundeId = bill.tableSession.customer?.customerNumber,
                kundeTyp = null,
                kundeStrasse = null,
                kundePlz = null,
                kundeOrt = null,
                kundeLand = null,
                kundeUstId = null,
                bonNotiz = bill.deletingReason?.take(255),
            )
        }
    }

    /**
     * BON_NR ranges for Vorgänge without a printed Beleg number. Anhang E p. 84 defines BON_NR
     * as the register-assigned number that „entspricht der auf dem Bon gedruckten Nummer" —
     * bills therefore export their printed number ([bonNrForBill]); AV Vorgänge (orders,
     * cancellations, Kassenbuch entries) print none and get deterministic per-type ranges that
     * cannot collide with printed bill numbers. Documented in manual/dsfinvk-export.html.
     */
    private object BonNrRanges {
        const val BILL_FALLBACK = 800_000_000L
        const val ORDER = 900_000_000L
        const val CANCELLATION = 920_000_000L
        const val KASSENBUCH = 940_000_000L
    }

    /**
     * The printed bill number as BON_NR (Anhang E p. 84: „entspricht der auf dem Bon
     * gedruckten Nummer"). A bill number without digits cannot satisfy that correspondence —
     * fall back to a reserved range keyed by the entity id (unique, non-zero) and warn.
     */
    private fun bonNrForBill(
        billId: Long,
        billNumber: String,
    ): Long {
        val printed = billNumber.filter { it.isDigit() }.toLongOrNull()
        if (printed == null || printed == 0L) {
            logger.warn(
                "DSFinV-K: bill {} has no numeric printed number '{}' — BON_NR uses the fallback range",
                billId,
                billNumber,
            )
            return BonNrRanges.BILL_FALLBACK + billId
        }
        return printed
    }

    /**
     * Sorts transactions.csv rows chronologically per closing (BON_START, tie-break BON_ID) —
     * the order [generateStammAbschluss] derives Z_START_ID/Z_ENDE_ID from — and asserts
     * BON_NR uniqueness within each closing (Anhang E p. 84). BON_NR is NO LONGER rewritten to
     * a synthetic running sequence: bills carry their printed number, AV rows their reserved
     * [BonNrRanges] (audit BKOPF-5 — the export must match the paper trail).
     */
    private fun sortTransactionsForClosing(rows: List<DsfinvkExportDto.BonkopfRowDto>): List<DsfinvkExportDto.BonkopfRowDto> {
        // BON_ENDE breaks a BON_START tie before BON_ID does. Under Tz. 2.7.1 a Kassenbeleg's
        // BON_START is its first order's capture time, so the Bestellung and the Kassenbeleg it
        // led to genuinely START at the same instant — and lexical BON_ID ordering would then put
        // "B-…" ahead of the "O-…" it derives from. Among Vorgänge starting together, the one that
        // FINISHED first came first; the order closes at ordering, the bill at payment. BON_ID
        // stays as the final deterministic tie-break.
        val sorted = rows.sortedWith(compareBy({ it.zNr }, { it.bonStart }, { it.bonEnde }, { it.bonId }))
        sorted.groupBy { it.zNr }.forEach { (zNr, closingRows) ->
            val duplicates = closingRows.groupingBy { it.bonNr }.eachCount().filterValues { it > 1 }
            require(duplicates.isEmpty()) {
                "duplicate BON_NR within Kassenabschluss $zNr: ${duplicates.keys.sorted()}"
            }
        }
        return sorted
    }

    // ============================================
    // BON_ID namespacing (DSFinV-K audit finding C5)
    // ============================================
    //
    // Bills, Orders and Cancellations are three separate LongIdTables, each with its own
    // auto-increment sequence starting at 1 — so Bill#1, Order#1 and Cancellation#1 would all
    // emit BON_ID="1" and collide in transactions.csv, making every parent/child join (lines,
    // lines_vat, datapayment, transactions_tse, itemamounts, subitems, allocation_groups,
    // references) ambiguous. To keep BON_IDs globally unique within an export, every source
    // gets a stable namespace prefix — the prefix strings for all four sources (bill/order/
    // cancellation/kassenbuch) are defined in one place, BonIdNamespace. The SAME helper MUST be
    // used for a source's parent header AND all its child producers (and any references pointing
    // at it) so children still join to exactly one parent.

    // The prefix STRINGS live only in BonIdNamespace; these thin delegates keep the call sites
    // terse and read the same as the existing KassenbuchDsfinvkMapping.generateBonId convention.

    /** Namespaced BON_ID for a Bill (transactions.csv Beleg + all its child rows). */
    private fun bonIdForBill(billId: Long): String = BonIdNamespace.bill(billId)

    /** Namespaced BON_ID for an Order (AVBestellung header + its child rows). */
    private fun bonIdForOrder(orderId: Long): String = BonIdNamespace.order(orderId)

    /** Namespaced BON_ID for a Cancellation (Storno header + its child rows). */

    /**
     * Groups the `Cancellation` rows created by ONE cancel action (M2).
     *
     * The rows are per-item, but the action is one Vorgang covered by one TSE signature — the same
     * shape an order of N items already has. Falls back for rows written before `batchId` existed:
     * their shared `tseTransaction` was the de-facto batch key, and an unsigned legacy row can only
     * stand alone.
     */
    private fun cancellationBatchKey(cancellation: Cancellation): String =
        cancellation.batchId
            // Legacy rows sharing a transaction WERE one action — group them, which changes their
            // BON_ID from N separate C-<id> to one C-tx<n>. That is the point: their old shape was
            // the unverifiable one. DSFinV-K requires BON_ID uniqueness within a closing, not
            // stability across re-exports.
            ?: cancellation.tseTransaction
                ?.id
                ?.value
                ?.let { "tx$it" }
            // A legacy row with no batch and no signature was already a standalone one-line
            // Vorgang, so it keeps exactly the BON_ID it always had.
            ?: cancellation.id.value.toString()

    private fun bonIdForCancellationBatch(batchKey: String): String = BonIdNamespace.cancellation(batchKey)

    /**
     * The TSE evidence for TERMINAL_ID: per exported Vorgang, its BON_ID, the ClientID a real TSE
     * signed it under, and the terminal the entity itself records. Keyed by the same BON_ID each
     * Bonkopf producer emits — a cancellation batch by its batch key, signed once per batch.
     * Kassenbuch entries and cancellations record no terminal of their own, so only the signature
     * speaks for them.
     */
    private fun terminalEvidence(
        bills: List<Bill>,
        kassenbuchEntries: List<KassenbuchHeader>,
        orders: List<Orders>,
        cancellations: List<Cancellation>,
    ): List<DsfinvkTerminalAttribution.Vorgang> {
        val signer = DsfinvkTerminalAttribution::signingClientOf
        return bills.map { DsfinvkTerminalAttribution.Vorgang(bonIdForBill(it.id.value), signer(it.tseTransaction), it.terminal?.clientId) } +
            kassenbuchEntries.map {
                DsfinvkTerminalAttribution.Vorgang(KassenbuchDsfinvkMapping.generateBonId(it.id.value), signer(it.tseTransaction), null)
            } +
            orders.map { DsfinvkTerminalAttribution.Vorgang(bonIdForOrder(it.id.value), signer(it.tseTransaction), it.terminal?.clientId) } +
            cancellations.groupBy(::cancellationBatchKey).map { (batchKey, batch) ->
                DsfinvkTerminalAttribution.Vorgang(
                    bonIdForCancellationBatch(batchKey),
                    signer(batch.minBy { it.id.value }.tseTransaction),
                    null,
                )
            }
    }

    /**
     * Generates transactions_tse.csv row DTOs from bills with TSE data using TseTransaktionenRowDto.
     *
     * DSFinV-K/§146a AO requires every fiscally-relevant Beleg in transactions.csv to have a
     * matching transactions_tse.csv row — even when the TSE was unavailable at signing time.
     * Bills with `tseTransaction == null` (e.g. committed under TseFailureBehavior.FAIL) still
     * get a row here, with the signature fields empty and TSE_TA_FEHLER documenting the outage.
     */
    private fun generateTransactionsTse(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        tseIdBySerial: Map<String, Int>,
    ): List<DsfinvkExportDto.TseTransaktionenRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val lateSigning = lateSigningRepository.notesFor(TseEntityType.BILL, bills.map { it.id.value.toString() })

        // Bill and row are paired INSIDE the map, like the three other producers: a later filter
        // over either list would silently misalign a zip, attributing one Bon's superseded TANR to
        // another Bon.
        val pairs =
            bills.map { bill ->
                val tse = bill.tseTransaction
                // Only a NORMAL-mode transaction is genuine Absicherung: simulation rows carry
                // fabricated pseudo-signatures, unsigned rows no device at all (STAM-4/5).
                val real = tse?.takeIf { it.tseMode == TseMode.NORMAL }
                val zBon = zBonMap[bill.zBon?.id?.value]

                bill.id.value.toString() to
                    DsfinvkExportDto.TseTransaktionenRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(bill.id.value),
                        tseId = tseIdFor(real, tseIdBySerial),
                        tseTanr = real?.transactionNumber,
                        // TSE_TA_START: prefer the TSE hardware's own StartTransaction log time
                        // (tseStartTimestamp, populated from TransactionStartResultDto.timestamp in
                        // RealTseTransactionServiceImpl.startTransaction() — Task 17b) over the server
                        // clock (startTime), matching the TSE_TA_ENDE precedence below.
                        // startTime is shop-local wall time written via currentTimeStamp(); reinterpreting it as UTC shifted the instant by the UTC offset while claiming Z (round-3 STAM-2/TSER-10). Convert with the write zone.
                        // M6: resolved by the SHARED chain the receipt QR uses, so one Vorgang cannot
                        // report two different start times on the two surfaces (Anhang I p. 123 and
                        // Anhang E p. 104 define the same value).
                        tseTaStart = real?.resolvedStartLogTimeSeconds()?.let { formatTseLogTime(it) },
                        // TSE_TA_ENDE: prefer the TSE hardware's own log time (tseTimestamp, populated from
                        // the finish/update/quickSign response) over the server clock (endTime) — DSFinV-K
                        // wants the TSE's own FinishTransaction log time, not when the server observed it.
                        tseTaEnde =
                            real?.tseTimestamp?.let { formatTseLogTime(it) }
                                ?: real?.endTime?.let { formatTseLogTime(it.toInstant(getTimeZone()).epochSeconds) },
                        tseTaVorgangsart = real?.processType,
                        tseTaSigz = real?.signatureCounter,
                        tseTaSig = real?.signature,
                        tseTaFehler = resolveTseTaFehler(tse, bill.tseSigningStatus, lateSigning[bill.id.value.toString()]),
                        tseVorgangsdaten = tseVorgangsdatenFor(real),
                    )
            }

        return pairs.map { it.second } +
            supersededRowsFor(setOf(TseEntityType.BILL), pairs.toMap(), tseIdBySerial)
    }

    /**
     * Resolves the TSE_TA_FEHLER value for a transactions_tse.csv row.
     *
     * Returns `null` only when the transaction was genuinely signed by the TSE (`tse != null`).
     * Otherwise returns a status-appropriate German description so an unsigned Beleg never
     * appears in transactions_tse.csv without an explanation (§146a AO documented-outage
     * requirement). The wording distinguishes a genuine outage (FAILED) from a still-pending
     * late-signing state (QUEUED/PENDING/PROCESSING), which is NOT an "Ausfall".
     */

    /**
     * `TSE_TA_FEHLER` — DSFinV-K 2.4: "Freies Feld für Erläuterungen von Problemen in der
     * Kommunikation zwischen Aufzeichnungssystem und TSE", at most 200 characters.
     *
     * [note] is the Vorgang's Nachsignierung record (tse_late_signing). With one, the field names
     * the outage's cause and start — and, once the Vorgang is signed late, keeps saying so: a
     * late signature is valid, but the outage behind it must stay documented (AEAO zu § 146a
     * Nr. 1.14.1: "Ausfallzeiten und -grund einer TSE sind zu dokumentieren"). Without a record the
     * texts are unchanged, and a Vorgang signed on time still leaves the field empty.
     */
    private fun resolveTseTaFehler(
        tse: TseTransaction?,
        signingStatus: TseSigningStatus,
        note: TseSigningNoteDto? = null,
    ): String? {
        // A SIMULATION-mode transaction is not genuine Absicherung — its fabricated
        // pseudo-signature is suppressed by the producers and the row documented here (STAM-5).
        if (tse != null && tse.tseMode == TseMode.SIMULATION) return "TSE-Simulationsmodus"
        if (tse != null && tse.tseMode == TseMode.NORMAL) {
            val signedAt = note?.signedAt ?: return null
            return taFehler("Nachsigniert am ${taFehlerDateTime(signedAt)}", note)
        }
        if (tse != null) return "TSE deaktiviert"
        return when (signingStatus) {
            TseSigningStatus.FAILED -> taFehler("TSE-Ausfall: Signierung fehlgeschlagen", note)
            TseSigningStatus.QUEUED,
            TseSigningStatus.PENDING,
            TseSigningStatus.PROCESSING,
            -> taFehler("TSE-Signierung ausstehend (Nachsignierung)", note)
            TseSigningStatus.CANCELLED -> taFehler("TSE-Signierung abgebrochen", note)
            TseSigningStatus.DISABLED -> "TSE deaktiviert"
            TseSigningStatus.SIMULATION -> "TSE-Simulationsmodus"
            // SIGNED without a TseTransaction should not occur; emit a diagnostic fallback.
            TseSigningStatus.SIGNED -> "TSE-Transaktion fehlt"
            // Deliberately a throw, NOT a diagnostic string like the SIGNED case above — the two
            // are different kinds of "shouldn't happen". A SIGNED row with no TseTransaction is a
            // description gap on a row that legitimately belongs in the export. NOT_APPLICABLE is
            // written on a NON-FISCAL cash book (Tresor) entry and on the fiscal Z-Bon aggregate; the
            // aggregate is removed by the `!isZbonAggregate` filter before this point, so reaching
            // here means safe cash (or the aggregate) is being written into a tax-office export: a
            // scope violation. Aborting the
            // export is strictly better than describing it, because the fiscal filter in
            // `generateExport` is what should have excluded this row.
            TseSigningStatus.NOT_APPLICABLE ->
                error("Non-fiscal cash-book entry reached DSFinV-K export — the fiscal filter is broken")
        }
    }

    /**
     * [head], then — when the Vorgang has a Nachsignierung record — the outage's cause and start and
     * the recorded failure, e.g. "Nachsigniert am 19.09.2026 18:23:49 – TSE nicht erreichbar seit
     * 19.09.2026 18:23:49 (TSE_UNREACHABLE: …)". Cut to the field's 200 characters.
     */
    private fun taFehler(
        head: String,
        note: TseSigningNoteDto?,
    ): String {
        val text =
            if (note == null) {
                head
            } else {
                "$head – ${lateSigningReasonLabelDe(note.reasonCode)} seit ${taFehlerDateTime(note.queuedAt)} (${note.reasonText})"
            }
        return text.take(TSE_TA_FEHLER_MAX_LENGTH)
    }

    /** ISO local date-time as stored → `dd.MM.yyyy HH:mm:ss`; unparseable text is kept as is. */
    private fun taFehlerDateTime(isoLocal: String): String =
        runCatching {
            java.time.LocalDateTime
                .parse(isoLocal)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
        }.getOrDefault(isoLocal)

    /**
     * The SUPERSEDED transactions belonging to the exported Vorgänge, deduplicated.
     *
     * Two routes, because neither alone is complete. By REFERENCE for the Vorgänge whose reference
     * ids are known here (bills, Kassenbuch entries, orders), which also catches a superseded row
     * whose replacement link was never written. By LINK (`superseded_by_id`) for everything else —
     * notably cancellations, which are referenced by batch key, a value only their producer builds.
     */
    private fun supersededTransactionsForExport(
        exported: List<TseTransaction>,
        referencesByType: List<Pair<Set<TseEntityType>, List<String>>>,
    ): List<TseTransaction> {
        val byLink =
            exported
                .map { it.id.value }
                .chunked(1000)
                .flatMap { chunk ->
                    TseTransaction
                        .find {
                            (TseTransactionTable.state eq TseTransactionState.SUPERSEDED) and
                                (TseTransactionTable.supersededById inList chunk)
                        }.toList()
                }
        val byReference =
            referencesByType.flatMap { (types, references) ->
                val typeNames = types.map { it.name }
                references.chunked(1000).flatMap { chunk ->
                    TseTransaction
                        .find {
                            (TseTransactionTable.state eq TseTransactionState.SUPERSEDED) and
                                (TseTransactionTable.entityType inList typeNames) and
                                (TseTransactionTable.referenceId inList chunk)
                        }.toList()
                }
            }
        return (byLink + byReference).distinctBy { it.id.value }
    }

    /**
     * Extra TSE_Transaktionen rows for SUPERSEDED TANRs (design 2026-09-22 D2, §4.6): the device
     * closed the TANR without the till receiving its finish, and the Vorgang was signed again under
     * a new TANR (TR-03153-1 §9.4). The old TANR is listed on the SAME Bon with empty signature
     * fields and the cause in TSE_TA_FEHLER, so every TANR in the TSE export has a DSFinV-K row. It
     * is never exported as an AVBelegabbruch Vorgang: the Bon was paid.
     */
    private fun supersededRowsFor(
        entityTypes: Set<TseEntityType>,
        rowsByReference: Map<String, DsfinvkExportDto.TseTransaktionenRowDto>,
        tseIdBySerial: Map<String, Int>,
    ): List<DsfinvkExportDto.TseTransaktionenRowDto> {
        if (rowsByReference.isEmpty()) return emptyList()
        val typeNames = entityTypes.map { it.name }
        return rowsByReference.keys
            .chunked(1000)
            .flatMap { chunk ->
                TseTransaction
                    .find {
                        (TseTransactionTable.state eq TseTransactionState.SUPERSEDED) and
                            (TseTransactionTable.entityType inList typeNames) and
                            (TseTransactionTable.referenceId inList chunk)
                    }.toList()
            }.sortedBy { it.transactionNumber }
            .mapNotNull { tx ->
                val base = rowsByReference[tx.referenceId] ?: return@mapNotNull null
                val replacementTanr = tx.supersededById?.let { TseTransaction.findById(it)?.transactionNumber }
                base.copy(
                    tseId = tseIdBySerial[tseSerialKey(tx)],
                    tseTanr = tx.transactionNumber,
                    tseTaStart = tx.tseStartTimestamp?.let { formatTseLogTime(it) },
                    tseTaEnde = null,
                    tseTaVorgangsart = null,
                    tseTaSigz = null,
                    tseTaSig = null,
                    tseTaFehler = supersededTaFehler(tx.transactionNumber, replacementTanr),
                    tseVorgangsdaten = null,
                )
            }
    }

    private fun supersededTaFehler(
        tanr: Long?,
        replacementTanr: Long?,
    ): String =
        (
            "TSE-Transaktion $tanr abgeschlossen, Antwort nicht erhalten" +
                (replacementTanr?.let { " – ersetzt durch TANR $it" } ?: "")
        ).take(TSE_TA_FEHLER_MAX_LENGTH)

    /**
     * Generates lines.csv row DTOs from order items using BonposRowDto.
     *
     * Determines the correct GV_TYP (business case type) for each line item based on:
     * - Product type (regular sale, voucher purchase)
     * - Discount application (generates separate Rabatt line)
     * - Bill status (cancelled = Storno)
     * - Voucher redemptions (MehrzweckgutscheinEinloesung)
     */
    @TestOnlyApi
    fun generateLineItems(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        /** When provided, records every POS_ZEILE assignment — the single numbering authority all child files consume (H11). */
        plans: MutableMap<Long, org.example.project.export.dsfinvk.builder.BonposLinePlan>? = null,
        /** BON_ID → the Bon's attributed transactions.csv TERMINAL_ID; the POS_TERMINAL_ID of its GV lines (Anhang E p. 93). */
        bonTerminalIds: Map<String, String> = emptyMap(),
        /** The till each item was entered on — the POS_TERMINAL_ID of its item and item-discount lines. */
        orderLineTerminals: OrderLineTerminals = OrderLineTerminals(),
    ): List<DsfinvkExportDto.BonposRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.BonposRowDto>()

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val billId = bill.id.value
            // DSFinV-K Tz. 3.1.1.1: P_STORNO must NOT be used once TSE-signed.
            // Storno is represented by BON_STORNO="1" at header level + negative amounts.
            val isBillStorno = bill.deletedBill != null
            // POS_TERMINAL_ID of every GV line applied at settlement (promotion Rabatt, Gutschein,
            // Forderung, Trinkgeld): the terminal the Bon was settled on — exactly its attributed
            // TERMINAL_ID, empty when that is. Item lines and their item-discount Rabatt line name
            // the terminal the item was entered on instead.
            val settledOn = bonTerminalIds[bonIdForBill(billId)]

            // For storno: use original bill's items (items stay linked to original bill)
            val orderItems = resolveOrderItems(bill)

            val plan =
                org.example.project.export.dsfinvk.builder
                    .BonposLinePlan()
            plans?.put(billId, plan)
            var lineNumber = 1

            orderItems.forEach { item ->
                // Determine GV_TYP based on product type and transaction context
                val gvTyp = determineGvTyp(item, isBillStorno)
                val gvName = determineGvName(gvTyp)

                // Pre-discount CHARGED base = unitPrice + priced extras — matches
                // OrderServiceImpl: finalPrice = (unitPrice + extrasDelta) * discount. Using
                // the bare unitPrice dropped the extras from Bonpos on discounted items and
                // could suppress the Rabatt line entirely (audit H13b).
                val originalPrice = chargedUnitPrice(item)
                val discountFactor = item.discount
                val hasDiscount = discountFactor < BigDecimal.ONE

                // Tz. 4.2.5: on reversals „Lediglich das Vorzeichen für das Feld MENGE ändert
                // sich" — MENGE carries the sign, STK_BR stays the (Grund)Preis (BPOS-5).
                // A deposit-return line carries a negative value by data contract; a storno
                // of such a line flips it back positive: sign = (value<0) XOR isBillStorno.
                val price = if (hasDiscount) originalPrice else item.finalPrice
                val negativeLine = (price.signum() < 0) != isBillStorno
                val stkBrMagnitude = price.abs()

                // Add main line item (at original or final price depending on discount)
                result.add(
                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = lineNumber.toString(),
                        // Voucher SALE lines carry the voucher number too — the official
                        // example V_2_3_02 fills GUTSCHEIN_NR on the Kauf position so the
                        // lifecycle Kauf→Einlösung is traceable (audit BPOS-14).
                        gutscheinNr =
                            if (item.product.productType == ProductType.VOUCHER) {
                                Voucher.find { VoucherTable.orderItem eq item.id }.firstOrNull()?.voucherNumber
                            } else {
                                null
                            },
                        artikeltext = item.product.name,
                        posTerminalId = orderLineTerminals.of(item, orderlessFallback = settledOn),
                        gvTyp = gvTyp,
                        gvName = gvName,
                        inhaus = if (!item.takeAway) "1" else "0",
                        pStorno = "0", // DSFinV-K Tz. 3.1.1.1: forbidden after TSE signing
                        agenturId = null,
                        artNr =
                            item.product.id.value
                                .toString(),
                        gtin = null,
                        warengrId =
                            item.product.category
                                ?.id
                                ?.value
                                ?.toString(),
                        warengr = item.product.category?.name,
                        menge = if (negativeLine) "-1.000" else "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = stkBrMagnitude.toDecimalString(),
                    ),
                )
                plan.recordItem(item.id.value, lineNumber)
                lineNumber++

                // If a Rabatt row is due, add it. The decision AND the magnitude come from the
                // ONE shared predicate (round-4 BPOS-1) that lines_vat and businesscases also
                // use — lines.csv is the POS_ZEILE authority, so a child producer must never
                // reach a different verdict. See discountRabattAmount for the INV-F7 contract.
                val discountAmount = discountRabattAmount(item)
                if (discountAmount != null) {
                    plan.recordDiscount(item.id.value, lineNumber)
                    // Normal: negative discount; Storno: positive (reversing the discount)
                    val effectiveDiscount = if (isBillStorno) discountAmount else discountAmount.negate()
                    result.add(
                        DsfinvkExportDto.BonposRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(billId),
                            posZeile = lineNumber.toString(),
                            gutscheinNr = null,
                            artikeltext = rabattLabel(discountFactor),
                            // An item discount is entered with the item at order creation, so its Rabatt line
                            // names the item's terminal, like the item line it belongs to (Anhang E p. 93).
                            posTerminalId = orderLineTerminals.of(item, orderlessFallback = settledOn),
                            gvTyp = DsfinvkExportDto.BusinessCaseTypes.RABATT,
                            gvName = "Rabatt",
                            inhaus = if (!item.takeAway) "1" else "0",
                            pStorno = "0",
                            agenturId = null,
                            artNr = null,
                            gtin = null,
                            warengrId = null,
                            warengr = null,
                            menge = "1.000",
                            faktor = "1.000",
                            einheit = "Stk",
                            stkBr = effectiveDiscount.toDecimalString(),
                        ),
                    )
                    lineNumber++
                }
            }

            // Add voucher redemption lines for this bill (queried through payments)
            val sourceBillId = if (isBillStorno) bill.deletedBill!!.id.value else billId
            val voucherRedemptions = redemptionsForBeleg(bill, sourceBillId, isBillStorno)
            voucherRedemptions.forEach { redemption ->
                // Normal: negative (redemption offsets revenue); Storno: positive (reversing
                // the redemption on the original bill's row, which the reverse bill re-queries
                // via sourceBillId above) — mirrors the discount-line sign flip at effectiveDiscount.
                val effectiveRedeemAmount = if (isBillStorno) redemption.redeemAmount else redemption.redeemAmount.negate()
                result.add(
                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = lineNumber.toString(),
                        gutscheinNr = redemption.voucherNumber,
                        artikeltext = "Gutschein ${redemption.voucherNumber}",
                        posTerminalId = settledOn,
                        gvTyp = DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG,
                        gvName = "Gutscheineinlösung",
                        // Round-3 BPOS-4: a voucher redemption is a tender/GV line, not an
                        // Inhaus/Außerhaus goods sale — Anhang G allows ''; render empty.
                        inhaus = "",
                        pStorno = "0", // DSFinV-K Tz. 3.1.1.1: forbidden after TSE signing
                        agenturId = null,
                        artNr = null,
                        gtin = null,
                        warengrId = null,
                        warengr = null,
                        menge = "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = effectiveRedeemAmount.toDecimalString(),
                    ),
                )
                plan.recordVoucher(redemption.id.value, lineNumber)
                lineNumber++
            }

            // 4c Forderungsentstehung (spec §5.4): the deferred gross gets its own balancing line,
            // negative, at key 5 — the lines.csv twin of the transactions_vat key-5 row, so
            // Σ(lines) equals the netted UMS_BRUTTO exactly as it does for a voucher redemption.
            // The Umsatz lines above are untouched: the goods were sold, only the money is owed.
            val receivableLineTotal = receivableTenderTotal(bill)
            if (receivableLineTotal.signum() != 0) {
                result.add(
                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = lineNumber.toString(),
                        gutscheinNr = null,
                        artikeltext = "Forderung",
                        posTerminalId = settledOn,
                        gvTyp = DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSENTSTEHUNG,
                        gvName = "Forderungsentstehung",
                        inhaus = "", // a GV line, not a goods sale — Anhang G allows empty
                        pStorno = "0", // DSFinV-K Tz. 3.1.1.1: forbidden after TSE signing
                        agenturId = null,
                        artNr = null,
                        gtin = null,
                        warengrId = null,
                        warengr = null,
                        menge = "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = receivableLineTotal.negate().toDecimalString(),
                    ),
                )
                plan.recordReceivable(lineNumber)
                lineNumber++
            }

            // 4c-2c Forderungsauflösung: a clearing carries no goods, so the whole tendered amount
            // is ONE positive GV line at key 5 — the lines.csv twin of the key-5 transactions_vat
            // row above, so Σ(lines) == UMS_BRUTTO. No Umsatz line exists: nothing was sold here.
            clearingLineAmount(bill)?.let { clearedAmount ->
                result.add(
                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { z -> formatDateTime(z.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = lineNumber.toString(),
                        gutscheinNr = null,
                        artikeltext = "Forderungsausgleich",
                        posTerminalId = settledOn,
                        gvTyp = DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSAUFLOESUNG,
                        gvName = "Forderungsauflösung",
                        inhaus = "", // a GV line, not a goods sale — Anhang G allows empty
                        pStorno = "0", // DSFinV-K Tz. 3.1.1.1: forbidden after TSE signing
                        agenturId = null,
                        artNr = null,
                        gtin = null,
                        warengrId = null,
                        warengr = null,
                        menge = "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = clearedAmount.toDecimalString(),
                    ),
                )
                plan.recordClearing(lineNumber)
                lineNumber++
            }

            // Add tip line if tips were given on this bill
            // For storno: tips is already negated on the reverse bill
            val tipAmount = bill.tips
            if (tipAmount.compareTo(BigDecimal.ZERO) != 0) {
                // Determine tip GV_TYP based on recipient (AN = Arbeitnehmer/Employee, AG = Arbeitgeber/Employer)
                val tipGvTyp =
                    when (bill.tipRecipient) {
                        TipRecipient.EMPLOYEE -> DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN
                        TipRecipient.OWNER -> DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AG
                    }
                val tipGvName =
                    when (bill.tipRecipient) {
                        TipRecipient.EMPLOYEE -> "Trinkgeld (Arbeitnehmer)"
                        TipRecipient.OWNER -> "Trinkgeld (Arbeitgeber)"
                    }

                result.add(
                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = lineNumber.toString(),
                        gutscheinNr = null,
                        artikeltext = tipGvName,
                        posTerminalId = settledOn,
                        gvTyp = tipGvTyp,
                        gvName = tipGvName,
                        // Round-3 BPOS-4: a tip is not an Inhaus/Außerhaus goods sale — render empty.
                        inhaus = "",
                        pStorno = "0",
                        agenturId = null,
                        artNr = null,
                        gtin = null,
                        warengrId = null,
                        warengr = null,
                        menge = "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = tipAmount.toDecimalString(),
                    ),
                )
                plan.recordTip(lineNumber)
                lineNumber++
            }

            // Add promotion Rabatt lines for promotional voucher deductions
            // Round-3 KABS-2b (Bonpos-level twin): resolve promos on the ORIGINAL bill for a storno
            // (reverse bills carry no BillPromotion rows) via the sourceBillId computed above —
            // the effectiveAmount sign-flip below then reverses the original's Rabatt line so
            // Σ lines(MENGE×STK_BR) nets to the reverse bill's UMS_BRUTTO.
            val billPromotions = BillPromotion.find { BillPromotionTable.billId eq sourceBillId }.toList()

            billPromotions.forEach { billPromotion ->
                if (billPromotion.orderItem != null) {
                    // ITEM_SPECIFIC promotion: single Rabatt line
                    val promotionName = billPromotion.promotion.name
                    val deducted = billPromotion.deductedAmount
                    val effectiveAmount = if (isBillStorno) deducted else deducted.negate()
                    result.add(
                        DsfinvkExportDto.BonposRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(billId),
                            posZeile = lineNumber.toString(),
                            gutscheinNr = null,
                            artikeltext = "Aktion: $promotionName",
                            posTerminalId = settledOn,
                            gvTyp = DsfinvkExportDto.BusinessCaseTypes.RABATT,
                            gvName = "Rabatt",
                            inhaus = if (billPromotion.orderItem?.let { !it.takeAway } != false) "1" else "0",
                            pStorno = "0",
                            agenturId = null,
                            artNr = null,
                            gtin = null,
                            warengrId = null,
                            warengr = null,
                            menge = "1.000",
                            faktor = "1.000",
                            einheit = "Stk",
                            stkBr = effectiveAmount.toDecimalString(),
                        ),
                    )
                    plan.recordItemPromo(billPromotion.id.value, lineNumber)
                    lineNumber++
                }
            }

            // BILL_WIDE promotions: ONE aggregated Rabatt line per VAT rate, using the SAME
            // A3-3/A3-4 weights+largest-remainder split lines_vat and BillVatDetail use — the
            // old per-promo pro-rata rows disagreed with lines_vat in both COUNT and amount
            // whenever two bill-wide promos (or an item-specific promo) shared the bill (H11).
            val billWidePromosForLines = billPromotions.filter { it.orderItem == null }
            if (billWidePromosForLines.isNotEmpty()) {
                // M1: `PromotionTable.name` is varchar(200), so two bill-wide promotions reach 410
                // characters against ARTIKELTEXT's declared 255 — the overflow exists only in
                // COMBINATION, so unlike BEDIENER_NAME/KUNDE_NAME/WARENGR/ZAHLART_NAME there is no
                // write moment at which it can be rejected. Clamping a GENERATED label is not the
                // silent rewriting of a fiscal record that C7 forbids: STK_BR (the auditable
                // amount) is untouched and every promotion stays fully recorded in its own rows.
                // The ellipsis keeps the abbreviation visible.
                //
                // The structurally cleaner fix — one Bonpos line per promotion — is deliberately
                // NOT done here: it would reopen H11, because apportioning each promotion
                // separately does not compose with the single combined apportionment that
                // lines_vat and BillVatDetail are built from. Tracked as its own task.
                val promoNames = billWidePromosForLines.joinToString(", ") { it.promotion.name }
                val promoArtikeltext =
                    DsfinvkFieldLimits.clampGeneratedLabel(
                        "Aktion: $promoNames",
                        DsfinvkFieldLimits.ARTIKELTEXT,
                    )
                val (_, shareByRate) = billWidePromotionShares(orderItems, billPromotions)
                shareByRate.forEach { (vatRate, share) ->
                    if (share.compareTo(BigDecimal.ZERO) == 0) return@forEach
                    val effectiveAmount = if (isBillStorno) share else share.negate()
                    result.add(
                        DsfinvkExportDto.BonposRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(billId),
                            posZeile = lineNumber.toString(),
                            gutscheinNr = null,
                            artikeltext = promoArtikeltext,
                            posTerminalId = settledOn,
                            gvTyp = DsfinvkExportDto.BusinessCaseTypes.RABATT,
                            gvName = "Rabatt",
                            // Round-3 BPOS-4: a bill-wide promotion is not tied to a single
                            // Inhaus/Außerhaus goods line — render empty.
                            inhaus = "",
                            pStorno = "0",
                            agenturId = null,
                            artNr = null,
                            gtin = null,
                            warengrId = null,
                            warengr = null,
                            menge = "1.000",
                            faktor = "1.000",
                            einheit = "Stk",
                            stkBr = effectiveAmount.toDecimalString(),
                        ),
                    )
                    plan.recordBillWideRate(vatRate, lineNumber)
                    lineNumber++
                }
            }
        }

        return result
    }

    /**
     * Determines the BON_TYP (receipt type) for a bill.
     *
     * Per DSFinV-K Anhang B:
     * - AVTraining: Training mode receipts (not fiscally relevant)
     * - AVBelegstorno: Storno/cancellation of previous transaction
     * - Beleg: Standard receipt with payment
     *
     * @param bill The bill to classify
     * @param isStorno Whether this bill is a storno (cancellation)
     * @return The appropriate DSFinV-K BON_TYP value
     */
    private fun determineBonTyp(
        bill: Bill,
        isStorno: Boolean,
    ): String =
        when {
            // Training mode takes precedence - not fiscally relevant
            bill.isTraining -> DsfinvkExportDto.ReceiptTypes.AV_TRAINING
            // Storno: DSFinV-K Tz. 4.2.1/4.2.2 — AVBelegstorno cannot be used with TSE.
            // TSE-secured storno must use BON_TYP="Beleg" with BON_STORNO="1" and inverted amounts.
            isStorno -> DsfinvkExportDto.ReceiptTypes.BELEG
            // Standard receipt
            else -> DsfinvkExportDto.ReceiptTypes.BELEG
        }

    /**
     * Determines the GV_TYP (business case type) for an order item.
     *
     * @param item The order item to classify
     * @param isStorno Whether the bill is cancelled
     * @return The appropriate DSFinV-K GV_TYP value
     */
    private fun determineGvTyp(
        item: OrderItem,
        isStorno: Boolean,
    ): String {
        // Storno is handled via BON_STORNO flag and negative amounts, not GV_TYP
        val product = item.product

        // Check for deposit (Pfand) products first
        if (product.isDeposit) {
            return DsfinvkExportDto.BusinessCaseTypes.PFAND
        }

        if (product.isDepositReturn) {
            return DsfinvkExportDto.BusinessCaseTypes.PFAND_RUECKZAHLUNG
        }

        // Check product type for voucher sales
        return when (product.productType) {
            ProductType.VOUCHER -> DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_KAUF
            else -> DsfinvkExportDto.BusinessCaseTypes.UMSATZ
        }
    }

    /**
     * Gets the German display name for a GV_TYP.
     *
     * @param gvTyp The business case type code
     * @return Human-readable German name
     */
    private fun determineGvName(gvTyp: String): String =
        when (gvTyp) {
            DsfinvkExportDto.BusinessCaseTypes.UMSATZ -> "Verkauf"
            DsfinvkExportDto.BusinessCaseTypes.RABATT -> "Rabatt"
            DsfinvkExportDto.BusinessCaseTypes.AUFSCHLAG -> "Aufschlag"
            DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_KAUF -> "Gutscheinkauf"
            DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG -> "Gutscheineinlösung"
            DsfinvkExportDto.BusinessCaseTypes.EINZWECKGUTSCHEIN_KAUF -> "Einzweckgutscheinkauf"
            DsfinvkExportDto.BusinessCaseTypes.EINZWECKGUTSCHEIN_EINLOESUNG -> "Einzweckgutscheineinlösung"
            DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN -> DsfinvkExportDto.BusinessCaseNames.TRINKGELD_AN
            DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AG -> DsfinvkExportDto.BusinessCaseNames.TRINKGELD_AG
            DsfinvkExportDto.BusinessCaseTypes.PFAND -> "Pfand"
            DsfinvkExportDto.BusinessCaseTypes.PFAND_RUECKZAHLUNG -> "Pfandrückgabe"
            DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSENTSTEHUNG -> "Forderungsentstehung"
            DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSAUFLOESUNG -> "Forderungsauflösung"
            else -> gvTyp
        }

    /**
     * Generates lines_vat.csv row DTOs from bill VAT details using BonposUstRowDto.
     */
    private fun generateLineVats(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        plans: Map<Long, org.example.project.export.dsfinvk.builder.BonposLinePlan>,
    ): List<DsfinvkExportDto.BonposUstRowDto> = generateLineVatsInternal(bills, zBons, cashRegisterId, plans)

    private fun generateLineVatsInternal(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        plans: Map<Long, org.example.project.export.dsfinvk.builder.BonposLinePlan>,
    ): List<DsfinvkExportDto.BonposUstRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.BonposUstRowDto>()

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val billId = bill.id.value

            // For storno: use original bill's items
            val isBillStorno = bill.deletedBill != null
            val orderItems = resolveOrderItems(bill)
            // Reverse bills carry no BillPromotion / voucher rows — resolve both on the ORIGINAL
            // bill (mirrors resolveOrderItems + generateLineItems); sign-flips below net them.
            val sourceBillId = if (isBillStorno) bill.deletedBill!!.id.value else billId

            // POS_ZEILE comes exclusively from the lines.csv numbering authority (H11) —
            // this producer runs NO counter of its own anymore.
            val plan = plans.getValue(billId)

            // 4c-2c: the lines_vat twin of the Forderungsauflösung line — one key-5 row so every
            // lines_vat row keeps a transactions_vat parent (invariant 15) and Σ per key agrees.
            clearingLineAmount(bill)?.let { clearedAmount ->
                result.add(
                    DsfinvkExportDto.BonposUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { z -> formatDateTime(z.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = plan.clearingPos().toString(),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR,
                        posBrutto = clearedAmount.toDecimalString(),
                        posNetto = clearedAmount.toDecimalString(),
                        posUst = "0.00",
                    ),
                )
            }

            // T9-5: the lines_vat twin of the Forderungsentstehung line — key 5, zero USt, the
            // NEGATIVE deferred gross at the POS_ZEILE lines.csv recorded. Without it Σ lines_vat
            // fell short of UMS_BRUTTO by the receivable share, and the Bonpos line had no
            // Bonpos_USt child at all.
            val receivableLineTotal = receivableTenderTotal(bill)
            if (receivableLineTotal.signum() != 0) {
                result.add(
                    DsfinvkExportDto.BonposUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { z -> formatDateTime(z.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = plan.receivablePos().toString(),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR,
                        posBrutto = receivableLineTotal.negate().toDecimalString(),
                        posNetto = receivableLineTotal.negate().toDecimalString(),
                        posUst = "0.00",
                    ),
                )
            }

            // VAT entries for promotion Rabatt lines are queried early so an ITEM_SPECIFIC
            // promo's Rabatt row can be folded into the SAME per-rate group-residual pass as its
            // target item's own row(s), below. BILL_WIDE promotions keep their own (already
            // correct) group-residual pass, unchanged, later in this function.
            // Round-3 KABS-2b: resolve on sourceBillId so a storno's reversing Rabatt VAT rows are
            // emitted (reverse bills have no BillPromotion rows) — Σ lines_vat then nets per key.
            val billPromotions = BillPromotion.find { BillPromotionTable.billId eq sourceBillId }.toList()
            val itemSpecificPromotionsByItemId =
                billPromotions
                    .filter { it.orderItem != null }
                    .groupBy { it.orderItem!!.id.value }
            val billWidePromotions = billPromotions.filter { it.orderItem == null }

            // Item / discount / item-specific-promo rows are staged here — in the NATURAL
            // (non-storno) direction — so a per-VAT-rate group residual can be applied BEFORE
            // rounding+signing. See applyLineVatGroupResidual for why (RA-1 Finding 1): rounding
            // each row's net INDEPENDENTLY (the prior behavior) can drift a cent from the
            // single-netted BillVatDetail/transactions_vat target whenever ≥2 rows share a rate.
            val pendingRows = mutableListOf<PendingLineVatRow>()

            orderItems.forEach { item ->
                val vatRate = item.vat.vatRate
                val ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble())
                val hasDiscount = item.discount < BigDecimal.ONE
                // Mirror generateLineItems (lines.csv): the item row carries the UNDISCOUNTED
                // unitPrice when a discount applies, and the separate Rabatt row below subtracts
                // the discount amount — item(unitPrice) + rabatt(-discount) = finalPrice. Using
                // item.finalPrice here directly would double-subtract the discount (H3b), since
                // the Rabatt row already accounts for it.
                val grossAmount = if (hasDiscount) chargedUnitPrice(item) else item.finalPrice
                // Round net directly to 2 decimals to mirror BillServiceImpl.createBillVat (the
                // BillVatDetail that transactions_vat reads) — dividing at scale 4 then re-rounding
                // to 2 double-rounds (e.g. 12.00 @7% -> 11.2150 -> 11.22 instead of 11.21), which
                // made lines_vat.POS_UST disagree with transactions_vat.BON_UST by a cent per key.
                val netAmount = grossAmount.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)

                pendingRows.add(PendingLineVatRow(vatRate, ustSchluessel, grossAmount, netAmount, plan.itemPos(item.id.value)))

                // If a Rabatt row is due, account for its VAT. The verdict comes from the ONE
                // shared predicate lines.csv uses (round-4 BPOS-1) — this producer previously
                // compared the RAW difference and so could ask BonposLinePlan for a POS_ZEILE
                // lines.csv never recorded, aborting the entire export.
                val discountGross = discountRabattAmount(item)
                if (discountGross != null) {
                    // Rabatt NET as the RESIDUAL of this item's own single-netted total so
                    // item_net + rabatt_net == round(finalPrice/rate, 2) for THIS item — the
                    // group-residual pass below then corrects the group's TOTAL against the
                    // single-netted BillVatDetail, absorbing any remaining cross-item drift
                    // (RA-1 Finding 1). `netAmount` above is the item row's net
                    // (round(unitPrice/rate, 2)).
                    val netFinal = item.finalPrice.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                    val discountNetResidual = netFinal - netAmount
                    pendingRows.add(
                        PendingLineVatRow(
                            vatRate,
                            ustSchluessel,
                            discountGross.negate(),
                            discountNetResidual,
                            plan.discountPos(item.id.value),
                        ),
                    )
                }

                // Item-specific promotions targeting THIS item — folded into the same per-rate
                // group-residual pass (see above); BILL_WIDE promotions are handled later, unchanged.
                itemSpecificPromotionsByItemId[item.id.value]?.forEach { billPromotion ->
                    val deducted = billPromotion.deductedAmount
                    // Rabatt NET as the RESIDUAL of this item's own single-netted total: the
                    // item's running net (item row + any discount row) is round(finalPrice/rate, 2);
                    // after the promotion it must be round((finalPrice - deducted)/rate, 2) — the
                    // value BillVatDetail (transactions_vat) stores for this rate. Emitting the
                    // difference (not an independently-rounded deductedNet) keeps this item's own
                    // contribution exact; the group-residual pass below corrects the group TOTAL.
                    val netBeforePromo = item.finalPrice.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                    val netAfterPromo = (item.finalPrice - deducted).divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                    val deductedNetResidual = netAfterPromo - netBeforePromo
                    pendingRows.add(
                        PendingLineVatRow(
                            vatRate,
                            ustSchluessel,
                            deducted.negate(),
                            deductedNetResidual,
                            plan.itemPromoPos(billPromotion.id.value),
                        ),
                    )
                }
            }

            // Correct each VAT-rate group's staged rows so their nets sum to EXACTLY the same
            // single-netted target BillServiceImpl/BillVatDetail uses — mirrors the group-residual
            // technique the BILL_WIDE promotion path (below) already applies (RA-1 Finding 1).
            applyLineVatGroupResidual(pendingRows)

            pendingRows.forEach { row ->
                val effectiveGross = if (isBillStorno) row.gross.negate() else row.gross
                val effectiveNet = if (isBillStorno) row.net.negate() else row.net
                val (rBrutto, rNetto, rUst) = roundVatTriple(effectiveGross, effectiveNet, BigDecimal.ZERO)
                result.add(
                    DsfinvkExportDto.BonposUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = row.posZeile.toString(),
                        ustSchluessel = row.ustSchluessel,
                        posBrutto = rBrutto.toDecimalString(),
                        posNetto = rNetto.toDecimalString(),
                        posUst = rUst.toDecimalString(),
                    ),
                )
            }

            // VAT entries for voucher redemption lines (sourceBillId hoisted to the top of the loop)
            val voucherRedemptions = redemptionsForBeleg(bill, sourceBillId, isBillStorno)
            voucherRedemptions.forEach { redemption ->
                // Voucher redemptions are UST_SCHLUESSEL=5 "Nicht steuerbar" (not taxable — VAT
                // was already applied to the items). POS_BRUTTO carries the NEGATIVE redemption
                // amount (positive on storno), mirroring the lines.csv row for this POS_ZEILE —
                // official example V_2_3_02 Bsp. 2.2 shows key 5 / −50, never 0,00 (audit C1/BPOS-1).
                val effectiveRedeemAmount =
                    if (isBillStorno) redemption.redeemAmount else redemption.redeemAmount.negate()
                result.add(
                    DsfinvkExportDto.BonposUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        posZeile = plan.voucherPos(redemption.id.value).toString(),
                        ustSchluessel = 5,
                        posBrutto = effectiveRedeemAmount.toDecimalString(),
                        posNetto = effectiveRedeemAmount.toDecimalString(),
                        posUst = "0.00",
                    ),
                )
            }

            // VAT entry for tip line — keyed off bill.tipRecipient so it reconciles with
            // transactions_vat per (BON_ID, UST_SCHLUESSEL):
            //  - EMPLOYEE (TrinkgeldAN): non-taxable -> a single key-5 "Nicht steuerbar" row. The
            //    2026-09-16 P1.1 change made BillServiceImpl write NO 0% BillVatDetail row for an
            //    employee tip, so transactions_vat can no longer source it from the VAT breakdown —
            //    generateBonkopfUst emits its own matching key-5 row (via NonTaxableTipCarrier).
            //    "A single row" is now ENFORCED rather than merely intended: a 0%-rated ITEM books
            //    at key 5 too, so such a bill has two key-5 sources, and collapseDuplicateVatKeys
            //    sums them into the one row DSFinV-K 3.1.2.1 permits.
            //  - OWNER (TrinkgeldAG): taxable -> apportioned across the item VAT rates exactly as
            //    BillServiceImpl folds it into BillVatDetail (no key-5 row).
            val tipAmount = bill.tips
            if (tipAmount.compareTo(BigDecimal.ZERO) != 0) {
                if (bill.tipRecipient == TipRecipient.OWNER) {
                    apportionOwnerTip(orderItems, tipAmount, billPromotions).forEach { share ->
                        val (rBrutto, rNetto, rUst) = roundVatTriple(share.gross, share.net, share.vat)
                        result.add(
                            DsfinvkExportDto.BonposUstRowDto(
                                zKasseId = cashRegisterId,
                                zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                                zNr = zBon?.zbonNummer ?: 0L,
                                bonId = bonIdForBill(billId),
                                // ALL owner-tip rate rows share the ONE tip Bonpos line (H11) —
                                // one lines_vat row per UST_SCHLUESSEL of the same POS_ZEILE.
                                posZeile = plan.tipPos().toString(),
                                ustSchluessel = share.ustSchluessel,
                                posBrutto = rBrutto.toDecimalString(),
                                posNetto = rNetto.toDecimalString(),
                                posUst = rUst.toDecimalString(),
                            ),
                        )
                    }
                } else {
                    result.add(
                        DsfinvkExportDto.BonposUstRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(billId),
                            posZeile = plan.tipPos().toString(),
                            ustSchluessel = 5, // Tips: "Nicht steuerbar" (not "steuerfrei"/key 6)
                            posBrutto = tipAmount.toDecimalString(),
                            posNetto = tipAmount.toDecimalString(),
                            posUst = "0.00",
                        ),
                    )
                }
            }

            // VAT entries for BILL_WIDE promotion Rabatt lines (ITEM_SPECIFIC promotions were
            // already folded into the per-rate group-residual pass above, alongside their target
            // item's own rows).
            // BILL_WIDE: distribute across VAT groups using the SAME penny-exact largest-remainder
            // split BillServiceImpl applied to the signed BillVatDetail, so the shares — and the
            // exported gross/UST — reproduce the signed values exactly and Σlines_vat.POS_BRUTTO ==
            // UMS_BRUTTO == Σtransactions_vat.BON_BRUTTO per (BON_ID, UST_SCHLUESSEL). Three points
            // must match BillServiceImpl.createBillVat's vatToTotalPrice pass exactly:
            //  - AGGREGATE all bill-wide deductions into ONE apportion call (A3-4): largest-remainder
            //    is not additive, so apportioning each promo separately then summing drifts.
            //  - Weight each rate by finalPrice MINUS its item-specific deductions (A3-3): that is the
            //    vatToTotalPrice base BillServiceImpl apportions over; RAW finalPrice gave different
            //    shares once an item-specific promo shared the bill.
            //  - Net the Rabatt residual against the GROUP-netted value round(groupGross/(1+rate), 2)
            //    the item-side rows actually sum to (A3-1) — the item rows were group-residual-
            //    corrected to that single-netted target, so a naive Σround(finalPrice_i/(1+rate))
            //    was a cent off whenever ≥2 rows shared the rate.
            // Weights + shares come from the SAME helper lines.csv used to emit its
            // aggregated per-rate Rabatt lines — identical rows, identical POS_ZEILE (H11).
            val (weightsByRate, shareByRate) = billWidePromotionShares(orderItems, billPromotions)
            val billWideDeduction = billWidePromotions.fold(BigDecimal.ZERO) { acc, p -> acc + p.deductedAmount }
            if (billWideDeduction.compareTo(BigDecimal.ZERO) != 0) {
                weightsByRate.keys.forEach { vatRate ->
                    val groupGross = weightsByRate.getValue(vatRate)
                    val share = shareByRate[vatRate] ?: BigDecimal.ZERO
                    if (groupGross.compareTo(BigDecimal.ZERO) == 0 || share.compareTo(BigDecimal.ZERO) == 0) return@forEach

                    val itemNetSum = groupGross.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                    val targetNet = (groupGross - share).divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                    val shareNetResidual = targetNet - itemNetSum

                    val effGross = if (isBillStorno) share else share.negate()
                    val effNet = if (isBillStorno) shareNetResidual.negate() else shareNetResidual

                    val (rBrutto, rNetto, rUst) = roundVatTriple(effGross, effNet, BigDecimal.ZERO)
                    result.add(
                        DsfinvkExportDto.BonposUstRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(billId),
                            posZeile = plan.billWideRatePos(vatRate).toString(),
                            ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble()),
                            posBrutto = rBrutto.toDecimalString(),
                            posNetto = rNetto.toDecimalString(),
                            posUst = rUst.toDecimalString(),
                        ),
                    )
                }
            }
        }

        return result
    }

    /**
     * lines_vat rows for AV Vorgänge (orders, order-item cancellations, Kassenbuch lines) —
     * Tz. 3.1.1.1: „Für jede Position werden in dieser Datei die Informationen zu den
     * verwendeten USt-Sätzen festgehalten"; without them every AV Bonpos was an orphan in
     * the lines↔lines_vat join (audit BPOS-11). POS_ZEILE mirrors the AV producers' own
     * numbering (sequential 1..N / "1" / lineItem.lineNumber — no interleaved lines there).
     */

    /**
     * Public with `@TestOnlyApi` (not private) for the same reason as [aggregateKassenbuchBusinessCases]: the three
     * Kassenbuch VAT producers must be assertable INDEPENDENTLY. Round-5 C6 was invisible precisely
     * because they were only ever compared against each other.
     */
    @TestOnlyApi
    fun generateAvLineVats(
        orders: List<Orders>,
        cancellations: List<Cancellation>,
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonposUstRowDto> {
        val result = mutableListOf<DsfinvkExportDto.BonposUstRowDto>()

        fun row(
            zBon: ZBon,
            bonId: String,
            posZeile: Int,
            rate: BigDecimal,
            gross: BigDecimal,
        ) {
            val key =
                if (rate.compareTo(BigDecimal.ZERO) == 0) {
                    DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR
                } else {
                    DsfinvkExportDto.VatKeyMapping.getVatKey(rate.toDouble())
                }
            val net = gross.divide(BigDecimal.ONE + rate, 2, RoundingMode.HALF_UP)
            val (rBrutto, rNetto, rUst) = roundVatTriple(gross, net, gross - net)
            result.add(
                DsfinvkExportDto.BonposUstRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = bonId,
                    posZeile = posZeile.toString(),
                    ustSchluessel = key,
                    posBrutto = rBrutto.toDecimalString(),
                    posNetto = rNetto.toDecimalString(),
                    posUst = rUst.toDecimalString(),
                ),
            )
        }

        orders.forEach { order ->
            val zBon = orderClosingFor(order, zBons) ?: return@forEach
            order.items.toList().forEachIndexed { index, item ->
                row(zBon, bonIdForOrder(order.id.value), index + 1, item.vat.vatRate, item.finalPrice)
            }
        }

        // M2: POS_ZEILE must match the batch numbering lines.csv uses for the same Vorgang.
        cancellations.groupBy(::cancellationBatchKey).forEach { (batchKey, batch) ->
            batch.sortedBy { it.id.value }.forEachIndexed { index, cancellation ->
                val zBon =
                    zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                        ?: closingFor(zBons, cancellation.createdAt)
                        ?: return@forEachIndexed
                val item = cancellation.orderItem
                row(zBon, bonIdForCancellationBatch(batchKey), index + 1, item.vat.vatRate, item.finalPrice.negate())
            }
        }

        kassenbuchEntries.forEach { entry ->
            val zBon =
                kassenbuchClosingFor(entry, zBons)
                    ?: return@forEach
            entry.lineItems.forEach { lineItem ->
                val amount = lineItem.getNumericAmount()
                val lineGvTyp =
                    KassenbuchDsfinvkMapping.getGvTyp(
                        entry.entryType,
                        lineItem.kind,
                        amount,
                        entry.correctsHeader?.entryType,
                    )
                // Round-7 C1/C2: PER-LINE classification through the SAME classifier the TSE
                // Kassenbeleg-V1 builder uses. The old entry-level `hasVatImplication` gate could
                // not express p. 118's entry, which legitimately mixes a container-5
                // MehrzweckgutscheinEinloesung line with 19 % and 7 % Umsatz lines — it was
                // patched by a hand-written MEHRZWECKGUTSCHEIN_EINLOESUNG special case here, now
                // redundant. It also booked CASH_WITHDRAWAL at key 5 although its GV_TYP is the
                // same `Auszahlung` as CASH_EXPENSE (p. 121 mandates the RATE container), so the
                // exporter emitted two identical Geschäftsvorfälle at two different USt keys.
                val rate =
                    KassenbuchTaxClassifier.effectiveVatRate(
                        lineGvTyp,
                        lineItem.ustSatz?.let { parseVatRate(it) },
                    )
                row(zBon, KassenbuchDsfinvkMapping.generateBonId(entry.id.value), lineItem.lineNumber, rate, amount)
            }
        }

        return result
    }

    /**
     * Generates datapayment.csv row DTOs from payments using BonkopfZahlartenRowDto.
     *
     * Public with `@TestOnlyApi` (not private) so the storno tender-line behavior can be unit-tested
     * without seeding the full ShopInfo/ZBon/VAT graph that [generateExport] requires.
     */
    @TestOnlyApi
    fun generateDataPayments(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonkopfZahlartenRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.BonkopfZahlartenRowDto>()

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val billId = bill.id.value

            // A storno reverse bill (deletedBill != null) carries no payment rows of its own —
            // the tender it reverses lives on the refunded source payments (refundedBillId ->
            // this reverse bill). DSFinV-K Tz. 4.2.x represents a Belegstorno via BON_STORNO=1
            // + inverted amounts, so the reverse Beleg must emit the NEGATED tender lines. This
            // is the only place datapayment.csv rows are produced, so emit them here — otherwise
            // a fully-reversed sale keeps its positive tender with no offsetting line.
            val isStorno = bill.deletedBill != null
            val payments =
                if (isStorno) {
                    bill.refundPayments.toList()
                } else {
                    Payment.find { PaymentTable.billId eq billId }.toList()
                }

            // A voucher redemption is NOT a tender (C1): its amount lives in the negative
            // key-5 GV rows. A Bon settled entirely by voucher gets the single „Keine"/0 row
            // the spec reserves for „vollständig mit ... Gutschein bezahlte Leistung"
            // (Anhang D; official example V_2_3_02 Bsp. 2.2).
            // 4c: a receivable leg is excluded for the same reason a voucher leg is — it is not a
            // tender. Without this it would reach the loop below and export ZAHLART_TYP „Keine"
            // carrying the full amount: a no-tender row with money on it.
            val tenderPayments = payments.filterNot { it.paymentType.isVoucher || it.createsReceivable }
            if (tenderPayments.isEmpty() && payments.isNotEmpty()) {
                result.add(
                    DsfinvkExportDto.BonkopfZahlartenRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        zahlartTyp = DsfinvkExportDto.PaymentTypes.KEINE,
                        zahlartName = "Keine",
                        zahlwaehCode = null,
                        zahlwaehBetrag = null,
                        basiswaehBetrag = "0.00",
                    ),
                )
            }

            tenderPayments.forEach { payment ->
                // Determine payment type per DSFinV-K
                val zahlartTyp = mapPaymentType(payment.paymentType)
                val amount = if (isStorno) payment.amount.negate() else payment.amount

                result.add(
                    DsfinvkExportDto.BonkopfZahlartenRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        zahlartTyp = zahlartTyp,
                        zahlartName = payment.paymentType.name,
                        zahlwaehCode = payment.currency.takeIf { it != "EUR" },
                        zahlwaehBetrag = amount.toDecimalString().takeIf { payment.currency != "EUR" },
                        basiswaehBetrag = amount.toDecimalString(),
                    ),
                )
            }
        }

        return result
    }

    /**
     * Generates ZAHLART_TYP="Keine" payment rows for AV-type transactions.
     * DSFinV-K Tz. 4.1.1: All AV-prefixed types (except AVTraining) must use ZAHLART_TYP="Keine".
     */
    @TestOnlyApi
    fun generateAvPayments(
        orders: List<Orders>,
        cancellations: List<Cancellation>,
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonkopfZahlartenRowDto> {
        val result = mutableListOf<DsfinvkExportDto.BonkopfZahlartenRowDto>()

        orders.forEach { order ->
            val zBon = orderClosingFor(order, zBons) ?: return@forEach
            result.add(
                DsfinvkExportDto.BonkopfZahlartenRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = bonIdForOrder(order.id.value),
                    zahlartTyp = DsfinvkExportDto.PaymentTypes.KEINE,
                    zahlartName = "Keine",
                    zahlwaehCode = null,
                    zahlwaehBetrag = null,
                    basiswaehBetrag = "0.00",
                ),
            )
        }

        // M2: one tender row per VORGANG (Anhang B: an AV* Vorgang carries only Zahlart "Keine"),
        // so the batch gets one row, not one per cancelled line.
        cancellations.groupBy(::cancellationBatchKey).forEach { (batchKey, batch) ->
            val cancellation = batch.minBy { it.id.value }
            val zBon =
                zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                    ?: closingFor(zBons, cancellation.createdAt)
                    ?: return@forEach
            result.add(
                DsfinvkExportDto.BonkopfZahlartenRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = bonIdForCancellationBatch(batchKey),
                    zahlartTyp = DsfinvkExportDto.PaymentTypes.KEINE,
                    zahlartName = "Keine",
                    zahlwaehCode = null,
                    zahlwaehBetrag = null,
                    basiswaehBetrag = "0.00",
                ),
            )
        }

        val cashName = if (kassenbuchEntries.isEmpty()) DsfinvkExportDto.PaymentTypes.BAR else cashTenderName()
        kassenbuchEntries.forEach { entry ->
            val zBon =
                kassenbuchClosingFor(entry, zBons)
                    ?: return@forEach
            // Cash movements are „Beleg" and carry their signed Bar tender (official examples
            // V_2_3_05/06: Privateinlage +1000/Bar, Geldtransit −4200/Bar — audit H2); only the
            // informational AVSonstige snapshots keep Zahlart „Keine"/0,00 (Anhang B: AV types
            // allow only „Keine").
            val isBeleg =
                KassenbuchDsfinvkMapping.getBonTyp(entry.entryType) ==
                    DsfinvkExportDto.ReceiptTypes.BELEG
            result.add(
                DsfinvkExportDto.BonkopfZahlartenRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = KassenbuchDsfinvkMapping.generateBonId(entry.id.value),
                    zahlartTyp = if (isBeleg) DsfinvkExportDto.PaymentTypes.BAR else DsfinvkExportDto.PaymentTypes.KEINE,
                    zahlartName = if (isBeleg) cashName else "Keine",
                    zahlwaehCode = null,
                    zahlwaehBetrag = null,
                    basiswaehBetrag = if (isBeleg) entry.totalAmount.toDecimalString() else "0.00",
                ),
            )
        }

        return result
    }

    /**
     * ZAHLART_NAME of a Kassenbuch cash tender: the stored name of the shop's cash payment type —
     * the same name its cash bills carry — so a closing lists cash ONCE in payment.csv instead of
     * once as the operator's "BAR" (bills) and again as a hardcoded "Bar" (Kassenbuch) (F54 #3).
     * Prefers the active cash type with the lowest id; falls back to the canonical [DsfinvkExportDto.PaymentTypes.BAR]
     * only when the shop has no cash payment type at all. ZAHLART_TYP is unaffected.
     */
    internal fun cashTenderName(): String {
        val cashTypes =
            PaymentType
                .find { (PaymentTypeTable.isCash eq true) and (PaymentTypeTable.isVoucher eq false) }
                .sortedBy { it.id.value }
        return (cashTypes.firstOrNull { it.isActive } ?: cashTypes.firstOrNull())?.name
            ?: DsfinvkExportDto.PaymentTypes.BAR
    }

    /**
     * Maps payment type to DSFinV-K ZAHLART_TYP using the isVoucher and isCash flags.
     */
    private fun mapPaymentType(paymentType: PaymentType): String {
        // Voucher payments: mapped to "Keine" — voucher redemption is a GV_TYP
        // (MehrzweckgutscheinEinloesung), not a payment method (DSFinV-K Anhang D)
        // 4c: settling "auf Rechnung" moves no money, so it is not a payment method either —
        // Anhang D's answer for that is „Keine", the same one a voucher gets.
        if (paymentType.createsReceivable) return DsfinvkExportDto.PaymentTypes.KEINE
        if (paymentType.isVoucher) return DsfinvkExportDto.PaymentTypes.KEINE
        if (paymentType.isCash) return DsfinvkExportDto.PaymentTypes.BAR
        return when (paymentType.name.uppercase()) {
            "CARD", "EC", "KARTE", "ECKARTE", "EC-KARTE" -> DsfinvkExportDto.PaymentTypes.EC_KARTE
            "CREDIT", "KREDITKARTE" -> DsfinvkExportDto.PaymentTypes.KREDITKARTE
            else -> DsfinvkExportDto.PaymentTypes.UNBAR
        }
    }

    /**
     * Generates businesscases.csv row DTOs from Z-reports using ZGvTypRowDto.
     *
     * Aggregates line items by GV_TYP (business case type) for each Z-Bon,
     * calculating totals for each business case category.
     */
    @TestOnlyApi
    fun generateBusinessCases(
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.ZGvTypRowDto> {
        val result = mutableListOf<DsfinvkExportDto.ZGvTypRowDto>()

        zBons.forEach { zBon ->
            val zBonId = zBon.id.value

            // All bills of the closing EXCEPT training: AVTraining Vorgänge „haben keine
            // Auswirkungen auf den Kassenabschluss" (Anhang B, audit H6) — they stay in the
            // Einzelaufzeichnung as BON_TYP=AVTraining but never aggregate.
            val bills = Bill.find { BillTable.zBonId eq zBonId }.filter { !it.isTraining }

            // Aggregate data by GV_TYP and UST_SCHLUESSEL
            data class AggregateKey(
                val gvTyp: String,
                val ustSchluessel: Int,
            )
            val aggregates = mutableMapOf<AggregateKey, BusinessCaseAggregate>()

            bills.forEach { bill ->
                val billId = bill.id.value
                val isStorno = bill.deletedBill != null
                val orderItems = resolveOrderItems(bill)
                // F43: this bill's taxed rows sum per UST_SCHLUESSEL to its STORED BillVatDetail net, the
                // rows transactions_vat and the Z-Bon VatGroup read. A storno reads the original's rows,
                // negated, exactly as generateBonkopfUst does.
                val vatSourceBillId = if (isStorno) bill.deletedBill!!.id.value else billId
                val storedNetByKey =
                    BillVatDetail
                        .find { BillVatDetailTable.billId eq vatSourceBillId }
                        .groupBy { DsfinvkExportDto.VatKeyMapping.getVatKey(it.vat.vatRate.toDouble()) }
                        .mapValues { (_, rows) ->
                            val net = rows.fold(BigDecimal.ZERO) { acc, row -> acc + row.netAmount }
                            if (isStorno) net.negate() else net
                        }
                val billNetting = BillVatNetting<AggregateKey>(storedNetByKey)

                orderItems.forEach { item ->
                    val gvTyp = determineGvTyp(item, isStorno)
                    val originalPrice = chargedUnitPrice(item)
                    val finalPrice = item.finalPrice
                    val hasDiscount = item.discount < BigDecimal.ONE

                    // Calculate VAT for this item
                    val vatRate = item.vat.vatRate
                    val ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble())
                    val grossAmount = if (hasDiscount) originalPrice else finalPrice

                    // For storno: negate amounts
                    val sign = if (isStorno) BigDecimal.ONE.negate() else BigDecimal.ONE

                    // Add to main GV_TYP aggregate. Umsatz-family (rate-carrying revenue: Umsatz,
                    // Pfand, PfandRueckzahlung, MehrzweckgutscheinKauf) rows contribute only gross
                    // here. Their net is the bill's STORED BillVatDetail net minus the bill's other
                    // taxed rows, split by billNetting (F43). Summing per-item nets drifted (round-3
                    // INV-F6), and so did netting the whole closing's gross once (F43).
                    val key = AggregateKey(gvTyp, ustSchluessel)
                    val aggregate = aggregates.getOrPut(key) { BusinessCaseAggregate() }
                    aggregate.brutto += grossAmount * sign
                    billNetting.addRevenue(key, ustSchluessel, vatRate, grossAmount * sign)

                    // If a Rabatt row is due, add its aggregate. Same ONE shared predicate as
                    // lines.csv/lines_vat (round-4 BPOS-1) — this producer used to require
                    // `> 0`, so it silently disagreed with both of them.
                    val discountAmount = discountRabattAmount(item)
                    if (discountAmount != null) {
                        val discountKey = AggregateKey(DsfinvkExportDto.BusinessCaseTypes.RABATT, ustSchluessel)
                        val discountAggregate = aggregates.getOrPut(discountKey) { BusinessCaseAggregate() }
                        val discountNet = discountAmount.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                        val discountVat = discountAmount - discountNet
                        // Normal: negative discount; Storno: positive (reversing)
                        discountAggregate.brutto -= discountAmount * sign
                        discountAggregate.netto -= discountNet * sign
                        discountAggregate.ust -= discountVat * sign
                        billNetting.addFixed(ustSchluessel, -discountAmount * sign, -discountNet * sign)
                    }
                }

                // Handle voucher redemptions (queried through payments). A redemption a Storno
                // reversed (stamped reversedByBill) is NOT a business case of the closing — its
                // money did not stay redeemed, so the original and its Storno both leave it out
                // (audit KABS-7).
                val sourceBillId = if (isStorno) bill.deletedBill!!.id.value else billId
                val voucherRedemptions =
                    Payment
                        .find { PaymentTable.billId eq sourceBillId }
                        .filter { it.paymentType.isVoucher }
                        .mapNotNull { it.voucherRedeem }
                        .filter { it.reversedByBill == null }
                if (voucherRedemptions.isNotEmpty()) {
                    // key 5 = "Nicht steuerbar", not "steuerfrei"/key 6
                    val voucherKey = AggregateKey(DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG, 5)
                    val voucherAggregate = aggregates.getOrPut(voucherKey) { BusinessCaseAggregate() }
                    // For storno: negate, mirroring the discount aggregate `sign` above — otherwise the
                    // reverse bill re-aggregates the original's redemption with the same sign
                    // and the GV_TYP total doubles instead of netting to zero.
                    val voucherSign = if (isStorno) BigDecimal.ONE.negate() else BigDecimal.ONE
                    voucherRedemptions.forEach { redemption ->
                        // Multi-purpose voucher redemption - no VAT effect (VAT was applied to original items)
                        voucherAggregate.brutto -= redemption.redeemAmount * voucherSign
                        voucherAggregate.netto -= redemption.redeemAmount * voucherSign
                        // No VAT for voucher redemption
                    }
                }

                // Handle promotional deductions under Rabatt aggregate
                val promoSign = if (isStorno) BigDecimal.ONE.negate() else BigDecimal.ONE
                // Round-3 KABS-2: resolve promos on the ORIGINAL bill for a storno (reverse bills have no
                // BillPromotion rows) — mirrors the voucher block's sourceBillId; promoSign then nets it.
                val billPromotions = BillPromotion.find { BillPromotionTable.billId eq sourceBillId }.toList()
                billPromotions.forEach { billPromotion ->
                    if (billPromotion.orderItem != null) {
                        // ITEM_SPECIFIC: use target item's VAT key
                        val targetItem = billPromotion.orderItem!!
                        val vatRate = targetItem.vat.vatRate
                        val ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble())
                        val deducted = billPromotion.deductedAmount
                        val deductedNet = deducted.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                        val deductedVat = deducted - deductedNet

                        val promoKey = AggregateKey(DsfinvkExportDto.BusinessCaseTypes.RABATT, ustSchluessel)
                        val promoAggregate = aggregates.getOrPut(promoKey) { BusinessCaseAggregate() }
                        // Normal: negative discount; Storno: positive (reversing)
                        promoAggregate.brutto -= deducted * promoSign
                        promoAggregate.netto -= deductedNet * promoSign
                        promoAggregate.ust -= deductedVat * promoSign
                        billNetting.addFixed(ustSchluessel, -deducted * promoSign, -deductedNet * promoSign)
                    } else {
                        // BILL_WIDE: distribute with the SAME penny-exact largest-remainder split as
                        // BillServiceImpl / generateLineVats (net at scale 2), so Σ shares == deducted
                        // and the Rabatt aggregate lines up per rate (RA-1).
                        val deducted = billPromotion.deductedAmount
                        val vatGroups = orderItems.groupBy { it.vat.vatRate }
                        val weightsByRate =
                            vatGroups.mapValues { (_, items) ->
                                items.fold(BigDecimal.ZERO) { acc, it -> acc + it.finalPrice }
                            }
                        val shareByRate = PromotionApportionment.apportion(deducted, weightsByRate)

                        vatGroups.forEach vatLoop@{ (vatRate, items) ->
                            val groupGross = weightsByRate.getValue(vatRate)
                            val share = shareByRate[vatRate] ?: BigDecimal.ZERO
                            if (groupGross.compareTo(BigDecimal.ZERO) == 0 || share.compareTo(BigDecimal.ZERO) == 0) return@vatLoop

                            val shareNet = share.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                            val shareVat = share - shareNet
                            val ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble())

                            val promoKey = AggregateKey(DsfinvkExportDto.BusinessCaseTypes.RABATT, ustSchluessel)
                            val promoAggregate = aggregates.getOrPut(promoKey) { BusinessCaseAggregate() }
                            promoAggregate.brutto -= share * promoSign
                            promoAggregate.netto -= shareNet * promoSign
                            promoAggregate.ust -= shareVat * promoSign
                            billNetting.addFixed(ustSchluessel, -share * promoSign, -shareNet * promoSign)
                        }
                    }
                }

                aggregateReceivableBusinessCases(bill) { gvTyp, amount ->
                    val receivableAggregate =
                        aggregates.getOrPut(AggregateKey(gvTyp, DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR)) {
                            BusinessCaseAggregate()
                        }
                    receivableAggregate.brutto += amount
                    receivableAggregate.netto += amount
                    // Key 5, no USt: under Soll the VAT sits on the real-key Umsatz rows (spec §5.4).
                }

                // Handle tips (TrinkgeldAN for employee, TrinkgeldAG for owner/employer).
                // bill.tips is already sign-flipped on a storno reverse bill (mirrors billAmount/
                // discountAmount/promotionAmount — see BillServiceImpl.deleteBill), so booking it
                // unconditionally on sign (not gated on `!isStorno`) makes a full reversal net the
                // aggregate back to zero, the same way items/vouchers/promotions already do (H3).
                val tipAmount = bill.tips
                if (tipAmount.compareTo(BigDecimal.ZERO) != 0) {
                    when (bill.tipRecipient) {
                        TipRecipient.EMPLOYEE -> {
                            // TrinkgeldAN: non-taxable (durchlaufender Posten) -> key-5, no VAT.
                            val tipKey = AggregateKey(DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN, 5)
                            val tipAggregate = aggregates.getOrPut(tipKey) { BusinessCaseAggregate() }
                            tipAggregate.brutto += tipAmount
                            tipAggregate.netto += tipAmount
                            // No VAT on an employee tip.
                        }
                        TipRecipient.OWNER -> {
                            // TrinkgeldAG: taxable -> apportion across the item VAT rates, matching
                            // BillVatDetail (and so transactions_vat) per UST_SCHLUESSEL.
                            apportionOwnerTip(orderItems, tipAmount, billPromotions).forEach { share ->
                                val tipKey =
                                    AggregateKey(
                                        DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AG,
                                        share.ustSchluessel,
                                    )
                                val tipAggregate = aggregates.getOrPut(tipKey) { BusinessCaseAggregate() }
                                tipAggregate.brutto += share.gross
                                tipAggregate.netto += share.net
                                tipAggregate.ust += share.vat
                                billNetting.addFixed(share.ustSchluessel, share.gross, share.net)
                            }
                        }
                    }
                }

                // F43: book this bill's revenue nets, split from its stored BillVatDetail net per key,
                // so Σ businesscases per key equals Σ BillVatDetail (transactions_vat, Z-Bon VatGroup).
                billNetting.revenueNets().forEach { (key, net) ->
                    val aggregate = aggregates.getValue(key)
                    aggregate.netto += net
                }
            }

            // Create result rows for each GV_TYP aggregate
            aggregates.forEach { (key, aggregate) ->
                if (aggregate.brutto != BigDecimal.ZERO) {
                    // Every aggregate's netto is now a sum of per-bill scale-2 nets (revenue rows via
                    // billNetting, F43), so emission is the same Σ-then-round for all of them;
                    // ust = brutto − netto. Gross is unchanged (Σ grossAmount rounded to scale 2).
                    val (rBrutto, rNetto, rUst) = roundVatTriple(aggregate.brutto, aggregate.netto, aggregate.ust)
                    result.add(
                        DsfinvkExportDto.ZGvTypRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = formatDateTime(zBon.createdAt),
                            zNr = zBon.zbonNummer,
                            gvTyp = key.gvTyp,
                            gvName = determineGvName(key.gvTyp),
                            agenturId = null,
                            ustSchluessel = key.ustSchluessel,
                            zUmsBrutto = rBrutto.toDecimalString(),
                            zUmsNetto = rNetto.toDecimalString(),
                            zUst = rUst.toDecimalString(),
                        ),
                    )
                }
            }
        }

        return result
    }

    /**
     * Helper class for aggregating business case amounts.
     */
    private class BusinessCaseAggregate {
        var brutto: BigDecimal = BigDecimal.ZERO
        var netto: BigDecimal = BigDecimal.ZERO
        var ust: BigDecimal = BigDecimal.ZERO
    }

    /**
     * payment.csv (Z_Zahlart) derived from the emitted Bonkopf_Zahlarten rows, so the
     * closing identity Σ(Z_Zahlart) == Σ(datapayment) == Z_SE_ZAHLUNGEN holds by
     * construction (audit KABS-2/4 — the old ZBonPaymentSummary source disagreed with the
     * Einzelaufzeichnung on vouchers and Kassenbuch tenders). AVTraining tenders are
     * excluded per Anhang B („keine Auswirkungen auf den Kassenabschluss", audit H6);
     * zero-value „Keine" markers are not Zahlarten of the closing.
     */
    @TestOnlyApi
    fun generatePaymentSummaries(
        zBons: List<ZBon>,
        cashRegisterId: String,
        dataPayments: List<DsfinvkExportDto.BonkopfZahlartenRowDto>,
        trainingBonIds: Set<String>,
    ): List<DsfinvkExportDto.ZZahlartRowDto> =
        zBons.flatMap { zBon ->
            dataPayments
                .asSequence()
                .filter { it.zNr == zBon.zbonNummer && it.bonId !in trainingBonIds }
                .groupBy { it.zahlartTyp to it.zahlartName }
                .map { (key, rows) ->
                    DsfinvkExportDto.ZZahlartRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        zahlartTyp = key.first,
                        zahlartName = key.second,
                        zZahlartBetrag = rows.sumOf { BigDecimal(it.basiswaehBetrag) }.toDecimalString(),
                    )
                }.filterNot {
                    it.zahlartTyp == DsfinvkExportDto.PaymentTypes.KEINE &&
                        BigDecimal(it.zZahlartBetrag).signum() == 0
                }
        }

    /**
     * cash_per_currency.csv (Z_Waehrungen) — Tz. 3.3.3: „In dieser Datei wird für jede
     * Währung der erfasste Bargeldbestand als Summe dargestellt. Damit stellt diese Datei
     * eine jederzeitige Kassensturzfähigkeit her." This is the closing CASH BALANCE the
     * Z-Bon already computes (opening + Kassenbuch movements + cash revenue) — never a
     * tender total across all payment types (audit C2). Single-currency system → exactly
     * one EUR row per closing.
     */
    private fun generateCashPerCurrency(
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.ZWaehrungenRowDto> =
        zBons.map { zBon ->
            DsfinvkExportDto.ZWaehrungenRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                zahlartWaeh = "EUR",
                zahlartBetragWaeh = zBon.closingBalance.toDecimalString(),
            )
        }

    // ============================================
    // Kassenbuch DSFinV-K Export Methods
    // ============================================

    /**
     * Generates transactions.csv row DTOs from Kassenbuch entries using BonkopfRowDto.
     *
     * Kassenbuch entries use BON_TYP = "AVSonstige" as they represent
     * non-sale transactions (cash movements, deposits, withdrawals, etc.).
     * BON_ID is namespaced via BonIdNamespace.kassenbuch to distinguish from regular bills.
     */
    private fun generateKassenbuchTransactions(
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonkopfRowDto> {
        return kassenbuchEntries.mapNotNull { entry ->
            // Membership: the entry's own closing if it is exported, else the covering window;
            // otherwise skip — never fabricate Z_NR=0 (audit H3). Shared with the businesscases
            // producer via kassenbuchClosingFor so the two agree exactly (round-3 INV-F8).
            val zBon =
                kassenbuchClosingFor(entry, zBons)
                    ?: return@mapNotNull null

            DsfinvkExportDto.BonkopfRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                bonId = KassenbuchDsfinvkMapping.generateBonId(entry.id.value),
                bonNr = BonNrRanges.KASSENBUCH + entry.id.value,
                bonTyp = KassenbuchDsfinvkMapping.getBonTyp(entry.entryType),
                bonName = KassenbuchDsfinvkMapping.bonName(entry.entryType, entry.counterpartyBook?.name),
                terminalId = null, // filled by DsfinvkTerminalAttribution from the signing ClientID (Anhang E p. 85)
                bonStorno = "0",
                bonStart = formatDateTime(entry.createdAt),
                bonEnde = formatDateTime(entry.createdAt),
                bedienerId = entry.employee.employeeNumber,
                bedienerName = employeeDisplayName(entry.employee),
                umsBrutto = entry.totalAmount.toDecimalString(), // Preserve sign: outgoing = negative
                kundeName = null,
                kundeId = null,
                kundeTyp = null,
                kundeStrasse = null,
                kundePlz = null,
                kundeOrt = null,
                kundeLand = null,
                kundeUstId = null,
                bonNotiz = entry.belegtext?.take(255),
            )
        }
    }

    /**
     * Generates transactions_tse.csv row DTOs from Kassenbuch entries with TSE data using TseTransaktionenRowDto.
     *
     * Emits a row for every entry, including unsigned/failed ones (TSE_TA_FEHLER populated,
     * signature fields empty) so transactions_tse.csv stays 1:1 with transactions.csv.
     */
    private fun generateKassenbuchTransactionsTse(
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        tseIdBySerial: Map<String, Int>,
    ): List<DsfinvkExportDto.TseTransaktionenRowDto> {
        val lateSigning = lateSigningRepository.notesFor(TseEntityType.KASSENBUCH, kassenbuchEntries.map { it.id.value.toString() })
        val pairs =
            kassenbuchEntries.mapNotNull { entry ->
                val tse = entry.tseTransaction
                // Only a NORMAL-mode transaction is genuine Absicherung: simulation rows carry
                // fabricated pseudo-signatures, unsigned rows no device at all (STAM-4/5).
                val real = tse?.takeIf { it.tseMode == TseMode.NORMAL }

                val zBon =
                    kassenbuchClosingFor(entry, zBons)
                        ?: return@mapNotNull null

                entry.id.value.toString() to
                    DsfinvkExportDto.TseTransaktionenRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        bonId = KassenbuchDsfinvkMapping.generateBonId(entry.id.value),
                        tseId = tseIdFor(real, tseIdBySerial),
                        tseTanr = real?.transactionNumber,
                        // TSE_TA_START: prefer the TSE hardware's own StartTransaction log time
                        // (tseStartTimestamp, populated from TransactionStartResultDto.timestamp in
                        // RealTseTransactionServiceImpl.startTransaction() — Task 17b) over the server
                        // clock (startTime), matching the TSE_TA_ENDE precedence below.
                        // M6: resolved by the SHARED chain the receipt QR uses, so one Vorgang cannot
                        // report two different start times on the two surfaces (Anhang I p. 123 and
                        // Anhang E p. 104 define the same value).
                        tseTaStart = real?.resolvedStartLogTimeSeconds()?.let { formatTseLogTime(it) },
                        // TSE_TA_ENDE: prefer the TSE hardware's own log time (tseTimestamp, populated from
                        // the finish/update/quickSign response) over the server clock (endTime) — DSFinV-K
                        // wants the TSE's own FinishTransaction log time, not when the server observed it.
                        tseTaEnde =
                            real?.tseTimestamp?.let { formatTseLogTime(it) }
                                ?: real?.endTime?.let { formatTseLogTime(it.toInstant(getTimeZone()).epochSeconds) },
                        tseTaVorgangsart = real?.processType,
                        tseTaSigz = real?.signatureCounter,
                        tseTaSig = real?.signature,
                        tseTaFehler = resolveTseTaFehler(tse, entry.tseSigningStatus, lateSigning[entry.id.value.toString()]),
                        tseVorgangsdaten = tseVorgangsdatenFor(real),
                    )
            }

        return pairs.map { it.second } + supersededRowsFor(setOf(TseEntityType.KASSENBUCH), pairs.toMap(), tseIdBySerial)
    }

    /**
     * Generates lines.csv row DTOs from Kassenbuch entries using BonposRowDto.
     *
     * Each Kassenbuch entry can have multiple line items (for multi-VAT support).
     * Each line item creates a separate BonposRowDto with:
     * - GV_TYP based on entry type mapping
     * - Amount from line item vorzBetrag
     * - Line number for proper ordering
     */

    /**
     * The Kassenbuch entries that belong in a DSFinV-K package.
     *
     * Extracted rather than inlined at the call site so tests bind to the REAL predicate. A test
     * that re-declares this filter passes even after the rule is deleted from the export — on a
     * tax-office filing that is a guard which guards nothing.
     *
     * Two exclusions, dropped as a unit across all five kassenbuch consumers so no orphaned KB-
     * ids survive:
     *  - the Z-Bon aggregate entry, which double-counts revenue the bills already carry
     *    (spec 2026-07-09)
     *  - entries in a non-fiscal cash book (Tresor, Nebenkasse), which are not part of an
     *    elektronisches Aufzeichnungssystem, so §146a AO does not reach them
     *
     * With a single fiscal book the second clause drops nothing.
     */
    @TestOnlyApi
    fun filterExportableKassenbuchEntries(entries: List<KassenbuchHeader>): List<KassenbuchHeader> =
        entries.filter { !it.isZbonAggregate && it.cashBook.isFiscal }

    @TestOnlyApi
    fun generateKassenbuchLineItems(
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        /** BON_ID → the KB Bon's attributed TERMINAL_ID, set only when a terminal client signed it (Anhang E p. 93). */
        bonTerminalIds: Map<String, String> = emptyMap(),
    ): List<DsfinvkExportDto.BonposRowDto> {
        return kassenbuchEntries.flatMap { entry ->
            val zBon =
                kassenbuchClosingFor(entry, zBons)
                    ?: return@flatMap emptyList()

            // Create a row for each line item
            entry.lineItems.map { lineItem ->
                // True signed amount (cash-flow sign). The old abs()+header-direction
                // re-sign corrupted mixed-sign entries: every Z-Bon "Unbare Zahlung"
                // line exported positive. Uniform-sign entries are unchanged.
                val amount = lineItem.getNumericAmount()
                val artikeltext = lineItem.getEffectiveBelegtext()
                val gvTyp =
                    KassenbuchDsfinvkMapping.getGvTyp(
                        entry.entryType,
                        lineItem.kind,
                        amount,
                        entry.correctsHeader?.entryType,
                    )
                val gvName = KassenbuchDsfinvkMapping.getGvName(gvTyp)

                DsfinvkExportDto.BonposRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = KassenbuchDsfinvkMapping.generateBonId(entry.id.value),
                    posZeile = lineItem.lineNumber.toString(),
                    gutscheinNr = null,
                    artikeltext = artikeltext,
                    posTerminalId = bonTerminalIds[KassenbuchDsfinvkMapping.generateBonId(entry.id.value)],
                    gvTyp = gvTyp,
                    gvName = gvName,
                    // Round-3 BPOS-4: a Kassenbuch cash movement is not an Inhaus/Außerhaus
                    // goods sale — render empty (Anhang G allows '').
                    inhaus = "",
                    pStorno = "0",
                    agenturId = null,
                    artNr = null,
                    gtin = null,
                    warengrId = null,
                    warengr = null,
                    menge = "1.000",
                    faktor = "1.000",
                    einheit = "Stk",
                    stkBr = amount.toDecimalString(),
                )
            }
        }
    }

    // ============================================
    // Order & Cancellation DSFinV-K Export Methods
    // ============================================

    /**
     * Generates transactions.csv row DTOs from Orders using BonkopfRowDto.
     *
     * Orders use BON_TYP = "AVBestellung" (or "AVTraining" if training mode).
     * These are non-fiscal transactions excluded from Z-Bon aggregation.
     */
    private fun generateOrderTransactions(
        orders: List<Orders>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonkopfRowDto> {
        return orders.mapNotNull { order ->
            val zBon = orderClosingFor(order, zBons) ?: return@mapNotNull null

            DsfinvkExportDto.BonkopfRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                bonId = bonIdForOrder(order.id.value),
                bonNr = BonNrRanges.ORDER + order.id.value,
                bonTyp = if (order.isTraining) "AVTraining" else "AVBestellung",
                bonName = if (order.isTraining) "Training" else "Bestellung",
                terminalId = null, // filled by DsfinvkTerminalAttribution from the signing ClientID (Anhang E p. 85)
                bonStorno = "0",
                bonStart = formatDateTime(order.orderTime),
                bonEnde = formatDateTime(order.orderTime),
                bedienerId = order.employee.employeeNumber,
                bedienerName = employeeDisplayName(order.employee),
                umsBrutto = order.orderTotal.toDecimalString(),
                kundeName = null,
                kundeId = null,
                kundeTyp = null,
                kundeStrasse = null,
                kundePlz = null,
                kundeOrt = null,
                kundeLand = null,
                kundeUstId = null,
                bonNotiz = null,
            )
        }
    }

    /**
     * Generates transactions.csv row DTOs from Cancellations using BonkopfRowDto.
     *
     * Cancellations use BON_TYP = "AVBestellung" with negative amounts.
     * Each cancellation is an independent TSE-signed transaction.
     */
    private fun generateCancellationTransactions(
        cancellations: List<Cancellation>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.BonkopfRowDto> {
        // M2: ONE Vorgang per cancel action, carrying that action's N lines — mirroring
        // generateOrderTransactions, which emits one Vorgang per Orders entity. UMS_BRUTTO is the
        // batch total, so it matches the single signed payload covering all its lines.
        return cancellations.groupBy(::cancellationBatchKey).mapNotNull { (batchKey, batch) ->
            val first = batch.minBy { it.id.value }
            val zBon =
                zBons.firstOrNull { it.id.value == first.zBon?.id?.value }
                    ?: closingFor(zBons, first.createdAt)
                    ?: return@mapNotNull null

            val amount = batch.fold(java.math.BigDecimal.ZERO) { acc, c -> acc + c.orderItem.finalPrice }

            DsfinvkExportDto.BonkopfRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                bonId = bonIdForCancellationBatch(batchKey),
                bonNr = BonNrRanges.CANCELLATION + first.id.value,
                bonTyp = if (first.isTraining) "AVTraining" else "AVBestellung",
                bonName = if (first.isTraining) "Training-Storno" else "Storno",
                terminalId = null, // filled by DsfinvkTerminalAttribution from the signing ClientID (Anhang E p. 85)
                bonStorno = "0",
                bonStart = formatDateTime(batch.minOf { it.createdAt }),
                bonEnde = formatDateTime(batch.maxOf { it.createdAt }),
                bedienerId = first.employeeId.employeeNumber,
                bedienerName = employeeDisplayName(first.employeeId),
                umsBrutto = amount.negate().toDecimalString(),
                kundeName = null,
                kundeId = null,
                kundeTyp = null,
                kundeStrasse = null,
                kundePlz = null,
                kundeOrt = null,
                kundeLand = null,
                kundeUstId = null,
                bonNotiz = first.reason?.take(255),
            )
        }
    }

    /**
     * Generates transactions_tse.csv row DTOs from Orders with TSE data.
     *
     * Emits a row for every order, including unsigned/failed ones (TSE_TA_FEHLER populated,
     * signature fields empty) so transactions_tse.csv stays 1:1 with transactions.csv.
     */
    private fun generateOrderTransactionsTse(
        orders: List<Orders>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        tseIdBySerial: Map<String, Int>,
    ): List<DsfinvkExportDto.TseTransaktionenRowDto> {
        val lateSigning = lateSigningRepository.notesFor(TseEntityType.ORDER, orders.map { it.id.value.toString() })
        val pairs =
            orders.mapNotNull { order ->
                val tse = order.tseTransaction
                // Only a NORMAL-mode transaction is genuine Absicherung: simulation rows carry
                // fabricated pseudo-signatures, unsigned rows no device at all (STAM-4/5).
                val real = tse?.takeIf { it.tseMode == TseMode.NORMAL }

                val zBon = orderClosingFor(order, zBons) ?: return@mapNotNull null

                order.id.value.toString() to
                    DsfinvkExportDto.TseTransaktionenRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        bonId = bonIdForOrder(order.id.value),
                        tseId = tseIdFor(real, tseIdBySerial),
                        tseTanr = real?.transactionNumber,
                        // TSE_TA_START: prefer the TSE hardware's own StartTransaction log time
                        // (tseStartTimestamp, populated from TransactionStartResultDto.timestamp in
                        // RealTseTransactionServiceImpl.startTransaction() — Task 17b) over the server
                        // clock (startTime), matching the TSE_TA_ENDE precedence below.
                        // M6: resolved by the SHARED chain the receipt QR uses, so one Vorgang cannot
                        // report two different start times on the two surfaces (Anhang I p. 123 and
                        // Anhang E p. 104 define the same value).
                        tseTaStart = real?.resolvedStartLogTimeSeconds()?.let { formatTseLogTime(it) },
                        // TSE_TA_ENDE: prefer the TSE hardware's own log time (tseTimestamp, populated from
                        // the finish/update/quickSign response) over the server clock (endTime) — DSFinV-K
                        // wants the TSE's own FinishTransaction log time, not when the server observed it.
                        tseTaEnde =
                            real?.tseTimestamp?.let { formatTseLogTime(it) }
                                ?: real?.endTime?.let { formatTseLogTime(it.toInstant(getTimeZone()).epochSeconds) },
                        tseTaVorgangsart = real?.processType,
                        tseTaSigz = real?.signatureCounter,
                        tseTaSig = real?.signature,
                        tseTaFehler = resolveTseTaFehler(tse, order.tseSigningStatus, lateSigning[order.id.value.toString()]),
                        tseVorgangsdaten = tseVorgangsdatenFor(real),
                    )
            }

        return pairs.map { it.second } + supersededRowsFor(setOf(TseEntityType.ORDER), pairs.toMap(), tseIdBySerial)
    }

    /**
     * Generates transactions_tse.csv row DTOs from Cancellations with TSE data.
     *
     * Emits a row for every cancellation, including unsigned/failed ones (TSE_TA_FEHLER
     * populated, signature fields empty) so transactions_tse.csv stays 1:1 with transactions.csv.
     */
    private fun generateCancellationTransactionsTse(
        cancellations: List<Cancellation>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        tseIdBySerial: Map<String, Int>,
    ): List<DsfinvkExportDto.TseTransaktionenRowDto> {
        // M2: ONE row per Vorgang, so transactions_tse stays 1:1 with transactions.csv. The batch
        // shares a single TSE transaction (one signature over one payload covering all its lines),
        // which is exactly what makes that signature verifiable against this Beleg.
        // The queue keys a cancellation Vorgang by its batch id — the same key as the grouping here.
        val lateSigning = lateSigningRepository.notesFor(TseEntityType.CANCELLATION, cancellations.map(::cancellationBatchKey))
        val pairs =
            cancellations.groupBy(::cancellationBatchKey).mapNotNull { (batchKey, batch) ->
                val cancellation = batch.minBy { it.id.value }
                val tse = cancellation.tseTransaction
                // Only a NORMAL-mode transaction is genuine Absicherung: simulation rows carry
                // fabricated pseudo-signatures, unsigned rows no device at all (STAM-4/5).
                val real = tse?.takeIf { it.tseMode == TseMode.NORMAL }

                val zBon =
                    zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                        ?: closingFor(zBons, cancellation.createdAt)
                        ?: return@mapNotNull null

                batchKey to
                    DsfinvkExportDto.TseTransaktionenRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        bonId = bonIdForCancellationBatch(batchKey),
                        tseId = tseIdFor(real, tseIdBySerial),
                        tseTanr = real?.transactionNumber,
                        // TSE_TA_START: prefer the TSE hardware's own StartTransaction log time
                        // (tseStartTimestamp, populated from TransactionStartResultDto.timestamp in
                        // RealTseTransactionServiceImpl.startTransaction() — Task 17b) over the server
                        // clock (startTime), matching the TSE_TA_ENDE precedence below.
                        // M6: resolved by the SHARED chain the receipt QR uses, so one Vorgang cannot
                        // report two different start times on the two surfaces (Anhang I p. 123 and
                        // Anhang E p. 104 define the same value).
                        tseTaStart = real?.resolvedStartLogTimeSeconds()?.let { formatTseLogTime(it) },
                        // TSE_TA_ENDE: prefer the TSE hardware's own log time (tseTimestamp, populated from
                        // the finish/update/quickSign response) over the server clock (endTime) — DSFinV-K
                        // wants the TSE's own FinishTransaction log time, not when the server observed it.
                        tseTaEnde =
                            real?.tseTimestamp?.let { formatTseLogTime(it) }
                                ?: real?.endTime?.let { formatTseLogTime(it.toInstant(getTimeZone()).epochSeconds) },
                        tseTaVorgangsart = real?.processType,
                        tseTaSigz = real?.signatureCounter,
                        tseTaSig = real?.signature,
                        tseTaFehler = resolveTseTaFehler(tse, cancellation.tseSigningStatus, lateSigning[batchKey]),
                        tseVorgangsdaten = tseVorgangsdatenFor(real),
                    )
            }

        return pairs.map { it.second } + supersededRowsFor(setOf(TseEntityType.CANCELLATION), pairs.toMap(), tseIdBySerial)
    }

    /**
     * Generates lines.csv row DTOs from Order items using BonposRowDto.
     *
     * Each order item creates a separate BonposRowDto with positive amounts.
     * Uses GV_TYP = "Umsatz" for standard sales.
     */
    private fun generateOrderLineItems(
        orders: List<Orders>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        orderLineTerminals: OrderLineTerminals,
    ): List<DsfinvkExportDto.BonposRowDto> {
        return orders.flatMap { order ->
            val zBon = orderClosingFor(order, zBons) ?: return@flatMap emptyList()

            var lineNumber = 1
            order.items.toList().map { item ->
                // Round-3 BPOS-2/3: an ordered line mirrors the goods classification (a Pfand
                // line is a Pfand movement, not "Umsatz"), and Tz. 4.2.5 puts the sign in MENGE
                // — STK_BR stays the positive (Grund)Preis even for a deposit-return line.
                val gvTyp = determineGvTyp(item, false)
                val row =
                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        bonId = bonIdForOrder(order.id.value),
                        posZeile = lineNumber.toString(),
                        gutscheinNr = null,
                        artikeltext = item.product.name,
                        posTerminalId = orderLineTerminals.of(item),
                        gvTyp = gvTyp,
                        gvName = determineGvName(gvTyp),
                        inhaus = if (!item.takeAway) "1" else "0",
                        pStorno = "0",
                        agenturId = null,
                        artNr =
                            item.product.id.value
                                .toString(),
                        gtin = null,
                        warengrId =
                            item.product.category
                                ?.id
                                ?.value
                                ?.toString(),
                        warengr = item.product.category?.name,
                        menge = if (item.finalPrice.signum() < 0) "-1.000" else "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = item.finalPrice.abs().toDecimalString(),
                    )
                lineNumber++
                row
            }
        }
    }

    /**
     * Generates lines.csv row DTOs from Cancellations using BonposRowDto.
     *
     * Each cancellation references one OrderItem. Creates a single BonposRowDto
     * with negative amount representing the cancelled item.
     */
    private fun generateCancellationLineItems(
        cancellations: List<Cancellation>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        orderLineTerminals: OrderLineTerminals,
        /** BON_ID → attributed TERMINAL_ID; the till of an orderless cancelled item (its own Bon's signer). */
        bonTerminalIds: Map<String, String>,
    ): List<DsfinvkExportDto.BonposRowDto> {
        // M2: the batch is ONE Vorgang, so its lines are numbered 1..N within it — exactly as
        // generateOrderLineItems numbers an order's lines.
        return cancellations.groupBy(::cancellationBatchKey).flatMap { (batchKey, batch) ->
            batch
                .sortedBy { it.id.value }
                .withIndex()
                .mapNotNull { (index, cancellation) ->
                    val zBon =
                        zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                            ?: closingFor(zBons, cancellation.createdAt)
                            ?: return@mapNotNull null

                    val item = cancellation.orderItem

                    DsfinvkExportDto.BonposRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        bonId = bonIdForCancellationBatch(batchKey),
                        posZeile = (index + 1).toString(),
                        gutscheinNr = null,
                        artikeltext = item.product.name,
                        posTerminalId = orderLineTerminals.of(item, orderlessFallback = bonTerminalIds[bonIdForCancellationBatch(batchKey)]),
                        // The reversing record mirrors the ordered line's classification — a Pfand
                        // line's cancellation is a Pfand reversal, not an "Umsatz" (audit BPOS-15).
                        gvTyp = determineGvTyp(item, false),
                        gvName = determineGvName(determineGvTyp(item, false)),
                        inhaus = if (!item.takeAway) "1" else "0",
                        pStorno = "0",
                        agenturId = null,
                        artNr =
                            item.product.id.value
                                .toString(),
                        gtin = null,
                        warengrId =
                            item.product.category
                                ?.id
                                ?.value
                                ?.toString(),
                        warengr = item.product.category?.name,
                        // Reversal of the ordered line: MENGE carries the sign (Tz. 4.2.5, BPOS-5);
                        // arithmetic negation — the old "-${'$'}{...}" string prefix produced the
                        // unparseable --0,25 for deposit-return lines (H13a).
                        menge = if (item.finalPrice.signum() > 0) "-1.000" else "1.000",
                        faktor = "1.000",
                        einheit = "Stk",
                        stkBr = item.finalPrice.abs().toDecimalString(),
                    )
                }
        }
    }

    /**
     * Aggregates Kassenbuch entries by GV_TYP for businesscases.csv using ZGvTypRowDto.
     *
     * Groups Kassenbuch entries by their mapped GV_TYP and calculates
     * aggregate totals for each business case type within each Z-Bon period.
     */
    @TestOnlyApi
    fun aggregateKassenbuchBusinessCases(
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.ZGvTypRowDto> {
        val result = mutableListOf<DsfinvkExportDto.ZGvTypRowDto>()

        // Group entries by Z-Bon (or by date if no Z-Bon assigned)
        zBons.forEach { zBon ->
            val zBonId = zBon.id.value

            // Find Kassenbuch entries for this Z-Bon period. Round-3 INV-F8: use the SAME
            // single-closing resolution as generateKassenbuchTransactions — the old inline
            // `createdAt <= endTime` predicate double-counted an entry stamped exactly on a
            // shared closing boundary into both adjacent closings' businesscases.
            val entriesForZBon =
                kassenbuchEntries.filter { entry ->
                    kassenbuchClosingFor(entry, zBons)?.id?.value == zBonId
                }

            // Aggregate by GV_TYP and UST_SCHLUESSEL
            data class AggregateKey(
                val gvTyp: String,
                val ustSchluessel: Int,
            )
            val aggregates = mutableMapOf<AggregateKey, KassenbuchBusinessCaseAggregate>()

            entriesForZBon.forEach { entry ->
                // Tz. 4 p. 31: only „Beleg" Vorgänge aggregate into the Kassenabschluss
                // (round-3 INV-F5). Legacy CLOSING_BALANCE snapshots are AVSonstige and must NOT
                // inflate the closing as a phantom Anfangsbestand; OPENING_BALANCE (now Beleg per
                // Anhang C p. 56) aggregates correctly as its genuine Anfangsbestand.
                if (KassenbuchDsfinvkMapping.getBonTyp(entry.entryType) !=
                    DsfinvkExportDto.ReceiptTypes.BELEG
                ) {
                    return@forEach
                }

                // Process each line item separately for proper VAT rate handling
                entry.lineItems.forEach { lineItem ->
                    val lineAmount = lineItem.getNumericAmount()
                    val gvTyp =
                        KassenbuchDsfinvkMapping.getGvTyp(
                            entry.entryType,
                            lineItem.kind,
                            lineAmount,
                            entry.correctsHeader?.entryType,
                        )

                    // True signed amount (cash-flow sign) — see generateKassenbuchLineItems.
                    val signedAmount = lineAmount

                    // Calculate VAT if applicable
                    val ustSchluessel: Int
                    val netAmount: BigDecimal
                    val vatAmount: BigDecimal

                    // Round-7 C1/C2: PER-LINE classification through the shared classifier (see
                    // generateAvLineVats). The MEHRZWECKGUTSCHEIN_EINLOESUNG special case that
                    // used to sit here is now redundant — the classifier pins that GV_TYP to the
                    // nicht-steuerbar class by spec (Anhang C p. 54 / V_2_3_02 Bsp. 2.2) — and the
                    // entry-level `hasVat` hoist is gone, so a CASH_WITHDRAWAL line no longer
                    // collapses to key 5 while its identical CASH_EXPENSE twin books key 1.
                    val vatRate =
                        KassenbuchTaxClassifier.effectiveVatRate(
                            gvTyp,
                            lineItem.ustSatz?.let { parseVatRate(it) },
                        )
                    if (vatRate.signum() == 0) {
                        // Nicht steuerbar (key 5) — brutto == netto, no USt. Reached both by a
                        // GV_TYP-pinned line and by a genuinely 0 %/rate-less one.
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR
                        netAmount = signedAmount
                        vatAmount = BigDecimal.ZERO
                    } else {
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble())
                        // Net each KB line at scale 2 (was scale 4 + a later roundVatTriple re-round
                        // to 2 = DOUBLE rounding). This is the SAME round-3 INV-F6 fix applied to
                        // appendKassenbuchBonkopfUst (KB transactions_vat) and generateAvLineVats (KB
                        // lines_vat): all three KB producers now net each line once at scale 2, so
                        // Z_UMS_NETTO == BON_NETTO == Σ POS_NETTO for a KB entry's GV_TYP/key by
                        // construction. Brutto/gross and the aggregated set are unchanged.
                        netAmount = signedAmount.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
                        vatAmount = signedAmount - netAmount
                    }

                    val key = AggregateKey(gvTyp, ustSchluessel)
                    val aggregate = aggregates.getOrPut(key) { KassenbuchBusinessCaseAggregate() }
                    aggregate.brutto += signedAmount
                    aggregate.netto += netAmount
                    aggregate.ust += vatAmount
                }
            }

            // Create result rows
            aggregates.forEach { (key, aggregate) ->
                if (aggregate.brutto != BigDecimal.ZERO) {
                    val (rBrutto, rNetto, rUst) = roundVatTriple(aggregate.brutto, aggregate.netto, aggregate.ust)
                    result.add(
                        DsfinvkExportDto.ZGvTypRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = formatDateTime(zBon.createdAt),
                            zNr = zBon.zbonNummer,
                            gvTyp = key.gvTyp,
                            gvName = KassenbuchDsfinvkMapping.getGvName(key.gvTyp),
                            agenturId = null,
                            ustSchluessel = key.ustSchluessel,
                            zUmsBrutto = rBrutto.toDecimalString(),
                            zUmsNetto = rNetto.toDecimalString(),
                            zUst = rUst.toDecimalString(),
                        ),
                    )
                }
            }
        }

        return result
    }

    /**
     * Helper class for aggregating Kassenbuch business case amounts.
     */
    private class KassenbuchBusinessCaseAggregate {
        var brutto: BigDecimal = BigDecimal.ZERO
        var netto: BigDecimal = BigDecimal.ZERO
        var ust: BigDecimal = BigDecimal.ZERO
    }

    /**
     * Parses VAT rate string to BigDecimal.
     *
     * Handles various formats: "19", "19,00", "0.19"
     */
    private fun parseVatRate(vatRateStr: String): BigDecimal {
        val normalized = vatRateStr.replace(",", ".")
        val decimal = normalized.toBigDecimalOrNull() ?: return BigDecimal.ZERO

        // If value >= 1, it's a percentage (19 for 19%)
        // Convert to decimal (0.19)
        return if (decimal >= BigDecimal.ONE) {
            decimal.divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
        } else {
            decimal
        }
    }

    // ============================================
    // DSFinV-K Phase 1 & 2 New CSV Generation Methods
    // ============================================

    /**
     * Generates allocation_groups.csv row DTOs from bills.
     *
     * Maps each bill to its allocation group (table session) for proper
     * revenue attribution. Required by DSFinV-K for table-based operations.
     */
    private fun generateAllocationGroups(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        orders: List<Orders> = emptyList(),
        cancellations: List<Cancellation> = emptyList(),
    ): List<DsfinvkExportDto.AllocationGroupRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.AllocationGroupRowDto>()

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]

            result.add(
                DsfinvkExportDto.AllocationGroupRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                    zNr = zBon?.zbonNummer ?: 0L,
                    bonId = bonIdForBill(bill.id.value),
                    // Tz. 2.7.1 / GoBD Rz. 94: ABRECHNUNGSKREIS must UNIQUELY identify the Geschäftsvorfall — a bare table number repeats per visit and across the same closing; the session id disambiguates (round-3 BKOPF-2).
                    abrechnungskreis = "Tisch-${bill.tableSession.table.tableNumber}/S-${bill.tableSession.id.value}",
                ),
            )
        }

        // Add allocation groups for orders (table number links orders to eventual bills)
        orders.forEach { order ->
            val zBon = orderClosingFor(order, zBons) ?: return@forEach
            val session = order.tableSession

            result.add(
                DsfinvkExportDto.AllocationGroupRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = bonIdForOrder(order.id.value),
                    abrechnungskreis = "Tisch-${session.table.tableNumber}/S-${session.id.value}",
                ),
            )
        }

        // Add allocation groups for cancellations
        cancellations.forEach { cancellation ->
            val zBon =
                zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                    ?: closingFor(zBons, cancellation.createdAt)
                    ?: return@forEach
            val session = cancellation.orderItem.session

            result.add(
                DsfinvkExportDto.AllocationGroupRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = bonIdForCancellationBatch(cancellationBatchKey(cancellation)),
                    abrechnungskreis = "Tisch-${session.table.tableNumber}/S-${session.id.value}",
                ),
            )
        }

        return result
    }

    /**
     * Header-level `references.csv` row from [bill] to the [original] Vorgang it reverses (a Storno),
     * replaces (a Zahlartwechsel Neubuchung) or re-bills (a sale settling items a "re-open the table"
     * Storno handed back). [zBon] is [bill]'s own closing; the REFERENCED closing is read off
     * [original] itself, so an original in an earlier closing resolves to that closing.
     */
    private fun billHeaderReference(
        bill: Bill,
        zBon: ZBon?,
        original: Bill,
        cashRegisterId: String,
    ): DsfinvkExportDto.ReferenceRowDto {
        // REFERENCED closing read DIRECTLY off the original bill — the export-range
        // map lost it for cross-period stornos (audit BKOPF-6).
        val refZBon = original.zBon
        return DsfinvkExportDto.ReferenceRowDto(
            zKasseId = cashRegisterId,
            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
            zNr = zBon?.zbonNummer ?: 0L,
            bonId = bonIdForBill(bill.id.value),
            posZeile = null, // Header-level reference
            refTyp = DsfinvkExportDto.ReferenceTypes.TRANSAKTION,
            // REF_NAME qualifies only „ExterneSonstige" (Anhang E p. 102).
            refName = null,
            // REF_DATUM = „Zeitstempel des Kassenabschlusses, auf den referenziert
            // wird", full ISO 8601 — not a date-only of the bill's own end time.
            refDatum =
                refZBon?.let { formatDateTime(it.createdAt) }
                    ?: formatDateTime(original.endTime),
            refZKasseId = cashRegisterId,
            refZNr = refZBon?.zbonNummer,
            refBonId = bonIdForBill(original.id.value),
        )
    }

    /**
     * Generates references.csv row DTOs from bills.
     *
     * Creates reference entries for:
     * - Storno bills referencing their original (cancelled) bill
     * - Zahlartwechsel Neubuchungen referencing the stornoed original they replace
     * - Re-bills: a sale settling items a "re-open the table" Storno handed back references each
     *   stornoed original those items came from ([RebilledOriginals], merged with a Neubuchung's own
     *   link so an original is referenced once)
     * - Item-level cancellations referencing original order items
     * - Kassenbuch correction REVERSALs referencing the entry they reverse (round-4 BKOPF-1)
     */
    private fun generateReferences(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        plans: Map<Long, org.example.project.export.dsfinvk.builder.BonposLinePlan> = emptyMap(),
        kassenbuchEntries: List<KassenbuchHeader> = emptyList(),
        cancellations: List<Cancellation> = emptyList(),
    ): List<DsfinvkExportDto.ReferenceRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.ReferenceRowDto>()

        // M3: the reversing Vorgang must name what it reverses — Anhang B p. 45 („eine Referenz auf
        // den ursprünglichen Vorgang muss erfolgen") and Tz. 4.2.2.
        //
        // `cancellations` was never passed here at all. The only cancellation branch lived inside
        // the bills loop below, reachable only for a BILLED item holding a cancellation — which the
        // service forbids, since only OPEN items can be cancelled and an OPEN item has no bill. So
        // every Storno Vorgang went out with nothing saying which Bestellung it reverses.
        //
        // The referenced Vorgang is the ORDER: the items were cancelled while OPEN, so they were
        // never billed. One row per cancel action, matching the one Vorgang it exports as (M2).
        cancellations.groupBy(::cancellationBatchKey).forEach { (batchKey, batch) ->
            val cancellation = batch.minBy { it.id.value }
            val cancellationZBon =
                zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                    ?: closingFor(zBons, cancellation.createdAt)
                    ?: return@forEach
            // An order-less line (attached straight to the session) has no original Vorgang to
            // point at; emitting a dangling REF_BON_ID would be worse than emitting nothing.
            val order = cancellation.orderItem.order ?: return@forEach
            // Orders carry no Z-Bon association (they are selected by window), so the referenced
            // closing is resolved the same way generateOrderTransactions resolves it.
            val orderZBon = orderClosingFor(order, zBons)

            result.add(
                DsfinvkExportDto.ReferenceRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(cancellationZBon.createdAt),
                    zNr = cancellationZBon.zbonNummer,
                    bonId = bonIdForCancellationBatch(batchKey),
                    // Header-level: the reference is to the whole reversed Vorgang, not one line.
                    posZeile = null,
                    refTyp = DsfinvkExportDto.ReferenceTypes.TRANSAKTION,
                    // REF_NAME qualifies only „ExterneSonstige" (Anhang E p. 102).
                    refName = null,
                    // REF_DATUM = „Zeitstempel des Kassenabschlusses, auf den referenziert wird".
                    refDatum =
                        orderZBon?.let { formatDateTime(it.createdAt) }
                            ?: formatDateTime(order.orderTime),
                    refZKasseId = cashRegisterId,
                    refZNr = orderZBon?.zbonNummer,
                    refBonId = bonIdForOrder(order.id.value),
                ),
            )
        }

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val deletedBill = bill.deletedBill
            val rebooksBill = bill.rebooksBill
            // Loaded once per bill and shared with the item-level pass below. On a Storno these are
            // the ORIGINAL's items (resolveOrderItems), which is why re-bill derivation skips Stornos.
            val orderItems = resolveOrderItems(bill)

            // Header-level reference: Storno bill referencing original
            if (deletedBill != null) {
                result.add(billHeaderReference(bill, zBon, original = deletedBill, cashRegisterId))
            } else {
                // Neubuchung after a Zahlartwechsel names the stornoed original it replaces, and a sale
                // settling handed-back items names every original they came from (Anhang E, REF_TYP
                // «Transaktion»; Anhang B p. 45). One distinct set: a Neubuchung's copies carry its own
                // original too, which must still be referenced once. The Storno carries its own row.
                val originals =
                    RebilledOriginals.of(
                        rebooksId = rebooksBill?.id?.value,
                        itemSourceIds = orderItems.map { it.readValues[OrderItemTable.duplicatedFromBillId]?.value },
                    )
                originals.forEach { originalId ->
                    val original = if (originalId == rebooksBill?.id?.value) rebooksBill else Bill.findById(originalId)
                    if (original != null) result.add(billHeaderReference(bill, zBon, original = original, cashRegisterId))
                }
            }
            if (deletedBill == null && rebooksBill == null && bill.clearsSalesInvoiceId != null) {
                // 4c-2c: a till clearing references the Vorgang that opened the receivable — the
                // ORIGINAL bill (§Anhang B p. 45, „eine Referenz auf den ursprünglichen Vorgang").
                val originalBill = invoices.originalBillIdOfClearing(bill)?.let { Bill.findById(it) }
                if (originalBill != null) {
                    val refZBon = originalBill.zBon
                    result.add(
                        DsfinvkExportDto.ReferenceRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(bill.id.value),
                            posZeile = null, // Header-level reference
                            refTyp = DsfinvkExportDto.ReferenceTypes.TRANSAKTION,
                            refName = null,
                            refDatum =
                                refZBon?.let { formatDateTime(it.createdAt) }
                                    ?: formatDateTime(originalBill.endTime),
                            refZKasseId = cashRegisterId,
                            refZNr = refZBon?.zbonNummer,
                            refBonId = bonIdForBill(originalBill.id.value),
                        ),
                    )
                }
            }

            // Item-level references: Cancelled order items
            val billId = bill.id.value

            val plan = plans[billId]
            orderItems.forEachIndexed { _, item ->
                val cancellation = item.cancellation
                if (cancellation != null) {
                    // This row belongs to the ORIGINAL bill's Bonkopf (bonId/zNr above are the
                    // bill's own), but must REFERENCE the Cancellation's own independent
                    // transaction — a Cancellation is a full standalone BON_ID (BON_TYP
                    // "AVBestellung"/"AVTraining", its own TSE signing; see
                    // generateCancellationTransactions) with its own Z-Bon assignment
                    // (cancellation.zBon), which can differ from the original bill's Z-Bon/period.
                    // Previously refBonId repeated bonIdForBill(bill.id.value) — a redundant
                    // self-link to the SAME row's own bonId — instead of pointing at the
                    // cancellation Vorgang it is meant to reference (RA-4). refZNr is likewise
                    // corrected to the cancellation's own Z-Bon number, mirroring the header-level
                    // storno reference above (which sources refZNr from the REFERENCED bill's own
                    // zBon, not the referencing row's).
                    // Direction per Anhang E p. 103: the REFERENCING row lives on the
                    // REVERSING Vorgang (the Cancellation), REF_BON_ID is „die ursprüngliche
                    // BON_ID" — the ordered line's bill. Round-3 BKOPF-3/BPOS-1: POS_ZEILE here is
                    // „Zeilennummer des referenzierenden Vorgangs" (index.xml line 1598) — the
                    // Cancellation's OWN single line ("1", see generateCancellationLineItems), NOT
                    // the original line's number. Round-3 CONT-4: resolve the cancellation's
                    // closing with the SAME membership pattern as generateCancellationTransactions
                    // (own closing, else covering window, else SKIP) — never a fabricated Z_NR=0.
                    val cancellationZBon =
                        zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                            ?: closingFor(zBons, cancellation.createdAt)
                            ?: return@forEachIndexed
                    result.add(
                        DsfinvkExportDto.ReferenceRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = formatDateTime(cancellationZBon.createdAt),
                            zNr = cancellationZBon.zbonNummer,
                            bonId = bonIdForCancellationBatch(cancellationBatchKey(cancellation)),
                            posZeile = "1",
                            refTyp = DsfinvkExportDto.ReferenceTypes.TRANSAKTION,
                            refName = null,
                            refDatum =
                                zBon?.let { formatDateTime(it.createdAt) }
                                    ?: formatDateTime(cancellation.createdAt),
                            refZKasseId = cashRegisterId,
                            refZNr = zBon?.zbonNummer,
                            refBonId = bonIdForBill(bill.id.value),
                        ),
                    )
                }
            }
        }

        // Round-4 BKOPF-1: a Kassenbuch correction REVERSAL is a „negative Darstellung eines
        // Beleges" (KassenbuchServiceImpl.correctEntry books CORRECTION_OUT/CORRECTION_IN with the
        // original's amounts sign-inverted, BON_TYP Beleg, linked by `correctsHeaderId`). Tz. 4.2.2
        // p. 35 for exactly that form: „muss ein Datensatz in der Datei: Bon_Referenzen angelegt
        // werden", repeated for the umgekehrte-Vorzeichen Beleg generally in Anhang B p. 45. This
        // producer only ever saw `bills`, so the equivalent bill storno WAS referenced while the
        // Kassenbuch counter-booking was untraceable to the Vorgang it reverses.
        kassenbuchEntries.forEach { entry ->
            // ONLY the REVERSAL. The RE_ENTRY carries the same `correctsHeaderId` link but is a
            // genuine new Geschäftsvorfall — referencing the original from it would assert a
            // Stornierung that the re-booking does not perform.
            if (entry.correctionRole != CorrectionRole.REVERSAL) return@forEach
            val original = entry.correctsHeader ?: return@forEach

            // The REFERENCING row lives in the reversal's own Kassenabschluss. Same membership rule
            // as every other Kassenbuch producer (kassenbuchClosingFor) so the reference cannot
            // claim a closing the header rows were never emitted under; skip rather than fabricate
            // Z_NR=0 (audit H3).
            val zBon = kassenbuchClosingFor(entry, zBons) ?: return@forEach

            // REF_* describe the REFERENCED closing, which is frequently NOT the reversal's: a
            // correction booked today against an entry from a closing three days ago must carry
            // that closing's Z_NR and Zeitstempel. Read the link DIRECTLY off the original entity
            // (mirroring the bill-storno branch above, audit BKOPF-6) so it stays correct even when
            // the referenced closing lies outside `zBons`; only an UNLINKED legacy original falls
            // back to window attribution.
            val refZBon = original.zBon ?: closingFor(zBons, original.createdAt)

            // m9: Anhang E p. 102 — REF_Z_NR/REF_DATUM „ist nur bei REF_TYP «Transaktion» zu füllen",
            // and here the referent IS a Transaktion. If neither the direct link nor window
            // attribution can name the referenced closing (an unlinked legacy original whose closing
            // is not in the exported set), emitting the row would leave REF_Z_NR blank — a
            // non-conformant reference. Fail loud with the entry to fix, the same fail-on-bad-master-
            // data stance as the country (STAM-6) and serial (m13) guards, rather than ship a
            // references.csv an importer silently mis-joins.
            if (refZBon == null) {
                throw fileGenerationFailed(
                    "Kassenbuch reversal ${entry.id.value} references original ${original.id.value}, " +
                        "whose Kassenabschluss cannot be resolved (no zbon link and no closing covers " +
                        "its date) — REF_Z_NR would be blank on a REF_TYP «Transaktion» row " +
                        "(Anhang E p. 102). Link the original entry to its closing and re-export.",
                )
            }

            result.add(
                DsfinvkExportDto.ReferenceRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    bonId = KassenbuchDsfinvkMapping.generateBonId(entry.id.value),
                    // POS_ZEILE is „Zeilennummer des referenzierenden Vorgangs" (index.xml line
                    // 1598) — omitted for a Bonkopf-level reference. Putting the REFERENCED entry's
                    // line number here would produce a dangling (BON_ID, POS_ZEILE) join.
                    posZeile = null,
                    refTyp = DsfinvkExportDto.ReferenceTypes.TRANSAKTION,
                    // REF_NAME qualifies only „ExterneSonstige" (Anhang E p. 102).
                    refName = null,
                    // REF_DATUM = „Zeitstempel des Kassenabschlusses, auf den referenziert wird",
                    // full ISO 8601 — the referenced closing's own timestamp (refZBon is guaranteed
                    // non-null by the m9 guard above), never the reversal's and never a fallback to
                    // the entry's own createdAt.
                    refDatum = formatDateTime(refZBon.createdAt),
                    refZKasseId = cashRegisterId,
                    refZNr = refZBon.zbonNummer,
                    refBonId = KassenbuchDsfinvkMapping.generateBonId(original.id.value),
                ),
            )
        }

        return result
    }

    /**
     * itemamounts.csv (Bonpos_Preisfindung) — intentionally EMPTY (header-only file).
     *
     * Tz. 3.1.1.2: Preisfindungs-Einträge are required only where "der Preis in der Datei
     * Bonpos bereits der rabattierte Preis ist. Wenn der Rabatt über eine separate Buchung
     * abgebildet wird, muss kein Eintrag erfolgen." This POS books every Entgeltminderung
     * as a separate Bonpos Rabatt line (Tz. 4.2.4 first form) — emitting Preisfindung rows
     * IN ADDITION represented the same reduction in two mutually exclusive schemes and made
     * per-position reconciliation flag every discounted item (audit BPOS-8; the old rows
     * also used the invalid TYP "Basispreis", audit H12).
     */
    private fun generateItemAmounts(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        orders: List<Orders> = emptyList(),
        cancellations: List<Cancellation> = emptyList(),
    ): List<DsfinvkExportDto.PriceFindingRowDto> {
        val result = mutableListOf<DsfinvkExportDto.PriceFindingRowDto>()

        /**
         * Decomposes ONE pre-discounted line into `Grundpreis` + `Rabatt`, so Σ PF_BRUTTO
         * reconstructs the STK_BR the Bonpos row charges.
         *
         * Uses the SAME `discountRabattAmount` predicate lines.csv/lines_vat.csv/businesscases.csv
         * decide with (round-4 BPOS-1) — its INV-F7 contract is exactly the identity needed here:
         * round2(charged) − Rabatt == round2(finalPrice). Deciding independently is what previously
         * let producers disagree about whether a sub-cent discount exists at all.
         */
        fun decompose(
            item: OrderItem,
            zBon: ZBon,
            bonId: String,
            posZeile: Int,
        ) {
            val rabatt = discountRabattAmount(item) ?: return
            val base = chargedUnitPrice(item).setScale(2, RoundingMode.HALF_UP)
            val vatKey = DsfinvkExportDto.VatKeyMapping.getVatKey(item.vat.vatRate.toDouble())
            val rate = item.vat.vatRate

            fun row(
                typ: String,
                brutto: BigDecimal,
            ) {
                val netto = brutto.divide(BigDecimal.ONE + rate, 5, RoundingMode.HALF_UP)
                val (b2, n2, u2) = roundVatTriple(brutto, netto, brutto - netto)
                result.add(
                    DsfinvkExportDto.PriceFindingRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = formatDateTime(zBon.createdAt),
                        zNr = zBon.zbonNummer,
                        bonId = bonId,
                        posZeile = posZeile,
                        typ = typ,
                        ustSchluessel = vatKey,
                        pfBrutto = b2.toDecimalString(),
                        pfNetto = n2.toDecimalString(),
                        pfUst = u2.toDecimalString(),
                    ),
                )
            }

            row(DsfinvkExportDto.PriceFindingTypes.BASISPREIS, base)
            row(DsfinvkExportDto.PriceFindingTypes.RABATT, rabatt.negate())
        }

        // BILLS emit nothing: every Entgeltminderung is already booked as its own Bonpos `Rabatt`
        // line, which §3.1.1.2 explicitly exempts — „Wenn der Rabatt über eine separate Buchung
        // abgebildet wird, muss kein Eintrag erfolgen."
        //
        // ORDERS and CANCELLATIONS are the opposite case: their Bonpos rows carry
        // `STK_BR = finalPrice`, already discounted by OrderServiceImpl, with no separate Rabatt
        // line anywhere. That is §3.1.1.2's first sentence — „der Preis in der Datei Bonpos bereits
        // der rabattierte Preis ist" — so the breakdown IS owed, otherwise the reduction is
        // invisible at order level while the same goods show `10,00` + `Rabatt −2,00` on the Beleg.
        orders.forEach { order ->
            val zBon = orderClosingFor(order, zBons) ?: return@forEach
            // POS_ZEILE must match generateOrderLineItems, which numbers 1..N over the same list.
            order.items.toList().forEachIndexed { index, item ->
                decompose(item, zBon, bonIdForOrder(order.id.value), index + 1)
            }
        }

        cancellations.groupBy(::cancellationBatchKey).forEach { (batchKey, batch) ->
            batch.sortedBy { it.id.value }.forEachIndexed { index, cancellation ->
                val zBon =
                    zBons.firstOrNull { it.id.value == cancellation.zBon?.id?.value }
                        ?: closingFor(zBons, cancellation.createdAt)
                        ?: return@forEachIndexed
                decompose(cancellation.orderItem, zBon, bonIdForCancellationBatch(batchKey), index + 1)
            }
        }

        return result
    }

    /**
     * Generates subitems.csv row DTOs from order item extras.
     *
     * Creates sub-item entries for product extras/modifiers attached to order items.
     * Each extra becomes a separate sub-item entry linked to its parent line item.
     */
    private fun generateSubItems(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        plans: Map<Long, org.example.project.export.dsfinvk.builder.BonposLinePlan>,
    ): List<DsfinvkExportDto.SubItemRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.SubItemRowDto>()

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val billId = bill.id.value
            val isBillStorno = bill.deletedBill != null
            val orderItems = resolveOrderItems(bill)

            val plan = plans.getValue(billId)
            orderItems.forEachIndexed { _, item ->
                // POS_ZEILE from the lines.csv numbering authority — a raw item index drifted
                // as soon as an earlier item's Rabatt line shifted the numbering (H11/BPOS-3).
                val lineNumber = plan.itemPos(item.id.value)
                val extras = item.itemExtras

                // Get VAT information for the parent item (extras inherit parent VAT)
                val vatRate = item.vat.vatRate
                val ustKey = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble())

                extras.forEach { extra ->
                    // Calculate VAT breakdown for the snapshotted extra price delta
                    val extraGross = extra.priceDeltaSnapshot
                    val extraNet = extraGross.divide(BigDecimal.ONE + vatRate, 4, RoundingMode.HALF_UP)
                    val extraVat = extraGross - extraNet

                    // For storno: negate
                    val sign = if (isBillStorno) BigDecimal.ONE.negate() else BigDecimal.ONE

                    val (rBrutto, rNetto, rUst) = roundVatTriple(extraGross * sign, extraNet * sign, extraVat * sign)
                    result.add(
                        DsfinvkExportDto.SubItemRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                            zNr = zBon?.zbonNummer ?: 0L,
                            bonId = bonIdForBill(billId),
                            posZeile = lineNumber,
                            ziArtNr =
                                extra.productExtras
                                    ?.id
                                    ?.value
                                    ?.toString(),
                            ziGtin = null,
                            ziName = extra.optionNameSnapshot,
                            ziWarengrId = null,
                            ziWarengr = extra.groupNameSnapshot,
                            ziMenge =
                                java.math
                                    .BigDecimal(extra.quantity)
                                    .setScale(3)
                                    .toPlainString(),
                            ziFaktor = "1.00",
                            ziEinheit = "Stk",
                            ziUstSchluessel = ustKey,
                            ziBasispreisBrutto = rBrutto.toDecimalString(),
                            ziBasispreisNetto = rNetto.toDecimalString(),
                            ziBasispreisUst = rUst.toDecimalString(),
                        ),
                    )
                }
            }
        }

        return result
    }

    // ============================================
    // HIGH PRIORITY: New Master Data Generation Methods
    // ============================================

    /**
     * Generates bonkopf_ust.csv row DTOs from bill VAT details.
     *
     * Per DSFinV-K spec 3.1.2.1: VAT breakdown per receipt header.
     */
    @OptIn(TestOnlyApi::class)
    private fun generateBonkopfUst(
        bills: List<Bill>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        kassenbuchEntries: List<KassenbuchHeader> = emptyList(),
    ): List<DsfinvkExportDto.BonkopfUstRowDto> {
        val zBonMap = zBons.associateBy { it.id.value }
        val result = mutableListOf<DsfinvkExportDto.BonkopfUstRowDto>()

        bills.forEach { bill ->
            val zBon = zBonMap[bill.zBon?.id?.value]
            val billId = bill.id.value

            // A storno reverse bill (deletedBill != null) carries NO BillVatDetail rows of its
            // own — the VAT breakdown lives on the original bill. Source from the original and
            // negate, mirroring resolveOrderItems()/generateLineVats(); otherwise this BON_ID's
            // transactions_vat.csv block would be empty while transactions.csv (UMS_BRUTTO) and
            // lines_vat.csv are correctly negated — a three-way per-BON_ID disagreement (H4).
            val isBillStorno = bill.deletedBill != null
            val sourceBillId = if (isBillStorno) bill.deletedBill!!.id.value else billId
            val vatDetails = BillVatDetail.find { BillVatDetailTable.billId eq sourceBillId }.toList()

            vatDetails.forEach { vatDetail ->
                val vatRate = vatDetail.vat.vatRate.toDouble()
                val effectiveGross = if (isBillStorno) vatDetail.totalAmount.negate() else vatDetail.totalAmount
                val effectiveNet = if (isBillStorno) vatDetail.netAmount.negate() else vatDetail.netAmount
                val effectiveVat = if (isBillStorno) vatDetail.vatAmount.negate() else vatDetail.vatAmount
                val (rBrutto, rNetto, rUst) = roundVatTriple(effectiveGross, effectiveNet, effectiveVat)
                result.add(
                    DsfinvkExportDto.BonkopfUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate),
                        bonBrutto = rBrutto.toDecimalString(),
                        bonNetto = rNetto.toDecimalString(),
                        bonUst = rUst.toDecimalString(),
                    ),
                )
            }

            // Mehrzweckgutschein redemption: negative key-5 decomposition row so that
            // Σ(transactions_vat) == UMS_BRUTTO after the tender netting (C1, example
            // V_2_3_02 Bsp. 2.2 — key 1 +50 / key 5 −50 for a fully voucher-paid 50-€ Bon).
            val voucherTender = voucherTenderTotal(bill)
            if (voucherTender.signum() != 0) {
                result.add(
                    DsfinvkExportDto.BonkopfUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR,
                        bonBrutto = voucherTender.negate().toDecimalString(),
                        bonNetto = voucherTender.negate().toDecimalString(),
                        bonUst = "0.00",
                    ),
                )
            }

            // TrinkgeldAN: non-taxable, no longer a BillVatDetail row (see plan 2026-09-16 P1).
            // lines_vat/businesscases already emit it at key 5; transactions_vat must agree or the
            // per-Beleg BRUTTO is short by the tip. NonTaxableTipCarrier returns ZERO for a
            // pre-upgrade bill, whose own 0 % row already produced a key-5 row in the loop above —
            // emitting another here would double-count it.
            //
            // A reverse bill carries no tip of its own, so the amount is read from the original and
            // negated, exactly as the VAT rows are. outsideVatDetails of the ORIGINAL is the SAME call
            // the signing site in BillServiceImpl.deleteBill ("6. Collect TSE signing data") negates,
            // so the signed WORM payload and this row read one formula.
            val sourceBill = if (isBillStorno) bill.deletedBill!! else bill
            val carried = NonTaxableTipCarrier.outsideVatDetails(sourceBill)
            val employeeTip = if (isBillStorno) carried.negate() else carried
            if (employeeTip.signum() != 0) {
                result.add(
                    DsfinvkExportDto.BonkopfUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR,
                        bonBrutto = employeeTip.toDecimalString(),
                        bonNetto = employeeTip.toDecimalString(),
                        bonUst = BigDecimal.ZERO.toDecimalString(),
                    ),
                )
            }

            // 4c Forderungsentstehung, Soll-Versteuerung (spec §5.4): the real key rows above keep
            // the VAT — it is owed at the sale — and the deferred gross is decomposed to key 5 so
            // Σ(transactions_vat) still equals the netted UMS_BRUTTO. Same shape as the voucher row
            // immediately above, which is the precedent this follows.
            val receivableTender = receivableTenderTotal(bill)
            if (receivableTender.signum() != 0) {
                result.add(
                    DsfinvkExportDto.BonkopfUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { formatDateTime(it.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR,
                        bonBrutto = receivableTender.negate().toDecimalString(),
                        bonNetto = receivableTender.negate().toDecimalString(),
                        bonUst = "0.00",
                    ),
                )
            }

            // 4c-2c: a clearing has no bill_vat_detail to derive from, and an empty list yields NO
            // rows — which fails the compliance gate's Σ(transactions_vat) == UMS_BRUTTO per BON_ID.
            // Under Soll the VAT was booked at the Forderungsentstehung, so the whole amount sits at
            // key 5 with zero USt (worked example 1.6).
            clearingLineAmount(bill)?.let { clearedAmount ->
                result.add(
                    DsfinvkExportDto.BonkopfUstRowDto(
                        zKasseId = cashRegisterId,
                        zErstellung = zBon?.let { z -> formatDateTime(z.createdAt) } ?: "",
                        zNr = zBon?.zbonNummer ?: 0L,
                        bonId = bonIdForBill(billId),
                        ustSchluessel = DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR,
                        bonBrutto = clearedAmount.toDecimalString(),
                        bonNetto = clearedAmount.toDecimalString(),
                        bonUst = "0.00",
                    ),
                )
            }
        }

        appendKassenbuchBonkopfUst(kassenbuchEntries, zBons, cashRegisterId, result)

        return collapseDuplicateVatKeys(result)
    }

    /**
     * Collapses rows that share one `Bonkopf_USt` key into a single summed row.
     *
     * DSFinV-K 3.1.2.1 makes (Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, BON_ID, UST_SCHLUESSEL) the KEY of
     * `Bonkopf_USt`, so emitting that tuple twice is a MALFORMED export — and
     * [org.example.project.export.dsfinvk.builder.BonkopfUstCsvExporter] writes rows 1:1 with no
     * grouping, so both halves would ship.
     *
     * [generateBonkopfUst] appends into one list from FIVE independent sources — the `BillVatDetail`
     * loop, the Mehrzweckgutschein decomposition, the Forderungsentstehung block, the clearing block
     * and the employee-tip row — and FOUR of them emit key 5. The newly reachable collision is a
     * bill with a 0 %-rated ITEM (which maps to key 5) plus an employee tip: the loop emits the item
     * at key 5 and the tip block emits another. Voucher/receivable/clearing rows could already
     * collide with each other and with a 0 % item row, so this is pre-existing in kind.
     *
     * Nothing else could detect it: every invariant in the DSFinV-K suite is a SUM, and a split row
     * sums identically. `DsfinvkComplianceGateTest.assertTransactionsVatKeysAreUnique` is the
     * standing guard.
     *
     * Amounts are summed as [BigDecimal] and formatted ONCE at the end — never by adding formatted
     * strings. `toDecimalString()` is `setScale(2, HALF_UP).toString()`, so parsing back round-trips
     * exactly and a row with no duplicate re-emits byte-identically (it is returned untouched).
     * `groupBy` yields a LinkedHashMap, so groups keep first-appearance order and the export stays
     * byte-stable.
     *
     * Deliberately applied at the END of [generateBonkopfUst], not inside
     * [appendKassenbuchBonkopfUst] — that helper is called directly by producer-level tests with
     * their own sink, and collapsing inside it would silently change those fixtures. The only other
     * consumer of this list derives the set of (Z_NR, UST_SCHLUESSEL) pairs for `stamm_ust.csv`,
     * which is unaffected: collapsing merges duplicate tuples and never removes a distinct one.
     */
    private fun collapseDuplicateVatKeys(rows: List<DsfinvkExportDto.BonkopfUstRowDto>): List<DsfinvkExportDto.BonkopfUstRowDto> =
        rows
            .groupBy { listOf(it.zKasseId, it.zErstellung, it.zNr, it.bonId, it.ustSchluessel) }
            .map { (_, group) ->
                if (group.size == 1) {
                    group.single()
                } else {
                    group.first().copy(
                        bonBrutto = group.sumOf { BigDecimal(it.bonBrutto) }.toDecimalString(),
                        bonNetto = group.sumOf { BigDecimal(it.bonNetto) }.toDecimalString(),
                        bonUst = group.sumOf { BigDecimal(it.bonUst) }.toDecimalString(),
                    )
                }
            }

    /**
     * transactions_vat rows for Beleg-typed Kassenbuch entries (audit H2): movement types
     * without VAT implication get the single key-5 decomposition row of their signed total
     * (Anhang C: Geldtransit/Privateinlage/-entnahme „ohne USt-Bezug (ID 5)"); Umsatz-family
     * entries decompose per line, netting each line ONCE at scale 2 — the SAME scale-2
     * per-line netting used by [aggregateKassenbuchBusinessCases] (Z_UMS_NETTO) and
     * [generateAvLineVats]' KB rows (Σ POS_NETTO), so all three KB producers agree to the cent
     * for a given BON_ID/UST_SCHLUESSEL (round-3 INV-F6). Aggregated per UST_SCHLUESSEL here.
     *
     * Round-4 INV-1: the per-line GV_TYP is resolved through the SAME
     * [KassenbuchDsfinvkMapping.getGvTyp] call the other two producers use, so a
     * MehrzweckgutscheinEinloesung line is bucketed to key 5 / USt 0 here too.
     *
     * Round-7 C1/C2/E3: „movement types without VAT implication" is no longer an ENTRY-level
     * decision at all. The admissible UST_SCHLUESSEL is a property of the LINE's GV_TYP, so every
     * line is classified individually via [KassenbuchTaxClassifier] — the SAME classifier the TSE
     * `Kassenbeleg-V1` builder and the other two CSV VAT producers use. The old entry-level
     * `hasVatImplication` boolean could not represent Anhang I p. 118's entry (one Vorgang mixing a
     * container-5 voucher line with 19 % and 7 % Umsatz lines) and had drifted from the signed TSE
     * payload in BOTH directions — the exporter booked `CASH_WITHDRAWAL` at key 5 although its
     * GV_TYP is the same `Auszahlung` as `CASH_EXPENSE`, while the builder booked the nine
     * spec-pinned key-5 GV_TYPs at their declared rate.
     */
    @TestOnlyApi
    fun appendKassenbuchBonkopfUst(
        kassenbuchEntries: List<KassenbuchHeader>,
        zBons: List<ZBon>,
        cashRegisterId: String,
        result: MutableList<DsfinvkExportDto.BonkopfUstRowDto>,
    ) {
        class VatAcc {
            var brutto: BigDecimal = BigDecimal.ZERO
            var netto: BigDecimal = BigDecimal.ZERO
            var ust: BigDecimal = BigDecimal.ZERO
        }
        kassenbuchEntries
            .filter { KassenbuchDsfinvkMapping.getBonTyp(it.entryType) == DsfinvkExportDto.ReceiptTypes.BELEG }
            .forEach { entry ->
                val zBon =
                    kassenbuchClosingFor(entry, zBons)
                        ?: return@forEach
                val perKey = mutableMapOf<Int, VatAcc>()
                entry.lineItems.forEach { lineItem ->
                    val amount = lineItem.getNumericAmount()
                    // Per-line GV_TYP via the SAME call the other two KB VAT producers use
                    // ([generateAvLineVats], [aggregateKassenbuchBusinessCases]). getGvTyp
                    // returns the header GV_TYP unchanged for non-Umsatz-family entries, so a
                    // GUTSCHEIN line inside e.g. a CASH_EXPENSE keeps its declared rate and is
                    // NOT re-bucketed (pinned by DsfinvkKassenbuchPerLineGvTypTest).
                    val lineGvTyp =
                        KassenbuchDsfinvkMapping.getGvTyp(
                            entry.entryType,
                            lineItem.kind,
                            amount,
                            entry.correctsHeader?.entryType,
                        )
                    // Round-7 C1/C2/E3: the admissible key is a property of the LINE's GV_TYP, so
                    // classification is per-line through the shared classifier. This block used to
                    // be gated by an ENTRY-LEVEL `hasVatImplication` boolean hoisted above the
                    // loop, with a whole-entry else-branch that booked Σ lines at key 5 — a shape
                    // that structurally cannot express Anhang I p. 118's entry (a container-5
                    // MehrzweckgutscheinEinloesung line alongside 19 % and 7 % Umsatz lines) and
                    // needed a hand-written MEHRZWECKGUTSCHEIN_EINLOESUNG patch, now redundant.
                    // The else-branch accumulated `entry.totalAmount`, which the repository
                    // computes as Σ line vorzBetrag, so the per-line sum below is identical for
                    // every entry that used to take it.
                    val rate =
                        KassenbuchTaxClassifier.effectiveVatRate(
                            lineGvTyp,
                            lineItem.ustSatz?.let { parseVatRate(it) },
                        )
                    val (key, net, vat) =
                        if (rate.signum() == 0) {
                            Triple(DsfinvkExportDto.VatKeyMapping.NICHT_STEUERBAR, amount, BigDecimal.ZERO)
                        } else {
                            // Net each line at scale 2 (was scale 4 + a later roundVatTriple re-round
                            // to 2 = DOUBLE rounding) so BON_NETTO/BON_UST match Σ lines_vat
                            // POS_NETTO/POS_UST for this BON_ID/key EXACTLY — generateAvLineVats' KB
                            // rows net each line at scale 2 (round(amount/(1+rate), 2)), so summing
                            // per-line scale-2 nets here reproduces that sum by construction
                            // (round-3 INV-F6, mirrors applyLineVatGroupResidual's single-netting
                            // principle: one net per amount at the emitted scale, no re-round).
                            val net = amount.divide(BigDecimal.ONE + rate, 2, RoundingMode.HALF_UP)
                            Triple(DsfinvkExportDto.VatKeyMapping.getVatKey(rate.toDouble()), net, amount - net)
                        }
                    val acc = perKey.getOrPut(key) { VatAcc() }
                    acc.brutto += amount
                    acc.netto += net
                    acc.ust += vat
                }
                perKey.forEach { (key, acc) ->
                    val (rBrutto, rNetto, rUst) = roundVatTriple(acc.brutto, acc.netto, acc.ust)
                    result.add(
                        DsfinvkExportDto.BonkopfUstRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = formatDateTime(zBon.createdAt),
                            zNr = zBon.zbonNummer,
                            bonId = KassenbuchDsfinvkMapping.generateBonId(entry.id.value),
                            ustSchluessel = key,
                            bonBrutto = rBrutto.toDecimalString(),
                            bonNetto = rNetto.toDecimalString(),
                            bonUst = rUst.toDecimalString(),
                        ),
                    )
                }
            }
    }

    /**
     * Round-5 INV-2: the SINGLE authority for which `VoucherRedeem` rows a Beleg emits as Bonpos
     * lines. Both Bonpos producers ([generateLineItems] and [generateLineVatsInternal]) MUST
     * resolve through this function — sourcing differently in only one of them leaves lines.csv
     * and lines_vat.csv disagreeing on POS_ZEILE.
     *
     * **Non-storno** (a sale): the redemptions tendered on this bill, unchanged.
     *
     * **Storno**: a reverse Beleg emits exactly the redemptions that its own refund actually
     * reversed — i.e. those stamped `reversedByBill == this bill` by `BillServiceImpl.deleteBill`.
     *
     * The storno set is therefore the union of (a) this bill's source-side redemptions with NO
     * stamp and (b) redemptions stamped with this bill's id.
     *
     * Branch (a) is a defensive fallback, NOT load-bearing — do not widen it. `deleteBill` writes
     * `payment.refundedBillId` and `redeem.reversedByBill` in the same loop body, `refundedBillId`
     * has a single writer in the whole server, and `V77__release_core_features.sql` backfilled every
     * pre-existing refunded row, so an unstamped-but-reversed redemption does not occur in
     * production data; (a) is exercised only by hand-built test fixtures. It is kept because it
     * fails in the safe direction: it can only admit a row whose header also nets it.
     *
     * **Widening (a) to "the source bill's redemptions" would re-introduce the INV-2 phantom
     * Bonpos** — and `distinctBy` would mask it rather than surface it. That `distinctBy` is
     * belt-and-braces only: the branches are disjoint by construction (a requires NULL, b requires
     * an FK match) and `Payment.voucherRedeem` is a 1:1 back-reference, so it provably drops
     * nothing for any DB state today. Sorted by id for a deterministic POS_ZEILE order shared by
     * both producers.
     */
    private fun redemptionsForBeleg(
        bill: Bill,
        sourceBillId: Long,
        isBillStorno: Boolean,
    ): List<VoucherRedeem> {
        val onSourceBill =
            Payment
                .find { PaymentTable.billId eq sourceBillId }
                .filter { it.paymentType.isVoucher }
                .mapNotNull { it.voucherRedeem }
        if (!isBillStorno) return onSourceBill
        val legacyUnstamped = onSourceBill.filter { it.reversedByBill == null }
        val reversedByThisBeleg = VoucherRedeem.find { VoucherRedeemTable.reversedByBillId eq bill.id.value }.toList()
        return (legacyUnstamped + reversedByThisBeleg)
            .distinctBy { it.id.value }
            .sortedBy { it.id.value }
    }

    /**
     * Signed voucher-tender total of a bill's payment rows. A Mehrzweckgutschein redemption
     * is a negative key-5 Geschäftsvorfall, not a tender (Anhang D „Keine"; official example
     * V_2_3_02) — UMS_BRUTTO, transactions_vat and datapayment all net it consistently.
     */

    /**
     * The part of [bill] settled "auf Rechnung" — the customer owes it, no money moved (slice 4c).
     *
     * Structurally the AR twin of [voucherTenderTotal]: both are legs that are **not a tender**, so
     * both are netted out of `UMS_BRUTTO`, both add a negative key-5 decomposition row so
     * Σ(transactions_vat) still equals the header, and both are excluded from the Zahlarten rows.
     * They differ only in the business case — `Forderungsentstehung` against
     * `MehrzweckgutscheinEinloesung` — and in why the money is absent.
     *
     * Soll-Versteuerung (spec §5.4): the VAT is booked at the sale, so the real key rows stay and
     * only the deferred gross is decomposed to key 5. Under Ist it would be the other way round;
     * this system does not support Ist.
     */
    private fun receivableTenderTotal(bill: Bill): BigDecimal {
        val isStorno = bill.deletedBill != null
        val payments =
            if (isStorno) {
                bill.refundPayments.toList()
            } else {
                Payment.find { PaymentTable.billId eq bill.id.value }.toList()
            }
        val sum = payments.filter { it.createsReceivable }.sumOf { it.amount }
        return if (isStorno) sum.negate() else sum
    }

    /**
     * The amount of the single Forderungsauflösung line of a clearing bill, or null when [bill]
     * clears no sales invoice (slice 4c-2c). ONE definition shared by lines, lines_vat,
     * transactions_vat and businesscases so the four cannot disagree (T9-5).
     */
    private fun clearingLineAmount(bill: Bill): BigDecimal? = bill.clearsSalesInvoiceId?.let { bill.billAmount }

    /**
     * T9-5: the businesscases twins of the two receivable GV lines lines.csv emits — the negative
     * Forderungsentstehung balancing line (the deferred gross) and the positive Forderungsauflösung
     * line of a clearing. Both sit at key 5 with zero USt, signed exactly as the Bonpos rows are, so
     * businesscases reconciles with lines_vat per (Z_NR, GV_TYP, UST_SCHLUESSEL). Before this,
     * `generateBusinessCases` had no branch for either, and a closing holding only clearings
     * produced no businesscases rows at all.
     */
    private fun aggregateReceivableBusinessCases(
        bill: Bill,
        add: (gvTyp: String, amount: BigDecimal) -> Unit,
    ) {
        val receivable = receivableTenderTotal(bill)
        if (receivable.signum() != 0) {
            add(DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSENTSTEHUNG, receivable.negate())
        }
        clearingLineAmount(bill)?.let { add(DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSAUFLOESUNG, it) }
    }

    private fun voucherTenderTotal(bill: Bill): BigDecimal {
        val isStorno = bill.deletedBill != null
        val payments =
            if (isStorno) {
                bill.refundPayments.toList()
            } else {
                Payment.find { PaymentTable.billId eq bill.id.value }.toList()
            }
        val sum = payments.filter { it.paymentType.isVoucher }.sumOf { it.amount }
        return if (isStorno) sum.negate() else sum
    }

    /**
     * The shop master data that applies to ONE Kassenabschluss — DSFinV-K Tz. 3.2 (p.23):
     * „Werden Änderungen an den … Stammdaten vorgenommen, ist zuvor automatisch ein Abschluss zu
     * erstellen" (audit KABS-2).
     *
     * Fields are already in export form: [plz] is padded to 5 digits, [land] is still the RAW
     * country string and must go through `toIsoAlpha3Country` at the emit site — exactly as the
     * live-row path did, so a snapshotted and a fallback closing produce identical bytes for
     * identical data.
     */
    private data class ClosingStammdaten(
        val name: String,
        val strasse: String,
        val plz: String,
        val ort: String,
        val land: String,
        val stNr: String?,
        val ustId: String?,
    )

    /**
     * Resolves the Stammdaten for [zBon]: its own snapshot when present, else the LIVE
     * [shopInfo] row.
     *
     * The fallback is load-bearing, not a convenience. Closings created before V95 have no
     * snapshot, and NULL is the honest representation — we cannot reconstruct what the shop data
     * was at those closings, and stamping today's values on them would fabricate history. Falling
     * back preserves exactly the pre-fix behaviour for those rows while every new closing becomes
     * stable.
     *
     * [ZBon.stammName] is the discriminator: it is non-null exactly when a snapshot was taken.
     *
     * Used by BOTH [generateStammAbschluss] and [generateStammOrte] — the only two producers that
     * emit shop master data per Z_NR. Leaving either on the live row would reintroduce the finding
     * in the file that was missed.
     */
    private fun stammdatenFor(
        zBon: ZBon,
        shopInfo: ShopInfo,
    ): ClosingStammdaten {
        val snapshotName =
            zBon.stammName
                ?: return ClosingStammdaten(
                    name = shopInfo.shopName,
                    strasse = shopInfo.shopAddress ?: "",
                    plz = shopInfo.zipCode.toString().padStart(5, '0'),
                    ort = shopInfo.shopCity ?: "",
                    land = shopInfo.shopCountry,
                    stNr = shopInfo.shopTaxNumber,
                    ustId = shopInfo.ustIdNr,
                )
        return ClosingStammdaten(
            name = snapshotName,
            strasse = zBon.stammStrasse ?: "",
            plz = zBon.stammPlz ?: shopInfo.zipCode.toString().padStart(5, '0'),
            ort = zBon.stammOrt ?: "",
            land = zBon.stammLand ?: shopInfo.shopCountry,
            stNr = zBon.stammStnr,
            ustId = zBon.stammUstId,
        )
    }

    /**
     * Round-7 C4 — the Z_NR of every exported closing whose RESOLVED Stammdaten carry neither a
     * Steuernummer nor a USt-IdNr (Anhang E p. 68, § 14 Abs. 4 Nr. 2 UStG).
     *
     * Deliberately routed through [stammdatenFor], the same resolution [generateStammAbschluss]
     * and [generateStammOrte] use to EMIT those fields. Re-deriving the fallback here would create
     * a second reading of one fiscal fact, which is precisely how the gate and the emitter drifted
     * apart in the first place: round-5's KABS-2 changed what the package emits without changing
     * what the gate validates.
     *
     * A pre-snapshot closing ([ZBon.stammName] == null) therefore keeps falling back to the live
     * row here exactly as it does in the emitted CSV — same verdict, same values.
     */
    private fun closingsMissingTaxId(
        zBons: List<ZBon>,
        shopInfo: ShopInfo,
    ): List<Long> =
        zBons
            .filter { zBon ->
                val stammdaten = stammdatenFor(zBon, shopInfo)
                stammdaten.stNr.isNullOrBlank() && stammdaten.ustId.isNullOrBlank()
            }.map { it.zbonNummer }
            .sorted()

    /**
     * Generates stamm_abschluss.csv row DTOs from Z-reports and shop info.
     *
     * Per DSFinV-K spec 3.2.1: Closing master data with company information.
     *
     * `Z_START_ID`/`Z_ENDE_ID` = "Erste/Letzte BON_ID im Abschluss" — this spans EVERY
     * transaction type emitted for the closing (bills, orders, cancellations, Kassenbuch
     * entries), not just bills (Task 19 / M7), ordered chronologically (`BON_START`, tie-breaks
     * `BON_ENDE` then `BON_ID`) exactly like [sortTransactionsForClosing].
     */
    private fun generateStammAbschluss(
        zBons: List<ZBon>,
        shopInfo: ShopInfo,
        cashRegisterId: String,
        transactions: List<DsfinvkExportDto.BonkopfRowDto>,
        paymentSummaryRows: List<DsfinvkExportDto.ZZahlartRowDto>,
    ): List<DsfinvkExportDto.StammAbschlussRowDto> =
        zBons.map { zBon ->
            val zBonTransactions =
                transactions
                    .filter { it.zNr == zBon.zbonNummer }
                    .sortedWith(compareBy({ it.bonStart }, { it.bonEnde }, { it.bonId }))

            val firstBonId = zBonTransactions.firstOrNull()?.bonId ?: ""
            val lastBonId = zBonTransactions.lastOrNull()?.bonId ?: ""

            // Z_SE_ZAHLUNGEN = Σ of ALL payment.csv rows of the closing, Z_SE_BARZAHLUNGEN =
            // Σ of its Bar rows — computed from the very rows payment.csv emits (which in turn
            // derive from datapayment), so the closing identity holds by construction (KABS-2/4).
            val stammdaten = stammdatenFor(zBon, shopInfo)
            val closingRows = paymentSummaryRows.filter { it.zNr == zBon.zbonNummer }
            val totalPayments = closingRows.sumOf { BigDecimal(it.zZahlartBetrag) }
            val cashPayments =
                closingRows
                    .filter { it.zahlartTyp == DsfinvkExportDto.PaymentTypes.BAR }
                    .sumOf { BigDecimal(it.zZahlartBetrag) }

            DsfinvkExportDto.StammAbschlussRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                // Round-3 CONT-3/KABS-7: Z_BUCHUNGSTAG is the trading-day deviation marker —
                // populated only when the closing's business happened on a different day from its
                // creation (an after-midnight closing books to the previous trading day). A
                // same-day closing needs no override and leaves it empty.
                //
                // The trading day is the day of the closing's OWN earliest Bon. It used to be
                // `zBon.startTime`, which is not when this closing's business began but when the
                // PREVIOUS closing ended: for a shop that closes every evening that is yesterday,
                // and after a closed day the day before that. Every ordinary evening closing was
                // therefore exported as the previous day — 136 of the 140 closings in the
                // production archive, e.g. Z 137 created 09.09. declared as trading day 08.09.,
                // a day that already had its own closing (E2E-2026-09-22 R3-2).
                //
                // A closing with no transactions at all claims nothing: no Bon, no trading day.
                zBuchungstag =
                    zBonTransactions
                        .firstOrNull()
                        ?.bonStart
                        ?.take(DATE_PREFIX_LENGTH)
                        ?.takeIf { it != formatDate(zBon.createdAt) } ?: "",
                taxonomieVersion = "2.4",
                zStartId = firstBonId,
                zEndeId = lastBonId,
                // Per-closing snapshot when this closing has one, else the live row (KABS-2).
                name = stammdaten.name,
                strasse = stammdaten.strasse,
                // German PLZ are 5 digits; the INTEGER column drops leading zeros (Dresden
                // 01067 → 1067) — padded at snapshot time, and at the export seam for the
                // fallback path (audit STAM-9/KABS-10).
                plz = stammdaten.plz,
                ort = stammdaten.ort,
                land = toIsoAlpha3Country(stammdaten.land),
                stNr = stammdaten.stNr,
                // STNR and USTID are ALTERNATIVES: a Steuernummer (12/345/67890) is NOT a
                // § 27a USt-IdNr (DE + 9 digits) — never copy it here (audit STAM-8/KABS-9).
                ustId = stammdaten.ustId,
                zSeZahlungen = totalPayments.toDecimalString(),
                zSeBarzahlungen = cashPayments.toDecimalString(),
            )
        }

    /**
     * Generates stamm_orte.csv row DTOs from shop info.
     *
     * Per DSFinV-K spec 3.2.2: Business location information.
     */
    private fun generateStammOrte(
        zBons: List<ZBon>,
        shopInfo: ShopInfo,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.StammOrteRowDto> =
        zBons.map { zBon ->
            // Same per-closing snapshot as cashpointclosing.csv — both Stamm producers or
            // neither, else a shop edit still rewrites a delivered closing in this file (KABS-2).
            val stammdaten = stammdatenFor(zBon, shopInfo)
            DsfinvkExportDto.StammOrteRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                locName = stammdaten.name,
                locStrasse = stammdaten.strasse,
                locPlz = stammdaten.plz,
                locOrt = stammdaten.ort,
                locLand = toIsoAlpha3Country(stammdaten.land),
                locUstId = stammdaten.ustId, // never the Steuernummer (audit STAM-8)
            )
        }

    /**
     * Generates stamm_kassen.csv row DTOs from shop info.
     *
     * Per DSFinV-K spec 3.2.3: Cash register/POS information.
     */
    private fun generateStammKassen(
        zBons: List<ZBon>,
        shopInfo: ShopInfo,
        cashRegisterId: String,
        kassenConfig: ShopConfigurationDto.KassenMeldungConfigDto = ShopConfigurationDto.KassenMeldungConfigDto(),
        keineUstZuordnungZNrs: Set<Long> = emptySet(),
    ): List<DsfinvkExportDto.StammKassenRowDto> =
        zBons.map { zBon ->
            // Round-7 C3: per-closing snapshot when this closing has one, else the live config —
            // the same shape as [stammdatenFor] one file over. Resolution (the ifBlank defaults)
            // lives in KassenStammdatenSnapshot so the snapshot and the fallback cannot emit
            // different bytes for the same data.
            val kasse = KassenStammdatenSnapshot.resolveFor(zBon, kassenConfig)
            DsfinvkExportDto.StammKassenRowDto(
                zKasseId = cashRegisterId,
                zErstellung = formatDateTime(zBon.createdAt),
                zNr = zBon.zbonNummer,
                kasseBrand = kasse.brand,
                kasseModell = kasse.modell,
                // NOT snapshotted, on purpose: Z_KASSE_ID above is derived from this very value,
                // and it is the join key every DSFinV-K file carries. Freezing it per closing
                // while Z_KASSE_ID stayed live would desynchronise the two and break the joins.
                kasseSerienNr = kassenConfig.kasseSeriennr.ifBlank { "SN-${shopInfo.id.value}" },
                kasseSwBrand = kasse.swBrand,
                kasseSwVersion = kasse.swVersion,
                kasseBasiswaehCode = "EUR",
                // "1" exactly when key 7 (UmsatzsteuerNichtErmittelbar) appears in THIS
                // closing's own rows — Tz. 3.2.6: „Wird die ID 7 eingesetzt, ist dies im
                // Kassenabschluss im Datenfeld KEINE_UST_ZUORDNUNG zu dokumentieren" (STAM-7);
                // evaluated per zNr, not blanket across the export range (round-3 STAM-3).
                keineUstZuordnung = if (zBon.zbonNummer in keineUstZuordnungZNrs) "1" else null,
            )
        }

    /**
     * Generates stamm_ust.csv row DTOs for VAT rate definitions.
     *
     * Per DSFinV-K spec 3.2.6: VAT rate definitions.
     */
    private fun generateStammUst(
        zBons: List<ZBon>,
        cashRegisterId: String,
        /**
         * m12: the USt keys each closing's own rows actually reference, keyed by `Z_NR`.
         *
         * §3.2.6: „Die Zuordnung der **verwendeten** Umsatzsteuerschlüssel wird innerhalb der
         * DSFinV-K in den Stammdaten festgelegt." — the keys in USE, not the whole Anlage-2
         * catalogue. Declaring all 15 for every closing padded the file with untrue lines: key 3
         * carries TODAY's §24 rate, which is wrong for a historical closing that never used it, and
         * key 7 carries a KEINE_UST_ZUORDNUNG documentation obligation the moment it is *used*.
         *
         * An empty map keeps the full catalogue, so callers that do not compute usage are unchanged.
         */
        usedKeysByZNr: Map<Long, Set<Int>> = emptyMap(),
    ): List<DsfinvkExportDto.StammUstRowDto> {
        val result = mutableListOf<DsfinvkExportDto.StammUstRowDto>()

        // Umsatzsteuerschlüssel per Anlage 2 (Stand 05.12.2024, audit STAM-6/7).
        // IDs 1-4 carry NO fixed rate in Anlage 2 ("zum Zeitpunkt der Erfassung geltender
        // Satz") — declared here with the CURRENT statutory value; the historical IDs
        // 11/13/21/22/23/33/43 are immutable per the Anlage table. The old key-3=8.40
        // misread the table: 8.40 % is the HISTORICAL ID 43 (historisch ab 01.01.2025).
        val vatRates =
            listOf(
                Triple(1, "19.00", "Allgemeiner Steuersatz (§ 12 Abs. 1 UStG)"),
                Triple(2, "7.00", "Ermäßigter Steuersatz (§ 12 Abs. 2 UStG)"),
                Triple(3, "7.80", "Durchschnittssatz (§ 24 Abs. 1 Nr. 3 UStG)"),
                Triple(4, "5.50", "Durchschnittssatz (§ 24 Abs. 1 Nr. 1 UStG)"),
                Triple(5, "0.00", "Nicht steuerbar"),
                Triple(6, "0.00", "Umsatzsteuerfrei"),
                Triple(7, "0.00", "UmsatzsteuerNichtErmittelbar"),
                Triple(11, "19.00", "Historischer allgemeiner Steuersatz (§ 12 Abs. 1 UStG)"),
                Triple(12, "7.00", "Historischer ermäßigter Steuersatz (§ 12 Abs. 2 UStG)"),
                Triple(13, "10.70", "Historischer Durchschnittssatz (§ 24 Abs. 1 Nr. 3 UStG)"),
                Triple(21, "16.00", "Historischer allgemeiner Steuersatz (§ 12 Abs. 1 UStG)"),
                Triple(22, "5.00", "Historischer ermäßigter Steuersatz (§ 12 Abs. 2 UStG)"),
                Triple(23, "9.50", "Historischer Durchschnittssatz (§ 24 Abs. 1 Nr. 3 UStG)"),
                Triple(33, "9.00", "Historischer Durchschnittssatz (§ 24 Abs. 1 Nr. 3 UStG)"),
                Triple(43, "8.40", "Historischer Durchschnittssatz (§ 24 Abs. 1 Nr. 3 UStG)"),
            )

        zBons.forEach { zBon ->
            // Declaring a SUPERSET is what breaks §3.2.6; declaring a SUBSET would dangle every
            // join from lines_vat/transactions_vat/businesscases/itemamounts, so the filter is
            // driven by what those producers actually emitted for THIS closing.
            val used = usedKeysByZNr[zBon.zbonNummer]
            vatRates
                .filter { (key, _, _) -> used == null || key in used }
                .forEach { (key, rate, description) ->
                    result.add(
                        DsfinvkExportDto.StammUstRowDto(
                            zKasseId = cashRegisterId,
                            zErstellung = formatDateTime(zBon.createdAt),
                            zNr = zBon.zbonNummer,
                            ustSchluessel = key,
                            ustSatz = rate,
                            ustBeschr = description,
                        ),
                    )
                }
        }

        return result
    }

    /**
     * Device identity for TSE_ID grouping (STAM-1): the canonical hex serial (the TR-03153 §7.5
     * form the export mandates, and the only form §9.3.2 allows for assigning a TSE) from the
     * `tse_serial_hex` column, falling back to normalizing the raw one on pre-V82 rows; "" when
     * unset. Two transactions share a device iff this key matches — the DB's
     * uniqueIndex(tseSerialHex, transactionNumber) confirms transaction numbers restart per
     * device, so the serial is the only reliable identity.
     */
    private fun tseSerialKey(tx: TseTransaction): String = tx.serialHex() ?: ""

    /**
     * Round-3 STAM-1: one TSE_ID per physical device that signed the exported Vorgänge.
     * Keyed by the hex-normalized serial; ids assigned 1..N in order of first use (min
     * transaction id) so re-exports are deterministic. Replaces the single hardcoded id 1.
     */
    private fun buildTseIdBySerial(transactions: List<TseTransaction>): Map<String, Int> =
        transactions
            .asSequence()
            .filter { it.tseMode == TseMode.NORMAL }
            .groupBy { tseSerialKey(it) }
            .filterKeys { it.isNotBlank() }
            .toList()
            .sortedBy { (_, txs) -> txs.minOf { it.id.value } }
            .mapIndexed { idx, (serial, _) -> serial to idx + 1 }
            .toMap()

    /**
     * Resolves a Vorgang's TSE_ID from its OWN NORMAL transaction's device serial (STAM-1) —
     * never the hardcoded 1. `null` when the Vorgang had no genuine (NORMAL) signature or its
     * device is not among the exported devices.
     */
    private fun tseIdFor(
        real: TseTransaction?,
        tseIdBySerial: Map<String, Int>,
    ): Int? = real?.let { tseIdBySerial[tseSerialKey(it)] }

    /**
     * The TSE_IDs each closing's own Vorgänge were signed by, read from the transactions_tse rows —
     * the same rows an auditor joins tse.csv against, so the two files cannot disagree (RU-1).
     */
    private fun usedTseIdsByZNr(transactionsTse: List<DsfinvkExportDto.TseTransaktionenRowDto>): Map<Long, Set<Int>> =
        transactionsTse
            .mapNotNull { row -> row.tseId?.let { row.zNr to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toSet() }

    /**
     * Generates stamm_tse.csv row DTOs for TSE master data.
     *
     * Per DSFinV-K spec 3.2.7: TSE device information.
     *
     * Round-3 STAM-1: one row-set per physical device that actually SIGNED the exported closings
     * — TSE_ID resolved per device from [tseIdBySerial], master data from THAT device's latest
     * NORMAL transaction. A single hardcoded id 1 broke on a TSE swap (old-device rows claiming
     * the new device's master data, TSE_TANR colliding across devices). SIMULATION rows carry
     * fabricated values and must never become master data (STAM-5). No genuine device used →
     * tse.csv stays header-only. There is deliberately NO global fallback to a device that signed
     * nothing here: such a device is not a „genutzte" TSE for these closings (audit H8).
     *
     * RU-1: that rule holds per CLOSING, not per export. tse.csv is keyed by Z_NR, so a device is
     * listed only under the closings in [tseIdsByZNr] whose own Vorgänge it signed. Crossing every
     * exported device with every closing made an export spanning a TSE swap claim the new device
     * for closings taken before it was commissioned, and the old one for closings after.
     */
    @OptIn(TestOnlyApi::class)
    private fun generateStammTse(
        zBons: List<ZBon>,
        cashRegisterId: String,
        tseIdBySerial: Map<String, Int>,
        exportedTseTransactions: List<TseTransaction>,
        tseIdsByZNr: Map<Long, Set<Int>>,
    ): List<DsfinvkExportDto.StammTseRowDto> {
        val normalBySerial =
            exportedTseTransactions
                .asSequence()
                .filter { it.tseMode == TseMode.NORMAL }
                .groupBy { tseSerialKey(it) }

        // Emit devices in TSE_ID order so re-exports are byte-stable.
        return tseIdBySerial.entries.sortedBy { it.value }.flatMap { (serial, tseId) ->
            // The device's master data comes from its latest NORMAL transaction in this export that
            // actually CARRIES master data. A device known only through SUPERSEDED rows — one that
            // was replaced mid-Vorgang — has rows with no certificate, no public key and no
            // algorithm, because the finish that would have delivered them never landed; picking
            // the newest row outright then emitted a tse.csv entry with every field blank.
            val forSerial = normalBySerial[serial].orEmpty()
            val latest =
                forSerial.filter { !it.certificate.isNullOrBlank() }.maxByOrNull { it.id.value }
                    ?: forSerial.maxByOrNull { it.id.value }
                    ?: return@flatMap emptyList()

            // DSFinV-K Anhang E p. 78-79: TSE_ZERTIFIKAT_I holds „die ersten 1.000" base64 chars,
            // TSE_ZERTIFIKAT_II „Rest des Zertifikats" — and, per the Hinweis, „Werden weitere
            // Zertifikatsfelder benötigt (Zertifikat > 2.000 Zeichen), können diese als Felder
            // TSE_ZERTIFIKAT_III, TSE_ZERTIFIKAT_IV, TSE_ZERTIFIKAT_V usw. angelegt werden. Eine
            // Ergänzung in der Beschreibungs-Datei index.xml ist dann zusätzlich vorzunehmen."
            // So we split the WHOLE certificate into 1000-char base64 chunks across as many fields
            // as it needs (real production certs run to 4316 chars = five fields) and never
            // truncate. index.xml declares exactly these fields via the shared StammTseCertificate
            // helper, so tse.csv and the descriptor always agree (audit R2).
            val certificateParts = StammTseCertificate.chunk(latest.certificate)

            // M5 guard: DSFinV-K/TR-03153 §7.5 defines TSE_SERIAL as the hash of the TSE's public
            // key. We do not know the exact byte encoding the vendor firmware hashes (raw EC point?
            // DER SubjectPublicKeyInfo? the full certificate?), so this is a best-effort SHA-256 of
            // the decoded publicKey bytes — a check, not a replacement. A mismatch is logged so an
            // operator can investigate; the stored, vendor-provided serial is still what gets
            // exported (never silently swapped for a guessed value). Compare against the
            // hex-NORMALIZED serial (H7) — the same value emitted as TSE_SERIAL below.
            val derivedSerialHash = deriveTseSerialHashHex(latest.publicKey)
            if (derivedSerialHash != null &&
                !derivedSerialHash.equals(serial.trim(), ignoreCase = true)
            ) {
                logger.warn(
                    "TSE_SERIAL verification: serial='{}' does not match SHA-256(publicKey)='{}'. " +
                        "This may be expected (unverified encoding assumption per TR-03153 §7.5) or may indicate " +
                        "a data integrity issue — exporting the stored value unchanged.",
                    // The same value the comparison used (and the one exported): logging the raw
                    // column instead would print Base64 next to a hex hash on a pre-V82 row and
                    // read as a far worse mismatch than it is.
                    serial,
                    derivedSerialHash,
                )
            }

            zBons.filter { tseId in tseIdsByZNr[it.zbonNummer].orEmpty() }.map { zBon ->
                DsfinvkExportDto.StammTseRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    tseId = tseId,
                    tseSerial = serial,
                    // Never fabricate an algorithm the device didn't declare (STAM-13): a blank
                    // stored value exports blank and is logged — a guessed enum literal would
                    // misdirect signature verification after a device swap.
                    tseSigAlgo =
                        latest.signatureAlgorithm?.takeIf { it.isNotBlank() }
                            ?: "".also { logger.warn("Stamm_TSE: no signatureAlgorithm stored for TSE transaction {}", latest.id.value) },
                    // m3: the device's real internal log-time format, persisted per transaction
                    // (V102). Falls back to "unixTime" for pre-V102 rows — the correct value for
                    // the Swissbit device those historical rows were signed by.
                    tseZeitformat = latest.logTimeFormat?.takeIf { it.isNotBlank() } ?: "unixTime",
                    tsePdEncoding = "UTF-8",
                    tsePublicKey = latest.publicKey,
                    tseZertifikatParts = certificateParts,
                )
            }
        }
    }

    /**
     * Generates stamm_terminals.csv row DTOs.
     *
     * Per DSFinV-K spec: Terminal information for multi-client POS systems.
     *
     * Round-7 C3: the terminals are resolved PER CLOSING through [KassenStammdatenSnapshot] — the
     * set captured when the closing was taken, else the live `terminals` table for a closing that
     * predates the snapshot. Reading the live table for every Z_NR made a re-export claim
     * terminals that did not exist yet and carry master data edited long afterwards.
     *
     * TERMINAL_SERIENNR here is the snapshotted hardware serial; [DsfinvkTerminalAttribution]
     * replaces it with the terminal code for every terminal the closing's own Vorgänge prove signed
     * as that TSE client (Anhang E p. 85, BKOPF-7/STAM-10).
     */
    private fun generateStammTerminals(
        zBons: List<ZBon>,
        cashRegisterId: String,
    ): List<DsfinvkExportDto.StammTerminalsRowDto> =
        zBons.flatMap { zBon ->
            KassenStammdatenSnapshot.terminalsFor(zBon).map { terminal ->
                DsfinvkExportDto.StammTerminalsRowDto(
                    zKasseId = cashRegisterId,
                    zErstellung = formatDateTime(zBon.createdAt),
                    zNr = zBon.zbonNummer,
                    terminalId = terminal.terminalId,
                    terminalBrand = terminal.brand,
                    terminalModel = terminal.modell,
                    terminalSerialNr = terminal.seriennr,
                    terminalSwBrand = terminal.swBrand,
                    terminalSwVersion = terminal.swVersion,
                )
            }
        }

    // ============================================
    // Utility formatting methods
    // ============================================

    /**
     * Formats a LocalDateTime for DSFinV-K date-only output.
     */
    /**
     * Resolves the order items for a bill.
     * For storno bills, returns the ORIGINAL bill's items (since items stay linked to original).
     * For normal bills, returns items linked directly to this bill.
     */

    /**
     * BEDIENER_NAME per Anhang E p. 87: the person's unternehmensinterner NAME — distinct
     * from BEDIENER_ID; the employee number was exported in both fields (audit BKOPF-9).
     *
     * The shared login-less device account has no `employee_info` by design, so this fallback used
     * to emit its raw username — `terminal-device`, lowercase developer text — as the operator name
     * of every order a station booked. [TerminalEmployee.displayName] substitutes that account's own
     * internal label and leaves every person's username fallback alone. BEDIENER_ID stays the
     * unchanged employee number, so the two Bonkopf fields remain distinct (BKOPF-9).
     */
    private fun employeeDisplayName(employee: org.example.project.features.employee.domain.models.Employee): String {
        val info = employee.employeeInfo
        val name = listOfNotNull(info?.firstName, info?.lastName).joinToString(" ").trim()
        return name.ifBlank { TerminalEmployee.displayName(employee.username) }
    }

    /**
     * BILL_WIDE promotion shares per VAT rate — the SINGLE math both lines.csv and
     * lines_vat.csv use (H11): weights = Σ finalPrice per rate MINUS that rate's
     * item-specific deductions (A3-3), ALL bill-wide deductions aggregated into ONE
     * largest-remainder apportionment (A3-4, largest-remainder is not additive).
     */
    private fun billWidePromotionShares(
        orderItems: List<OrderItem>,
        billPromotions: List<BillPromotion>,
    ): Pair<Map<BigDecimal, BigDecimal>, Map<BigDecimal, BigDecimal>> {
        val itemSpecificPromotionsByItemId =
            billPromotions
                .filter { it.orderItem != null }
                .groupBy { it.orderItem!!.id.value }
        val billWideDeduction =
            billPromotions
                .filter { it.orderItem == null }
                .fold(BigDecimal.ZERO) { acc, p -> acc + p.deductedAmount }
        val vatGroups = orderItems.groupBy { it.vat.vatRate }
        val weightsByRate =
            vatGroups.mapValues { (_, items) ->
                items.fold(BigDecimal.ZERO) { acc, item ->
                    acc + item.finalPrice -
                        (
                            itemSpecificPromotionsByItemId[item.id.value]
                                ?.fold(BigDecimal.ZERO) { a, p -> a + p.deductedAmount } ?: BigDecimal.ZERO
                        )
                }
            }
        if (billWideDeduction.compareTo(BigDecimal.ZERO) == 0) {
            return weightsByRate to weightsByRate.mapValues { BigDecimal.ZERO }
        }
        return weightsByRate to PromotionApportionment.apportion(billWideDeduction, weightsByRate)
    }

    /**
     * Pre-discount CHARGED unit base = unitPrice + priced-extras deltas (the ItemExtras
     * snapshots generateSubItems exports) — the exact base OrderServiceImpl discounts:
     * finalPrice = (unitPrice + extrasDelta) * discount (audit H13b).
     */
    private fun chargedUnitPrice(item: OrderItem): BigDecimal =
        item.unitPrice + item.itemExtras.sumOf { it.priceDeltaSnapshot * java.math.BigDecimal(it.quantity) }

    /**
     * Round-4 BPOS-1: the SINGLE Rabatt-row predicate shared by lines.csv, lines_vat.csv and
     * businesscases.csv. Returns the 2dp (HALF_UP) Rabatt magnitude in the NATURAL (non-storno)
     * direction, or null when NO Rabatt row must be emitted for this item.
     *
     * Rounding BOTH operands before subtracting is the INV-F7 contract: item(round2 charged) −
     * Rabatt == round2(finalPrice), the value the transactions header sums.
     *
     * Each producer previously carried its own copy: lines.csv rounded both operands, lines_vat
     * compared the RAW difference, businesscases required `> 0`. A sub-cent delta that collapses
     * to 0.00 after rounding made lines.csv skip the row while lines_vat still asked
     * BonposLinePlan for its POS_ZEILE — NoSuchElementException → the WHOLE export aborted.
     * lines.csv is the POS_ZEILE authority, so every child producer MUST decide with this function.
     */
    private fun discountRabattAmount(item: OrderItem): BigDecimal? {
        if (item.discount >= BigDecimal.ONE) return null
        val amount =
            chargedUnitPrice(item)
                .setScale(2, RoundingMode.HALF_UP)
                .subtract(item.finalPrice.setScale(2, RoundingMode.HALF_UP))
        return if (amount.compareTo(BigDecimal.ZERO) != 0) amount else null
    }

    private fun resolveOrderItems(bill: Bill): List<OrderItem> {
        val sourceBillId =
            if (bill.deletedBill != null) {
                bill.deletedBill!!.id.value
            } else {
                bill.id.value
            }
        return OrderItem.find { OrderItemTable.billId eq sourceBillId }.toList()
    }

    /**
     * m16: the set of valid ISO 3166-1 alpha-3 country codes, taken from the JVM's own locale data.
     *
     * The fall-through used to accept „any three letters", so a typo like „XYZ" was stamped verbatim
     * onto LAND / LOC_LAND for every Kassenabschluss — indistinguishable in the export from a real
     * code, and (unlike the 6-letter „Narnia") never rejected. Validating against the real set keeps
     * a genuine-but-uncommon code like „JPN" working while catching the typo, and needs no
     * hand-maintained table.
     */
    private val iso3CountryCodes: Set<String> by lazy {
        java.util.Locale
            .getISOCountries()
            .map {
                java.util
                    .Locale("", it)
                    .isO3Country
                    .uppercase()
            }.toSet()
    }

    /**
     * Maps country name/code to ISO 3166 ALPHA-3 as required by DSFinV-K.
     */
    private fun toIsoAlpha3Country(country: String?): String {
        if (country.isNullOrBlank()) return "DEU"
        return when (country.lowercase().trim()) {
            "germany", "deutschland", "de", "deu" -> "DEU"
            "austria", "österreich", "oesterreich", "at", "aut" -> "AUT"
            "switzerland", "schweiz", "ch", "che" -> "CHE"
            "france", "frankreich", "fr", "fra" -> "FRA"
            "italy", "italien", "it", "ita" -> "ITA"
            "netherlands", "niederlande", "nl", "nld" -> "NLD"
            "belgium", "belgien", "be", "bel" -> "BEL"
            "luxembourg", "luxemburg", "lu", "lux" -> "LUX"
            "poland", "polen", "pl", "pol" -> "POL"
            "czech republic", "tschechien", "cz", "cze" -> "CZE"
            "denmark", "dänemark", "dk", "dnk" -> "DNK"
            else ->
                if (country.length == 3 && country.uppercase() in iso3CountryCodes) {
                    country.uppercase()
                } else {
                    // Round-3 STAM-6: silently exporting DEU would hide bad master data and stamp a
                    // false country on every Kassenabschluss. Fail the export loudly instead.
                    throw fileGenerationFailed(
                        "shop country '$country' is not a recognizable ISO 3166 ALPHA-3 country — " +
                            "fix the shop master data",
                    )
                }
        }
    }

    /**
     * Rounds a brutto/netto/ust VAT triple to 2 decimals while preserving the identity
     * `brutto == netto + ust` exactly at the emitted scale (audit finding M1).
     *
     * Every VAT triple in this exporter is derived as `netto = gross / (1+rate)` (scale 4,
     * HALF_UP) and `ust = gross - netto` (so the identity holds exactly at full precision).
     * Rounding each of the three components independently to scale 2 can violate that identity
     * by 0.01 whenever netto/ust land on a `.xx5` tie (e.g. netto 8.405 + ust 1.595 = brutto
     * 10.000 -> netto rounds to 8.41, ust rounds to 1.60, sum 10.01 != brutto 10.00).
     *
     * BRUTTO is the reconciliation anchor across DSFinV-K CSVs — `transactions.UMS_BRUTTO`,
     * `Σ lines_vat.POS_BRUTTO` and `Σ transactions_vat.BON_BRUTTO` for a BON_ID must all agree,
     * and all three are computed from the same underlying gross amount independently of this
     * triple. So BRUTTO and NETTO are rounded directly (unchanged from their prior behavior,
     * preserving those cross-file invariants) and UST is derived as the residual
     * `brutto2 - netto2`, guaranteeing the identity holds by construction.
     */

    /**
     * A pending `lines_vat.csv` row (item / discount / item-specific-promo) awaiting the per-rate
     * group-residual correction ([applyLineVatGroupResidual]) before rounding+signing.
     * `gross`/`net` are in the NATURAL (non-storno) direction — the storno sign flip is applied
     * once, uniformly, at emission time, same as it always was per-row.
     */
    private data class PendingLineVatRow(
        val vatRate: BigDecimal,
        val ustSchluessel: Int,
        val gross: BigDecimal,
        var net: BigDecimal,
        /** lines.csv POS_ZEILE this VAT row belongs to — looked up from the BonposLinePlan (H11). */
        val posZeile: Int,
    )

    /**
     * Corrects the per-item/discount/item-specific-promo `POS_NETTO` rounding so each VAT-rate
     * group's rows sum to the SAME single-netted target `BillServiceImpl`/`BillVatDetail` uses
     * (`round(Σgross/(1+rate), 2)`) — mirroring the group-residual technique the BILL_WIDE
     * promotion path in [generateLineVats] already applies.
     *
     * Rounding each row's net INDEPENDENTLY (the prior behavior) drifts a cent whenever ≥2 rows
     * share a rate — e.g. two 2.60 items @19% independently round to 2.18 net each (Σ 4.36), but
     * the single-netted target is `round(5.20/1.19, 2) = 4.37` (RA-1 Finding 1). A promotion's own
     * Rabatt row happens to absorb this drift into itself (its residual is computed as
     * `target − actualSum`, so any internal inaccuracy of `actualSum` cancels out) — but a group
     * with NO promotion at all has no such row to catch it, hence the bug.
     *
     * The entire residual is placed on the LAST row of the group (in emission order), so each
     * row's own `gross` (brutto) is untouched ("Gross rows unchanged") and only that one row's
     * `net` — and therefore its derived `ust` via [roundVatTriple] (`ust = brutto − netto`) —
     * absorbs the correction.
     */
    private fun applyLineVatGroupResidual(rows: List<PendingLineVatRow>) {
        rows.groupBy { it.vatRate }.forEach { (rate, group) ->
            // Round-7 C6: sum the ROUNDED grosses — i.e. exactly the `POS_BRUTTO` values
            // [roundVatTriple] will emit — not the raw ones. The rows carry MIXED scales: an item
            // row's gross is the raw `chargedUnitPrice` (`unit_price` is `decimal(19,4)`, and a
            // sub-cent product price is reachable — nothing clamps `ProductDto.price` on write),
            // while its Rabatt row's gross comes from the scale-2 `discountRabattAmount` helper.
            // Netting the raw sum therefore targets a value that is neither `Σ POS_BRUTTO` nor
            // `BillVatDetail.totalAmount` (`transactions_vat`), and `Σ lines_vat` drifts a cent —
            // e.g. unitPrice 4.9010 @19 % with discount 0.85 (finalPrice 4.17) summed raw to
            // 4.1710 → target 3.51, against a `BON_NETTO` of 3.50 (round-5 BPOS-1 regression;
            // before it both operands were raw so the sum telescoped to `finalPrice` by accident).
            // Rounding first restores the telescoping and makes the target the same single netting
            // `BillServiceImpl.createBillVat` performs — Tz. 3.1.2 requires the Bonkopf's VAT split
            // to be derivable from the positions.
            val grossSum = group.fold(BigDecimal.ZERO) { acc, r -> acc + r.gross.setScale(2, RoundingMode.HALF_UP) }
            val target = BillVatNetting.singleNet(grossSum, rate)
            val naiveSum = group.fold(BigDecimal.ZERO) { acc, r -> acc + r.net }
            val residual = target - naiveSum
            if (residual.compareTo(BigDecimal.ZERO) != 0) {
                val last = group.last()
                last.net = last.net + residual
            }
        }
    }

    /** One VAT-rate slice of an apportioned OWNER tip. */
    private data class OwnerTipShare(
        val vatRate: BigDecimal,
        val ustSchluessel: Int,
        val gross: BigDecimal,
        val net: BigDecimal,
        val vat: BigDecimal,
    )

    /**
     * Apportions a taxable OWNER tip (TrinkgeldAG) across the bill's item VAT rates using the SAME
     * penny-exact largest-remainder split (`PromotionApportionment.apportion`) `BillServiceImpl`
     * uses to fold the tip into `BillVatDetail` — but weighted by the POST-promotion per-rate
     * totals, NOT the raw `finalPrice` sums (Round-3 INV-F2). `BillServiceImpl.createBill` feeds
     * the tip apportionment its `vatToTotalPrice` map, which has ALREADY had item-specific promo
     * deductions subtracted from each rate AND bill-wide deductions distributed across rates. Raw
     * `finalPrice` weights matched only when no promotion existed; the moment a promo shared a
     * multi-rate bill the export's per-rate tip share diverged from the signed `BillVatDetail`
     * (and so `transactions_vat`) by up to a cent per key. Reproducing the post-promotion weights
     * here — via the same helper math [billWidePromotionShares] uses — makes the gross shares
     * IDENTICAL to the service's by construction (determinism guaranteed by
     * [PromotionApportionment]'s tie-breaking).
     *
     * The NET share is derived via the same group-residual technique the bill-wide promotion path
     * uses: the combined (item-gross + tip-share) total for the rate is netted ONCE — exactly
     * mirroring `BillServiceImpl.createBillVat`'s single `totalAmount.divide(vatRate, 2)` — and the
     * tip's own net is the RESIDUAL against the item rows' already-netted total, rather than an
     * independently-rounded value. This keeps `lines_vat` reconciled with `transactions_vat` per
     * (BON_ID, UST_SCHLUESSEL) even when the bill spans multiple VAT rates.
     *
     * The weights come from `OwnerTipWeights` via [OwnerTipApportionment], as for `BillServiceImpl`: the
     * post-promotion per-rate gross, then the charged item gross when a promotion discounted the
     * bill to zero, then the item count. An owner tip is taxable at the rate of the underlying
     * supply (Anhang I p. 120), and a zero price does not change that rate — so the tip is written
     * to key 5 ("Nicht steuerbar") only for a bill with no items at all, where there is no supply
     * whose rate it could follow.
     *
     * [tipAmount] is passed ALREADY SIGNED (negative on a storno reverse bill), so the shares
     * carry the correct sign and a full reversal nets to zero. [orderItems] are the ORIGINAL
     * (positive) item rows for the bill (see `resolveOrderItems`); [billPromotions] are the bill's
     * promotion rows (resolved on the SAME sourceBillId as `orderItems`, so a storno reverses
     * identically).
     */
    private fun apportionOwnerTip(
        orderItems: List<OrderItem>,
        tipAmount: BigDecimal,
        billPromotions: List<BillPromotion>,
    ): List<OwnerTipShare> {
        // Post-promotion per-rate weights == BillServiceImpl.createBill's vatToTotalPrice (summed
        // per rate). Derived from the SAME [billWidePromotionShares] helper the bill-wide Rabatt
        // lines use — a single source of truth for the promotion-weighting rule (INV-F2), so a
        // future change to that rule lands in one place and the tip split can't silently drift
        // from the Rabatt split (and thus from BillVatDetail) again. The helper's `weightsByRate`
        // is Σ finalPrice per rate MINUS item-specific deductions; `billWideShareByRate` is that
        // rate's share of the aggregated bill-wide deduction (all-zero when there is none). Their
        // difference is exactly the per-rate total BillServiceImpl folds the tip into.
        val (weightsByRate, billWideShareByRate) = billWidePromotionShares(orderItems, billPromotions)
        val grossByRate =
            weightsByRate.mapValues { (rate, weight) ->
                weight - (billWideShareByRate[rate] ?: BigDecimal.ZERO)
            }

        val itemsByRate = orderItems.groupBy { it.vat.vatRate }
        // Apportion + group-residual net on the MAGNITUDE of the tip, then re-apply the sign, so a
        // storno reverse bill (tipAmount negative) produces the EXACT negation of the original
        // share/net/vat (A3-2). `grossByRate` is the POST-promotion per-rate gross (magnitude-
        // positive for a real sale), so netting `(groupGross ± share)` on a signed share was NOT
        // sign-symmetric — the reverse
        // Beleg's tip net drifted a cent from the negated transactions_vat anchor (which is the
        // original BillVatDetail negated). Computing on `absTip` and negating keeps
        // Σlines_vat.POS_UST == Σtransactions_vat.BON_UST per (BON_ID, UST_SCHLUESSEL) on both.
        //
        // The split itself is THE shared OwnerTipApportionment — the one BillServiceImpl signs and
        // BillToInvoiceMapper takes back out of the Ausgangsrechnung — keyed here per rate.
        val sign = tipAmount.signum()
        val absTip = tipAmount.abs()
        val shareByRate =
            OwnerTipApportionment.split(
                tip = absTip,
                basis =
                    OwnerTipBasis(
                        postPromotionGross = grossByRate,
                        chargedGross = itemsByRate.mapValues { (_, items) -> items.sumOf { it.finalPrice } },
                        itemCount = itemsByRate.mapValues { (_, items) -> BigDecimal(items.size) },
                        rateOf = { it },
                    ),
                tieBreaker = Comparator.naturalOrder(),
            )
        if (shareByRate == null) {
            // No items at all (mirrors BillServiceImpl): there is no supply whose rate the tip could
            // follow, so it becomes ONE 0%/key-5 "Nicht steuerbar" row. tipAmount is already
            // signed, so a storno reversal negates this row symmetrically.
            return listOf(
                OwnerTipShare(
                    vatRate = BigDecimal.ZERO,
                    ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(0.0),
                    gross = tipAmount,
                    net = tipAmount,
                    vat = BigDecimal.ZERO,
                ),
            )
        }

        return shareByRate.toSortedMap().mapNotNull { (vatRate, _) ->
            val groupGross = grossByRate[vatRate] ?: BigDecimal.ZERO
            val absShare = shareByRate[vatRate] ?: BigDecimal.ZERO
            if (absShare.compareTo(BigDecimal.ZERO) == 0) return@mapNotNull null
            // Group-residual netting (mirrors the BILL_WIDE promotion Rabatt row): net the
            // COMBINED (item + tip) total once, then take the tip's net as the residual against
            // the items' own already-netted total, so item_net + tip_net == round((groupGross +
            // share)/rate, 2) == BillVatDetail's single-fold netAmount for this rate.
            val itemNet = groupGross.divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
            val combinedNet = (groupGross + absShare).divide(BigDecimal.ONE + vatRate, 2, RoundingMode.HALF_UP)
            val absNet = combinedNet - itemNet
            val signFactor = BigDecimal(sign)
            OwnerTipShare(
                vatRate = vatRate,
                ustSchluessel = DsfinvkExportDto.VatKeyMapping.getVatKey(vatRate.toDouble()),
                gross = absShare * signFactor,
                net = absNet * signFactor,
                vat = (absShare - absNet) * signFactor,
            )
        }
    }

    private fun roundVatTriple(
        brutto: BigDecimal,
        netto: BigDecimal,
        @Suppress("UNUSED_PARAMETER") ust: BigDecimal,
    ): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val brutto2 = brutto.setScale(2, RoundingMode.HALF_UP)
        val netto2 = netto.setScale(2, RoundingMode.HALF_UP)
        val ust2 = brutto2 - netto2
        return Triple(brutto2, netto2, ust2)
    }

    private fun formatDate(dateTime: LocalDateTime): String =
        "${dateTime.year}-${dateTime.month.number.toString().padStart(2, '0')}-${dateTime.day.toString().padStart(2, '0')}"

    /**
     * Formats a LocalDateTime for DSFinV-K date-time output.
     */

    /**
     * The exported closing covering [at], or null when none does. Rows for uncovered
     * Vorgänge must be SKIPPED — a fabricated Z_NR=0 references a Kassenabschluss that
     * exists in no closing file and breaks the Anhang-E key-field joins (audit H3).
     */
    private fun closingFor(
        zBons: List<ZBon>,
        at: LocalDateTime,
    ): ZBon? =
        // Inclusive window; `find` returns the FIRST covering closing, so a Vorgang stamped
        // exactly on a boundary shared by two consecutive closings resolves to a single closing
        // rather than matching both (round-3 INV-F8). A half-open bound was rejected because it
        // drops a Vorgang from a zero-width single-instant closing.
        //
        // "FIRST" is only well-defined because [zBons] arrives sorted by zbonNummer ASC — the
        // determinism lives in generateExport's ORDER BY, NOT in `find`. Drop that ORDER BY and a
        // boundary Vorgang's Z_NR becomes a function of the query plan. Any future caller that
        // builds its own closing list must sort it the same way.
        // Guard: DsfinvkClosingBoundaryOrderingTest.
        zBons.find { at >= it.startTime && at <= it.endTime }

    /**
     * Round-3 INV-F8: the single closing a Kassenbuch entry belongs to — its own exported
     * closing if present, else the covering window. Used by EVERY Kassenbuch producer so an entry
     * can never land in one file's closing but a different file's — and never in two at once (the
     * old inline `createdAt <= endTime` predicate in the aggregate double-counted boundary
     * entries because it did not defer to a single covering-closing resolution).
     *
     * Round-4 KABS-1: this was previously copy-pasted inline at five further call sites, the exact
     * "fixed N−1 of N producers" shape that caused BPOS-1/INV-1/INV-2. All producers now call here.
     * Keep it that way — do not re-inline the expression.
     *
     * The zbon_id link is checked FIRST, which is also what makes the export tolerate rows whose
     * `createdAt` was frozen by the pre-KABS-1 column default: such an entry is admitted by
     * `zbon_id` in [generateExport]'s selection and still resolves to the right closing here.
     *
     * Round-5: a LINKED entry whose closing is absent from [zBons] resolves to null — it does NOT
     * fall through to the covering window. Falling through would relabel the row with a foreign
     * Z_NR, and since it is also emitted (under its true Z_NR) by the export that does contain its
     * closing, a day-by-day export set would carry the same Kassenbuch entry twice under two
     * different Kassenabschlüsse. Only an UNLINKED entry may be attributed by window. This mirrors
     * the selection predicate in [generateExport]; keep the two in lockstep.
     */

    /**
     * The single closing an Order belongs to — its recorded closing if present, else the covering
     * window for rows written before `t_order.zbon_id` existed.
     *
     * Mirrors [kassenbuchClosingFor], including the round-5 rule: a LINKED order whose closing is
     * absent from [zBons] resolves to null rather than falling through to the window, so a
     * day-by-day export set cannot emit the same Vorgang twice under two different Z_NR.
     */
    private fun orderClosingFor(
        order: Orders,
        zBons: List<ZBon>,
    ): ZBon? {
        val linkedId =
            order.zBon?.id?.value
                ?: return closingFor(zBons, order.orderTime)
        return zBons.firstOrNull { it.id.value == linkedId }
    }

    private fun kassenbuchClosingFor(
        entry: KassenbuchHeader,
        zBons: List<ZBon>,
    ): ZBon? {
        val linkedId =
            entry.zBon?.id?.value
                ?: return closingFor(zBons, entry.createdAt)
        return zBons.firstOrNull { it.id.value == linkedId }
    }

    private fun formatDateTime(dateTime: LocalDateTime): String = "${formatDate(dateTime)}T${formatTime(dateTime)}"

    /**
     * Formats a LocalDateTime for DSFinV-K time-only output.
     */
    private fun formatTime(dateTime: LocalDateTime): String =
        "${dateTime.hour.toString().padStart(
            2,
            '0',
        )}:${dateTime.minute.toString().padStart(2, '0')}:${dateTime.second.toString().padStart(2, '0')}"

    /**
     * Renders a TSE log time (epoch seconds) in the DSFinV-K-mandated CSV representation.
     *
     * Anhang E p. 104: `TSE_TA_START`/`TSE_TA_ENDE` „ist nach den Vorgaben nach ISO 8601 im
     * Format ‚YYYY-MM-DDThh:mm:ss.fffZ' darzustellen" — UNCONDITIONALLY (audit H1).
     * `TSE_ZEITFORMAT` in Stamm_TSE documents the TSE's INTERNAL log-time format (what the
     * TAR export carries) and does not change the CSV rendering — it is now read from the
     * per-transaction `logTimeFormat` (m3, V102), falling back to `unixTime` for pre-V102 rows.
     *
     * Preferred input is the TSE hardware's own log time (`tseStartTimestamp`/`tseTimestamp`,
     * epoch seconds); the fallback converts the stored server-clock [LocalDateTime] with the
     * shop `getTimeZone()` — the zone `currentTimeStamp()` wrote it in, so the resulting `Z`
     * instant is truthful (round-3 STAM-2/TSER-10). E.g. `quickSign` combines Start+Finish into
     * one TSE call and has no distinct start log time.
     */
    private val tseLogTimeFormatter =
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)

    private fun formatTseLogTime(epochSeconds: Long): String = tseLogTimeFormatter.format(java.time.Instant.ofEpochSecond(epochSeconds))

    /**
     * Best-effort SHA-256 hash of a Base64-encoded TSE public key, hex-encoded.
     *
     * DSFinV-K / TR-03153 §7.5 defines `TSE_SERIAL` as a hash of the TSE's public key. The exact
     * byte representation the certified TSE firmware hashes (raw EC point, DER
     * SubjectPublicKeyInfo, the full certificate, …) is not documented anywhere in this codebase,
     * so this is a guard/verification helper, NOT a value we substitute for the vendor-reported
     * `tseSerialNumber`. Returns null if `publicKeyBase64` is null/blank or not valid Base64.
     */
    @TestOnlyApi
    fun deriveTseSerialHashHex(publicKeyBase64: String?): String? {
        if (publicKeyBase64.isNullOrBlank()) return null
        val keyBytes =
            try {
                java.util.Base64
                    .getDecoder()
                    .decode(publicKeyBase64)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(keyBytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculates net amount from bill by subtracting VAT.
     */
    private fun calculateNetAmount(bill: Bill): BigDecimal {
        val totalVat = bill.vatDetails.sumOf { it.vatAmount }
        return bill.billAmount - totalVat
    }

    // ============================================
    // TSE TAR Export Integration
    // ============================================

    /**
     * Fetches the TSE TAR export (TR-03153 export interface) through the mode-aware
     * [TseAdminProvider] — local WORM device or remote TSE server, whichever is configured
     * (audit H10: the old raw-HTTP path silently produced exports WITHOUT TSE data in
     * local-TSE deployments).
     *
     * The DSFinV-K package ships the FULL device TAR (never a time-filtered slice) because
     * closings are cross-midnight-capable, so no calendar window is ever exactly right
     * (CONT-1) — a full export always covers every closing by construction.
     *
     * Historically there was a second reason (round-3 TSER-1): the time-filtered WormAPI call
     * is client-filtered, and pos-tse supplied a synthetic maintenance clientId, so a filtered
     * TAR carried no fiscal log messages at all. That cause is gone — pos-tse's filtered exports
     * now cover all clients (F69) — but the decision stands on CONT-1 alone. Recorded so the obsolete premise
     * is not mistaken for a live one, and so nobody "restores" filtered exports on the
     * strength of the fix.
     *
     * Note that on TSE 2.0 filtered exports fail outright regardless: the hardware dropped
     * them, and the ERS must filter the TAR itself.
     *
     * @return Pair of TAR file bytes and filename
     * @throws PosException when no provider is configured or the export fails.
     */
    private suspend fun fetchTseTarExport(): Pair<ByteArray, String> {
        val provider =
            tseAdminProvider
                ?: throw tseTarUnavailable("TSE admin provider not configured")
        return try {
            val exportResult =
                provider.exportFull(
                    outputPath = "exports/dsfinvk_tse",
                )

            if (!exportResult.success) {
                logger.error("DsfinvkExportServiceImpl.fetchTseTarExport: TSE export failed: {}", exportResult.message)
                throw tseTarUnavailable("TSE export failed: ${exportResult.message}")
            }

            val filePath = exportResult.filePath
            if (filePath.isNullOrBlank()) {
                logger.error("DsfinvkExportServiceImpl.fetchTseTarExport: TSE export returned no file path")
                throw fileGenerationFailed("TSE export returned no file path")
            }

            // Read the TAR file
            val tarFile = File(filePath)
            if (!tarFile.exists()) {
                logger.error("DsfinvkExportServiceImpl.fetchTseTarExport: TSE TAR file not found at: {}", filePath)
                throw fileGenerationFailed("TSE TAR file not found at: $filePath")
            }

            val tarBytes = tarFile.readBytes()
            val fileName = exportResult.fileName ?: tarFile.name

            // Clean up the temporary file
            try {
                tarFile.delete()
            } catch (e: Exception) {
                logger.warn("DsfinvkExportServiceImpl.fetchTseTarExport: failed to delete temporary TSE TAR file: {}", filePath, e)
            }

            logger.info(
                "DsfinvkExportServiceImpl.fetchTseTarExport: successfully fetched TSE TAR export: {} ({} bytes)",
                fileName,
                tarBytes.size,
            )
            Pair(tarBytes, fileName)
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("DsfinvkExportServiceImpl.fetchTseTarExport: failed to fetch TSE TAR export", e)
            throw tseTarUnavailable("TSE TAR export failed: ${e.message}", e)
        }
    }

    /**
     * Checklist 28.4: the TARs shipped must hold every signed transaction the package references.
     *
     * The export reads the ATTACHED device's TAR and, where that does not reach back far enough, the
     * registered export-and-delete archives ([TseTarAssembly]). A period signed by another device — a
     * swap, a virtual TSE restarted without its in-memory log — is covered only by that device's
     * registered archive; otherwise the refusal names its serial. A package with no signed
     * transaction needs nothing and is not checked, which also keeps every covered package
     * byte-identical: this only reads the TARs.
     *
     * @return the device TAR followed by the archives needed to cover the period
     * @throws PosException [PosErrorCode.TSE_EXPORT_INCOMPLETE] (409) naming each uncovered device and range.
     */
    private fun tarsCoveringPeriod(
        device: TseTarFile,
        periodTransactions: List<TseTransaction>,
    ): List<TseTarFile> {
        val required = signedTanrsBySerial(periodTransactions)
        val recordedAt =
            periodTransactions
                .mapNotNull { tx -> tx.transactionNumber?.let { (tseSerialKey(tx).uppercase() to it) to tx.startTime } }
                .toMap()
        return tseTarAssembly.assemble(device, required) { serial, tanr -> recordedAt[serial to tanr] }
    }

    /**
     * The TANRs a device actually signed for this package, by upper-case hex serial. Only NORMAL,
     * FINISHED rows with a signature: a STARTED row awaits late signing, a SUPERSEDED one is exported
     * unsigned, and a SIMULATION row never touched a device.
     */
    private fun signedTanrsBySerial(transactions: List<TseTransaction>): Map<String, Set<Long>> =
        transactions
            .asSequence()
            .filter {
                it.tseMode == TseMode.NORMAL &&
                    it.state == TseTransactionState.FINISHED &&
                    !it.signature.isNullOrBlank()
            }.mapNotNull { tx ->
                val tanr = tx.transactionNumber ?: return@mapNotNull null
                val serial = tseSerialKey(tx).uppercase()
                if (serial.isBlank()) null else serial to tanr
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, tanrs) -> tanrs.toSet() }

    private fun tseTarUnavailable(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.TSE_UNREACHABLE,
        HttpStatusCode.ServiceUnavailable,
        "TSE TAR export unavailable: $msg — retry when the TSE is reachable, or request the " +
            "export with allowMissingTseTar=true to explicitly ship WITHOUT TSE data",
        cause = cause,
    )

    // ============================================
    // PosException helpers (Stage B PosError migration)
    // ============================================

    private fun invalidDateRange(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "DSFinV-K invalid date range: $msg",
        )

    /**
     * KABS-6: a Kassenabschluss must carry either a Steuernummer or a USt-IdNr
     * (§ 14 Abs. 4 Nr. 2 UStG, Anhang E p. 68). Shared by the generateExport hard gate and the
     * validateExportData pre-export check, which surfaces the same [missingTaxIdMessage] text as
     * an error — both computed from [closingsMissingTaxId], so they cannot disagree.
     */
    private fun missingTaxId(zNrs: List<Long>) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.UnprocessableEntity,
            missingTaxIdMessage(zNrs),
        )

    private fun invalidCashRegisterConfig(msg: String) =
        PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "DSFinV-K invalid cash register config: $msg",
        )

    private fun noDataInRange(
        startDate: String,
        endDate: String,
    ) = PosException(
        PosErrorCode.RESOURCE_NOT_FOUND,
        HttpStatusCode.NotFound,
        "DSFinV-K export found no data in range $startDate..$endDate",
    )

    private fun fileGenerationFailed(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "DSFinV-K file generation failed: $msg",
        cause = cause,
    )

    private fun exportInternal(
        msg: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.INTERNAL_ERROR,
        HttpStatusCode.InternalServerError,
        "DSFinV-K export internal error: $msg",
        cause = cause,
    )
}
