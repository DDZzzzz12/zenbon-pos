/**
 * Enumeration for Kassenbuch (cash book) entry types.
 *
 * Defines the different types of cash-related transactions that can be recorded
 * in the cash book system. Each type corresponds to specific accounting rules
 * and DATEV export categories for proper German accounting compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.kassenbuch

import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking

/**
 * Which side of a Z-Bon a cash-book entry counts on.
 *
 * [NEUTRAL] is the "neither takings nor expense" group — Geldtransit, Kassendifferenz,
 * Einlagen/Entnahmen, Wechselgeld, opening and closing balances. Those move the drawer, so they
 * still belong in the closing balance, but reporting them as turnover or as a business expense
 * misstates both figures.
 */
enum class ZBonBucket {
    REVENUE,
    EXPENSE,
    NEUTRAL,
}

/**
 * Types of cash book entries for German accounting compliance.
 *
 * Categorizes cash transactions according to their business purpose and
 * accounting treatment. Essential for proper DATEV export and tax compliance.
 */
enum class KassenbuchEntryType(
    val displayName: String,
    val description: String,
    val isIncoming: Boolean,
) {
    /**
     * Geldtransit inflow: cash arriving from the bank, the Tresor or another Kasse.
     *
     * [displayName] is the server-side German label (Eigenbeleg, DSFinV-K BON_NAME fallback). It
     * used to read "Geldtransit Bank-Auszahlung", so every transit — including a Tresor → Ladenkasse
     * move — was described as a bank withdrawal. DSFinV-K Anhang C p. 58 defines Geldtransit as
     * any move of cash to or from the bank, a Tresor or another Kasse; the label now names only the
     * direction, and a transfer leg names its counterpart book on its own (`KassenbuchDsfinvkMapping.bonName`).
     */
    CASH_TRANSFER_IN(
        displayName = "Geldtransit (Zugang)",
        description = "Cash arriving from the bank, the safe or another cash book (Geldtransit) to increase the cash balance",
        isIncoming = true,
    ),

    /** Geldtransit outflow: cash leaving for the bank, the Tresor or another Kasse. See [CASH_TRANSFER_IN]. */
    CASH_TRANSFER_OUT(
        displayName = "Geldtransit (Abgang)",
        description = "Cash leaving for the bank, the safe or another cash book (Geldtransit) to decrease the cash balance",
        isIncoming = false,
    ),

    /** Cash sales from POS transactions */
    CASH_SALE(
        displayName = "Barverkauf",
        description = "Cash sale from point-of-sale transaction",
        isIncoming = true,
    ),

    /** Cash payments received from customers */
    CASH_PAYMENT(
        displayName = "Barzahlung",
        description = "Cash payment received from customer",
        isIncoming = true,
    ),

    /** Generic cash deposit into register/safe — a neutral inflow, NOT the owner's private capital */
    CASH_DEPOSIT(
        displayName = "Bargeldeinlage",
        description = "Cash deposit into register or safe",
        isIncoming = true,
    ),

    /**
     * Private deposit by the owner (m6).
     *
     * The incoming mirror of [PRIVATE_WITHDRAWAL]: owner's private cash injected into the till.
     * Maps to GV_TYP `Privateinlage`, which Anhang C p. 57 defines as „die Einlage von Bargeld aus
     * der Privatsphäre des Unternehmers" and which is deducted from the Gewinnermittlung — a
     * treatment a generic [CASH_DEPOSIT] must not claim.
     */
    PRIVATE_DEPOSIT(
        displayName = "Privateinlage",
        description = "Private cash deposit by business owner",
        isIncoming = true,
    ),

    /** Business expense paid in cash */
    CASH_EXPENSE(
        displayName = "Barausgabe",
        description = "Business expense paid with cash",
        isIncoming = false,
    ),

    /** Cash withdrawal from register */
    CASH_WITHDRAWAL(
        displayName = "Barentnahme",
        description = "Cash withdrawal from register",
        isIncoming = false,
    ),

    /** Private withdrawal by owner */
    PRIVATE_WITHDRAWAL(
        displayName = "Privatentnahme",
        description = "Private cash withdrawal by business owner",
        isIncoming = false,
    ),

    /** Cash refund to customer */
    REFUND(
        displayName = "Rückerstattung",
        description = "Cash refund issued to customer",
        isIncoming = false,
    ),

    /** Cash difference correction (plus) */
    CASH_SURPLUS(
        displayName = "Kassendifferenz Plus",
        description = "Cash surplus found during reconciliation",
        isIncoming = true,
    ),

    /** Cash difference correction (minus) */
    CASH_SHORTAGE(
        displayName = "Kassendifferenz Minus",
        description = "Cash shortage found during reconciliation",
        isIncoming = false,
    ),

    /** Opening balance for new period */
    OPENING_BALANCE(
        displayName = "Anfangsbestand",
        description = "Opening cash balance for accounting period",
        isIncoming = true,
    ),

    /** Closing balance for period */
    CLOSING_BALANCE(
        displayName = "Endbestand",
        description = "Closing cash balance for accounting period",
        isIncoming = false,
    ),

    /** Correction entry for errors */
    CORRECTION_IN(
        displayName = "Korrektur",
        description = "Correction entry for previous booking errors",
        isIncoming = true,
    ),

    CORRECTION_OUT(
        displayName = "Korrektur",
        description = "Correction entry for previous booking errors",
        isIncoming = false,
    ),

    /** Change fund for register operation */
    CHANGE_FUND(
        displayName = "Wechselgeld",
        description = "Change fund for register operation",
        isIncoming = true,
    ),
    ;

    /**
     * Whether a VAT rate is meaningful on this entry type's lines — a **UI affordance**, not the
     * fiscal authority (round-7 C1).
     *
     * The authority is the server-side `KassenbuchTaxClassifier`: the USt container is a function
     * of `(line GV_TYP, declared rate)`, and nine GV_TYPs are pinned by DSFinV-K to the
     * 0 %/nicht-steuerbar class and DISCARD the declared rate. `false` here means this entry type's
     * GV_TYP is one of those, so whatever the operator picks in the dropdown is thrown away by both
     * the signed TSE payload and the CSV export.
     *
     * Offering a rate anyway is how C1 became reachable on the DEFAULT path: `EntryLineState` opens
     * at `vatRate = "19"`, so a Privateinlage booked without touching the control used to sign
     * `Beleg^100.00_0.00_0.00_0.00_0.00^100.00:Bar` — a false §12 Abs. 1 declaration in an
     * immutable record. The control is hidden rather than merely ignored so the operator is never
     * shown a tax rate that does not apply.
     *
     * `CORRECTION_IN`/`CORRECTION_OUT` are `true` because their GV_TYP is the CORRECTED entry's,
     * which cannot be resolved client-side — corrections are built by `createCorrection` from the
     * original's lines, not through this control, so the value is not user-facing there.
     */
    val carriesVatRate: Boolean
        get() =
            when (this) {
                // GV_TYP „Umsatz"
                CASH_SALE, CASH_PAYMENT, REFUND,
                // GV_TYP „Auszahlung" — Anhang I p. 121 „Barausgabe: Büromaterial 50€ zu 19%".
                // BOTH of these map to Auszahlung; the spec cannot tell them apart.
                CASH_EXPENSE, CASH_WITHDRAWAL,
                CORRECTION_IN, CORRECTION_OUT,
                -> true
                // Geldtransit / Privateinlage / Privatentnahme / Anfangsbestand / DifferenzSollIst
                CASH_TRANSFER_IN, CASH_TRANSFER_OUT, CASH_DEPOSIT, PRIVATE_DEPOSIT, PRIVATE_WITHDRAWAL,
                CASH_SURPLUS, CASH_SHORTAGE, OPENING_BALANCE, CLOSING_BALANCE, CHANGE_FUND,
                -> false
            }

    /**
     * Whether a `GUTSCHEIN` line may be booked on this entry type (round-7 D1).
     *
     * A voucher redemption is a customer-facing Geschäftsvorfall (Anhang C p. 54/55), and the
     * per-line GV_TYP override that turns a `GUTSCHEIN` line into `MehrzweckgutscheinEinloesung`
     * only fires when the header GV_TYP is „Umsatz". Inside e.g. a Barausgabe the kind carries no
     * fiscal meaning at all — the line is simply part of that `Auszahlung`. Enforced server-side in
     * `KassenbuchServiceImpl.createEntry`; this drives the client control (CLAUDE.md rule 8).
     */
    val allowsGutscheinLine: Boolean
        get() = this == CASH_SALE || this == CASH_PAYMENT || this == REFUND

    /**
     * Which account-mapping side this entry type's lines resolve against
     * (spec §3.4). REFUND and CORRECTION_OUT reverse income entries, so their
     * lines are revenue reductions and hit income accounts (the S/H flip on the
     * negative cash-flow amounts produces the reversal); CORRECTION_IN mirrors
     * expense entries. Null = the account mapping as a whole does not apply to
     * this entry type — a blanket per-entry-type eligibility gate covering ALL
     * mapping lookups, including the NON_CASH_PAYMENT payment-type lookup, not
     * just the kind x rate tables — the whole entry resolves through the legacy
     * per-entry-type defaults.
     *
     * The DATEV exporters only reach the `CORRECTION_*` rows here for an UNLINKED (legacy)
     * correction: a Storno linked through `correctsHeader` resolves through the entry it
     * reverses (server `KassenbuchBookingSource`, F64), because a Storno of a Privateinlage is
     * not a revenue reduction.
     */
    val mappingSide: DatevMappingSide?
        get() =
            when (this) {
                CASH_SALE, CASH_PAYMENT, REFUND, CORRECTION_OUT -> DatevMappingSide.INCOME
                CASH_EXPENSE, CORRECTION_IN -> DatevMappingSide.EXPENSE
                CASH_TRANSFER_IN, CASH_TRANSFER_OUT, CASH_DEPOSIT, PRIVATE_DEPOSIT, CASH_WITHDRAWAL,
                PRIVATE_WITHDRAWAL, CASH_SURPLUS, CASH_SHORTAGE,
                OPENING_BALANCE, CLOSING_BALANCE, CHANGE_FUND,
                -> null
            }

    /**
     * The special-booking slot this entry type resolves through, or null when the income/expense
     * rate tables cover it (i.e. whenever [mappingSide] is non-null).
     *
     * Exactly complementary to [mappingSide]: every entry type resolves through one axis or the
     * other, and `KassenbuchAccountResolverTest` asserts the partition is total. That totality is
     * what allowed the hardcoded `getDefaultAccountNumber` / `getDefaultCounterAccountNumber`
     * tables to be deleted — they were a fixed SKR03/SKR04 mix (Kasse and Privatentnahme from
     * SKR03, Geldtransit from SKR04) that could not be correct for both charts, and they put a
     * non-Kasse account in `Konto` for five types, which the exporter's cash-flow sign then
     * inverted.
     */
    val specialBooking: KassenbuchSpecialBooking?
        get() =
            when (this) {
                CASH_SALE, CASH_PAYMENT, REFUND, CORRECTION_OUT,
                CASH_EXPENSE, CORRECTION_IN,
                -> null
                CASH_TRANSFER_IN, CASH_TRANSFER_OUT -> KassenbuchSpecialBooking.GELDTRANSIT
                CASH_DEPOSIT -> KassenbuchSpecialBooking.CASH_DEPOSIT
                PRIVATE_DEPOSIT -> KassenbuchSpecialBooking.PRIVATE_DEPOSIT
                CASH_WITHDRAWAL -> KassenbuchSpecialBooking.CASH_WITHDRAWAL
                PRIVATE_WITHDRAWAL -> KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL
                CASH_SURPLUS -> KassenbuchSpecialBooking.CASH_SURPLUS
                CASH_SHORTAGE -> KassenbuchSpecialBooking.CASH_SHORTAGE
                OPENING_BALANCE, CLOSING_BALANCE, CHANGE_FUND -> KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD
            }
}

/**
 * The Z-Bon bucket for this entry type, given the sign of its amount.
 *
 * `ZBonServiceImpl.bucketKassenbuchTotals` used to split every entry on the arithmetic sign alone,
 * with a single carve-out for Geldtransit. A `CORRECTION_IN` — the storno leg of a correction to a
 * cash *expense*, and therefore positive — was consequently reported as cash **takings**, while
 * DATEV books that same entry on the EXPENSE side via [mappingSide]. Measured on 2026-09-20: a
 * 12,34 € expense, its correction and a 15,00 € re-booking left Z-Bon 139 reporting
 * `cash_revenue = 81.74` against actual takings of 69,40 € (E2E-2026-09-20 #10). The closing
 * balance stayed right — the two errors cancel — which is why it never showed as a till difference.
 *
 * Where [mappingSide] has an opinion, it decides, so the Z-Bon and the DATEV export can no longer
 * answer the same question differently.
 *
 * **Deliberately unchanged:** the types [mappingSide] leaves `null` — Kassendifferenz,
 * Einlagen/Entnahmen, Wechselgeld, opening and closing balances — keep the existing sign split,
 * apart from the Geldtransit carve-out that was already there. Whether a Bareinlage belongs in a
 * Z-Bon's reported takings is a product decision, not a defect: `ZBonGeldtransitTest` asserts today
 * that a `CASH_DEPOSIT` counts as `cashRevenue`, and changing that is a separate conversation.
 *
 * @param isNegative whether the entry's amount is below zero.
 */
fun KassenbuchEntryType.zBonBucket(isNegative: Boolean): ZBonBucket =
    when (mappingSide) {
        DatevMappingSide.INCOME -> ZBonBucket.REVENUE
        DatevMappingSide.EXPENSE -> ZBonBucket.EXPENSE
        null ->
            when (this) {
                // Its own GV_TYP under DSFinV-K, never an Auszahlung — the carve-out that already
                // existed before entry types decided the bucket at all.
                KassenbuchEntryType.CASH_TRANSFER_IN, KassenbuchEntryType.CASH_TRANSFER_OUT -> ZBonBucket.NEUTRAL
                else -> if (isNegative) ZBonBucket.EXPENSE else ZBonBucket.REVENUE
            }
    }
