/**
 * Builder utility for constructing TSE ProcessData for Kassenbuch entries.
 *
 * Generates DSFinV-K compliant ProcessData strings for TSE signing of
 * cash book transactions. Kassenbuch entries use the "SonstigerVorgang"
 * process type as they represent non-standard transactions.
 *
 * ProcessData Format: SonstigerVorgang^GV_TYP:AMOUNT[:VAT_RATE]
 *
 * Examples:
 * - Bank deposit: "SonstigerVorgang^Geldtransit:100.00"
 * - Cash expense with VAT: "SonstigerVorgang^Auszahlung:50.00:19"
 * - Private withdrawal: "SonstigerVorgang^Privatentnahme:200.00"
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.builder.tse

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.export.dsfinvk.builder.KassenbuchTaxClassifier
import org.example.project.utils.builder.ProcessDataBuilder
import org.example.project.utils.formatter.toBigDecimal
import org.example.project.utils.formatter.toDecimalString
import java.math.BigDecimal

/**
 * Builder for creating TSE ProcessData for Kassenbuch entries.
 *
 * Constructs properly formatted ProcessData strings according to
 * DSFinV-K specification for "SonstigerVorgang" process type.
 */
object KassenbuchProcessDataBuilder {
    /**
     * Builds ProcessData with explicit amount as BigDecimal.
     *
     * @param entryType The type of Kassenbuch entry
     * @param amount The transaction amount as BigDecimal
     * @param vatRate Optional VAT rate as decimal (e.g., 0.19 for 19%)
     * @return Formatted ProcessData string for TSE signing
     */
    private fun build(
        lineItems: List<KassenbuchDto.LineItemData>,
        totalNonCashWithdrawals: BigDecimal?,
        cashPaymentLineItem: BigDecimal,
    ): String {
        val groupBy = lineItems.groupBy { it.ustSatz == null }
        // vorzBetrag is an exact decimal string; parse into BigDecimal so the decimal separator is
        // always "." regardless of the JVM default locale (Double.toDecimalString() uses a
        // locale-sensitive DecimalFormat, which is exactly the bug this builder must not reintroduce).
        val umst = groupBy[false]?.sortedBy { it.ustSatz?.toLong() }?.joinToString("_") { toBigDecimal(it.vorzBetrag).toDecimalString() }

        return buildString {
            umst?.let {
                append(it)
                append("^")
            }
            append("${cashPaymentLineItem.toDecimalString()}:Bar")
            totalNonCashWithdrawals?.let {
                append("_")
                append("${it.toDecimalString()}:Unbar")
            }
        }
    }

    /**
     * Round-3 TSER-2: cash-moving Kassenbuch entries ARE Kassenbelege (they export BON_TYP
     * „Beleg"), so they sign in the BMF Kassenbeleg-V1 format — Anhang I p. 119/120 signs
     * Privateinlage/Geldtransit/Barausgabe exactly this way. [netCashAmount] is the signed Bar
     * tender (plus [totalNonCashWithdrawals] as Unbar when present); a 0.00 payment is omitted
     * (Anhang I p. 114: „Zahlungen von 0.00 müssen entfallen"). NON_CASH_PAYMENT lines are Unbar
     * tender (surfaced via [totalNonCashWithdrawals]), not revenue, and are excluded from the
     * buckets BEFORE classification (round-3 TSER-2/ZBon — the Z-Bon aggregate mixes them, at a
     * null rate, with the revenue lines): a tender leg is a Zahlart, not a Geschäftsvorfall.
     *
     * **Round-7 C1/C2 — the bucket is `(GV_TYP, declared rate)`, not the declared rate alone.**
     * Each line's GV_TYP is resolved per line via `KassenbuchDsfinvkMapping.getGvTyp` (so the
     * PFAND/GUTSCHEIN overrides, which change the GV_TYP WITHIN one entry, are honoured), then
     * routed through [KassenbuchTaxClassifier] — the SAME classifier the three DSFinV-K CSV VAT
     * producers use. Before this, the builder bucketed on the raw declared `ustSatz` and never
     * called `getGvTyp` at all, so with the client's `vatRate = "19"` default a Privateinlage
     * signed `Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar` — declaring 100 € of §12 Abs. 1 Umsatz
     * that never happened — into an immutable WORM record, where Anhang I p. 119 mandates
     * `Beleg^0.00_0.00_0.00_0.00_100.00^100.00:Bar`.
     *
     * Format: <Vorgangstyp>^<b1>_<b2>_<b3>_<b4>_<b5>^<amount>:Bar[_<amount>:Unbar]
     *
     * @param vorgangstyp The `<Vorgangstyp>` prefix — pass
     *   `KassenbuchDsfinvkMapping.getBonTyp(entryType)`, never a literal (round-7 D4: this used to
     *   be a hard-coded `"Beleg"` while `getBonTyp` could return `AVSonstige`, so the prefix could
     *   silently disagree with the BON_TYP the same Vorgang exports). Anhang I p. 113 enumerates
     *   the valid values; Tz. 4.1.1 p. 31: „Alles andere wird nicht im Kassenabschluss
     *   berücksichtigt und beginnt mit dem Präfix „AV"." Only „Beleg" is a Kassenbeleg-V1 — an AV*
     *   Vorgang has different rules entirely (Anhang B p. 43: „Bei allen Vorgangstypen, die mit AV
     *   beginnen (Ausnahme: AVTraining), ist nur die Zahlart „Keine" möglich.") and is rejected.
     * @param entryType The header entry type — decides the GV_TYP, hence the container class
     * @param correctedEntryType For a `CORRECTION_IN`/`CORRECTION_OUT`, the `entryType` of the
     *   entry it corrects. Ignored for every other entry type, exactly like `getGvTyp`. Threading
     *   it is what makes a Storno land in the SAME container as its original — nothing nets across
     *   containers, so that is the only way BON_STORNO p. 85's „rechnerisch wieder ausgleicht" can
     *   hold.
     * @param lineItems The Kassenbuch line items
     * @param netCashAmount The signed cash amount tendered (Bar)
     * @param totalNonCashWithdrawals Optional signed non-cash amount (Unbar)
     * @return Formatted Kassenbeleg-V1 ProcessData string for TSE signing
     */
    fun buildKassenbelegV1(
        vorgangstyp: String,
        entryType: KassenbuchEntryType,
        correctedEntryType: KassenbuchEntryType?,
        lineItems: List<KassenbuchDto.LineItemData>,
        netCashAmount: BigDecimal,
        totalNonCashWithdrawals: BigDecimal?,
    ): String {
        // Round-7 G1: DSFinV-K has NO Endbestand GV_TYP. Tz. 4.1.3 p. 34: „Andere als die hier
        // aufgeführten Typen dürfen nicht verwendet werden."; Anhang C p. 56 scopes Anfangsbestand
        // to „den Bargeldbestand der Kasse zu Beginn des jeweiligen Abrechnungszeitraums". There is
        // no spec-correct container for a closing snapshot, so we FREEZE rather than invent one —
        // signing a guessed container into a WORM record is unfixable. New CLOSING_BALANCE entries
        // are already rejected at creation (round-3 KABS-3); this guards the legacy shape.
        require(entryType != KassenbuchEntryType.CLOSING_BALANCE) {
            "CLOSING_BALANCE has no DSFinV-K Endbestand GV_TYP and therefore no admissible USt " +
                "container — it must not sign as Kassenbeleg-V1 (round-7 G1)"
        }
        require(vorgangstyp == DsfinvkExportDto.ReceiptTypes.BELEG) {
            "Kassenbeleg-V1 is by definition a 'Beleg' Vorgang; got '$vorgangstyp' " +
                "(Anhang I p. 113 / Tz. 4.1.1 p. 31 — round-7 D4)"
        }

        val buckets = Array(5) { BigDecimal.ZERO }
        // NON_CASH_PAYMENT lines are Unbar tender (surfaced via totalNonCashWithdrawals), not
        // revenue — the Z-Bon aggregate mixes them (null rate, negative amount) with the revenue
        // lines, so a rate-only bucketer would drop them into bucket 5 (0 %) AND double-count them
        // as Unbar. Exclude them BEFORE classification (round-3 TSER-2/ZBon): a tender swap lives
        // exclusively in <Zahlungen> (Anhang I p. 119, Guthabenkarte block — all five containers
        // 0.00, „da es sich lediglich um einen Tausch von Zahlungsmitteln handelt").
        lineItems.filter { it.kind != KassenbuchLineKind.NON_CASH_PAYMENT }.forEach { li ->
            val amount = toBigDecimal(li.vorzBetrag)
            // ustSatz is a percent string ("19", "7", "0"); convert to the fraction the bucket
            // sets are keyed by ("0.19", "0.07", ...). The GV_TYP decides whether that declared
            // rate governs at all — see KassenbuchTaxClassifier.
            val declaredRate = li.ustSatz?.let { BigDecimal(it).movePointLeft(2) }
            val lineGvTyp =
                KassenbuchDsfinvkMapping.getGvTyp(
                    entryType,
                    li.kind,
                    amount,
                    correctedEntryType,
                )
            val idx =
                ProcessDataBuilder.bucketIndexFor(
                    KassenbuchTaxClassifier.effectiveVatRate(lineGvTyp, declaredRate),
                )
            buckets[idx] = buckets[idx] + amount
        }
        val bucketStr = buckets.joinToString("_") { ProcessDataBuilder.fmtAmount(it) }
        val payments =
            buildList {
                if (netCashAmount.compareTo(BigDecimal.ZERO) != 0) {
                    add("${ProcessDataBuilder.fmtAmount(netCashAmount)}:Bar")
                }
                totalNonCashWithdrawals
                    ?.takeIf { it.compareTo(BigDecimal.ZERO) != 0 }
                    ?.let { add("${ProcessDataBuilder.fmtAmount(it)}:Unbar") }
            }.joinToString("_")
        return "$vorgangstyp^$bucketStr^$payments"
    }

    /**
     * Builds ProcessData with additional descriptive text.
     *
     * Format: SonstigerVorgang^GV_TYP:AMOUNT[:VAT_RATE]^DESCRIPTION
     *
     * @param entryType The type of Kassenbuch entry
     * @param description Additional descriptive text (belegtext)
     * @return Formatted ProcessData string for TSE signing
     */
    fun buildProcessData(
        entryType: KassenbuchEntryType,
        description: String,
        totalNonCashWithdrawals: BigDecimal?,
        netCashAmount: BigDecimal,
        lineItems: List<KassenbuchDto.LineItemData>,
    ): String {
        val baseProcessData = build(lineItems, totalNonCashWithdrawals, netCashAmount)
        val gvType = KassenbuchDsfinvkMapping.getGvTyp(entryType)

        // Sanitize description for TSE (remove special characters that could break parsing)
        val sanitizedDescription =
            description
                .replace("^", " ")
                .replace(":", " ")
                .take(60) // Limit to 60 chars for compatibility
                .trim()

        return if (sanitizedDescription.isNotBlank()) {
            "$gvType^$baseProcessData^$sanitizedDescription"
        } else {
            "$gvType^$baseProcessData"
        }
    }

    /**
     * Formats a DATEV amount string to a numeric string for ProcessData.
     *
     * Converts from DATEV format (+100,00 or -100,00) to decimal format (100.00).
     *
     * @param datevAmount The DATEV formatted amount string
     * @return Formatted numeric string (always positive, 2 decimal places)
     */
    private fun formatAmount(datevAmount: String): String {
        // Preserve sign, only replace comma with period
        return datevAmount
            .replace(",", ".")
            .trim()
    }

    /**
     * Generates a reference ID for TSE transaction logging.
     *
     * @param belegNr The Kassenbuch entry Beleg number
     * @return Reference ID string for TSE
     */
    fun generateReferenceId(belegNr: String): String = "KB-$belegNr"

    /**
     * Generates a reference ID for TSE transaction logging with multi-entry support.
     *
     * For receipts with multiple VAT rates, each entry gets a unique reference ID
     * by appending the line item index to the beleg number.
     *
     * @param belegNr The Kassenbuch entry Beleg number (YYYY-MM-NNNN format)
     * @param lineItemIndex The index of the VAT line item (0-based)
     * @return Reference ID string for TSE (e.g., "KB-2026-04-0001-0")
     */
    fun generateReferenceId(
        belegNr: String,
        lineItemIndex: Int,
    ): String = "KB-$belegNr-$lineItemIndex"
}
