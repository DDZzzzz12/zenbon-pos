package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.features.customexport.domain.catalogue.ExportFieldResolver
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.jetbrains.exposed.v1.core.Column
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A field key is a derived schema path, so renaming a database column renames the key and breaks
 * every stored definition naming it. `ExportColumn.legacyKey` is the one-line repair, and this is
 * what proves it is actually wired: an unconsumed mitigation is one discovered broken by an operator.
 *
 * The fields here are **built by hand**, not taken from the registry. No column has been renamed yet,
 * so the real catalogue carries no legacy key at all — waiting for the first rename to test this
 * would mean testing it under exactly the pressure that makes it matter.
 */
@DisplayName("Legacy field keys")
class LegacyFieldKeyTest {
    private fun field(
        key: String,
        legacyKey: String? = null,
        column: Column<*> = BusinessPartnerTable.name,
    ) = ExportField(
        key = key,
        label = key,
        type = ExportFieldType.TEXT,
        capability = Capability.CREDITOR_READ,
        column = column,
        legacyKey = legacyKey,
    )

    @Test
    fun `a current key resolves to its field`() {
        val city = field("partner.city")

        assertEquals(city, ExportFieldResolver.fieldFor("partner.city", listOf(field("partner.name"), city)))
    }

    @Test
    fun `a legacy key resolves to the field that replaced it`() {
        val city = field("partner.city", legacyKey = "partner.town")

        assertEquals(
            city,
            ExportFieldResolver.fieldFor("partner.town", listOf(field("partner.name"), city)),
            "a definition saved before the column was renamed no longer resolves",
        )
    }

    @Test
    fun `a key nobody publishes resolves to nothing`() {
        assertNull(ExportFieldResolver.fieldFor("partner.moon_phase", listOf(field("partner.city"))))
    }

    /**
     * The shadowing case, spelled out because getting it backwards is silent.
     *
     * If a legacy key ever equals another column's *current* key, resolving legacy-first would point
     * the definition at the wrong column — the same data type, a plausible value, and a different
     * meaning. Nothing in the file would say so.
     */
    @Test
    fun `a current key beats another field's legacy key`() {
        val realCity = field("partner.city")
        val impostor = field("partner.zip", legacyKey = "partner.city")

        assertEquals(
            realCity,
            ExportFieldResolver.fieldFor("partner.city", listOf(impostor, realCity)),
            "the legacy claim won over the field that actually carries the key today",
        )
    }

    /**
     * The trap this feature could have introduced.
     *
     * `ExportSubject.capabilityRequirements()` is keyed by the field's **current** key, so a stored
     * legacy key is absent from it. A guard reading that map with the stored key directly finds
     * nothing — and the run path defaults a missing entry to the empty set, which would wave a
     * legacy-keyed field past its capability check entirely. A security guard that silently does not
     * apply is worse than a missing feature, so the resolution happens first and the lookup uses the
     * resolved field's current key.
     */
    @Test
    fun `a legacy-keyed field still carries its field's capability requirement`() {
        val creditorNo = field("supplier:by:partner_id.creditor_no", legacyKey = "supplier.creditor_no")
        val requirements = mapOf(creditorNo.key to setOf(Capability.CREDITOR_READ))

        assertEquals(
            setOf(Capability.CREDITOR_READ),
            ExportFieldResolver.requirementsFor("supplier.creditor_no", listOf(creditorNo), requirements),
            "a legacy key found no requirement, so the guard would have let it through unchecked",
        )
        assertNull(
            ExportFieldResolver.requirementsFor("supplier.unknown", listOf(creditorNo), requirements),
            "an unknown key must stay unknown rather than resolve to no requirement",
        )
    }

    /**
     * A registry invariant, vacuous today and deliberately written before it is not.
     *
     * The registry has no legacy key at all yet. From the first rename onwards this is the guard that
     * keeps somebody from declaring a legacy key that is some other column's live key — the exact
     * shadowing the resolver's current-wins rule handles, caught here as the declaration mistake it is
     * rather than left to be found as a wrong column in an advisor's CSV.
     */
    @Test
    fun `no registered legacy key shadows a current column key`() {
        val currentKeys = ExportCatalogue.subjects.flatMap { subject -> subject.allFields.map { it.key } }.toSet()
        val legacyKeys = ExportTableRegistry.entries.flatMap { entry -> entry.columns.mapNotNull { it.legacyKey } }

        legacyKeys.forEach { legacy ->
            assertTrue(
                legacy !in currentKeys,
                "legacy key '$legacy' is also a key some field carries today",
            )
        }
    }

    /**
     * The picker offers what exists now.
     *
     * A legacy key is a repair for keys already stored, not a second name an operator may pick: a
     * catalogue offering both would let a definition be *created* on a name that only exists to
     * survive a rename, and the next rename would have to keep it alive forever.
     */
    @Test
    fun `a legacy key is not offered in the catalogue`() {
        val offered =
            ExportCatalogue.subjects.flatMap { subject -> subject.allFields }
        val legacyKeys = offered.mapNotNull { it.legacyKey }
        val offeredKeys = offered.map { it.key }.toSet()

        legacyKeys.forEach { legacy ->
            assertTrue(legacy !in offeredKeys, "legacy key '$legacy' is offered as if it were a current field")
        }
        assertNotNull(offered.firstOrNull(), "the catalogue offers nothing, so this proves nothing")
    }
}
