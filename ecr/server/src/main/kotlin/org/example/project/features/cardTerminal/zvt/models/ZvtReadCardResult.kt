/**
 * Read Card (06 C0) result model.
 *
 * Contains card data returned by the payment terminal after reading
 * a card without initiating a payment transaction.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Result of a Read Card operation.
 *
 * @property success Whether the card was successfully read
 * @property cardType The card type ID from BMP field 8A
 * @property cardTypeName Human-readable card type name
 * @property pan Masked PAN (card number) if available
 * @property expiryDate Card expiry date (YYMM) if available
 * @property cardSequenceNumber Card sequence number if available
 * @property aid Authorization identifier / AID if available
 * @property cardTechnology Card technology byte (contact/contactless/mag-stripe)
 * @property resultCode The raw result code from the terminal
 * @property errorDescription Error description if read failed
 */
data class ZvtReadCardResult(
    val success: Boolean,
    val cardType: Int? = null,
    val cardTypeName: String? = null,
    val pan: String? = null,
    val expiryDate: String? = null,
    val cardSequenceNumber: String? = null,
    val aid: String? = null,
    val cardTechnology: Int? = null,
    val resultCode: Int = 0,
    val errorDescription: String = "",
)
