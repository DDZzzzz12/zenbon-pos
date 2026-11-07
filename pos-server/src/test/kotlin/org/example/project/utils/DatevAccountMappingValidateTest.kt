package org.example.project.utils

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DatevAccountMappingValidateTest {
    private val valid = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())

    @Test
    fun `seeded mapping validates cleanly`() {
        assertTrue(valid.validate().isEmpty())
    }

    @Test
    fun `konto out of range is rejected`() {
        val bad = valid.copy(kasseKonto = 0)
        assertTrue(bad.validate().any { it.contains("kasseKonto") })
        val badCell =
            valid.copy(
                income = mapOf(KassenbuchLineKind.STANDARD to mapOf("19" to AccountCell(konto = 100_000_000))),
            )
        assertTrue(badCell.validate().isNotEmpty())
    }

    @Test
    fun `bu longer than 4 chars is rejected and non-canonical rate keys are rejected`() {
        val badBu =
            valid.copy(
                expense = mapOf(KassenbuchLineKind.GUTSCHEIN to mapOf("19" to AccountCell(konto = 3720, bu = "12345"))),
            )
        assertTrue(badBu.validate().isNotEmpty())
        val badRate =
            valid.copy(
                income = mapOf(KassenbuchLineKind.STANDARD to mapOf("19,00" to AccountCell(konto = 8400))),
            )
        assertTrue(badRate.validate().any { it.contains("rate") })
    }

    @Test
    fun `NON_CASH_PAYMENT rate cells are rejected`() {
        val bad =
            valid.copy(
                income = mapOf(KassenbuchLineKind.NON_CASH_PAYMENT to mapOf("19" to AccountCell(konto = 1360))),
            )
        assertTrue(bad.validate().isNotEmpty())
    }

    @Test
    fun `paymentTypes cells and ids are validated`() {
        val badKonto = valid.copy(paymentTypes = mapOf(2L to AccountCell(konto = 0)))
        assertTrue(badKonto.validate().isNotEmpty())

        val badId = valid.copy(paymentTypes = mapOf(-1L to AccountCell(konto = 1360)))
        assertTrue(badId.validate().any { it.contains("paymentTypes") })

        val badBu = valid.copy(paymentTypes = mapOf(2L to AccountCell(konto = 1360, bu = "12345")))
        assertTrue(badBu.validate().isNotEmpty())
    }
}
