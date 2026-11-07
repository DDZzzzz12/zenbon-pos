package org.example.project.core.domain.dto.statistics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the wire form of `VatSummaryDto` / `PaymentMethodSummaryDto` inside `FinancialOverviewResponseDto`. */
class LiftedStatisticsDtosJsonTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun test_lifted_dtos_json_unchanged_statistics() {
        val financial =
            json.decodeFromString(
                StatisticsDto.FinancialOverviewResponseDto.serializer(),
                """{"period":"p","currency":"EUR","grossRevenue":"1","netRevenue":"1","totalRefunds":"0","vatBreakdown":{"7":{"vatRate":"7","netAmount":"1.00","vatAmount":"0.07","totalAmount":"1.07","transactionCount":1}},"paymentMethodBreakdown":{"Karte":{"paymentMethod":"Karte","totalAmount":"1.07","transactionCount":1,"revenuePercentage":"100.00","averageTransactionValue":"1.07"}},"cashFlow":{"openingBalance":"0","cashSales":"0","expenses":"0","cashRefunds":"0","netCashFlow":"0","closingBalance":"0"},"dailyFinancialSummary":{}}""",
            )
        val financialOut = json.parseToJsonElement(json.encodeToString(StatisticsDto.FinancialOverviewResponseDto.serializer(), financial)).jsonObject
        assertEquals(
            """{"7":{"vatRate":"7","netAmount":"1.00","vatAmount":"0.07","totalAmount":"1.07","transactionCount":1}}""",
            financialOut["vatBreakdown"].toString(),
        )
        assertEquals(
            """{"Karte":{"paymentMethod":"Karte","paymentMethodEN":null,"paymentMethodCH":null,"totalAmount":"1.07","transactionCount":1,"revenuePercentage":"100.00","averageTransactionValue":"1.07"}}""",
            financialOut["paymentMethodBreakdown"].toString(),
        )
    }
}
