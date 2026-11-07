/**
 * ZVT protocol command definitions for ECR↔PT communication.
 *
 * Defines all standard ZVT commands as specified in PA00P015 Revision 13.13.
 * Each command is identified by a 2-byte control field (class byte + instruction byte).
 * Commands are categorized by direction (ECR→PT or PT→ECR) and purpose.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

/**
 * Enumeration of all ZVT protocol commands.
 *
 * Each entry maps to a 2-byte control field used in APDU headers.
 * The [classByte] identifies the command class and [instructionByte]
 * identifies the specific instruction within that class.
 *
 * @property classByte The first byte of the control field
 * @property instructionByte The second byte of the control field
 * @property direction Whether this command is sent by ECR or PT
 * @property description Human-readable description of the command
 */
enum class ZvtCommand(
    val classByte: Byte,
    val instructionByte: Byte,
    val direction: Direction,
    val description: String,
) {
    // ===== ECR → PT Commands =====

    /** Initialize ECR↔PT session with configuration and capabilities exchange */
    REGISTRATION(0x06, 0x00, Direction.ECR_TO_PT, "Registration"),

    /** Initiate a payment transaction */
    AUTHORIZATION(0x06, 0x01, Direction.ECR_TO_PT, "Authorization"),

    /** End ECR↔PT session */
    LOG_OFF(0x06, 0x02, Direction.ECR_TO_PT, "Log-Off"),

    /** Pre-authorize an amount on card without completing payment */
    PRE_AUTHORIZATION(0x06, 0x22, Direction.ECR_TO_PT, "Pre-Authorization"),

    /** Finalize (book) a previously pre-authorized amount */
    BOOK_PRE_AUTHORIZATION(0x06, 0x24, Direction.ECR_TO_PT, "Book Pre-Authorization"),

    /** Reverse a completed transaction */
    REVERSAL(0x06, 0x30, Direction.ECR_TO_PT, "Reversal"),

    /** Issue a credit refund to card */
    REFUND(0x06, 0x31, Direction.ECR_TO_PT, "Refund"),

    /** Perform daily settlement / end-of-day reconciliation */
    END_OF_DAY(0x06, 0x50, Direction.ECR_TO_PT, "End-of-Day"),

    /** Print totals report showing all transactions since last settlement */
    PRINT_TOTALS(0x06, 0x12, Direction.ECR_TO_PT, "Print Totals"),

    /** Run terminal diagnostics */
    DIAGNOSIS(0x06, 0x70, Direction.ECR_TO_PT, "Diagnosis"),

    /** Abort the current operation on the terminal */
    ABORT(0x06, 0xB0.toByte(), Direction.ECR_TO_PT, "Abort"),

    /** Reset the terminal hardware */
    RESET_TERMINAL(0x06, 0x18, Direction.ECR_TO_PT, "Reset Terminal"),

    /** Force a network initialization / re-registration with the acquirer */
    INITIALIZATION(0x06, 0x93.toByte(), Direction.ECR_TO_PT, "Initialization"),

    /** Read card data without initiating a payment */
    READ_CARD(0x06, 0xC0.toByte(), Direction.ECR_TO_PT, "Read Card"),

    /** Display text on the terminal screen (up to 8 lines) */
    DISPLAY_TEXT(0x06, 0xE0.toByte(), Direction.ECR_TO_PT, "Display Text"),

    /** Display text on terminal with numeric input from customer */
    DISPLAY_TEXT_INPUT(0x06, 0xE2.toByte(), Direction.ECR_TO_PT, "Display Text with Input"),

    /** Poll terminal status */
    STATUS_ENQUIRY(0x05, 0x01, Direction.ECR_TO_PT, "Status Enquiry"),

    /** Send turnover totals from ECR to PT */
    SEND_TURNOVER_TOTALS(0x0F, 0x10, Direction.ECR_TO_PT, "Send Turnover Totals"),

    // ===== PT → ECR Commands =====

    /** Transaction result with complete BMP data */
    STATUS_INFORMATION(0x04, 0x0F, Direction.PT_TO_ECR, "Status Information"),

    /** Returns master-rights to ECR — signals command completion */
    COMPLETION(0x06, 0x0F, Direction.PT_TO_ECR, "Completion"),

    /** PT-initiated abort, returns master-rights to ECR */
    PT_ABORT(0x06, 0x1E, Direction.PT_TO_ECR, "PT Abort"),

    /** Progress update during transaction processing */
    INTERMEDIATE_STATUS(0x04, 0xFF.toByte(), Direction.PT_TO_ECR, "Intermediate Status"),

    /** Single receipt line to print */
    PRINT_LINE(0x06, 0xD1.toByte(), Direction.PT_TO_ECR, "Print Line"),

    /** Full receipt text block (TLV-formatted) */
    PRINT_TEXT_BLOCK(0x06, 0xD3.toByte(), Direction.PT_TO_ECR, "Print Text-Block"),

    // ===== Acknowledgement =====

    /** Positive acknowledgement */
    ACK(0x80.toByte(), 0x00, Direction.BOTH, "Positive Acknowledgement"),

    /** Negative acknowledgement (error code in instruction byte) */
    NAK(0x84.toByte(), 0x00, Direction.BOTH, "Negative Acknowledgement"),
    ;

    /**
     * Returns the 2-byte control field as a byte array.
     */
    fun toBytes(): ByteArray = byteArrayOf(classByte, instructionByte)

    /**
     * Direction of command transmission.
     */
    enum class Direction {
        /** Command sent from ECR (POS) to PT (terminal) */
        ECR_TO_PT,

        /** Command sent from PT (terminal) to ECR (POS) */
        PT_TO_ECR,

        /** Can be sent by either side */
        BOTH,
    }

    companion object {
        /**
         * Looks up a ZVT command by its 2-byte control field.
         *
         * For NAK commands, the instruction byte may vary (carries error code),
         * so any command with class byte 0x84 is treated as NAK.
         *
         * @param classByte The first byte of the control field
         * @param instructionByte The second byte of the control field
         * @return The matching [ZvtCommand], or null if unknown
         */
        fun fromBytes(
            classByte: Byte,
            instructionByte: Byte,
        ): ZvtCommand? {
            // NAK special case: class 0x84 with any instruction byte is NAK
            if (classByte == 0x84.toByte()) return NAK

            return entries.find {
                it.classByte == classByte && it.instructionByte == instructionByte
            }
        }

        /**
         * Checks if a control field represents a positive acknowledgement.
         *
         * @param classByte The first byte
         * @param instructionByte The second byte
         * @return true if this is an ACK (80 00)
         */
        fun isAck(
            classByte: Byte,
            instructionByte: Byte,
        ): Boolean = classByte == 0x80.toByte() && instructionByte == 0x00.toByte()

        /**
         * Checks if a control field represents a negative acknowledgement.
         *
         * @param classByte The first byte
         * @return true if this is a NAK (84 xx)
         */
        fun isNak(classByte: Byte): Boolean = classByte == 0x84.toByte()

        /**
         * Extracts the error code from a NAK response.
         *
         * The instruction byte of a NAK carries the error code.
         *
         * @param instructionByte The second byte of the NAK control field
         * @return The error code value (0x00-0xFF)
         */
        fun getNakErrorCode(instructionByte: Byte): Int = instructionByte.toInt() and 0xFF
    }
}
