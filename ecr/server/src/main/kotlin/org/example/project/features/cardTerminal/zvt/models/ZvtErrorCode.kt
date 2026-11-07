/**
 * ZVT protocol error codes as defined in PA00P015 Chapter 10.
 *
 * These error codes appear in result code BMP field 27 and NAK
 * instruction bytes. They indicate the outcome of terminal operations.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * ZVT protocol error codes from Chapter 10 of the specification.
 *
 * @property code The numeric error code (0x00-0xFF)
 * @property description Human-readable error description
 * @property isRetryable Whether the operation might succeed on retry
 */
enum class ZvtErrorCode(
    val code: Int,
    val description: String,
    val isRetryable: Boolean = false,
) {
    /** No error — operation successful */
    SUCCESS(0x00, "No error"),

    /** Card not permitted for this terminal */
    CARD_NOT_PERMITTED(0x56, "Card not permitted"),

    /** Card expired */
    CARD_EXPIRED(0x62, "Card expired"),

    /** Card not readable */
    CARD_NOT_READABLE(0x64, "Card not readable", isRetryable = true),

    /** Communication error */
    COMMUNICATION_ERROR(0x65, "Communication error", isRetryable = true),

    /** Function not permitted for card type */
    FUNCTION_NOT_PERMITTED(0x66, "Function not permitted for this card"),

    /** Transaction not possible */
    TRANSACTION_NOT_POSSIBLE(0x67, "Transaction not possible"),

    /** Abort via timeout or user */
    ABORT_TIMEOUT(0x6C, "Abort via timeout or user action"),

    /** Card removed */
    CARD_REMOVED(0x6E, "Card removed during operation"),

    /** Card not inserted */
    CARD_NOT_INSERTED(0x6F, "Card not inserted"),

    /** Amount not accepted */
    AMOUNT_NOT_ACCEPTED(0x71, "Amount not accepted"),

    /** Max amount exceeded */
    MAX_AMOUNT_EXCEEDED(0x73, "Maximum amount exceeded"),

    /** PIN wrong — no retries left */
    PIN_WRONG_NO_RETRIES(0x75, "Wrong PIN, card blocked"),

    /** PIN wrong — retries remaining */
    PIN_WRONG_RETRY(0x76, "Wrong PIN, retries remaining", isRetryable = true),

    /** Abort via card */
    ABORT_VIA_CARD(0x78, "Abort via card"),

    /** Card data incorrect (checksum error) */
    CARD_DATA_INCORRECT(0x79, "Card data incorrect"),

    /** System error registering */
    SYSTEM_ERROR_REGISTRATION(0x83, "System error during registration"),

    /** Authorization host not reachable */
    HOST_NOT_REACHABLE(0x85, "Authorization host not reachable", isRetryable = true),

    /** ZVT protocol error */
    PROTOCOL_ERROR(0x9A, "ZVT protocol error"),

    /** Receipt number not found */
    RECEIPT_NOT_FOUND(0xA0, "Receipt number not found"),

    /** Already reversed */
    ALREADY_REVERSED(0xB4, "Transaction already reversed"),

    /** Max amount exceeded (alternate) */
    MAX_AMOUNT_EXCEEDED_ALT(0xC3, "Maximum amount exceeded"),

    /** PT not registered */
    NOT_REGISTERED(0xCC, "PT not registered"),

    /** Card not allowed for this transaction */
    CARD_NOT_ALLOWED(0xDC, "Card not allowed for this transaction"),

    /** System error — general */
    SYSTEM_ERROR(0xFF, "System error"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /**
         * Looks up an error code by its numeric value.
         *
         * @param code The error code byte value (0x00-0xFF)
         * @return The matching [ZvtErrorCode], or null if unknown
         */
        fun fromCode(code: Int): ZvtErrorCode? = byCode[code and 0xFF]

        /**
         * Returns an error code's description, or a default message for unknown codes.
         *
         * @param code The error code byte value
         * @return Human-readable error description
         */
        fun descriptionFor(code: Int): String = fromCode(code)?.description ?: "Unknown error code: 0x${(code and 0xFF).toString(16).padStart(2, '0')}"
    }
}
