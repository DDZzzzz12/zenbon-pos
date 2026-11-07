package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class KassenbuchEntryValidationTest {
    private fun request(vararg lines: KassenbuchDto.EntryLineDto) =
        KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = KassenbuchEntryType.CASH_EXPENSE,
            lines = lines.toList(),
            belegtext = "Metro",
            documentId = 1L,
        )

    @Test
    fun `metro mixed-sign request is valid`() {
        val errors =
            request(
                KassenbuchDto.EntryLineDto(vatRate = "19", amount = "23.78"),
                KassenbuchDto.EntryLineDto(vatRate = "7", amount = "131.78"),
                KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.GUTSCHEIN, vatRate = "19", amount = "-19.11"),
                KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.GUTSCHEIN, vatRate = "7", amount = "-105.89"),
            ).validate()
        assertTrue(errors.isEmpty(), "unexpected: $errors")
    }

    @Test
    fun `zero amount, missing rate, non-cash kind, empty lines are rejected`() {
        assertTrue(request(KassenbuchDto.EntryLineDto(vatRate = "19", amount = "0.0")).validate().any { it.contains("zero") })
        assertTrue(request(KassenbuchDto.EntryLineDto(amount = "10.0")).validate().any { it.contains("VAT rate") })
        assertTrue(
            request(KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.NON_CASH_PAYMENT, amount = "10.0"))
                .validate()
                .any { it.contains("Z-Bon-internal") },
        )
        assertTrue(request().validate().any { it.contains("At least one line") })
    }

    @Test
    fun `non-positive document total is rejected, comma rates accepted`() {
        assertTrue(
            request(
                KassenbuchDto.EntryLineDto(vatRate = "19", amount = "10.0"),
                KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.GUTSCHEIN, vatRate = "19", amount = "-15.0"),
            ).validate().any { it.contains("must be positive") },
        )
        assertTrue(request(KassenbuchDto.EntryLineDto(vatRate = "19,00", amount = "10.0")).validate().isEmpty())
    }
}
