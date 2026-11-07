package org.example.project.core.utils

import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tagesbericht "Zahlungen" strip: one stable order, by the name shown in the UI language. */
class PaymentSummaryOrderTest {
    private fun payment(
        de: String,
        en: String? = null,
        zh: String? = null,
    ) = PaymentDto.PaymentSummary(amount = "1.00", paymentTypeDE = de, paymentTypeEN = en, paymentTypeCH = zh)

    @Test
    fun theOrderDoesNotDependOnTheOrderTheServerSent() {
        val a = listOf(payment("EC-Karte"), payment("Bar"), payment("Gutschein"))
        val b = listOf(payment("Gutschein"), payment("EC-Karte"), payment("Bar"))

        val expected = listOf("Bar", "EC-Karte", "Gutschein")
        assertEquals(expected, a.sortedForDisplay(Language.GERMAN).map { it.paymentTypeDE })
        assertEquals(expected, b.sortedForDisplay(Language.GERMAN).map { it.paymentTypeDE })
    }

    @Test
    fun caseDoesNotDecideTheOrder() {
        val sorted = listOf(payment("bar"), payment("Amex"), payment("Card")).sortedForDisplay(Language.GERMAN)

        assertEquals(listOf("Amex", "bar", "Card"), sorted.map { it.paymentTypeDE })
    }

    @Test
    fun anUmlautSortsWithItsBaseLetterNotAfterZ() {
        val sorted = listOf(payment("Zahlung später"), payment("Überweisung"), payment("Bar")).sortedForDisplay(Language.GERMAN)

        assertEquals(listOf("Bar", "Überweisung", "Zahlung später"), sorted.map { it.paymentTypeDE })
    }

    @Test
    fun sortsByTheNameShownInTheUiLanguage() {
        val payments = listOf(payment("Bar", en = "Cash"), payment("EC-Karte", en = "Card"), payment("Gutschein"))

        // English: Card, Cash, and "Gutschein" (no English name, shown in German).
        assertEquals(listOf("Card", "Cash", "Gutschein"), payments.sortedForDisplay(Language.ENGLISH).map { it.getLocalizedName(Language.ENGLISH) })
        assertEquals(listOf("Bar", "EC-Karte", "Gutschein"), payments.sortedForDisplay(Language.GERMAN).map { it.paymentTypeDE })
    }

    @Test
    fun namesThatFoldTheSameStillHaveOneOrder() {
        val a = listOf(payment("bar"), payment("Bar")).sortedForDisplay(Language.GERMAN)
        val b = listOf(payment("Bar"), payment("bar")).sortedForDisplay(Language.GERMAN)

        assertEquals(a.map { it.paymentTypeDE }, b.map { it.paymentTypeDE })
    }
}
