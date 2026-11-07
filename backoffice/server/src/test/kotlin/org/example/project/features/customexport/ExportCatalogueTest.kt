package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The catalogue is the whole security boundary: only a declared field can ever be exported, so its
 * integrity is worth asserting rather than assuming.
 */
@DisplayName("Export catalogue")
class ExportCatalogueTest {
    @Test
    fun `the partner subject is registered and has fields`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))

        assertTrue(subject.allFields.isNotEmpty())
        assertEquals("partner", subject.key)
    }

    /**
     * Shop scoping is a per-subject declaration the runner reads, so the expectation belongs in a
     * test: a subject author who forgets it would otherwise ship an export carrying another shop's
     * rows, which nobody finds until it is in someone's spreadsheet.
     */
    @Test
    fun `the partner subject declares the column its rows are scoped by`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))

        assertEquals(BusinessPartnerTable.shopId, subject.shopColumn)
    }

    /** A duplicate key would make a saved definition ambiguous about which column it meant. */
    @Test
    fun `field keys are unique within a subject`() {
        ExportCatalogue.subjects.forEach { subject ->
            val keys = subject.allFields.map { it.key }
            assertEquals(keys.size, keys.distinct().size, "duplicate field key in subject '${subject.key}'")
        }
    }

    @Test
    fun `subject keys are unique`() {
        val keys = ExportCatalogue.subjects.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    /** An unknown key must answer null rather than throw — a stale definition is a handled case. */
    @Test
    fun `an unknown subject key resolves to null`() {
        assertEquals(null, ExportCatalogue.subject("no-such-subject"))
    }
}
