package org.example.project.ui.components.zBon

import org.example.project.core.domain.dto.report.ZBonDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA admin16 NOTE-2: the back office's Z-Bon view had no per-promotion breakdown although the
 * printed Z-Bon and the till's panel show it. Both panels now render [zBonPromotionLines].
 */
class ZBonPromotionLinesTest {
    private fun zBon(breakdown: List<ZBonDto.PromotionBreakdownDto>?) =
        ZBonDto.ZBonResponseDto(
            id = 166,
            zbonNummer = 154,
            shopInfoId = 1,
            shopName = "Testladen",
            employeeNumber = "M001",
            startTime = "2026-09-27T21:58",
            endTime = "2026-10-01T21:47",
            cancelledAmount = "0.00",
            totalPromotions = "2.50",
            promotionBreakdown = breakdown,
            totalRevenue = "258.52",
            openingBalance = "5440.32",
            expenses = "0.00",
            closingBalance = "5524.72",
            createdAt = "2026-10-01T21:47",
        )

    private fun promo(
        name: String,
        times: Int,
        deducted: String,
    ) = ZBonDto.PromotionBreakdownDto(
        promotionId = 1,
        promotionName = name,
        discountType = "PERCENTAGE",
        timesApplied = times,
        totalDeducted = deducted,
    )

    @Test
    fun each_promotion_becomes_a_row_with_count_and_formatted_amount_in_server_order() {
        val lines =
            zBonPromotionLines(
                zBon(listOf(promo("Rabatt Wein Abend", 3, "1.5000"), promo("Herbstfest", 1, "1000.00"))),
                "EUR",
            )

        assertEquals(
            listOf(
                ZBonPromotionLine("Rabatt Wein Abend", "3×", "1,50 €"),
                ZBonPromotionLine("Herbstfest", "1×", "1.000,00 €"),
            ),
            lines,
        )
    }

    @Test
    fun a_z_bon_without_a_breakdown_has_no_rows_so_the_section_is_hidden() {
        assertTrue(zBonPromotionLines(zBon(null), "EUR").isEmpty())
        assertTrue(zBonPromotionLines(zBon(emptyList()), "EUR").isEmpty())
    }
}
