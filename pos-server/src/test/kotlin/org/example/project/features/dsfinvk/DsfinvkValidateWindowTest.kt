package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertTrue

/**
 * Round-3 CONT-2: /validate uses the SAME half-open selection window as /export. A closing
 * created in the final sub-second of the end date must be counted (the old inclusive
 * atTime(23,59,59) bound silently dropped it, so validate said "no Z-reports" for an export
 * that would in fact ship one).
 */
@DisplayName("DSFinV-K validate window parity (round-3 CONT-2)")
class DsfinvkValidateWindowTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `closing at 23-59-59-5 of the end date is counted by validate`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            ZBon.new {
                this.shopInfo = shop
                this.employee = admin
                this.startTime = LocalDateTime(2026, 7, 13, 8, 0)
                this.endTime = LocalDateTime(2026, 7, 13, 23, 59, 59, 500_000_000)
                this.cancelledAmount = BigDecimal.ZERO
                this.totalDiscounts = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = LocalDateTime(2026, 7, 13, 23, 59, 59, 500_000_000)
                this.trainingRevenue = BigDecimal.ZERO
                this.trainingBillCount = 0
                this.refundAmount = BigDecimal.ZERO
                this.stornoBillCount = 0
                this.zbonNummer = 1L
            }
        }

        val result = runBlocking { service.validateExportData("2026-07-13", "2026-07-13") }
        assertTrue(
            result.errors.none { it.contains("No Z-reports") },
            "the sub-second closing must be counted by validate; got errors=${result.errors}",
        )
    }
}
