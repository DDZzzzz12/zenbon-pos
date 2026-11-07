package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportRelationLabels
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A picker heading has one job: to tell an operator which block they are ticking a column out of.
 *
 * It stopped doing that as soon as one table became reachable twice. `kassenbuch_header` holds
 * `cash_book_id` **and** the Geldtransit counterparty, so the Kassenbuch subject showed two blocks
 * both headed "Kasse"; ticking a column from each is refused at download time by
 * `requireOneRoutePerTable`, and the operator had no way to see the choice was ambiguous. The refusal
 * was right — one FROM clause holds a table once — so the heading is what had to change.
 *
 * These tests hold the property that makes the picker honest: **within one subject, no two relation
 * paths carry the same heading.**
 */
@DisplayName("Relation group headings")
class RelationGroupLabelTest {
    @Test
    fun `no subject offers two groups under one heading`() {
        ExportCatalogue.subjects.forEach { subject ->
            val duplicated =
                (subject.paths + subject.aggregatePaths)
                    .groupBy { it.label }
                    .filterValues { it.size > 1 }
                    .mapValues { (_, paths) -> paths.map { it.key } }

            assertEquals(
                emptyMap(),
                duplicated,
                "subject '${subject.key}' shows one heading over two different routes; an operator " +
                    "ticking a column from each gets the run refused for a choice that looked " +
                    "unambiguous. Name the foreign keys in ExportRelationLabels.",
            )
        }
    }

    /**
     * The fallback is a safety net, not a shipping state.
     *
     * `SchemaGraph.groupLabelsFor` appends the raw `<target>:<direction>:<column>` segment when two
     * hops would still collide, which keeps the *guarantee* above true whatever the schema grows next.
     * A colon cannot occur in a hand-written German heading, so its presence is exactly the signal
     * that a foreign key needs naming in [ExportRelationLabels] — and it must fail here rather than
     * reach an admin panel.
     */
    @Test
    fun `no heading falls back to a raw schema segment`() {
        val raw =
            ExportCatalogue.subjects.flatMap { subject ->
                (subject.paths + subject.aggregatePaths)
                    .map { subject.key to it.label }
                    .filter { (_, label) -> label.contains(':') }
            }

        assertEquals(emptyList(), raw, "these headings show a foreign key to the operator; name it instead")
    }

    /** The case that provoked the fix, pinned by name. */
    @Test
    fun `the two cash books a Kassenbuch entry reaches are headed differently`() {
        val paths = SchemaGraph.pathsFrom(KassenbuchHeaderTable).filter { it.key.startsWith("cash_book:") }

        assertEquals(
            listOf("cash_book:via:cash_book_id" to "Kasse", "cash_book:via:counterparty_book_id" to "Gegenkasse (Geldtransit)"),
            paths.map { it.key to it.label },
        )
        val subject = assertNotNull(ExportCatalogue.subject("kassenbuch"))
        assertTrue(
            subject.allFields.any { it.group == "Gegenkasse (Geldtransit)" },
            "the heading must reach the fields the picker groups by, not only the path",
        )
    }

    /**
     * A two-hop heading names **both** hops, so it says which route rather than only where it ended.
     *
     * Also the reason a heading is not simply the far table's registry label: `bill` reaches the
     * debtor role through the invoice it settles *and* through the invoices raised for it, and only
     * the route tells those two blocks apart.
     */
    @Test
    fun `a two-hop heading names the route it took`() {
        val subject = assertNotNull(ExportCatalogue.subject("bill"))
        val headings = subject.allOfferings.mapNotNull { it.group }.toSet()

        assertTrue("Ausgangsrechnung (beglichen) → Debitor-Rolle" in headings, "found: $headings")
        assertTrue(
            headings.any { it.startsWith("Personal${SchemaGraph.HOP_SEPARATOR}Personaldaten") },
            "the personnel record reached through the till operator says so: $headings",
        )
    }

    /**
     * Every declared label still names an edge some subject actually walks.
     *
     * A typo in a segment key is otherwise silent: the map entry simply never matches, the heading
     * falls back, and only the sibling test above would notice — and then only if the fallback
     * happened to collide. This says it directly.
     */
    @Test
    fun `every declared relation label names a real edge`() {
        val walked =
            ExportCatalogue.subjects
                .flatMap { it.paths + it.aggregatePaths }
                .flatMap { path -> path.key.split(".") }
                .toSet()

        assertEquals(
            emptySet(),
            ExportRelationLabels.declaredSegments() - walked,
            "these segments are named but never reached; a renamed column leaves a dead entry behind",
        )
    }

    /**
     * Headings are display, and the field keys a definition stores are untouched by this slice.
     *
     * The one assertion that would fail if somebody ever "fixed" an ambiguous heading by changing the
     * key it is derived from — which would silently break every saved definition naming it.
     */
    @Test
    fun `renaming a heading does not touch the stored key`() {
        val subject = assertNotNull(ExportCatalogue.subject("kassenbuch"))
        val keys = subject.allFields.map { it.key }

        assertTrue("cash_book:via:cash_book_id.name" in keys, "found: $keys")
        assertTrue("cash_book:via:counterparty_book_id.name" in keys, "found: $keys")
    }
}
