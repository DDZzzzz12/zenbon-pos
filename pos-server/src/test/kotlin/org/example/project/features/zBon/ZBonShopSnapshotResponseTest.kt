package org.example.project.features.zBon

import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.mapper.toResponseDto
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * `GET /api/z-bon/{id}` — and the PDF re-render and reprint, which are built from the same
 * [org.example.project.core.domain.dto.report.ZBonDto.ZBonResponseDto] — must show the shop identity
 * the closing was TAKEN under, not today's `shop_info` row (E2E 2026-09-20 Low list).
 *
 * The closing already stores that identity (audit KABS-2, `StammdatenSnapshot`) and the DSFinV-K
 * export already reads it; the response mapper did not, so after a shop edit a historic Z-Bon
 * showed the new address while its export showed the old one.
 */
@DisplayName("Z-Bon response carries the closing's shop snapshot")
class ZBonShopSnapshotResponseTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun admin(): Employee = Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()

    /** A closing written by the real producer, which snapshots the shop's Stammdaten. */
    private fun closingViaProducer(): Long =
        transaction {
            val shop =
                TestData.createShopInfo(
                    shopName = "Alt GmbH",
                    shopAddress = "Hauptstr. 5",
                    shopTaxNumber = "143/815/09876",
                )
            TestData.createTableWithSession("SNAP")
            ZBonRepositoryImpl().createReport(employee = admin(), shopInfo = shop).id.value
        }

    private fun moveShop() =
        transaction {
            val shop = ShopInfo.all().first()
            shop.shopName = "Neu GmbH"
            shop.shopAddress = "Neue Allee 99"
            shop.shopCity = "Neustadt"
            shop.shopTaxNumber = "999/999/99999"
        }

    @Test
    fun `a snapshotted closing keeps the identity it was taken under after the shop moves`() {
        val id = closingViaProducer()
        moveShop()

        val dto = transaction { ZBon.findById(id)!!.toResponseDto("EUR") }

        assertEquals("Alt GmbH", dto.shopName)
        assertEquals("Hauptstr. 5", dto.shopAddress)
        assertEquals("143/815/09876", dto.shopTaxNumber)
    }

    @Test
    fun `a closing without a snapshot falls back to the live shop row`() {
        val id =
            transaction {
                val shop = TestData.createShopInfo(shopName = "Alt GmbH", shopAddress = "Hauptstr. 5")
                val now = currentTimeStamp()
                ZBon
                    .new {
                        this.shopInfo = shop
                        this.employee = admin()
                        this.startTime = now
                        this.endTime = now
                        this.cancelledAmount = BigDecimal.ZERO
                        this.totalDiscounts = BigDecimal.ZERO
                        this.totalPosRevenue = BigDecimal.ZERO
                        this.adminTip = BigDecimal.ZERO
                        this.openingBalance = BigDecimal.ZERO
                        this.expenses = BigDecimal.ZERO
                        this.cashRevenue = BigDecimal.ZERO
                        this.closingBalance = BigDecimal.ZERO
                        this.createdAt = now
                        this.trainingRevenue = BigDecimal.ZERO
                        this.trainingBillCount = 0
                        this.refundAmount = BigDecimal.ZERO
                        this.stornoBillCount = 0
                        this.zbonNummer = 1L
                    }.id.value
            }
        moveShop()

        val dto = transaction { ZBon.findById(id)!!.toResponseDto("EUR") }

        // Nothing was recorded for this closing, so the live row is the only identity there is.
        assertEquals("Neu GmbH", dto.shopName)
        assertEquals("Neue Allee 99", dto.shopAddress)
        assertEquals("Neustadt", dto.shopCity)
    }
}
