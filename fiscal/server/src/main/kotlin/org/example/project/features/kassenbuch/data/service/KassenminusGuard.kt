package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.zBon.domain.DrawerCashDerivation
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.formatter.toDecimalString
import java.math.BigDecimal

/**
 * THE Kassenminus rule (F59): no booking may take a cash book below zero — no cash can leave a book
 * that the book does not hold. A negative Kassenbestand is a formal defect of the records
 * (GoBD Tz. 3.3.3, Kassensturzfähigkeit): it proves an inflow is missing from them.
 *
 * One definition for every withdrawal: an operator entry through `KassenbuchServiceImpl.createEntry`
 * (Barausgabe, Entnahme, Privatentnahme, refund, a correction's re-entry), a tip payout after a
 * Z-Bon, the tip payouts inside a closing, and both legs-out of a Geldtransit
 * ([CashBookTransferServiceImpl], operator transfers and the closing's own top-up and daily transfer).
 *
 * ## Available cash
 *
 * What the book physically holds at the moment of booking:
 *
 * - **a non-fiscal book** (Tresor, Hauptkasse, Nebenkasse): its running balance. Nothing reaches it
 *   except Kassenbuch entries.
 * - **the fiscal Ladenkasse, outside a closing**: its running balance PLUS the cash takings of the
 *   bills no Z-Bon has closed yet ([DrawerCashDerivation.unbookedCashTakings]). Cash sales reach the
 *   book only as the next closing's CASH_SALE aggregate, but the money is in the drawer from the
 *   moment the bill is paid — a till that sold 300 € in cash this morning can pay a 40 € expense at
 *   noon with an empty book. It is the very figure the pre-closing drawer count compares against, so
 *   the guard and the count cannot disagree about what the drawer holds.
 * - **the fiscal Ladenkasse, inside a closing** ([Takings.BOOKED_BY_CLOSING]): its running balance
 *   alone. By the time the closing books its top-up, payouts and Geldtransit, its aggregate has
 *   already booked those same takings; adding them again would count them twice.
 *
 * Must be evaluated inside the booking's transaction AFTER `pg_advisory_xact_lock(1)` — every
 * Kassenbuch write and every closing takes that lock first, so the balance read here includes every
 * booking committed before this one, and two concurrent withdrawals cannot both pass on the same
 * balance. Every function needs an ambient transaction.
 *
 * ## Exempt
 *
 * - **Stornos** (`correctionRole = REVERSAL`, a transfer's reversal pair, a tip payout's storno): a
 *   Storno says a recorded booking did not happen. Refusing it would force the books to keep a
 *   record known to be false — strictly worse under GoBD (Richtigkeit) than the negative balance the
 *   Storno exposes, which was already the truth. A Storno of an Einlage whose cash has since been
 *   spent therefore stays possible and shows the gap; the operator then books the missing inflow.
 *   This is the rule `CashBookTransferServiceImpl` states for transfer legs: a locked leg MUST stay
 *   reversible. The re-entry of a correction is NOT exempt — it is a new booking.
 * - **The cash count's Kassendifferenz** (standalone or in the Z-Bon dialog): it records what was
 *   counted, i.e. reality, and is the way a book is brought back in line with the drawer.
 */
internal object KassenminusGuard {
    /** Whether the fiscal Ladenkasse's not-yet-closed cash takings count as available. */
    enum class Takings {
        /** Outside a closing: the drawer holds the takings the book does not show yet. */
        UNBOOKED_IN_DRAWER,

        /** Inside a closing, after its aggregate: the book already holds the takings. */
        BOOKED_BY_CLOSING,
    }

    /** What [book] can pay out right now, split into its two parts. */
    data class Available(
        val bookBalance: BigDecimal,
        val unbookedCashTakings: BigDecimal,
    ) {
        val total: BigDecimal get() = bookBalance + unbookedCashTakings
    }

    fun available(
        kassenbuchRepository: KassenbuchRepository,
        book: CashBook,
        takings: Takings,
    ): Available {
        val bookBalance = kassenbuchRepository.getCurrentBalance(book.shop, book.id.value)
        val unbooked =
            if (book.isFiscal && takings == Takings.UNBOOKED_IN_DRAWER) {
                DrawerCashDerivation.unbookedCashTakings()
            } else {
                BigDecimal.ZERO
            }
        return Available(bookBalance, unbooked)
    }

    /**
     * 409 [PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH] when paying out [outflow] (a positive amount)
     * would leave [book] below zero. The detail names the available amount, at scale 2.
     */
    fun requireCovered(
        kassenbuchRepository: KassenbuchRepository,
        book: CashBook,
        outflow: BigDecimal,
        takings: Takings,
    ) {
        if (outflow.signum() <= 0) return
        val available = available(kassenbuchRepository, book, takings).total
        if (available >= outflow) return
        throw insufficient(book, available, outflow)
    }

    fun insufficient(
        book: CashBook,
        available: BigDecimal,
        outflow: BigDecimal,
    ) = PosException(
        PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH,
        HttpStatusCode.Conflict,
        // Money at scale 2, never the stored DECIMAL(19,4) form (checklist 12.9: "holds 35.8100").
        "Cash book '${book.name}' holds ${available.toDecimalString()} — cannot pay out ${outflow.toDecimalString()}",
    )
}
