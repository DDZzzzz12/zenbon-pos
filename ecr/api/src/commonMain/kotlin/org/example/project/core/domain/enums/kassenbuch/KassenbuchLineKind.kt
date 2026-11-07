package org.example.project.core.domain.enums.kassenbuch

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking

/**
 * Classification of a single Kassenbuch line item.
 *
 * Determines VAT-rate requirements at entry creation (see
 * KassenbuchDto.CreateKassenbuchEntryRequestDto.validate) and which DATEV
 * account-mapping cell resolves the line at export
 * (KassenbuchAccountResolver, spec §3.4).
 *
 * @property requiresVatRate whether a VAT rate is mandatory on lines of this kind
 * @property allowedInManualEntry whether the manual-creation API accepts this kind
 * @property resolvesByPaymentType whether the DATEV contra account comes from
 *   `mapping.paymentTypes[paymentTypeId]` instead of the kind × VAT-rate table.
 *   This is the necessary counterpart of `requiresVatRate = false`: a rate-less line has
 *   nothing to look up on the rate axis, so without a second axis it resolves to
 *   `Resolution.Legacy` and `KassenbuchBuchungsstapelRowMapper` refuses the whole export
 *   with 409 — naming a mapping cell the operator cannot create. Declared here rather than
 *   branched on in the resolver so a future rate-less kind has to state which axis carries it.
 * @property specialBooking the third axis: a kind that carries neither a meaningful VAT rate nor a
 *   payment type resolves through `mapping.special[specialBooking]` — one account per chart — and
 *   takes precedence over the ENTRY type's own axis. Null for every kind that resolves otherwise.
 */
@Serializable
enum class KassenbuchLineKind(
    val requiresVatRate: Boolean,
    val allowedInManualEntry: Boolean,
    val resolvesByPaymentType: Boolean = false,
    val specialBooking: KassenbuchSpecialBooking? = null,
) {
    /** Waren/Erlöse at a VAT rate — the default and only pre-existing semantic. */
    STANDARD(requiresVatRate = true, allowedInManualEntry = true),

    /** Pfand/Leergut: + taken, − returned. VAT-rated (typically 19 %). */
    PFAND(requiresVatRate = true, allowedInManualEntry = true),

    /** VAT-carrying discount (Warengutschein/Rabatt), per rate. */
    GUTSCHEIN(requiresVatRate = true, allowedInManualEntry = true),

    /** Non-cash tender netted out of a cash total ("Unbare Zahlung"). Z-Bon-internal. */
    NON_CASH_PAYMENT(requiresVatRate = false, allowedInManualEntry = false, resolvesByPaymentType = true),

    /**
     * Money taken at the till against an existing receivable — cash only; a card clearing moves no
     * drawer.
     *
     * Resolves through the **receivable tender** that OPENED the invoice (the payment type of the
     * original sale's `createsReceivable` leg), not through the cash the clearing brought in. That
     * is the symmetric contra of the `NON_CASH_PAYMENT` line the opening leg already books through
     * the very same `mapping.paymentTypes` cell, so settling a receivable clears exactly the
     * account that opening it debited — and no new mapping cell is needed.
     */
    RECEIVABLE_SETTLEMENT(requiresVatRate = false, allowedInManualEntry = false, resolvesByPaymentType = true),

    /**
     * Employee tips (DSFinV-K `TrinkgeldAN`) — not turnover, held for the staff.
     *
     * Written by the server only, in two places: the Z-Bon aggregate's `"TE:Trinkgeld (AN)"` line
     * (cash AND card tips — a card tip reached the drawer as cash the moment the card payment was
     * netted out as `NON_CASH_PAYMENT`) and the tip-payout withdrawal that hands the tip to the
     * employee after the closing. DATEV books both against `special[EMPLOYEE_TIP]` (Durchlaufende
     * Posten by default), so the receipt and the payout net to zero on one account.
     *
     * DSFinV-K maps the line to `TrinkgeldAN` whatever the header's GV_TYP — Anhang C p. 52:
     * „Es ist möglich, mit diesem Geschäftsvorfalltyp sowohl die Ein- als auch die Auszahlung an den
     * Arbeitnehmer darzustellen." — which `KassenbuchTaxClassifier` pins to the 0 %/key-5 class.
     *
     * The Z-Bon line keeps `ustSatz = "0"` (the aggregate's SIGNED bytes depend on it, see
     * ZBonServiceImpl); the rate is ignored for account resolution, so `requiresVatRate` is false.
     */
    EMPLOYEE_TIP(
        requiresVatRate = false,
        allowedInManualEntry = false,
        specialBooking = KassenbuchSpecialBooking.EMPLOYEE_TIP,
    ),
}
