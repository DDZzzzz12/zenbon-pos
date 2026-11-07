package org.example.project.admin

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the JSON wire format of the money fields adminApp renders.
 *
 * The migration's central rule is that money crosses the wire as a **string**, never a number —
 * a JSON number is a double on the way in and reintroduces exactly the truncation this work
 * removed. `KassenbuchGroupedSummaryDto.totalAmount`/`currentBalance` were `Double` until this
 * change, and the route tests that cover these endpoints are status-code smoke tests over empty
 * result sets, so nothing else would notice a silent regression to a numeric field.
 *
 * Asserting on the serialized text rather than on a round trip is deliberate: a round trip alone
 * passes for a `Double` field too.
 */
class MoneyWireFormatTest {
    private val json = Json

    private fun summary() =
        KassenbuchDto.KassenbuchGroupedSummaryDto(
            headerId = 7,
            cashBookId = 1,
            belegNr = "B7",
            entryType = KassenbuchEntryType.entries.first(),
            belegtext = "Tisch 7",
            belegDatum = "2026-07-10",
            waehrung = "EUR",
            totalAmount = "16.9000",
            lineItems = emptyList(),
            currentBalance = "-1234.5678",
            employeeNumber = "E1",
            createdAt = "2026-07-10T09:00:00",
            currency = "CHF",
        )

    @Test
    fun kassenbuchGroupedSummaryCarriesMoneyAsJsonStrings() {
        val encoded = json.encodeToString(KassenbuchDto.KassenbuchGroupedSummaryDto.serializer(), summary())

        assertTrue(
            """"totalAmount":"16.9000"""" in encoded,
            "totalAmount must be a JSON string, got: $encoded",
        )
        assertTrue(
            """"currentBalance":"-1234.5678"""" in encoded,
            "currentBalance must be a JSON string, got: $encoded",
        )
    }

    @Test
    fun kassenbuchGroupedSummaryPreservesSubCentPrecisionAndCurrency() {
        val encoded = json.encodeToString(KassenbuchDto.KassenbuchGroupedSummaryDto.serializer(), summary())
        val decoded = json.decodeFromString(KassenbuchDto.KassenbuchGroupedSummaryDto.serializer(), encoded)

        // The DB column is DECIMAL(19,4); a Double field would have rendered "16.9" here.
        assertEquals("16.9000", decoded.totalAmount)
        assertEquals("-1234.5678", decoded.currentBalance)
        // The per-request display currency, distinct from the persisted `waehrung` column.
        assertEquals("CHF", decoded.currency)
        assertEquals("EUR", decoded.waehrung)
    }

    /**
     * The dashboard's four KPI cards (revenue, tips, cancelled, open items) read their currency
     * from this root. It was the one money-bearing response root that did not carry one, so the
     * cards rendered bare decimals with no symbol.
     */
    @Test
    fun salesOverviewCarriesTheCurrencyAndStringAmounts() {
        val dto =
            StatisticsDto.SalesOverviewResponseDto(
                period = "2026-07-10",
                totalRevenue = "1240.50",
                billedRevenue = "1240.50",
                canceledRevenue = "62.00",
                openItemAmount = "120.00",
                totalTips = "86.00",
                currency = "CHF",
            )
        val encoded = json.encodeToString(StatisticsDto.SalesOverviewResponseDto.serializer(), dto)

        assertTrue(""""totalRevenue":"1240.50"""" in encoded, "totalRevenue must be a JSON string, got: $encoded")
        assertTrue(""""currency":"CHF"""" in encoded, "the response root must carry a currency, got: $encoded")
        assertEquals(
            "CHF",
            json.decodeFromString(StatisticsDto.SalesOverviewResponseDto.serializer(), encoded).currency,
        )
    }
}
