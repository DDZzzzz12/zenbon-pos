package org.example.project.features.customexport.domain.catalogue

/**
 * What one foreign key is *called*, where the table's own name does not say it.
 *
 * A relation's picker heading is normally the far table's registry label — `cash_book` reached from a
 * cash-book entry is "Kasse", and that is the whole of it. It stops being enough the moment one table
 * is reachable by **two** foreign keys: `kassenbuch_header` holds both `cash_book_id` and the
 * Geldtransit counterparty, so the operator was shown two blocks both headed "Kasse", picked a column
 * from each, and had the run refused by `requireOneRoutePerTable`. The refusal was right; the heading
 * was the defect.
 *
 * ## Keyed by the edge, not by the table
 *
 * The key is one `SchemaGraph.segmentOf` segment — `<target>:<direction>:<column>` — which is what
 * identifies a foreign key traversed in a given direction, and is already the unit a stored field key
 * is built from. Keying by the *column* alone would not do: `sales_invoice.bill_id` followed forwards
 * names a Kassenbeleg and followed backwards names an Ausgangsrechnung, and one entry cannot label
 * both.
 *
 * ## These are display only
 *
 * Nothing here reaches a stored key. An entry may be reworded, added or removed without touching a
 * single saved definition — which is the reason the heading is where the ambiguity gets fixed and the
 * key is left alone.
 *
 * ## When to add one
 *
 * When two paths of one subject would otherwise carry the same heading. `SchemaGraph` falls back to
 * appending the raw segment when that happens, and `RelationGroupLabelTest` fails on any subject
 * showing such a fallback — so a new foreign key that reintroduces the ambiguity is a failing test
 * with the offending segment in the message, not a wart an operator finds.
 *
 * ## When *not* to add one
 *
 * When the two routes are one relation seen from both ends — a mutual 1:1, a foreign key on each
 * side. There is nothing to name: both blocks hold the same values, and a heading saying so still
 * leaves the operator two identical blocks to choose between. That belongs in
 * [NonTraversableForeignKeys], which removes the redundant route instead of labelling it.
 */
object ExportRelationLabels {
    /**
     * The heading a path arriving over this segment carries, in place of the far table's own label.
     *
     * Written as a **pair** wherever a pair is what is ambiguous: leaving one of two routes at the
     * bare table name and naming only the other reads as though the bare one were the general case,
     * which is exactly the misreading being removed.
     */
    private val bySegment: Map<String, String> =
        mapOf(
            // Two books on one cash-book entry: the book it was booked in, and the far side of a
            // Geldtransit. "Gegenkasse" is the word the Kassenbuch screens already use for the latter.
            "cash_book:via:cash_book_id" to "Kasse",
            "cash_book:via:counterparty_book_id" to "Gegenkasse (Geldtransit)",
            // `sales_invoice` names the person who issued it and the person who cancelled it. On most
            // rows the second is empty, which is precisely why the two must not share a heading.
            "employees:via:issued_by" to "Personal (ausgestellt von)",
            "employees:via:cancelled_by" to "Personal (storniert von)",
            // `employee_info` used to need a pair here: `employees.info_id` and
            // `employee_info.employee_id` are one link written twice, so the walk found the personnel
            // record twice and the second block was headed "Personaldaten (alternative Verknüpfung)".
            // A heading could only *describe* that duplicate — the two blocks held the same four
            // values — so the fix moved to `NonTraversableForeignKeys`, which stops the walk taking
            // the back-reference at all. One route survives and carries the registry label
            // "Personaldaten" with no entry needed here.
            // A till receipt and an outgoing invoice reference each other, so each is reachable from
            // the other twice: the invoice a receipt settles, and the invoices raised for it.
            "sales_invoice:via:clears_sales_invoice_id" to "Ausgangsrechnung (beglichen)",
            "sales_invoice:by:bill_id" to "Ausgangsrechnung (zum Kassenbeleg)",
            "bill:via:bill_id" to "Kassenbeleg (Grundlage)",
            "bill:by:clears_sales_invoice_id" to "Kassenbeleg (Begleichung)",
            // The staff-side mirror of `issued_by`/`cancelled_by`, reached from a person or from the
            // cash-book entry they booked.
            "sales_invoice:by:issued_by" to "Ausgangsrechnung (ausgestellt)",
            "sales_invoice:by:cancelled_by" to "Ausgangsrechnung (storniert)",
        )

    /** The declared heading for [segment], or null to use the far table's registry label. */
    fun forSegment(segment: String): String? = bySegment[segment]

    /** Every declared segment, for the test that proves each one still names a real edge. */
    fun declaredSegments(): Set<String> = bySegment.keys
}
