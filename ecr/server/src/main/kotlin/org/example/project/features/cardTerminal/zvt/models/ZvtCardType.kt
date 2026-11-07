/**
 * ZVT card type identifiers as defined in PA00P015 Chapter 12.
 *
 * Card type IDs appear in BMP field 8A and identify the card brand
 * or payment network used for a transaction. The specification defines
 * approximately 280 card type IDs; this enum covers the most common ones.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Common card type IDs from ZVT Chapter 12.
 *
 * @property id The card type ID byte value
 * @property displayName Human-readable card brand name
 */
enum class ZvtCardType(
    val id: Int,
    val displayName: String,
) {
    /** Unknown or unspecified card type */
    UNKNOWN(0, "Unknown"),

    /** girocard (German debit card) */
    GIROCARD(5, "girocard"),

    /** Mastercard */
    MASTERCARD(6, "Mastercard"),

    /** American Express */
    AMEX(7, "American Express"),

    /** Diners Club */
    DINERS_CLUB(8, "Diners Club"),

    /** JCB (Japan Credit Bureau) */
    JCB(9, "JCB"),

    /** Visa */
    VISA(10, "Visa"),

    /** Visa Electron */
    VISA_ELECTRON(11, "Visa Electron"),

    /** Mastercard Maestro */
    MAESTRO(12, "Maestro"),

    /** V PAY */
    V_PAY(14, "V PAY"),

    /** Discover */
    DISCOVER(15, "Discover"),

    /** Union Pay */
    UNION_PAY(16, "Union Pay"),

    /** GeldKarte (German prepaid card) */
    GELDKARTE(46, "GeldKarte"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        /**
         * Looks up a card type by its ID.
         *
         * @param id The card type ID byte value
         * @return The matching [ZvtCardType], or null if not in common list
         */
        fun fromId(id: Int): ZvtCardType? = byId[id]

        /**
         * Returns a display name for the given card type ID.
         *
         * @param id The card type ID byte value
         * @return Human-readable name, or "Card Type [id]" for unknown IDs
         */
        fun displayNameFor(id: Int): String = fromId(id)?.displayName ?: "Card Type $id"
    }
}
