package org.example.project.core.domain.enums.tse

/**
 * Enumeration of available TSE sign commands.
 *
 * These commands are used for cryptographic signing of fiscal transactions
 * according to German KassenSichV (Cash Security Regulation) requirements.
 *
 * Process Types (DSFinV-K compliant):
 * - "Bestellung-V1": Order/draft bill (restaurant pre-orders)
 * - "Kassenbeleg-V1": Final receipt/bill (payment completion)
 * - "SonstigerVorgang": Other business processes
 *
 * @property op The string name used in API requests
 * @property processType The DSFinV-K process type for TSE signing
 * @property description Human-readable description of the command
 *
 * @since 1.0.0
 */
enum class TseSignOperation(
    val op: String,
    val processType: String,
    val description: String,
) {
    /**
     * Sign order transactions (Bestellung-V1).
     * Used for restaurant/hospitality pre-orders before final payment.
     */
    SIGN_ORDERS("signorders", "Bestellung-V1", "Sign order/draft transactions"),

    /**
     * Sign final bill/receipt transactions (Kassenbeleg-V1).
     * Used when customer completes payment.
     */
    SIGN_BILL("signbill", "Kassenbeleg-V1", "Sign final bill/receipt transactions"),

    /**
     * Sign normal bill transactions (alias for SIGN_BILL).
     * Alternative command name for compatibility.
     */
    SIGN_NORMAL_BILL("signnormalbill", "Kassenbeleg-V1", "Sign normal bill transactions"),

    /**
     * Sign free-form content transactions (SonstigerVorgang).
     * Used for non-standard business processes.
     */
    SIGN_FREE_CONTENT("signfreecontent", "SonstigerVorgang", "Sign free-form content transactions"),
    ;

    // Task 15d: `SIGN_CANCELLATION → "Kassenbeleg-Storno"` and `SIGN_OTHER → "AVSonstige"` were
    // REMOVED — neither is an Anhang I processType. A storno is an ordinary `Kassenbeleg-V1` with
    // negative amounts (what `ProcessDataBuilder` emits), and `AVSonstige` is a BON_TYP, not a
    // processType; `SIGN_FREE_CONTENT` already covers the real `SonstigerVorgang`. Anhang I defines
    // exactly the three types above, so anything else would have the TSE sign a Vorgangstyp the
    // spec does not know.

    companion object {
        /**
         * Get op by its string name (case-insensitive).
         *
         * @param op The op name string
         * @return The matching TseSignOperation or null if not found
         */
        fun fromString(op: String): TseSignOperation? = entries.find { it.op.equals(op, ignoreCase = true) }

        /**
         * Get all available command names.
         *
         * @return List of all command name strings
         */
        fun getAllCommandNames(): List<String> = entries.map { it.op }

        /**
         * Get process type for a command string.
         *
         * @param command The command name string
         * @return The DSFinV-K process type or null if command not found
         */
        fun getProcessType(command: String): String? = fromString(command)?.processType

        /**
         * Resolve the canonical sign command (op) for a DSFinV-K process type.
         *
         * Reverse of [getProcessType]. When several commands share a process type
         * (e.g. [SIGN_BILL] / [SIGN_NORMAL_BILL] both map to "Kassenbeleg-V1"), the
         * first declared entry wins, which is the canonical command for that type.
         *
         * Task 15d: there is deliberately NO fallback. This previously returned [SIGN_BILL]'s op
         * for anything unrecognised, and because `BaseTseSignOperation` reads the processType it
         * signs back off the RESOLVED entry (`val processType = signCommand.processType`), the TSE
         * would sign `Kassenbeleg-V1` while the database recorded the type the caller asked for.
         * A signature claiming the wrong Vorgangstyp cannot be corrected once it is in the WORM,
         * so an unknown type must fail loudly instead of being quietly reinterpreted.
         *
         * @param processType The DSFinV-K process type
         *   (e.g. "Bestellung-V1", "Kassenbeleg-V1", "SonstigerVorgang")
         * @return The matching command op
         * @throws IllegalArgumentException if [processType] is not an Anhang I process type
         */
        fun commandForProcessType(processType: String): String =
            entries.find { it.processType == processType }?.op
                ?: throw IllegalArgumentException(
                    "unknown TSE process type '$processType' — Anhang I defines only " +
                        entries
                            .map { it.processType }
                            .distinct()
                            .sorted()
                            .joinToString(", ") +
                        "; refusing to resolve it to a command rather than sign the wrong Vorgangstyp",
                )

        /**
         * Get commands for order transactions (Bestellung-V1).
         *
         * @return List of commands that sign order transactions
         */
        fun getOrderCommands(): List<TseSignOperation> = entries.filter { it.processType == "Bestellung-V1" }

        /**
         * Get commands for receipt/bill transactions (Kassenbeleg-V1).
         *
         * @return List of commands that sign final receipts
         */
        fun getBillCommands(): List<TseSignOperation> = entries.filter { it.processType == "Kassenbeleg-V1" }
    }
}
