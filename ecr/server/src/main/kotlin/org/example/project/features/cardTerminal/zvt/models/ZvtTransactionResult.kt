/**
 * Transaction result model parsed from ZVT Status Information (04 0F).
 *
 * Contains all relevant data returned by the payment terminal after
 * a transaction attempt, including approval status, amounts, card data,
 * and receipt information.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Parsed result of a ZVT payment transaction.
 *
 * Constructed from BMP fields in a Status Information (04 0F) APDU.
 *
 * @property approved Whether the transaction was approved
 * @property resultCode The raw result code from BMP field 27 (0x00 = success)
 * @property errorDescription Human-readable error description from the result code
 * @property amountCents The transaction amount in minor currency units
 * @property receiptNumber The receipt number assigned by the terminal
 * @property traceNumber The trace number for the transaction
 * @property terminalId The terminal ID string
 * @property cardType The card type ID from BMP field 8A
 * @property cardTypeName Human-readable card type name
 * @property pan Masked PAN (card number) if available
 * @property expiryDate Card expiry date (YYMM) if available
 * @property paymentType The payment type (debit/credit/etc.)
 * @property currencyCode ISO 4217 currency code
 * @property date Transaction date (MMDD) if available
 * @property time Transaction time (HHMMSS) if available
 * @property aid Authorization identifier / AID if available
 * @property cardSequenceNumber Card sequence number if available
 * @property receiptText Receipt text lines collected from Print Line / Print Text-Block messages
 */
data class ZvtTransactionResult(
    val approved: Boolean,
    val resultCode: Int,
    val errorDescription: String,
    val amountCents: Long = 0,
    val receiptNumber: Int? = null,
    val traceNumber: Int? = null,
    val terminalId: String? = null,
    val cardType: Int? = null,
    val cardTypeName: String? = null,
    val pan: String? = null,
    val expiryDate: String? = null,
    val paymentType: Int? = null,
    val currencyCode: Int? = null,
    val date: String? = null,
    val time: String? = null,
    val aid: String? = null,
    val cardSequenceNumber: String? = null,
    val receiptText: List<String> = emptyList(),
) {
    /**
     * Returns the payment type display name.
     */
    val paymentTypeName: String?
        get() = paymentType?.let { ZvtPaymentType.displayNameFor(it) }

    /**
     * Returns the amount formatted as a string with 2 decimal places.
     * Example: 12345 → "123.45"
     */
    val formattedAmount: String
        get() = String.format("%.2f", amountCents / 100.0)
}
