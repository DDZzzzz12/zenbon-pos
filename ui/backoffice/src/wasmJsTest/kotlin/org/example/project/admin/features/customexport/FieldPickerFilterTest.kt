package org.example.project.admin.features.customexport

import org.example.project.admin.features.customexport.presentation.components.FieldSection
import org.example.project.admin.features.customexport.presentation.components.filterFields
import org.example.project.core.domain.dto.customexport.CustomExportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker's filtering, tested where it can be tested.
 *
 * `FieldPicker` renders to a canvas the wasm test leg cannot read, so the part that has to be right —
 * what a query keeps, what it drops, and what it must never drop — lives in `filterFields` and is
 * asserted on a plain list.
 */
class FieldPickerFilterTest {
    private fun field(
        key: String,
        label: String,
        group: String? = null,
    ) = CustomExportDto.FieldDto(key = key, label = label, type = "TEXT", group = group)

    private val sections =
        listOf(
            FieldSection(
                heading = null,
                fields = listOf(field("partner.name", "Name"), field("partner.city", "Ort")),
            ),
            FieldSection(
                heading = "Kreditor-Rolle",
                fields = listOf(field("supplier.creditor_no", "Kreditorennummer"), field("supplier.active", "Aktiv")),
            ),
            FieldSection(
                heading = "Bankverbindung",
                fields = listOf(field("bank.iban", "IBAN"), field("bank.holder", "Kontoinhaber")),
            ),
        )

    @Test
    fun `a blank query returns the input unchanged`() {
        assertEquals(sections, filterFields(sections, "", emptySet()), "an empty box is the resting state, not a query")
    }

    @Test
    fun `a whitespace-only query returns the input unchanged`() {
        assertEquals(sections, filterFields(sections, "   ", emptySet()), "spaces are not a query either")
    }

    @Test
    fun `a query matching a field label keeps that field and drops its siblings`() {
        val result = filterFields(sections, "IBAN", emptySet())

        assertEquals(listOf("Bankverbindung"), result.map { it.heading })
        assertEquals(listOf("bank.iban"), result.single().fields.map { it.key }, "the non-matching sibling must go")
    }

    /**
     * "kreditor" is how an operator asks for an *area*. Answering with only the one field whose label
     * happens to contain it would answer a question nobody asked.
     */
    @Test
    fun `a query matching a group heading keeps that whole section`() {
        val result = filterFields(sections, "kreditor", emptySet())

        assertEquals(listOf("Kreditor-Rolle"), result.map { it.heading })
        assertEquals(
            listOf("supplier.creditor_no", "supplier.active"),
            result.single().fields.map { it.key },
            "a matching heading keeps every field of its section, including the ones whose labels do not match",
        )
    }

    @Test
    fun `matching is case-insensitive in both directions`() {
        assertEquals(
            listOf("bank.iban"),
            filterFields(sections, "iban", emptySet()).single().fields.map { it.key },
            "a lowercase query must find an uppercase label",
        )
        assertEquals(
            listOf("partner.city"),
            filterFields(sections, "ORT", emptySet()).single().fields.map { it.key },
            "an uppercase query must find a lowercase label",
        )
    }

    @Test
    fun `a section with no match disappears entirely`() {
        val result = filterFields(sections, "Konto", emptySet())

        assertEquals(
            listOf("Bankverbindung"),
            result.map { it.heading },
            "the root block and the Kreditor block hold nothing matching and must not render as empty headings",
        )
    }

    /**
     * The rule this whole parameter exists for. A ticked column that the query hides leaves the
     * operator with a checkbox they cannot reach — and the checkbox is what they untick with.
     */
    @Test
    fun `a ticked field stays visible even when it does not match`() {
        val result = filterFields(sections, "IBAN", chosenKeys = setOf("partner.city"))

        assertEquals(
            listOf(null, "Bankverbindung"),
            result.map { it.heading },
            "the ticked field's section must survive a query that matches nothing in it",
        )
        assertEquals(
            listOf("partner.city"),
            result.first().fields.map { it.key },
            "only the ticked field is kept from a section the query did not match",
        )
    }

    @Test
    fun `a query matching nothing with nothing ticked yields no sections`() {
        assertTrue(
            filterFields(sections, "Umsatzsteuer-Identifikationsnummer", emptySet()).isEmpty(),
            "no match and no ticked column means an empty pane, which the picker labels",
        )
    }
}
