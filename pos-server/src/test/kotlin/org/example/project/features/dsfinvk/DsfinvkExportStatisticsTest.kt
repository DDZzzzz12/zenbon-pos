package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.domain.service.DsfinvkExportService
import org.example.project.features.zBon.domain.models.VatGroup
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * admin17 NOTE-12: the DSFinV-K "Vorschau" showed Gesamtumsatz 287,90 beside a Z-Bon whose Umsatz was
 * 258,52 — it re-summed Σ billAmount + owner tips over every bill of the closing, so the 29,38 € of
 * Rabatte were still in it. It now reads the closings' own stored figures.
 */
@DisplayName("DSFinV-K preview totals = the closings' stored figures")
class DsfinvkExportStatisticsTest {
    private fun seed() {
        val shop = TestData.createShopInfo()
        val admin = TestData.createAdmin()
        val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
        val tws = TestData.createTableWithSession("DSF-STAT")
        val zBon = TestData.createZBonFullDay(shop, admin)
        val now = currentTimeStamp()
        transaction {
            zBon.totalPosRevenue = BigDecimal("258.5200")
            zBon.adminTip = BigDecimal("4.0000")
            VatGroup.new {
                this.vat = vat
                this.netAmount = BigDecimal("217.2400")
                this.vatAmount = BigDecimal("41.2800")
                this.grossAmount = BigDecimal("258.5200")
                this.zBon = zBon
            }
            // The bill behind it: 283,90 billed, 29,38 promotions, 4,00 owner tip. Σ billAmount +
            // owner tip (the old figure) would be 287,90.
            Bill.new {
                this.employeeId = tws.session.employeeId
                this.tableSession = tws.session
                this.billNumber = "DSF-1"
                this.billAmount = BigDecimal("283.9000")
                this.promotionAmount = BigDecimal("29.3800")
                this.tips = BigDecimal("4.0000")
                this.tipRecipient = TipRecipient.OWNER
                this.paidAmount = BigDecimal("258.5200")
                this.startTime = now
                this.endTime = now
                this.isTraining = false
                this.zBon = zBon
            }
        }
    }

    @Test
    fun `the preview's Gesamtumsatz and MwSt are the closings' Umsatz and VAT groups`() =
        testApp(setupData = { seed() }) { _ ->
            val today = currentTimeStamp().date.toString()
            val stats = runBlocking { GlobalContext.get().get<DsfinvkExportService>().getExportStatistics(today, today) }

            assertEquals(1, stats.totalZBons)
            assertEquals(0, BigDecimal("258.52").compareTo(BigDecimal(stats.totalRevenue)), "Z-Bon Umsatz, got ${stats.totalRevenue}")
            assertEquals(0, BigDecimal("41.28").compareTo(BigDecimal(stats.totalVatAmount)), "Z-Bon VAT groups, got ${stats.totalVatAmount}")
        }
}
