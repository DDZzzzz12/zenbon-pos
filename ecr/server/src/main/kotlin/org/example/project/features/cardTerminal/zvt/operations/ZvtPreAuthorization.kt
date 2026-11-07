/**
 * ZVT Pre-Authorization (06 22) and Book Pre-Authorization (06 24) operation builders.
 *
 * Pre-authorization reserves an amount on the customer's card without
 * completing the transaction. The reserved amount is finalized later
 * via Book Pre-Authorization.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds Pre-Authorization (06 22) and Book Pre-Authorization (06 24) APDUs.
 */
object ZvtPreAuthorization {
    /**
     * Builds a Pre-Authorization APDU to reserve an amount.
     *
     * @param amountCents Amount to reserve in minor currency units
     * @param currencyCode ISO 4217 currency code (default: 978 = EUR)
     * @return The Pre-Authorization command APDU
     */
    fun buildPreAuthApdu(
        amountCents: Long,
        currencyCode: Int = 978,
    ): ZvtApdu {
        require(amountCents > 0) { "Pre-auth amount must be positive" }

        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
        fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] = ZvtBcdCodec.encodeCurrency(currencyCode)

        return ZvtApdu.create(ZvtCommand.PRE_AUTHORIZATION, fields)
    }

    /**
     * Builds a Book Pre-Authorization APDU to finalize a reservation.
     *
     * @param receiptNumber Receipt number of the original pre-authorization
     * @param amountCents Final amount to book (may differ from pre-auth)
     * @return The Book Pre-Authorization command APDU
     */
    fun buildBookApdu(
        receiptNumber: Int,
        amountCents: Long,
    ): ZvtApdu {
        require(receiptNumber > 0) { "Receipt number must be positive" }
        require(amountCents > 0) { "Book amount must be positive" }

        val fields = mutableMapOf<Int, ByteArray>()
        fields[BmpFieldDefinition.RECEIPT_NUMBER.fieldId] = ZvtBcdCodec.encode(receiptNumber.toLong(), 2)
        fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)

        return ZvtApdu.create(ZvtCommand.BOOK_PRE_AUTHORIZATION, fields)
    }
}
