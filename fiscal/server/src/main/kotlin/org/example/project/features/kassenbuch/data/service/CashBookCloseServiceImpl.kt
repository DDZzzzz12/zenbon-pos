/**
 * Implementation of [CashBookCloseService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. [preview] is read-only — no `cash_book_close` row is written, nothing
 * is locked. [close] is where the Kassensturz actually happens: it reuses [buildPreview] (so a
 * book that cannot be previewed cannot be closed either), books the counted/book difference as a
 * real accounting entry, and locks the period.
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.database.tables.CashBookCloseTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.CashBookClose
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.kassenbuch.domain.service.CashBookCloseService
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.formatGerman
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.math.BigDecimal
import java.util.UUID

class CashBookCloseServiceImpl(
    private val cashBookRepository: CashBookRepository,
    private val kassenbuchRepository: KassenbuchRepositoryImpl,
    private val employeeRepository: EmployeeRepository,
    private val documents: KassenbuchDocuments,
) : CashBookCloseService {
    override suspend fun preview(cashBookId: Long): CashBookDto.ClosePreviewDto =
        dbQuery {
            val data = buildPreview(cashBookId)
            CashBookDto.ClosePreviewDto(
                cashBookId = data.book.id.value,
                cashBookName = data.book.name,
                periodStart = data.periodStart.toString(),
                periodEnd = data.periodEnd.toString(),
                bookBalance = data.bookBalance.toDecimalString(),
            )
        }

    override suspend fun close(
        employeeId: String,
        cashBookId: Long,
        countedBalance: BigDecimal,
        notes: String?,
    ): CashBookDto.CloseResponseDto =
        dbQuery {
            // Round-8 R7-D9, the SAME lock and the SAME reason as CashBookTransferServiceImpl
            // .createTransfer / KassenbuchServiceImpl.createSignedEntry: this path creates a
            // Kassenbuch entry, so it must serialize against ZBonServiceImpl.createReport, which
            // holds pg_advisory_xact_lock(1) for its whole closing transaction. Without it the
            // difference entry could commit invisibly INSIDE the closing's REPEATABLE_READ
            // snapshot and be stranded is_locked=false — and there is no unlock/delete/repair path
            // for a stranded entry.
            //
            // Must be the FIRST statement. Postgres-only; H2 aliases it to a no-op (TestDatabase).
            TransactionManager.current().exec("SELECT pg_advisory_xact_lock(1)")

            val data = buildPreview(cashBookId)
            val difference = countedBalance - data.bookBalance

            val employee =
                employeeRepository.getEmployeeById(UUID.fromString(employeeId))
                    ?: throw employeeNotFound(employeeId)

            // A Kassensturz that never books the delta is bookkeeping theatre: the book would
            // stay knowingly wrong and the discrepancy would compound into the next period.
            val differenceHeader =
                if (difference.signum() != 0) {
                    createDifferenceEntry(
                        book = data.book,
                        employee = employee,
                        bookBalance = data.bookBalance,
                        countedBalance = countedBalance,
                        difference = difference,
                        periodEnd = data.periodEnd,
                    )
                } else {
                    null
                }

            lockPeriod(cashBookId, data.periodEnd, differenceHeader?.id?.value)

            CashBookClose
                .new {
                    this.cashBook = data.book
                    this.periodStart = data.periodStart
                    this.periodEnd = data.periodEnd
                    this.bookBalance = data.bookBalance
                    this.countedBalance = countedBalance
                    this.difference = difference
                    this.differenceHeader = differenceHeader
                    this.closedBy = employee
                    this.notes = notes
                }.toResponseDto()
        }

    override suspend fun closeHistory(cashBookId: Long): List<CashBookDto.CloseResponseDto> =
        dbQuery {
            cashBookRepository.findById(cashBookId) ?: throw bookNotFound(cashBookId)

            CashBookClose
                .find { CashBookCloseTable.cashBookId eq cashBookId }
                .orderBy(CashBookCloseTable.periodEnd to SortOrder.DESC, CashBookCloseTable.id to SortOrder.DESC)
                .map { it.toResponseDto() }
        }

    /** What [preview] and [close] share: the derived period plus the guards that gate both. */
    private data class PreviewData(
        val book: CashBook,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val bookBalance: BigDecimal,
    )

    private fun buildPreview(cashBookId: Long): PreviewData {
        val book = cashBookRepository.findById(cashBookId) ?: throw bookNotFound(cashBookId)
        requireNonFiscal(book)

        val derivedStart = derivePeriodStart(book) ?: throw nothingToClose(book)
        val today = currentTimeStamp().date
        val periodEnd = computePeriodEnd(book.closeInterval, derivedStart).coerceAtMost(today)

        // derivedStart is the day AFTER the previous close, while periodEnd is capped at today —
        // so closing twice without a day passing derives start = tomorrow, end = today.
        // Left unguarded that writes an inverted-period row, re-locks the whole book, and books a
        // SECOND difference entry: a Kassensturz the operator can repeat at will, each one moving
        // the balance again. Periods must also stay ordered, and this is what enforces it, since
        // the caller never supplies a period to put out of order.
        //
        // But a repeat is refused only when there is genuinely nothing new (T9-11). Entries booked
        // AFTER today's close are unlocked, block a Stammdaten edit (OpenBusinessCheck, ALL_BOOKS)
        // and can only be locked by a Kassensturz of this book — refusing it until tomorrow left the
        // operator with no way out for the rest of the day. So when unlocked entries exist, a
        // same-day follow-up close covers today again (periodStart = periodEnd = today): still
        // ordered and gapless, and it sweeps exactly the entries booked since the previous close.
        val periodStart =
            when {
                periodEnd >= derivedStart -> derivedStart
                hasUnlockedEntries(book) -> today
                else ->
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.UnprocessableEntity,
                        "Cash book '${book.name}' has nothing new to close: the last close already covers " +
                            "everything through ${derivedStart.minus(1, DateTimeUnit.DAY)}",
                    )
            }

        val balance = kassenbuchRepository.getCurrentBalance(book.shop, cashBookId)

        return PreviewData(book, periodStart, periodEnd, balance)
    }

    /** `true` when [book] holds an entry no Kassensturz has locked yet — booked since its last close. */
    private fun hasUnlockedEntries(book: CashBook): Boolean =
        !KassenbuchHeader
            .find { (KassenbuchHeaderTable.cashBookId eq book.id.value) and (KassenbuchHeaderTable.isLocked eq false) }
            .limit(1)
            .empty()

    private fun requireNonFiscal(book: CashBook) {
        if (book.isFiscal) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.UnprocessableEntity,
                "'${book.name}' is the fiscal Ladenkasse — it is closed by the Z-Bon, not a Kassensturz",
            )
        }
    }

    /**
     * The day after the previous close's `periodEnd`, or the date of the book's earliest entry
     * when it has never been closed. Null when there is neither — nothing to close yet.
     */
    private fun derivePeriodStart(book: CashBook): LocalDate? {
        val previousClose =
            CashBookClose
                .find { CashBookCloseTable.cashBookId eq book.id.value }
                .orderBy(CashBookCloseTable.periodEnd to SortOrder.DESC, CashBookCloseTable.id to SortOrder.DESC)
                .firstOrNull()
        if (previousClose != null) {
            return previousClose.periodEnd.plus(1, DateTimeUnit.DAY)
        }

        // createdAt, not belegDatum — the latter is an operator-typed varchar that neither sorts
        // nor parses reliably (see KassenbuchRepository.getEntriesByDateRange's comment).
        val earliestEntry =
            KassenbuchHeader
                .find { KassenbuchHeaderTable.cashBookId eq book.id.value }
                .orderBy(KassenbuchHeaderTable.createdAt to SortOrder.ASC)
                .firstOrNull()
        return earliestEntry?.createdAt?.date
    }

    private fun computePeriodEnd(
        interval: CashBookCloseInterval,
        periodStart: LocalDate,
    ): LocalDate =
        when (interval) {
            CashBookCloseInterval.WEEKLY -> endOfIsoWeek(periodStart)
            CashBookCloseInterval.MONTHLY -> endOfMonth(periodStart)
            CashBookCloseInterval.QUARTERLY -> endOfQuarter(periodStart)
            CashBookCloseInterval.MANUAL -> currentTimeStamp().date
        }

    private fun endOfIsoWeek(date: LocalDate): LocalDate {
        val daysUntilSunday = DayOfWeek.SUNDAY.isoDayNumber - date.dayOfWeek.isoDayNumber
        return date.plus(daysUntilSunday, DateTimeUnit.DAY)
    }

    private fun endOfMonth(date: LocalDate): LocalDate =
        LocalDate(date.year, date.monthNumber, 1)
            .plus(1, DateTimeUnit.MONTH)
            .plus(-1, DateTimeUnit.DAY)

    private fun endOfQuarter(date: LocalDate): LocalDate {
        val quarterStartMonth = ((date.monthNumber - 1) / 3) * 3 + 1
        return LocalDate(date.year, quarterStartMonth, 1)
            .plus(3, DateTimeUnit.MONTH)
            .plus(-1, DateTimeUnit.DAY)
    }

    /**
     * Books the counted/book difference as one CASH_SURPLUS (positive) or CASH_SHORTAGE
     * (negative) entry, dated [periodEnd], drawing its belegNr from [book]'s own series, with a
     * mandatory Eigenbeleg.
     */
    private suspend fun createDifferenceEntry(
        book: CashBook,
        employee: Employee,
        bookBalance: BigDecimal,
        countedBalance: BigDecimal,
        difference: BigDecimal,
        periodEnd: LocalDate,
    ): KassenbuchHeader {
        val entryType = if (difference.signum() > 0) KassenbuchEntryType.CASH_SURPLUS else KassenbuchEntryType.CASH_SHORTAGE
        val belegNr = kassenbuchRepository.nextBelegNrBlocking(book.id.value)
        val belegDatum = formatBelegDatum(periodEnd)
        val signedAmount = difference.toPlainString()
        val amountLabel =
            if (difference.signum() > 0) "+${formatGerman(difference)} EUR" else "-${formatGerman(difference.abs())} EUR"
        val belegtext = KassensturzBelegtext.of(book.name, entryType)
        val description =
            "Kassensturz ${book.name} zum $belegDatum: gezählt ${formatGerman(countedBalance)} EUR, " +
                "Buch ${formatGerman(bookBalance)} EUR"

        val documentId =
            documents.createEigenbeleg(
                belegNr = belegNr,
                date = belegDatum,
                entryType = entryType.displayName,
                amount = amountLabel,
                description = description,
                employeeId = employee.id.value,
            )

        val header =
            kassenbuchRepository.createEntry(
                shop = book.shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = entryType,
                        belegNr = belegNr,
                        vorzBetrag = signedAmount,
                        belegDatum = belegDatum,
                        belegtext = belegtext,
                        cashBookId = book.id.value,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = signedAmount, ustSatz = null)),
            )

        // A non-fiscal book (guaranteed by requireNonFiscal, above) is not an elektronisches
        // Aufzeichnungssystem — §146a does not reach it, so this entry is never signed.
        // createEntry itself leaves tseSigningStatus at its table default (PENDING) — every other
        // non-fiscal caller (CashBookTransferServiceImpl, KassenbuchServiceImpl.createSignedEntry)
        // sets it explicitly afterwards for the same reason, so this does too.
        header.tseSigningStatus = TseSigningStatus.NOT_APPLICABLE
        kassenbuchRepository.linkDocument(header.id.value, documentId)
        return header
    }

    @Suppress("DefaultLocale")
    private fun formatBelegDatum(date: LocalDate): String = String.format("%02d.%02d.%04d", date.dayOfMonth, date.monthNumber, date.year)

    /**
     * Locks every unlocked entry in [bookId] up to [periodEnd] — the difference entry included,
     * even though its `createdAt` is NOW and so may fall AFTER `periodEnd` when the period being
     * closed ended in the past. Excluding it there would strand it `isLocked = false` forever: a
     * later close would exclude it again for the same reason, and there is no repair path (see
     * `KassenbuchHeaderTable.isLocked`'s KDoc).
     *
     * Scoped to `cashBookId eq bookId` ONLY, never by date/shop alone — the second-writer
     * invariant in that same KDoc requires this predicate be structurally incapable of touching
     * the fiscal book's rows (the Z-Bon sweep's) or another non-fiscal book's rows.
     */
    private fun lockPeriod(
        bookId: Long,
        periodEnd: LocalDate,
        differenceHeaderId: Long?,
    ) {
        val periodEndExclusive = periodEnd.plus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
        val createdWithinPeriod = KassenbuchHeaderTable.createdAt less periodEndExclusive
        val lockCondition =
            if (differenceHeaderId != null) {
                createdWithinPeriod or (KassenbuchHeaderTable.id eq differenceHeaderId)
            } else {
                createdWithinPeriod
            }

        KassenbuchHeader
            .find {
                (KassenbuchHeaderTable.cashBookId eq bookId) and
                    (KassenbuchHeaderTable.isLocked eq false) and
                    lockCondition
            }.forEach { it.isLocked = true }
    }

    private fun CashBookClose.toResponseDto(): CashBookDto.CloseResponseDto =
        CashBookDto.CloseResponseDto(
            cashBookId = cashBook.id.value,
            periodStart = periodStart.toString(),
            periodEnd = periodEnd.toString(),
            bookBalance = bookBalance.toDecimalString(),
            countedBalance = countedBalance.toDecimalString(),
            difference = difference.toDecimalString(),
            differenceHeaderId = differenceHeader?.id?.value,
            closedByEmployeeId = closedBy.id.value.toString(),
            closedAt = closedAt.toString(),
            notes = notes,
        )

    private fun bookNotFound(id: Long) = PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Cash book with id=$id not found")

    private fun nothingToClose(book: CashBook) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.UnprocessableEntity,
            "Cash book '${book.name}' has no entries and has never been closed — nothing to close",
        )

    private fun employeeNotFound(employeeId: String) = PosException(PosErrorCode.EMPLOYEE_NOT_FOUND, HttpStatusCode.NotFound, "Employee not found: $employeeId")
}
