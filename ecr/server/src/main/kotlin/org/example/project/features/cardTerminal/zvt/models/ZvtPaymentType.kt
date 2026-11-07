/**
 * ZVT payment type identifiers.
 *
 * Payment type appears in BMP field 19 and indicates whether the
 * transaction was processed as debit, credit, or another payment method.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Payment types used in ZVT transactions.
 *
 * @property id The payment type byte value from BMP field 19
 * @property displayName Human-readable description
 */
enum class ZvtPaymentType(
    val id: Int,
    val displayName: String,
) {
    /** Debit card payment (girocard, Maestro, V PAY) */
    DEBIT(0x00, "Debit"),

    /** Credit card payment (Visa, Mastercard, etc.) */
    CREDIT(0x10, "Credit"),

    /** Prepaid card (GeldKarte) */
    PREPAID(0x20, "Prepaid"),

    /** Contactless payment */
    CONTACTLESS(0x30, "Contactless"),

    /** Customer card / loyalty card */
    CUSTOMER_CARD(0x40, "Customer Card"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        /**
         * Looks up a payment type by its ID.
         *
         * @param id The payment type byte value
         * @return The matching [ZvtPaymentType], or null if unknown
         */
        fun fromId(id: Int): ZvtPaymentType? = byId[id]

        /**
         * Returns a display name for the given payment type ID.
         *
         * @param id The payment type byte value
         * @return Human-readable name
         */
        fun displayNameFor(id: Int): String = fromId(id)?.displayName ?: "Payment Type 0x${id.toString(16).padStart(2, '0')}"
    }
}
