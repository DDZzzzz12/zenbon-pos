package org.example.project.core.domain.enums.datev

/**
 * The cash-book bookings whose contra account depends on neither VAT rate nor payment type.
 *
 * The income/expense tables in `DatevAccountMappingDto` are keyed by line kind × VAT rate, and
 * `paymentTypes` by payment type. Transfers, private movements, cash differences and balance
 * carries fit none of those axes — one entry type, one contra account, no rate involved. Before
 * this enum existed they were the *only* thing left resolving through hardcoded per-entry-type
 * constants, which were a documented SKR03/SKR04 mix: an SKR04 shop got SKR03's Kasse (1000
 * instead of 1600) and SKR03's Privatentnahme, while its Geldtransit came from SKR04.
 *
 * Every value must be mapped before a mapping is storable — see `MappingDto.validate`. There is no
 * built-in fallback on purpose: a plausible-looking wrong account is not detectable downstream.
 */
enum class KassenbuchSpecialBooking {
    /** Cash moved between the till and the bank, or between two cash books (both directions). */
    GELDTRANSIT,

    /** Neutral cash inflow that is not the owner's private capital. */
    CASH_DEPOSIT,

    /** Owner's private cash paid into the till (Privateinlage). */
    PRIVATE_DEPOSIT,

    /** Neutral cash outflow that is not a private withdrawal. */
    CASH_WITHDRAWAL,

    /** Owner's private cash taken out of the till (Privatentnahme). */
    PRIVATE_WITHDRAWAL,

    /** Counted cash exceeds the book balance (Kassendifferenz +). */
    CASH_SURPLUS,

    /** Counted cash falls short of the book balance (Kassendifferenz −). */
    CASH_SHORTAGE,

    /** Opening/closing balance and change-fund bookings (Saldenvortrag). */
    BALANCE_CARRY_FORWARD,

    /**
     * Employee tips (TrinkgeldAN) held for the staff — the Z-Bon's tip line when the tips come in,
     * and the payout entry when they are handed to the employee (Durchlaufende Posten).
     *
     * Unlike every other slot this one is selected by the **line kind**
     * (`KassenbuchLineKind.EMPLOYEE_TIP`), not by the entry type: the tip line sits inside the
     * Z-Bon's `CASH_SALE` aggregate and the payout is a `CASH_WITHDRAWAL`, and neither entry type's
     * own axis is right for it. A tip is not turnover, so the rate tables would book it as revenue
     * (the pre-2026-10 behaviour, on 8100/4100), and the `CASH_WITHDRAWAL` slot would book the
     * payout against a different account than the receipt.
     *
     * The one slot that is **seeded for both charts and backfilled on read** into mappings stored
     * before it existed (`DatevAccountMappingDefaults.withBackfilledDefaults`), so a configured shop
     * never meets the 409 because of it.
     */
    EMPLOYEE_TIP,
}
