package org.example.project.export.dsfinvk

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals

/**
 * Pins [DsfinvkExportDto.ReferenceTypes] to the exact REF_TYP literal set allowed by the
 * DSFinV-K 2.4 specification (Anhang E): Transaktion, ExterneRechnung, ExternerLieferschein,
 * ExterneSonstige. Guards against re-introducing an invalid literal (e.g. the removed
 * "ExterneBestellung") or a mis-spelled one (e.g. the old "Sonstige").
 */
class DsfinvkReferenceTypesTest {
    private val validRefTypes =
        setOf(
            "Transaktion",
            "ExterneRechnung",
            "ExternerLieferschein",
            "ExterneSonstige",
        )

    @Test
    fun `ReferenceTypes exposes only spec-valid REF_TYP literals`() {
        val declaredValues =
            DsfinvkExportDto.ReferenceTypes::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map { it.get(null) as String }
                .toSet()

        assertEquals(validRefTypes, declaredValues)
    }

    @Test
    fun `known constants map to the expected REF_TYP literals`() {
        assertEquals("Transaktion", DsfinvkExportDto.ReferenceTypes.TRANSAKTION)
        assertEquals("ExterneRechnung", DsfinvkExportDto.ReferenceTypes.EXTERNE_RECHNUNG)
        assertEquals("ExternerLieferschein", DsfinvkExportDto.ReferenceTypes.EXTERNER_LIEFERSCHEIN)
        assertEquals("ExterneSonstige", DsfinvkExportDto.ReferenceTypes.EXTERNE_SONSTIGE)
    }
}
