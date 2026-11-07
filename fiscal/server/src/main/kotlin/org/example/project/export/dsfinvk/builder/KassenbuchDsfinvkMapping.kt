/**
 * Mapping utility for Kassenbuch entry types to DSFinV-K GV_TYP values.
 *
 * Provides bidirectional mapping between KassenbuchEntryType enumeration and
 * DSFinV-K compliant business case types (GV_TYP) for German fiscal export.
 * Essential for proper classification of cash book entries in DSFinV-K exports.
 *
 * GV_TYP Mapping Reference:
 * - Geldtransit: Cash transfers to/from bank
 * - Umsatz: Revenue transactions (cash sales)
 * - Privateinlage: Owner deposits
 * - Privatentnahme: Owner withdrawals
 * - Auszahlung: Cash payouts/expenses
 * - DifferenzSollIst: Cash discrepancies
 * - Anfangsbestand: Opening balances and change funds
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.slf4j.LoggerFactory

/**
 * Utility object for mapping Kassenbuch entry types to DSFinV-K business case types.
 *
 * Ensures consistent classification of cash book entries for German fiscal compliance
 * in accordance with DSFinV-K specification requirements.
 */
object KassenbuchDsfinvkMapping {
    private val logger = LoggerFactory.getLogger(KassenbuchDsfinvkMapping::class.java)

    /**
     * Maps a KassenbuchEntryType to its corresponding DSFinV-K GV_TYP value.
     *
     * This mapping follows the German DSFinV-K specification for categorizing
     * cash book transactions in fiscal exports.
     *
     * **Corrections (round-3 KABS-5).** A correction is a Storno (`CORRECTION_OUT`,
     * `REVERSAL`) + re-entry of an *arbitrary* Geschäftsvorfall — e.g. a wrongly-booked
     * Privateinlage. The reversal must net *inside the corrected entry's* GV_TYP
     * (Privateinlage), not surface as a `DifferenzSollIst`. Anhang C p. 59 reserves
     * `DifferenzSollIst` for the counted Soll/Ist difference at Kassensturz — i.e. the
     * genuine `CASH_SURPLUS`/`CASH_SHORTAGE` Kassendifferenzen, which have NO `corrects`
     * link. So a `CORRECTION_*` row maps to its corrected entry's GV_TYP; the reversal's
     * own signed amount (a `CORRECTION_OUT` carries the negative) nets the original.
     *
     * @param entryType The Kassenbuch entry type to map
     * @param correctedEntryType For a `CORRECTION_IN`/`CORRECTION_OUT` entry, the
     *   `entryType` of the entry it corrects (`KassenbuchHeader.correctsHeader?.entryType`).
     *   Ignored for every other entry type. Null on a `CORRECTION_*` row is a defensive/
     *   legacy fallback to `DifferenzSollIst` (logged).
     * @return The corresponding DSFinV-K GV_TYP constant
     */
    fun getGvTyp(
        entryType: KassenbuchEntryType,
        correctedEntryType: KassenbuchEntryType? = null,
    ): String =
        when (entryType) {
            // Cash transfers to/from bank - Geldtransit
            KassenbuchEntryType.CASH_TRANSFER_IN,
            KassenbuchEntryType.CASH_TRANSFER_OUT,
            -> DsfinvkExportDto.BusinessCaseTypes.GELDTRANSIT

            // Revenue transactions - Umsatz
            KassenbuchEntryType.CASH_SALE,
            KassenbuchEntryType.CASH_PAYMENT,
            -> DsfinvkExportDto.BusinessCaseTypes.UMSATZ

            // m6: a generic cash deposit is the catch-all inflow Einzahlung (Anhang C p. 58 —
            // „bezogen auf den jeweiligen Sachverhalt zu prüfen"). It is NOT the owner's private
            // capital; that is PRIVATE_DEPOSIT below.
            KassenbuchEntryType.CASH_DEPOSIT -> DsfinvkExportDto.BusinessCaseTypes.EINZAHLUNG

            // Owner private deposit - Privateinlage (Anhang C p. 57, deducted from the Gewinn)
            KassenbuchEntryType.PRIVATE_DEPOSIT -> DsfinvkExportDto.BusinessCaseTypes.PRIVATEINLAGE

            // Cash expenses/withdrawals - Auszahlung
            KassenbuchEntryType.CASH_EXPENSE,
            KassenbuchEntryType.CASH_WITHDRAWAL,
            -> DsfinvkExportDto.BusinessCaseTypes.AUSZAHLUNG

            // Owner withdrawal - Privatentnahme
            KassenbuchEntryType.PRIVATE_WITHDRAWAL -> DsfinvkExportDto.BusinessCaseTypes.PRIVATENTNAHME

            // Refunds - use Umsatz with negative amount
            KassenbuchEntryType.REFUND -> DsfinvkExportDto.BusinessCaseTypes.UMSATZ

            // Counted Soll/Ist difference at Kassensturz - DifferenzSollIst. These are the
            // GENUINE Kassendifferenzen (they have NO `corrects` link); Anhang C p. 59 reserves
            // DifferenzSollIst for exactly this. Corrections (below) must NOT land here.
            KassenbuchEntryType.CASH_SURPLUS,
            KassenbuchEntryType.CASH_SHORTAGE,
            -> DsfinvkExportDto.BusinessCaseTypes.DIFFERENZ_SOLL_IST

            // Balance snapshots - Anfangsbestand. Anhang C p. 56 mandates the genuine
            // Anfangsbestand (initial cash fill) „als BON_TYP «Beleg» zu erfassen" with GV_TYP
            // Anfangsbestand and a Bar tender (worked example p. 56/57) — hence OPENING_BALANCE is
            // now a Beleg (see getBonTyp). CLOSING_BALANCE keeps ANFANGSBESTAND for LEGACY display
            // only (new ones are rejected at creation, round-3 KABS-3) and is EXCLUDED from
            // businesscases aggregation because its BON_TYP is AVSonstige, not Beleg (INV-F5).
            // Refilling the drawer (Wechselgeld) stays „über den Geschäftsvorfalltyp
            // ‚Geldtransit' zu erfassen" (audit KABS-6, official example 6.1).
            KassenbuchEntryType.OPENING_BALANCE,
            KassenbuchEntryType.CLOSING_BALANCE,
            -> DsfinvkExportDto.BusinessCaseTypes.ANFANGSBESTAND
            KassenbuchEntryType.CHANGE_FUND -> DsfinvkExportDto.BusinessCaseTypes.GELDTRANSIT

            // Corrections net INSIDE the corrected entry's GV_TYP (round-3 KABS-5). The
            // reversal's own signed amount provides the +/- that nets the original; the
            // re-entry (a real booking of the corrected entryType) is not a CORRECTION_* row
            // and maps directly. A CORRECTION_* row with no `corrects` link is a defensive/
            // legacy anomaly — fall back to DifferenzSollIst so it is at least visible.
            KassenbuchEntryType.CORRECTION_IN,
            KassenbuchEntryType.CORRECTION_OUT,
            ->
                if (correctedEntryType != null) {
                    getGvTyp(correctedEntryType)
                } else {
                    logger.warn(
                        "CORRECTION_* Kassenbuch entry has no corrected-entry type; " +
                            "falling back to DifferenzSollIst (round-3 KABS-5 defensive path)",
                    )
                    DsfinvkExportDto.BusinessCaseTypes.DIFFERENZ_SOLL_IST
                }
        }

    /**
     * GV_TYP for a single line. Per-line kinds override the header GV_TYP ONLY for the
     * Umsatz-family (entry types whose header GV_TYP is Umsatz — currently CASH_SALE/
     * CASH_PAYMENT/REFUND). A PFAND/GUTSCHEIN line inside a non-Umsatz entry (e.g. a
     * Barausgabe) is part of that Auszahlung, not a register-side sales event.
     *
     * The single exception is [KassenbuchLineKind.EMPLOYEE_TIP], which is `TrinkgeldAN` in ANY
     * entry — the tip payout is a `CASH_WITHDRAWAL` whose header GV_TYP is Auszahlung.
     */
    fun getGvTyp(
        entryType: KassenbuchEntryType,
        kind: KassenbuchLineKind,
        vorzBetrag: java.math.BigDecimal,
        correctedEntryType: KassenbuchEntryType? = null,
    ): String {
        // An employee-tip line is TrinkgeldAN wherever it sits — inside the Z-Bon aggregate's
        // Umsatz and inside the payout's Auszahlung alike. Anhang C p. 52: „Im
        // Geschäftsvorfalltyp ‚TrinkgeldAN' werden alle kassenwirksamen Ein- und Auszahlungen von
        // Trinkgeldern an den Arbeitnehmer erfasst … Es ist möglich, mit diesem Geschäftsvorfalltyp
        // sowohl die Ein- als auch die Auszahlung an den Arbeitnehmer darzustellen." Checked
        // BEFORE the Umsatz gate below, which is the one exception to "non-Umsatz entries keep the
        // header GV_TYP": a payout booked as plain Auszahlung would claim a rate container for
        // money that was never turnover. For a CORRECTION_* the line kind is copied from the
        // corrected entry, so a reversal lands in the same GV_TYP and container as its original.
        if (kind == KassenbuchLineKind.EMPLOYEE_TIP) return DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN
        val headerGvTyp = getGvTyp(entryType, correctedEntryType)
        if (headerGvTyp != DsfinvkExportDto.BusinessCaseTypes.UMSATZ) return headerGvTyp
        return when (kind) {
            KassenbuchLineKind.PFAND ->
                if (vorzBetrag.signum() >= 0) {
                    DsfinvkExportDto.BusinessCaseTypes.PFAND
                } else {
                    DsfinvkExportDto.BusinessCaseTypes.PFAND_RUECKZAHLUNG
                }
            // A GUTSCHEIN line is a voucher redemption, not a discount. The Kassenbuch
            // model doesn't distinguish single- vs multi-purpose vouchers, so use the
            // general-case multi-purpose redemption GV_TYP. The redemption itself is a
            // non-taxable Geschäftsvorfall (key-5 / USt-0, official example V_2_3_02
            // Bsp. 2.2) — the VAT already sat on the supply's Umsatz lines (round-3 KABS-4).
            KassenbuchLineKind.GUTSCHEIN -> DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG
            // 4c-2c: a till clearing line is a Forderungsauflösung — non-taxable (key 5), the same
            // classification the per-bill clearing export uses. KassenbuchTaxClassifier already lists
            // FORDERUNGSAUFLOESUNG as non-rate-governed, so it buckets to key 5 with zero USt.
            KassenbuchLineKind.RECEIVABLE_SETTLEMENT -> DsfinvkExportDto.BusinessCaseTypes.FORDERUNGSAUFLOESUNG
            KassenbuchLineKind.STANDARD, KassenbuchLineKind.NON_CASH_PAYMENT -> headerGvTyp
            // Unreachable — returned above, before the Umsatz gate. Kept so the `when` stays total.
            KassenbuchLineKind.EMPLOYEE_TIP -> DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN
        }
    }

    /**
     * Gets the German display name for a GV_TYP value in the context of Kassenbuch entries.
     *
     * @param gvTyp The DSFinV-K GV_TYP value
     * @return Human-readable German name for the business case type
     */
    fun getGvName(gvTyp: String): String =
        when (gvTyp) {
            DsfinvkExportDto.BusinessCaseTypes.GELDTRANSIT -> "Geldtransit"
            DsfinvkExportDto.BusinessCaseTypes.UMSATZ -> "Umsatz"
            DsfinvkExportDto.BusinessCaseTypes.PRIVATEINLAGE -> "Privateinlage"
            DsfinvkExportDto.BusinessCaseTypes.EINZAHLUNG -> "Einzahlung"
            DsfinvkExportDto.BusinessCaseTypes.PRIVATENTNAHME -> "Privatentnahme"
            DsfinvkExportDto.BusinessCaseTypes.AUSZAHLUNG -> "Auszahlung"
            DsfinvkExportDto.BusinessCaseTypes.DIFFERENZ_SOLL_IST -> "DifferenzSollIst"
            DsfinvkExportDto.BusinessCaseTypes.ANFANGSBESTAND -> "Anfangsbestand"
            DsfinvkExportDto.BusinessCaseTypes.PFAND -> "Pfand"
            DsfinvkExportDto.BusinessCaseTypes.PFAND_RUECKZAHLUNG -> "Pfand-Rückzahlung"
            DsfinvkExportDto.BusinessCaseTypes.RABATT -> "Rabatt"
            DsfinvkExportDto.BusinessCaseTypes.MEHRZWECKGUTSCHEIN_EINLOESUNG -> "Mehrzweckgutschein-Einlösung"
            DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN -> DsfinvkExportDto.BusinessCaseNames.TRINKGELD_AN
            else -> gvTyp
        }

    /**
     * BON_TYP per entry type. Cash-moving Vorgänge change the Vermögenszusammensetzung and
     * are „Beleg" (Anhang B: „Der Vorgangstyp ‚Beleg' ist immer dann zu wählen, wenn eine
     * Änderung der Vermögenszusammensetzung des Betriebes aus dem Vorgang resultiert";
     * official examples V_2_3_05/06 book Privateinlage/-entnahme and Geldtransit as Beleg
     * with a signed Bar tender — audit H2).
     *
     * The genuine Anfangsbestand (OPENING_BALANCE) is likewise a „Beleg": Anhang C p. 56 states
     * the initial cash fill is „als BON_TYP «Beleg» zu erfassen" (GV Anfangsbestand / Bar,
     * worked example p. 56/57) — round-3 KABS-3 reopened the former AVSonstige/„Keine" decision.
     * CLOSING_BALANCE is rejected for NEW entries (a count snapshot is the Kassensturz/
     * DifferenzSollIst flow's job), so it stays AVSonstige here purely for LEGACY rows already
     * persisted — and, being AVSonstige, is excluded from businesscases aggregation (INV-F5).
     */
    fun getBonTyp(entryType: KassenbuchEntryType): String =
        when (entryType) {
            // Legacy-only: new CLOSING_BALANCE entries are rejected at the service (KABS-3).
            KassenbuchEntryType.CLOSING_BALANCE -> DsfinvkExportDto.ReceiptTypes.AV_SONSTIGE
            else -> DsfinvkExportDto.ReceiptTypes.BELEG
        }

    /**
     * BON_NAME for a Kassenbuch entry — free text (Alpha 60, no fixed value list), so it may say
     * what actually happened. The fiscal classification lives in BON_TYP and GV_TYP, which this
     * never touches: a Geldtransit stays GV_TYP `Geldtransit` whatever its name reads.
     *
     * A transfer leg names its counterpart book ("Geldtransit an Tresor", "Geldtransit von
     * Ladenkasse"). The enum's own label used to read "Geldtransit Bank-Einzahlung" for every
     * outgoing transit, so a Ladenkasse → Tresor move was described to a Prüfer as a bank deposit
     * (E2E 2026-09-20 observation). A transit with no counterpart book — booked by hand, e.g. a real
     * bank deposit — falls back to the entry type's direction-neutral label.
     */
    fun bonName(
        entryType: KassenbuchEntryType,
        counterpartyBookName: String?,
    ): String {
        val direction =
            when (entryType) {
                KassenbuchEntryType.CASH_TRANSFER_OUT -> "an"
                KassenbuchEntryType.CASH_TRANSFER_IN -> "von"
                else -> null
            }
        if (direction == null || counterpartyBookName.isNullOrBlank()) return entryType.displayName
        return "Geldtransit $direction ${counterpartyBookName.trim()}".take(BON_NAME_MAX_LENGTH)
    }

    /** DSFinV-K `BON_NAME` is Alpha(60) (`DsfinvkIndexXml`). */
    private const val BON_NAME_MAX_LENGTH = 60

    @Deprecated(
        "BON_TYP depends on the entry type (audit H2)",
        ReplaceWith("getBonTyp(entryType)"),
    )
    fun getBonTyp(): String = DsfinvkExportDto.ReceiptTypes.AV_SONSTIGE

    /**
     * Generates a unique BON_ID for Kassenbuch entries.
     *
     * Delegates to [BonIdNamespace.kassenbuch] so the namespace prefix that distinguishes
     * Kassenbuch entries from bills/orders/cancellations is defined in a single place.
     *
     * @param kassenbuchId The Kassenbuch entry ID
     * @return The formatted BON_ID string
     */
    fun generateBonId(kassenbuchId: Long): String = BonIdNamespace.kassenbuch(kassenbuchId)

    // Round-7 C1/C2/E3: `hasVatImplication(entryType, correctedEntryType)` USED TO LIVE HERE and
    // has been DELETED, not moved. It was an ENTRY-level boolean answering a question that is a
    // property of the LINE's GV_TYP, and it was the second, independently-written classifier whose
    // drift from the TSE `Kassenbeleg-V1` builder round 7 found. Its cells were almost the exact
    // complement of the builder's: it booked `CASH_WITHDRAWAL` at UST_SCHLUESSEL 5 although
    // `getGvTyp` maps it to the SAME `Auszahlung` as `CASH_EXPENSE` (Anhang I p. 121 mandates the
    // RATE container), so the export emitted two identical Geschäftsvorfälle at two different USt
    // keys; and its shape could not represent p. 118's entry, which legitimately mixes a
    // container-5 voucher line with 19 % and 7 % Umsatz lines — a gap patched by three
    // hand-written MehrzweckgutscheinEinloesung special cases, all now removed.
    //
    // Every producer — the TSE builder and all three CSV VAT producers — now resolves the per-line
    // GV_TYP through [getGvTyp] and the container/key through [KassenbuchTaxClassifier]. Do NOT
    // reintroduce an entry-level VAT predicate: that is precisely the failure mode this audit
    // series keeps rediscovering.
}
