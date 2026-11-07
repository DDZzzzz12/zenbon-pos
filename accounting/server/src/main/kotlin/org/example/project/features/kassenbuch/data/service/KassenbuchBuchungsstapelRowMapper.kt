package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.error.PosErrorCode
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps a [KassenbuchHeader] and its line items to [BuchungsstapelRowInput]s.
 * Shared by the plain Buchungsstapel export and the Buchungsstapel-mit-Belegen export.
 *
 * @param belegLink optional pre-formatted DATEV Beleglink cell value applied to every
 *        row of this header (null for the plain CSV export).
 */
object KassenbuchBuchungsstapelRowMapper {
    private val logger = LoggerFactory.getLogger(KassenbuchBuchungsstapelRowMapper::class.java)

    fun mapHeader(
        header: KassenbuchHeader,
        belegLink: String? = null,
        mapping: DatevAccountMappingDto.MappingDto? = null,
    ): List<BuchungsstapelRowInput> {
        val belegDate = parseBelegDatum(header.belegDatum)
        // Accounts resolve through KassenbuchAccountResolver.resolveLine: a NON-numeric
        // header.gegenkonto is dropped (spec §3.4) and a Storno resolves through the entry it
        // reverses — same accounts, opposite sign (the sign is already in its vorzBetrag, F64).
        // Belegfeld 1: plain for the fiscal Ladenkasse, book-prefixed for any other book, so two
        // books' identical per-book numbers cannot collide in one Buchungsstapel.
        val belegNr = DatevBelegfeld1.of(header)

        return header.lineItems.mapNotNull { li ->
            val amount = li.vorzBetrag
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                logger.warn(
                    "KassenbuchBuchungsstapelRowMapper: skipping zero-amount line item on header {}",
                    header.id.value,
                )
                return@mapNotNull null
            }

            val resolution = KassenbuchAccountResolver.resolveLine(header, li, mapping)
            val buchungstext = (li.belegtext ?: header.belegtext).ifBlank { "Buchung" }
            when (resolution) {
                is KassenbuchAccountResolver.Resolution.Mapped ->
                    BuchungsstapelRowInput(
                        umsatz = amount,
                        belegDate = belegDate,
                        belegNr = belegNr,
                        buchungstext = buchungstext,
                        konto = cashAccountOf(header, mappedKasseKonto = resolution.konto),
                        // Null when the cell's „Konto automatisch zuordnen" switch is off: the
                        // generator then writes the Gegenkonto (and BU) field empty, Konto and
                        // Soll/Haben unchanged, and the Steuerberater completes it in DATEV.
                        gegenkonto = resolution.gegenkonto,
                        bu = resolution.bu,
                        kost1 = header.kost1,
                        kost2 = header.kost2,
                        kostMenge = li.kostmenge,
                        belegLink = belegLink,
                    )
                // No fallback exists any more, deliberately. This used to drop to
                // entryType.getDefault*AccountNumber(), a fixed SKR03/SKR04 mix that also put the
                // contra account in Konto for five entry types, inverting the booking. An
                // unresolvable line is a configuration gap the operator must close, and a refused
                // export is the only outcome that cannot become a wrong one in the ledger.
                KassenbuchAccountResolver.Resolution.Legacy -> throw unresolvableLine(header, li)
            }
        }
    }

    /**
     * The Konto (the cash side) of a row: the cash book's OWN DATEV account (T9-10).
     *
     * The mapping's `kasseKonto` is the account of the fiscal Ladenkasse. A non-fiscal book (a
     * Tresor, 1010 by default) is a different Sachkonto, and booking its rows on the Kasse account
     * made a Geldtransit Ladenkasse → Tresor cancel itself out — `1000 / 1360` out and `1000 / 1360`
     * in — so the money never reached the Tresor in the books. The fiscal book keeps `kasseKonto`,
     * which is what the operator maintains on the mapping screen (its own `datevAccount` defaults
     * to 1000 and is not edited there); a non-fiscal book whose `datevAccount` is not a number falls
     * back to `kasseKonto` as well rather than inventing an account.
     */
    private fun cashAccountOf(
        header: KassenbuchHeader,
        mappedKasseKonto: Int,
    ): Int {
        val book = header.cashBook
        if (book.isFiscal) return mappedKasseKonto
        return book.datevAccount.trim().toIntOrNull() ?: mappedKasseKonto.also {
            logger.warn(
                "KassenbuchBuchungsstapelRowMapper: cash book {} has no numeric DATEV account ('{}'), using Kasse {}",
                book.id.value,
                book.datevAccount,
                mappedKasseKonto,
            )
        }
    }

    /**
     * The 409 that refuses a line the mapping could not resolve — **two shapes, two messages**,
     * because they have two different remedies.
     *
     * The ordinary shape IS a configuration gap: a cell is missing, and the operator creates it on
     * the mapping screen. A [KassenbuchLineKind.RECEIVABLE_SETTLEMENT] carrying **no payment type**
     * is not. That line resolves on the payment-type axis, and it carries no payment type because
     * `DrawerCashDerivation.receivableTenderOf` could not determine the tender that OPENED the
     * receivable at all — the settled invoice has no linked original bill, that bill has no
     * `createsReceivable` leg, or its receivable legs name more than one tender. There is no
     * "unknown tender" cell in `mapping.paymentTypes` and none can be created, so the ordinary
     * message sends the reader to the account mapping to perform something that does not exist
     * there — an error naming a remedy its reader cannot carry out.
     *
     * Refusing stays correct and is not what changes here: splitting one clearing across several
     * opening tenders has no ground truth in the data, and a plausible wrong contra account in the
     * ledger is precisely what nothing downstream flags. Only the SENTENCE differs — there is no
     * fallback account, no default tender and no invented split on either branch.
     */
    private fun unresolvableLine(
        header: KassenbuchHeader,
        line: KassenbuchLineItem,
    ): PosException {
        if (line.kind == KassenbuchLineKind.RECEIVABLE_SETTLEMENT && line.paymentType == null) {
            return PosException(
                PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
                HttpStatusCode.Conflict,
                "Beleg ${header.belegNr} (entry type ${header.entryType}) carries a cash " +
                    "Forderungsausgleich of ${line.vorzBetrag.toPlainString()} whose opening payment " +
                    "type could not be determined, so there is no receivable account to book the " +
                    "settlement against. This happens when the settled invoice has no linked original " +
                    "receipt, when that receipt has no payment leg that opened a receivable, or when " +
                    "its receivable legs name more than one payment type — one clearing cannot be split " +
                    "across several opening tenders without inventing the split, so the line is refused " +
                    "instead. The DATEV account mapping is NOT at fault and completing it will not clear " +
                    "this: check the invoice settled on this Beleg and the receipt it was raised from.",
            )
        }
        return PosException(
            PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
            HttpStatusCode.Conflict,
            "No DATEV account is configured for entry type ${header.entryType} " +
                "(line kind ${line.kind}, VAT rate ${line.ustSatz ?: "-"}, " +
                "payment type ${line.paymentType?.name ?: "-"}) on Beleg ${header.belegNr}. " +
                "Complete the account mapping under ${DatevBuchungsstapelMappingGate.MAPPING_LOCATION} and export again.",
        )
    }

    /** Parses "DD.MM.YYYY" (storage format) to [LocalDate]. */
    private fun parseBelegDatum(ddMmYyyy: String): LocalDate {
        val parts = ddMmYyyy.split(".")
        if (parts.size != 3) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "Invalid belegDatum format: $ddMmYyyy",
            )
        }
        return LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
    }
}
