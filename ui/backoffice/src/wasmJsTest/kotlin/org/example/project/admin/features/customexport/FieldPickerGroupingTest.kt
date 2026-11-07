package org.example.project.admin.features.customexport

import org.example.project.admin.features.customexport.presentation.components.groupFields
import org.example.project.core.domain.dto.customexport.CustomExportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker's sectioning, tested where it can be tested.
 *
 * `FieldPicker` renders to a canvas the wasm test leg cannot read, so the part that has to be right —
 * which heading, in which order, how many — lives in `groupFields` and is asserted on a plain list.
 */
class FieldPickerGroupingTest {
    private fun field(
        key: String,
        group: String? = null,
    ) = CustomExportDto.FieldDto(key = key, label = key, type = "TEXT", group = group)

    @Test
    fun `root fields come first and carry no heading`() {
        val sections =
            groupFields(
                listOf(
                    field("partner.name"),
                    field("supplier.creditor_no", group = "Kreditor-Rolle"),
                    field("partner.city"),
                ),
            )

        assertEquals(null, sections.first().heading, "the subject's own columns are the unlabelled block")
        assertEquals(
            listOf("partner.name", "partner.city"),
            sections.first().fields.map { it.key },
            "a root field must not be pulled out of order by a grouped field between them",
        )
    }

    /**
     * The server's order is the schema graph's traversal order — nearest relation first — so it
     * carries meaning. Sorting the headings would replace that with the alphabet: here "Debitor-Rolle"
     * would jump ahead of "Kreditor-Rolle" for no reason an operator could act on.
     */
    @Test
    fun `groups keep the order the server sent them in, not alphabetical order`() {
        val sections =
            groupFields(
                listOf(
                    field("partner.name"),
                    field("supplier.creditor_no", group = "Kreditor-Rolle"),
                    field("debtor.debtor_no", group = "Debitor-Rolle"),
                ),
            )

        assertEquals(
            listOf(null, "Kreditor-Rolle", "Debitor-Rolle"),
            sections.map { it.heading },
        )
    }

    /**
     * A table the graph reaches over two paths contributes fields that are not adjacent. Two sections
     * under one heading would leave the operator with no way to tell which is which.
     */
    @Test
    fun `a group whose fields are not adjacent still yields exactly one section`() {
        val sections =
            groupFields(
                listOf(
                    field("supplier.creditor_no", group = "Kreditor-Rolle"),
                    field("debtor.debtor_no", group = "Debitor-Rolle"),
                    field("supplier.is_active", group = "Kreditor-Rolle"),
                ),
            )

        assertEquals(
            listOf("Kreditor-Rolle", "Debitor-Rolle"),
            sections.map { it.heading },
            "the repeated heading must not open a second section",
        )
        assertEquals(
            listOf("supplier.creditor_no", "supplier.is_active"),
            sections.first().fields.map { it.key },
            "both of the group's fields belong to its one section",
        )
    }

    @Test
    fun `no fields yields no sections`() {
        assertTrue(groupFields(emptyList()).isEmpty(), "an empty pane must render nothing, not an empty heading")
    }
}
