package org.example.project.core.auth

/**
 * What kind of station a request came from, for the purpose of masking capabilities.
 *
 * **A separate type from `TerminalRole` on purpose.** [UNBOUND] is not a station — it is the
 * *absence* of a proven terminal binding — and adding it to `TerminalRole` would leak a non-station
 * into terminal provisioning, where `TerminalRole.fromCode` defaults unknown input to the fiscal
 * `CASHIER`. A value that means "we could not identify this" must never be one typo away from
 * meaning "full till".
 *
 * In `shared/`, not `pos-server/`, because the client's shell-selection layer picks a UI shell from
 * the same classification. One definition, or the two drift the way the client and server role
 * hierarchies did in defect D2.
 */
enum class StationClass {
    /** Full till. Employee logs in; the JWT is the credential, so the identity axis governs. */
    CASHIER,

    /** Login-less order taking. Fiscal, signs Bestellungen under its own TSE client. */
    ORDER_STATION,

    /** Kitchen display. Login-less, non-fiscal. Reserved — the shell is a later phase. */
    KITCHEN,

    /** Customer-facing display. Login-less, non-fiscal. Reserved. */
    CUSTOMER_DISPLAY,

    /** Self-service kiosk. Login-less, non-fiscal. Reserved. */
    KIOSK,

    /**
     * No proven station binding: the back office, an unpaired device, a spoofed header.
     *
     * Only two identifications are server-side *facts* — a matching device-token hash and a matching
     * terminal row. Everything else is a client-supplied string. adminApp's `admin-web-` device-id
     * prefix included: the server recognises it, but it cannot be trusted to *grant* anything,
     * because anyone can send it.
     *
     * So [UNBOUND] carries the **full** capability set. A narrower mask here would be defeated by
     * sending a garbage device id, which would make the whole axis decorative while looking strict.
     */
    UNBOUND,
    ;

    companion object {
        /**
         * Classify a `TerminalRole` name.
         *
         * Unknown, blank or absent maps to [UNBOUND] — deliberately NOT to `CASHIER` the way
         * `TerminalRole.fromCode` does. That default is right for provisioning, where an
         * unrecognised role should get the safe *fiscal* treatment, and wrong here, where it would
         * silently claim a station identity nothing proved.
         */
        fun fromTerminalRole(role: String?): StationClass = entries.firstOrNull { it != UNBOUND && it.name.equals(role?.trim(), ignoreCase = true) } ?: UNBOUND

        /**
         * The station a request speaks from, for the capability mask: the class of the registered
         * terminal the request's device resolved to ([deviceTerminalRole], that terminal's
         * `TerminalRole` name), or [fallback] — the token's own claim — when it resolved to none.
         *
         * The route plugin's station mask is the one caller. The billing and line-pricing rules no
         * longer classify by station at all: they key on who the request is attributed to
         * (`BillingAuthority.mayBillAs`, `PriceAuthority.mayPriceLinesAs`, owner's decision
         * 2026-09-28), so a person's own login on a station device bills and prices as that person.
         */
        fun forRequest(
            deviceTerminalRole: String?,
            fallback: StationClass,
        ): StationClass = fromTerminalRole(deviceTerminalRole).takeUnless { it == UNBOUND } ?: fallback
    }
}
