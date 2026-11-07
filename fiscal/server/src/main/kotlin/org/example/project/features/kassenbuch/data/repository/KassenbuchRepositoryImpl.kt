/**
 * Implementation of KassenbuchRepository using Exposed ORM.
 *
 * Provides concrete implementation of DATEV-compliant cash book data access
 * operations using Exposed ORM. Exceptions propagate to the service layer
 * for Result wrapping.
 *
 * Uses header/line-item structure for multi-VAT support.
 */
package org.example.project.features.kassenbuch.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Implementation of KassenbuchRepository using Exposed ORM.
 *
 * Provides concrete implementation of DATEV-compliant cash book data access
 * operations. Creates headers with line items in a single operation for atomicity.
 */
class KassenbuchRepositoryImpl(
    /** Answers whether a document to be linked exists; the `Document` model is accounting's. */
    private val documents: KassenbuchDocuments,
    private val cashBookRepository: CashBookRepository = CashBookRepositoryImpl(),
) : KassenbuchRepository {
    private val logger = LoggerFactory.getLogger(KassenbuchRepositoryImpl::class.java)

    @Suppress("DefaultLocale")
    override fun createEntry(
        shop: ShopInfo,
        employee: Employee,
        headerData: KassenbuchDto.KassenbuchData,
        lineItemsData: List<KassenbuchDto.LineItemData>,
        tseTransaction: TseTransaction?,
    ): KassenbuchHeader {
        logger.info(
            "Creating kassenbuch header for shop: ${shop.id.value}, employee: ${employee.id.value}, lineItems: ${lineItemsData.size}",
        )

        val belegNr = headerData.belegNr

        // Validate belegDatum format
        val belegDatum = headerData.belegDatum
        require(isValidDdMmYyyy(belegDatum)) {
            "Invalid belegDatum format, valid format: DD.MM.YYYY"
        }

        // Resolve the book: null means the shop's fiscal Ladenkasse, which is what every
        // pre-cash-book caller means.
        val cashBook =
            headerData.cashBookId
                ?.let { CashBook.findById(it) ?: error("Cash book $it not found") }
                ?: cashBookRepository.fiscalBookFor(shop)

        // Previous balance — scoped to THIS book. Chaining off a global "last header" would
        // make a Tresor entry continue the Ladenkasse's balance.
        val lastHeader =
            KassenbuchHeader
                .find { KassenbuchHeaderTable.cashBookId eq cashBook.id }
                .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                .firstOrNull()
        val lastBalance = lastHeader?.currentBalance ?: BigDecimal.ZERO

        // Calculate total amount from all line items
        var totalAmount = BigDecimal.ZERO
        for (lineData in lineItemsData) {
            val lineAmount = BigDecimal(lineData.vorzBetrag)
            totalAmount += lineAmount
        }

        // Signed accumulation: line amounts are stored in cash-flow sign, so the
        // balance simply adds the (signed) total. For manual entries the mapper
        // CONSTRUCTS the line sign from entryType.isIncoming (uniform sign), and the
        // Z-Bon path builds signed cash-flow lines directly — so signed accumulation
        // is equivalent to the previous "isIncoming ? +abs : -abs" for uniform-sign
        // entries and correct for mixed-sign ones (e.g. Z-Bon days where non-cash
        // tenders exceed cash revenue, which the abs() variant booked with the
        // wrong sign).
        val currentBalance = lastBalance + totalAmount

        // Create header
        val header =
            KassenbuchHeader.new {
                this.shop = shop
                this.cashBook = cashBook
                this.counterpartyBook = headerData.counterpartyBookId?.let { CashBook.findById(it) }
                this.transferId = headerData.transferId
                this.employee = employee
                this.entryType = headerData.entryType
                this.belegNr = belegNr
                this.belegDatum = belegDatum
                this.belegtext = headerData.belegtext
                this.waehrung = headerData.waehrung
                this.bu = headerData.bu
                this.kost1 = headerData.kost1
                this.kost2 = headerData.kost2
                this.nachricht = headerData.nachricht
                this.lateBookingReason = headerData.lateBookingReason
                this.totalAmount = totalAmount
                this.currentBalance = currentBalance
                this.tseTransaction = tseTransaction
                this.correctsHeader = headerData.correctsHeaderId?.let { KassenbuchHeader.findById(it) }
                this.correctionRole = headerData.correctionRole
                this.isZbonAggregate = headerData.isZbonAggregate
                // Fiscal timestamp stamped explicitly here (same contract as ZBonRepositoryImpl:114)
                // so BON_START/BON_ENDE never depends on a column-default mechanism.
                this.createdAt = currentTimeStamp()
            }

        // Create line items with incremental signed sub-balance
        var runningBalance = lastBalance
        lineItemsData.forEachIndexed { index, lineData ->
            val lineAmount = BigDecimal(lineData.vorzBetrag)
            runningBalance += lineAmount

            KassenbuchLineItem.new {
                this.header = header
                this.lineNumber = index
                this.vorzBetrag = lineAmount
                this.ustSatz = lineData.ustSatz
                this.belegtext = lineData.belegtext
                this.kostmenge = lineData.kostmenge?.toBigDecimal()
                this.skonto = lineData.skonto?.toBigDecimal()
                this.kind = lineData.kind
                this.paymentType =
                    lineData.paymentTypeId?.let { id ->
                        PaymentType.findById(id) ?: run {
                            // Null is the designed degradation: the export resolver treats a
                            // null paymentTypeId as whole-line legacy account fallback.
                            logger.warn(
                                "Payment type $id not found for kassenbuch line — " +
                                    "line will resolve to legacy accounts at export",
                            )
                            null
                        }
                    }
                this.subBalance = runningBalance
            }
        }

        logger.info("Successfully created kassenbuch header with ID: ${header.id.value}, ${lineItemsData.size} line items")
        return header
    }

    private fun isValidDdMmYyyy(input: String): Boolean {
        if (input.length != 10) return false
        if (input[2] != '.' || input[5] != '.') return false

        val day = input.substring(0, 2).toIntOrNull() ?: return false
        val month = input.substring(3, 5).toIntOrNull() ?: return false
        val year = input.substring(6, 10).toIntOrNull() ?: return false

        return try {
            LocalDate(year, month, day)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DefaultLocale")
    override suspend fun getNextBelegNr(
        shop: ShopInfo,
        cashBookId: Long?,
    ): String = nextBelegNrBlocking(cashBookId ?: cashBookRepository.fiscalBookFor(shop).id.value)

    /** Non-suspend variant so tests and the transfer path can call it inside a transaction. */
    @Suppress("DefaultLocale")
    fun nextBelegNrBlocking(cashBookId: Long?): String {
        val now = currentTimeStamp()
        val prefix = String.format("%04d-%02d", now.year, now.month.number)

        // Find the max belegNr for the current month prefix
        val maxExpression = KassenbuchHeaderTable.belegNr.max()
        val maxBelegNr =
            KassenbuchHeaderTable
                .select(maxExpression)
                .where {
                    if (cashBookId != null) {
                        (KassenbuchHeaderTable.belegNr like "$prefix-%") and
                            (KassenbuchHeaderTable.cashBookId eq cashBookId)
                    } else {
                        KassenbuchHeaderTable.belegNr like "$prefix-%"
                    }
                }.singleOrNull()
                ?.get(maxExpression)

        val nextSeq = maxBelegNr?.substringAfterLast('-')?.toIntOrNull()?.plus(1) ?: 1
        return String.format("%s-%04d", prefix, nextSeq)
    }

    override fun getCurrentBalance(
        shop: ShopInfo,
        cashBookId: Long?,
    ): BigDecimal {
        logger.info("Getting current balance for shop: ${shop.id.value}")

        val bookId = cashBookId ?: cashBookRepository.fiscalBookFor(shop).id.value
        // Keep the shop predicate the pre-cash-book version had. Scoping by book alone would let
        // a caller passing ANOTHER shop's bookId read that shop's balance — the book id is the
        // only thing standing between tenants otherwise. With both, a foreign book simply matches
        // no rows and yields ZERO, which is the truthful answer for "this book, in this shop".
        val lastHeader =
            KassenbuchHeader
                .find {
                    (KassenbuchHeaderTable.shopId eq shop.id) and
                        (KassenbuchHeaderTable.cashBookId eq bookId)
                }.orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                .firstOrNull()

        val balance = lastHeader?.currentBalance ?: BigDecimal.ZERO
        logger.info("Current balance for shop ${shop.id.value}: $balance")
        return balance
    }

    override fun getEntriesByDateRange(
        shop: ShopInfo,
        startDate: String,
        endDate: String,
        unlinkedOnly: Boolean,
        cashBookIds: List<Long>?,
    ): List<KassenbuchHeader> {
        logger.info(
            "Getting kassenbuch headers for shop: ${shop.id.value}, range: $startDate to $endDate, " +
                "unlinkedOnly=$unlinkedOnly, cashBookIds=$cashBookIds",
        )

        // Filter by createdAt, NOT belegDatum — deliberately (7f8bee3be, together with V94).
        //
        // createdAt is no longer a mere record-insertion timestamp: V94 realigned
        // kassenbuch_header.created_at to the TSE-attested signature time, i.e. the
        // cryptographically signed moment the entry was finalised. belegDatum is an
        // operator-typed varchar and can name any date at all, so filtering on it would let a
        // backdated entry fall into a period whose Kassenabschluss is already closed and
        // exported. Four of this method's five callers are DATEV exports, where the period an
        // entry belongs to must be the attested one, not the typed one.
        //
        // (An earlier version DID parse belegDatum, to stop backdated/imported entries being
        // hidden from short UI ranges like "this week". 7f8bee3be reverted that in favour of the
        // fiscal reading. If the history list ever needs the operator-facing date again, give it
        // its own query rather than changing this one — the export callers depend on this
        // behaviour.)
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)

        val headers =
            KassenbuchHeader
                .find {
                    var predicate = KassenbuchHeaderTable.shopId eq shop.id
                    if (unlinkedOnly) predicate = predicate and (KassenbuchHeaderTable.linkedDocumentId.isNull())
                    // null = ALL of the shop's cash books (today's behaviour, unchanged). A non-null
                    // list — even an empty one — is honoured literally, so an empty selection
                    // correctly yields zero rows instead of silently falling back to "all books".
                    if (cashBookIds != null) predicate = predicate and (KassenbuchHeaderTable.cashBookId inList cashBookIds)
                    predicate
                }.orderBy(
                    KassenbuchHeaderTable.createdAt to SortOrder.DESC,
                    // Stable tiebreaker: entries sharing one createdAt (transfer legs, a Z-Bon sweep).
                    KassenbuchHeaderTable.id to SortOrder.DESC,
                ).filter { header ->
                    header.createdAt.date in start..end
                }.toList()

        logger.info("Retrieved ${headers.size} kassenbuch headers for shop ${shop.id.value}")
        return headers
    }

    override fun searchEntries(
        shop: ShopInfo,
        query: String,
        cashBookIds: List<Long>?,
        limit: Int,
    ): List<KassenbuchHeader> {
        logger.info(
            "Searching kassenbuch headers for shop: {}, cashBookIds={}, limit={}",
            shop.id.value,
            cashBookIds,
            limit,
        )

        // Lower BOTH sides, matching ProductRepositoryImpl/EmployeeInfoRepositoryImpl: SQL `LOWER()`
        // is standard, so H2 (tests) and PostgreSQL (prod) agree. Lower-casing only the column
        // would silently fail on any upper-case character the operator types.
        val term = "%${query.lowercase()}%"

        return KassenbuchHeader
            .find {
                // The shop predicate is NOT optional. A cash book id is not a tenancy boundary —
                // without this a caller could search another shop's entries by omitting the book
                // filter, the same leak already closed in getCurrentBalance and the Z-Bon close.
                var predicate = KassenbuchHeaderTable.shopId eq shop.id
                if (cashBookIds != null) predicate = predicate and (KassenbuchHeaderTable.cashBookId inList cashBookIds)
                predicate and
                    (
                        (KassenbuchHeaderTable.belegNr.lowerCase() like term) or
                            (KassenbuchHeaderTable.belegtext.lowerCase() like term)
                    )
            }.orderBy(
                KassenbuchHeaderTable.createdAt to SortOrder.DESC,
                // Tiebreaker: several entries can share one createdAt (transfer legs, a Z-Bon
                // sweep), and without it the capped window would be non-deterministic.
                KassenbuchHeaderTable.id to SortOrder.DESC,
            ).limit(limit)
            .toList()
    }

    override fun getEntryById(headerId: Long): KassenbuchHeader? {
        logger.info("Getting kassenbuch header by ID: $headerId")
        return KassenbuchHeader.findById(headerId)
    }

    override fun getEntryByBelegNr(belegNr: String): KassenbuchHeader? {
        logger.info("Getting kassenbuch header by belegNr: $belegNr")
        return KassenbuchHeader
            .find {
                KassenbuchHeaderTable.belegNr eq belegNr
            }.firstOrNull()
    }

    override fun linkDocument(
        headerId: Long,
        pdfDocumentId: Long,
    ): KassenbuchHeader {
        val header =
            KassenbuchHeader.findById(headerId)
                ?: throw IllegalArgumentException("Kassenbuch header not found: $headerId")

        // Guard: reject if already linked (1:1 constraint)
        if (header.linkedDocumentId != null) {
            throw IllegalArgumentException(
                "Kassenbuch entry $headerId already has a linked document (id=${header.linkedDocumentId})",
            )
        }

        if (!documents.documentExists(pdfDocumentId)) {
            throw IllegalArgumentException("PDF document not found: $pdfDocumentId")
        }

        // Guard: reject if document is already linked to another kassenbuch entry
        val existingLink =
            KassenbuchHeader
                .find {
                    KassenbuchHeaderTable.linkedDocumentId eq pdfDocumentId
                }.firstOrNull()
        if (existingLink != null) {
            throw IllegalArgumentException(
                "Document $pdfDocumentId is already linked to kassenbuch entry ${existingLink.id.value}",
            )
        }

        header.linkedDocumentId = pdfDocumentId
        logger.info("Linked document $pdfDocumentId to kassenbuch header $headerId")
        return header
    }

    override fun unlinkDocument(headerId: Long): KassenbuchHeader {
        val header =
            KassenbuchHeader.findById(headerId)
                ?: throw IllegalArgumentException("Kassenbuch header not found: $headerId")

        header.linkedDocumentId = null
        logger.info("Unlinked document from kassenbuch header $headerId")
        return header
    }

    override fun getBalanceAt(
        at: kotlinx.datetime.LocalDateTime,
        cashBookId: Long?,
    ): java.math.BigDecimal {
        val lastHeader =
            KassenbuchHeader
                .find {
                    if (cashBookId != null) {
                        (KassenbuchHeaderTable.createdAt less at) and
                            (KassenbuchHeaderTable.cashBookId eq cashBookId)
                    } else {
                        KassenbuchHeaderTable.createdAt less at
                    }
                }.orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
        return lastHeader?.currentBalance ?: java.math.BigDecimal.ZERO
    }

    override fun findEntriesInWindow(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
        cashBookId: Long,
    ): List<KassenbuchHeader> =
        KassenbuchHeader
            .find {
                (KassenbuchHeaderTable.createdAt greaterEq start) and
                    (KassenbuchHeaderTable.createdAt less end) and
                    (KassenbuchHeaderTable.cashBookId eq cashBookId)
            }.orderBy(KassenbuchHeaderTable.createdAt to SortOrder.ASC)
            .toList()

    override fun getRecentEntries(
        shop: ShopInfo,
        limit: Int,
        cashBookId: Long?,
    ): List<KassenbuchHeader> {
        logger.info(
            "Getting {} most recent kassenbuch headers for shop: {}, cashBookId={}",
            limit,
            shop.id.value,
            cashBookId,
        )
        return KassenbuchHeader
            .find {
                if (cashBookId != null) {
                    (KassenbuchHeaderTable.shopId eq shop.id) and (KassenbuchHeaderTable.cashBookId eq cashBookId)
                } else {
                    KassenbuchHeaderTable.shopId eq shop.id
                }
            }.orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
    }

    override fun replaceDocument(
        headerId: Long,
        newDocumentId: Long,
    ): KassenbuchHeader {
        val header =
            KassenbuchHeader.findById(headerId)
                ?: throw IllegalArgumentException("Kassenbuch header not found: $headerId")

        if (!documents.documentExists(newDocumentId)) {
            throw IllegalArgumentException("PDF document not found: $newDocumentId")
        }

        // Guard: reject if the new document is already linked to a DIFFERENT kassenbuch entry.
        val existingLink =
            KassenbuchHeader
                .find {
                    KassenbuchHeaderTable.linkedDocumentId eq newDocumentId
                }.firstOrNull()
        if (existingLink != null && existingLink.id.value != headerId) {
            throw IllegalStateException(
                "Document $newDocumentId is already linked to kassenbuch entry ${existingLink.id.value}",
            )
        }

        val oldDocumentId = header.linkedDocumentId
        header.linkedDocumentId = newDocumentId
        logger.info(
            "Replaced linked document on kassenbuch header $headerId " +
                "(old=$oldDocumentId, new=$newDocumentId)",
        )
        return header
    }

    override fun countUndocumentedInWindow(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
    ): Int =
        KassenbuchHeaderTable
            .selectAll()
            .where {
                (KassenbuchHeaderTable.createdAt greaterEq start) and
                    (KassenbuchHeaderTable.createdAt less end) and
                    KassenbuchHeaderTable.linkedDocumentId.isNull()
            }.count()
            .toInt()

    override fun hasReversal(headerId: Long): Boolean =
        KassenbuchHeader
            .find {
                (KassenbuchHeaderTable.correctsHeaderId eq headerId) and
                    (KassenbuchHeaderTable.correctionRole eq CorrectionRole.REVERSAL)
            }.empty()
            .not()

    override fun findCorrectionsFor(headerId: Long): List<KassenbuchHeader> =
        KassenbuchHeader
            .find {
                KassenbuchHeaderTable.correctsHeaderId eq headerId
            }.toList()
}
