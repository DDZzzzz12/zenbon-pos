package org.example.project.core.utils

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Report bill rows, the payments sheet and the bill detail name a payment type in the UI language
 * (UI review follow-up 6), through one rule for the three payment DTOs: the translation, else the
 * German master name.
 */
class PaymentLocalizedNameTest {
    private fun summary(
        en: String? = "Cash",
        zh: String? = "现金",
    ) = PaymentDto.PaymentSummary(amount = "1.00", paymentTypeDE = "Bar", paymentTypeEN = en, paymentTypeCH = zh)

    private fun response(
        en: String? = "Cash",
        zh: String? = "现金",
    ) = PaymentDto.PaymentResponse(
        id = 1,
        amount = "1.00",
        paymentTypeId = 1,
        paymentTypeName = "Bar",
        paymentTypeEN = en,
        paymentTypeCH = zh,
        billId = 1,
        createdAt = "2026-10-16T12:00:00",
    )

    private fun record(
        en: String? = "Cash",
        zh: String? = "现金",
    ) = StatisticsDto.PaymentRecordDto(
        id = 1,
        billId = 1,
        billNumber = "R-1",
        paymentTypeDE = "Bar",
        paymentTypeEN = en,
        paymentTypeCH = zh,
        amount = "1.00",
        currency = "EUR",
        paymentStatus = PaymentStatus.COMPLETED,
        paymentTime = "2026-10-16T12:00:00",
    )

    private fun methodSummary(
        en: String? = "Cash",
        zh: String? = "现金",
    ) = PaymentMethodSummaryDto(
        paymentMethod = "Bar",
        paymentMethodEN = en,
        paymentMethodCH = zh,
        totalAmount = "1.00",
        transactionCount = 1,
        revenuePercentage = "100.00",
        averageTransactionValue = "1.00",
    )

    @Test
    fun everyPaymentDtoIsNamedInTheUiLanguage() {
        for (language in Language.entries) {
            val expected =
                when (language) {
                    Language.ENGLISH -> "Cash"
                    Language.GERMAN -> "Bar"
                    Language.CHINESE -> "现金"
                }
            assertEquals(expected, summary().getLocalizedName(language), "summary, $language")
            assertEquals(expected, response().getLocalizedName(language), "bill payment, $language")
            assertEquals(expected, record().getLocalizedName(language), "payment record, $language")
            assertEquals(expected, methodSummary().getLocalizedName(language), "financial overview row, $language")
        }
    }

    @Test
    fun aMissingTranslationFallsBackToTheGermanName() {
        assertEquals("Bar", summary(en = null).getLocalizedName(Language.ENGLISH))
        assertEquals("Bar", response(zh = null).getLocalizedName(Language.CHINESE))
        assertEquals("Bar", record(en = null).getLocalizedName(Language.ENGLISH))
        assertEquals("Bar", methodSummary(zh = null).getLocalizedName(Language.CHINESE))
    }

    /** Follow-up 7: a financial overview from a server without the two translation fields still decodes. */
    @Test
    fun aFinancialOverviewRowFromAnOlderServerDecodesAndShowsTheGermanName() {
        val json =
            "{\"paymentMethod\":\"Bar\",\"totalAmount\":\"1.00\",\"transactionCount\":1," +
                "\"revenuePercentage\":\"100.00\",\"averageTransactionValue\":\"1.00\"}"
        val row = Json.decodeFromString(PaymentMethodSummaryDto.serializer(), json)
        assertEquals(null, row.paymentMethodEN)
        assertEquals("Bar", row.getLocalizedName(Language.ENGLISH))
        assertEquals("Bar", row.getLocalizedName(Language.CHINESE))
    }

    @Test
    fun aBlankTranslationFallsBackToTheGermanNameRatherThanShowingNothing() {
        assertEquals("Bar", summary(en = " ").getLocalizedName(Language.ENGLISH))
        assertEquals("Bar", response(zh = "").getLocalizedName(Language.CHINESE))
        assertEquals("Bar", record(en = "").getLocalizedName(Language.ENGLISH))
        assertEquals("Bar", methodSummary(en = "").getLocalizedName(Language.ENGLISH))
    }
}
