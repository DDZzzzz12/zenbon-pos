package org.example.project.core.domain.enums.terminal

/**
 * The station role of a Terminal — what the physical device is *for*.
 *
 * Two capabilities are decided at Phase A: [defaultFiscal] (does this station perform
 * TSE-signed transactions → needs a registered TSE client) and [loginLess] (does the
 * station operate as a device-principal with no employee login). The concrete UI shell
 * each role loads is added in a later phase; this enum only reserves the roles.
 *
 * A terminal row stores its own `fiscal` flag (defaulting from [defaultFiscal]); this
 * enum's value is only the provisioning default.
 */
enum class TerminalRole(
    val defaultFiscal: Boolean,
    val loginLess: Boolean,
) {
    /** Full cashier till: fiscal, employee logs in. */
    CASHIER(defaultFiscal = true, loginLess = false),

    /** Order-taking station: fiscal (signs Bestellungen), login-less; billing via step-up. */
    ORDER_STATION(defaultFiscal = true, loginLess = true),

    /** Kitchen display: non-fiscal, no transactions, login-less. (Reserved — shell is a later phase.) */
    KITCHEN(defaultFiscal = false, loginLess = true),

    /** Customer-facing display: non-fiscal, login-less. (Reserved.) */
    CUSTOMER_DISPLAY(defaultFiscal = false, loginLess = true),

    /** Self-service kiosk menu: non-fiscal, login-less. (Reserved.) */
    KIOSK(defaultFiscal = false, loginLess = true),
    ;

    companion object {
        /** Parse a stored/DTO role string; unknown/blank/null → [CASHIER] (the safe fiscal default). */
        fun fromCode(code: String?): TerminalRole = parseOrNull(code) ?: CASHIER

        /**
         * The role [code] names (trimmed, case-insensitive), or null when it names none. For input that
         * must be refused rather than defaulted — provisioning a terminal from a typo would otherwise
         * create a fiscal cashier till.
         */
        fun parseOrNull(code: String?): TerminalRole? = entries.firstOrNull { it.name.equals(code?.trim(), ignoreCase = true) }
    }
}
