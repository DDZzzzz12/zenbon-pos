package org.example.project.core.utils

/**
 * DSFinV-K 2.4 Anhang E `Feldlänge` / index.xml `MaxLength` for the operator-supplied shop
 * Stammdaten, enforced at the **write boundary** (round-7 C7).
 *
 * Auditor software parses every CSV in the package through the ordered `VariableColumn` descriptors
 * in index.xml. A value longer than its declared `MaxLength` breaks that contract and makes the
 * package formally invalid — and nothing on the emit path shortens it: `stammdatenFor` in the
 * export service hands these strings through untouched.
 *
 * **Rejected on input, never truncated on export.** Truncating would write a value the operator
 * never entered into a record whose whole purpose is to be an unaltered account of what happened;
 * a wrong-but-intact value stays auditable, a silently shortened one does not.
 *
 * These four are the fields whose DB columns are materially wider than the declared field, so an
 * over-long value is genuinely reachable: `shop_name` 255 vs 60, `shop_address` 500 vs 60,
 * `shop_city` 100 vs 62, `shop_tax_number` 50 vs 20. `ust_id_nr` is already `varchar(15)` against
 * a declared 15 and needs no check here; `PLZ` and `LAND` are not free text on the emit path (an
 * `Int` padded to 5, and the ISO country-code mapper).
 *
 * `DsfinvkFieldLengthTest` pins every constant below to the corresponding `Alpha` length in
 * `DsfinvkIndexXml`, the verified transcription of the reference `02_index.xml/index.xml`, so a
 * hand-edited number here cannot drift away from the schema the package is validated against.
 */
object DsfinvkFieldLimits {
    /** `cashpointclosing.csv` NAME / `location.csv` LOC_NAME — shop name. */
    const val NAME: Int = 60

    /** `cashpointclosing.csv` STRASSE / `location.csv` LOC_STRASSE — street address. */
    const val STRASSE: Int = 60

    /** `cashpointclosing.csv` ORT / `location.csv` LOC_ORT — city. */
    const val ORT: Int = 62

    /** `cashpointclosing.csv` STNR — Steuernummer. */
    const val STNR: Int = 20

    /** `transactions.csv` BEDIENER_NAME — the serving employee, on EVERY Bonkopf row. */
    const val BEDIENER_NAME: Int = 50

    /** `transactions.csv` KUNDE_NAME — the customer attached to the Vorgang. */
    const val KUNDE_NAME: Int = 50

    /** `lines.csv` WARENGR — the product's category name. */
    const val WARENGR: Int = 50

    /** `datapayment.csv` / `payment.csv` ZAHLART_NAME — the payment type's name. */
    const val ZAHLART_NAME: Int = 60

    /** `lines.csv` ARTIKELTEXT — the line label. */
    const val ARTIKELTEXT: Int = 255

    /**
     * The single definition of a person's exported display name.
     *
     * `BEDIENER_NAME` and `KUNDE_NAME` are both `first last`, and the write boundary must measure
     * EXACTLY the string the exporter emits — validating the parts separately would let
     * `"<25 chars>" + " " + "<25 chars>"` pass while the joined 51-character value breaks the
     * declared `MaxLength`. Both sides call this.
     */
    fun displayName(
        firstName: String?,
        lastName: String?,
    ): String = listOfNotNull(firstName, lastName).joinToString(" ").trim()

    /**
     * Clamps a GENERATED label to [max], marking the cut with an ellipsis.
     *
     * Only for values the system composes itself — today just `lines.csv` ARTIKELTEXT on the
     * aggregated bill-wide Rabatt line, whose text is `"Aktion: " + every bill-wide promotion
     * name` and so can exceed 255 through combination even though each name is individually legal.
     * There is no single write moment at which that can be rejected.
     *
     * This is NOT the treatment for operator-entered values: those are rejected at the write
     * boundary via [requireMaxLength], because a fiscal record must not be silently rewritten
     * (round-7 C7/C8). A generated label is different in kind — the auditable quantity on that
     * line is `STK_BR`, which is untouched, and each promotion remains fully recorded in its own
     * rows. The ellipsis keeps the abbreviation visible rather than silent.
     */
    fun clampGeneratedLabel(
        value: String,
        max: Int,
    ): String = if (value.length <= max) value else value.take(max - 1) + "…"

    /**
     * Rejects [value] when it exceeds [max], naming both the DSFinV-K field and its length so the
     * operator can see what has to give.
     *
     * @throws IllegalArgumentException which the shop-info service converts to a 400 ProblemDetail.
     */
    fun requireMaxLength(
        value: String?,
        max: Int,
        csvField: String,
    ) {
        if (value != null) {
            require(value.length <= max) {
                "$csvField must not exceed $max characters (DSFinV-K Anhang E), was ${value.length}"
            }
        }
    }
}
