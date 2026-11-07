/**
 * ZVT intermediate status codes for transaction progress reporting.
 *
 * The PT sends Intermediate Status messages (04 FF) to the ECR during
 * long-running operations to report progress. These contain a status
 * byte indicating the current operation state.
 *
 * Defined in PA00P015 Chapter 11 (terminal status codes).
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Intermediate status codes sent by the PT during transaction processing.
 *
 * These codes indicate what the terminal is currently doing and can
 * be displayed to the user for progress feedback.
 *
 * @property code The status code byte value
 * @property description Human-readable description for UI display
 */
enum class ZvtIntermediateStatus(
    val code: Int,
    val description: String,
) {
    /** PT is ready, waiting for card */
    PLEASE_INSERT_CARD(0x00, "Please insert card"),

    /** Card inserted, reading data */
    CARD_INSERTED(0x01, "Card inserted, reading..."),

    /** Please remove card */
    PLEASE_REMOVE_CARD(0x02, "Please remove card"),

    /** Card removed */
    CARD_REMOVED(0x03, "Card removed"),

    /** Please enter PIN */
    PIN_ENTRY(0x04, "Please enter PIN on terminal"),

    /** PIN entry in progress */
    PIN_ENTRY_IN_PROGRESS(0x05, "PIN entry in progress"),

    /** Please wait (general) */
    PLEASE_WAIT(0x06, "Please wait..."),

    /** Processing */
    PROCESSING(0x07, "Processing transaction..."),

    /** Connecting to host */
    CONNECTING_HOST(0x08, "Connecting to payment network..."),

    /** Transmitting data */
    TRANSMITTING(0x09, "Transmitting data..."),

    /** Receiving data */
    RECEIVING(0x0A, "Receiving response..."),

    /** Please use other card */
    USE_OTHER_CARD(0x0B, "Please use another card"),

    /** Card wrong — try again */
    CARD_WRONG(0x0C, "Card error, please try again"),

    /** Card locked */
    CARD_LOCKED(0x0D, "Card is locked"),

    /** Not accepted */
    NOT_ACCEPTED(0x0E, "Not accepted"),

    /** Transaction started */
    TRANSACTION_STARTED(0x10, "Transaction started"),

    /** Contactless: present card */
    PRESENT_CARD(0x18, "Present card for contactless payment"),

    /** Transaction in progress on host */
    HOST_PROCESSING(0x20, "Host processing..."),

    /** Amount confirmed */
    AMOUNT_CONFIRMED(0x25, "Amount confirmed"),

    /** Initializing terminal */
    INITIALIZING(0xFD, "Terminal initializing..."),

    /** Terminal busy */
    TERMINAL_BUSY(0xFE, "Terminal busy"),

    /** Terminal ready */
    TERMINAL_READY(0xFF, "Terminal ready"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /**
         * Looks up an intermediate status by its code.
         *
         * @param code The status code byte value
         * @return The matching [ZvtIntermediateStatus], or null if unknown
         */
        fun fromCode(code: Int): ZvtIntermediateStatus? = byCode[code and 0xFF]

        /**
         * Returns a description for the given status code.
         *
         * @param code The status code byte value
         * @return Human-readable description
         */
        fun descriptionFor(code: Int): String = fromCode(code)?.description ?: "Unknown status: 0x${(code and 0xFF).toString(16).padStart(2, '0')}"
    }
}
