package org.example.project.core.domain.enums.setup

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GermanVatRateTest {
    @Test
    fun everyMappableRateHasAnEnumConstant() {
        // The enum must stay in step with the guard: a rate the server accepts but the enum
        // doesn't know would be unrenderable in the picker.
        val enumRates = GermanVatRate.entries.map { it.rate }.toSet()
        DsfinvkExportDto.VatKeyMapping.MAPPED_RATES.forEach { rate ->
            assertTrue(enumRates.any { kotlin.math.abs(it - rate) < 0.0001 }, "no constant for $rate")
        }
    }

    @Test
    fun exactlyTheCurrentlyValidRatesAreConfigurable() {
        val configurable =
            GermanVatRate.entries
                .filter { it.configurable }
                .map { it.rate }
                .sorted()
        assertEquals(listOf(0.0, 0.055, 0.07, 0.078, 0.19), configurable)
    }

    @Test
    fun deadRatesAreNotConfigurable() {
        // 16% and 5% were the 2020 Corona rates; 8.4% was §24 Nr.3 until 2025. All must remain
        // mappable for export, none may be newly chosen.
        listOf(0.16, 0.05, 0.084, 0.09, 0.095, 0.107).forEach { dead ->
            val c = GermanVatRate.entries.first { kotlin.math.abs(it.rate - dead) < 0.0001 }
            assertEquals(false, c.configurable, "$dead must not be configurable")
        }
    }

    @Test
    fun eachConstantCarriesTheDsfinvkKeyTheMappingAssigns() {
        GermanVatRate.entries.forEach { c ->
            assertEquals(
                DsfinvkExportDto.VatKeyMapping.getVatKey(c.rate),
                c.dsfinvkKey,
                "${c.name} key mismatch",
            )
        }
    }
}
