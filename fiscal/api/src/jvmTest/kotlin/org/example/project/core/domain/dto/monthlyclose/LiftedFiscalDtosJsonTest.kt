package org.example.project.core.domain.dto.monthlyclose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wire form of `DocumentSummaryDto`, `VatSummaryDto` and `PaymentMethodSummaryDto` inside the fiscal containers, which
 * were lifted out of `DocumentDto` / `StatisticsDto` (the nesting is not on the wire). The test
 * names only the containers, so it is identical before and after the lift.
 */
class LiftedFiscalDtosJsonTest {
    private val json = Json { encodeDefaults = true }

    private fun reencode(sub: String): JsonObject = json.parseToJsonElement(sub).jsonObject

    @Test
    fun test_lifted_dtos_json_unchanged_fiscal() {
        val kassenbuch =
            json.decodeFromString(
                KassenbuchDto.KassenbuchGroupedDetailDto.serializer(),
                """{"headerId":1,"belegNr":"B1","entryType":"${firstEntryType()}","belegtext":"t","belegDatum":"01.01.2026","waehrung":"EUR","totalAmount":"1.00","currentBalance":"2.00","employeeNumber":"E1","isLocked":false,"createdAt":"2026-01-01T00:00:00","bu":null,"gegenkonto":null,"kost1":null,"kost2":null,"nachricht":null,"tseTransactionId":null,"tseSignature":null,"tseTransactionNumber":null,"tseSignedAt":null,"lineItems":[],"hasLinkedDocuments":true,"linkedDocumentCount":1,"linkedDocument":{"id":7,"filename":"a.pdf","downloadUrl":"/d/7"}}""",
            )
        val kassenbuchOut = json.encodeToString(KassenbuchDto.KassenbuchGroupedDetailDto.serializer(), kassenbuch)
        assertEquals(
            """{"id":7,"filename":"a.pdf","downloadUrl":"/d/7"}""",
            reencode(kassenbuchOut)["linkedDocument"].toString(),
        )

        val monthly =
            json.decodeFromString(
                MonthlyCloseDto.MonthlyCloseResponseDto.serializer(),
                """{"periodStart":"s","periodEnd":"e","kpis":[],"previousPeriodStart":null,"previousPeriodEnd":null,"vatBreakdown":{"19":{"vatRate":"19","netAmount":"10.00","vatAmount":"1.90","totalAmount":"11.90","transactionCount":3}},"paymentBreakdown":{"Bar":{"paymentMethod":"Bar","paymentMethodEN":"Cash","totalAmount":"11.90","transactionCount":3,"revenuePercentage":"100.00","averageTransactionValue":"3.97"}},"zBonRollup":{"rows":[],"totals":{"zBonId":0,"zBonNumber":"","startTime":"","endTime":"","grossRevenue":"0","netRevenue":"0","vatTotal":"0","tips":"0","refunds":"0","cancellations":"0","paymentMethodTotals":[]},"chainGaps":[]},"kassenbuchRollup":{"openingBalance":"0","closingBalance":"0","groups":[]},"checklist":[],"exportStatus":{"badgeStatus":"NOT_EXPORTED","lastExportedAt":null,"log":[]}}""",
            )
        val monthlyOut = reencode(json.encodeToString(MonthlyCloseDto.MonthlyCloseResponseDto.serializer(), monthly))
        assertEquals(
            """{"19":{"vatRate":"19","netAmount":"10.00","vatAmount":"1.90","totalAmount":"11.90","transactionCount":3}}""",
            monthlyOut["vatBreakdown"].toString(),
        )
        assertEquals(
            """{"Bar":{"paymentMethod":"Bar","paymentMethodEN":"Cash","paymentMethodCH":null,"totalAmount":"11.90","transactionCount":3,"revenuePercentage":"100.00","averageTransactionValue":"3.97"}}""",
            monthlyOut["paymentBreakdown"].toString(),
        )
    }

    private fun firstEntryType(): String =
        org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType.entries
            .first()
            .name
}
