/**
 * ZVT Authorization (06 01) operation builder.
 *
 * Authorization initiates a payment transaction on the terminal.
 * The terminal handles card reading, PIN entry, and host authorization.
 * The ECR receives intermediate status updates and the final result
 * via Status Information (04 0F).
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds the Authorization (06 01) command APDU for payment transactions.
 */
object ZvtAuthorization {
    /**
     * Builds an Authorization APDU with the specified amount.
     *
     * @param amountCents Amount in minor currency units (e.g., cents)
     * @param currencyCode ISO 4217 numeric currency code (default: 978 = EUR)
     * @return The Authorization command APDU
     */
    fun buildApdu(
        amountCents: Long,
        currencyCode: Int = 978,
    ): ZvtApdu {
        require(amountCents > 0) { "Authorization amount must be positive" }

        val fields = mutableMapOf<Int, ByteArray>()
        // Amount (BMP 04) — 6-byte BCD
        fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
        // Currency code (BMP 49)
        fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] = ZvtBcdCodec.encodeCurrency(currencyCode)

        return ZvtApdu.create(ZvtCommand.AUTHORIZATION, fields)
    }
}
