package org.example.project.features.kassenbuch.data.service

import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.utils.formatter.formatGerman
import java.math.BigDecimal

/**
 * Books the drawer count of the "Z-Bon erstellen" dialog INSIDE the closing's own transaction (F58),
 * after the aggregate and the in-closing tip payouts, before the Geldtransit and the sweep.
 *
 * At that point the fiscal book's balance IS the drawer the operator counted: the book before the
 * closing, plus the day's cash takings (the aggregate), minus the tips just handed out. So the
 * Kassendifferenz can only ever assume payouts that are booked in the same transaction — and a
 * closing refused or failing anywhere rolls the difference back with everything else. The entry is
 * the standalone count's ([CashCountDifference]); a matching drawer books nothing.
 */
internal class ClosingCashCountBooker(
    private val kassenbuchRepository: KassenbuchRepository,
    private val documents: KassenbuchDocuments,
) {
    /** The difference entry, PENDING until phase 2 signs it. */
    data class Booked(
        val headerId: Long,
        val belegNr: String,
        val difference: BigDecimal,
        val processType: String,
        val processData: String,
    )

    /**
     * What the closing hands over: [takings] = the aggregate's netCashAmount, [tipPayouts] = Σ in-closing
     * CASH payouts, [topUp] = the top-up put in from another book before them (F59), zero without one.
     */
    data class Count(
        val counted: BigDecimal,
        val takings: BigDecimal,
        val tipPayouts: BigDecimal,
        val topUp: BigDecimal = BigDecimal.ZERO,
    )

    /** Requires the closing's ambient transaction; returns null for a matching drawer. */
    suspend fun book(
        count: Count,
        closing: ClosingTipPayoutBooker.Closing,
    ): Booked? {
        val expected = kassenbuchRepository.getCurrentBalance(closing.shop, closing.fiscalBookId)
        val difference = count.counted - expected
        if (difference.signum() == 0) return null

        val bookBefore = expected - count.takings - count.topUp + count.tipPayouts
        val description =
            "Kassenzählung zum ${closing.belegDatum}: gezählt ${formatGerman(count.counted)} EUR, " +
                "Soll ${formatGerman(expected)} EUR (Buch ${formatGerman(bookBefore)} + " +
                "Bareinnahmen ${formatGerman(count.takings)}" +
                (if (count.topUp.signum() > 0) " + Einlage ${formatGerman(count.topUp)}" else "") +
                (if (count.tipPayouts.signum() > 0) " - Trinkgeld-Auszahlung ${formatGerman(count.tipPayouts)}" else "") +
                ")"
        val belegNr = kassenbuchRepository.getNextBelegNr(closing.shop, closing.fiscalBookId)
        val eigenbeleg =
            CashCountDifference.eigenbeleg(
                documents = documents,
                belegNr = belegNr,
                belegDatum = closing.belegDatum,
                difference = difference,
                description = description,
                operatorId = closing.operator.id.value,
            )
        val request =
            CashCountDifference
                .request(difference, closing.belegDatum, description, eigenbeleg)
                .copy(cashBookId = closing.fiscalBookId)
        val written = InClosingEntryWriter.write(kassenbuchRepository, closing.shop, closing.operator, request, belegNr)
        return Booked(
            headerId = written.header.id.value,
            belegNr = belegNr,
            difference = difference,
            processType = written.payload.processType,
            processData = written.payload.processData,
        )
    }
}
