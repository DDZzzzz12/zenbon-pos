package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a user may tick is decided by what they hold.
 *
 * The filter is the security boundary the whole design rests on: a field the caller cannot see must
 * not appear in the catalogue they are offered, because a UI that offers it will let them save it.
 */
@DisplayName("Catalogue capability filter")
class CatalogueCapabilityFilterTest {
    @Test
    fun `a caller holding the field's capability sees it`() {
        val visible = ExportCatalogue.visibleTo(setOf(Capability.CREDITOR_READ))

        assertTrue(visible.single { it.subject.key == "partner" }.fields.any { it.key == "partner.vat_id" })
    }

    /** A subject whose every field is out of reach is not worth offering at all. */
    @Test
    fun `a caller holding nothing sees no subjects`() {
        assertEquals(emptyList(), ExportCatalogue.visibleTo(emptySet()))
    }

    /**
     * The unfiltered catalogue must never be what a caller receives — this pins that filtering
     * actually removes something, so a filter that silently returned everything would fail.
     */
    @Test
    fun `filtering removes fields the caller cannot see`() {
        // allOfferings, not allFields: the unfiltered catalogue includes the aggregates, and counting
        // only the columns would let the filter leak every aggregate while this test stayed green.
        val all = ExportCatalogue.subjects.sumOf { it.allOfferings.size }
        val none = ExportCatalogue.visibleTo(emptySet()).sumOf { it.fields.size }

        assertTrue(all > 0, "the catalogue is empty, so this test proves nothing")
        assertEquals(0, none)
    }
}
