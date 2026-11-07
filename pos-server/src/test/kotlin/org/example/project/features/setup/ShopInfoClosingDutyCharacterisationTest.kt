package org.example.project.features.setup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.service.ShopInfoServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Characterisation of the DSFinV-K Tz. 3.2 closing duty of `ShopInfoServiceImpl.updateShopInfo`
 * (KABS-2, round-6 C5): refusal while business is open, freeze of the pre-edit Stammdaten onto
 * un-snapshotted closings BEFORE the row update, narrowness (non-fiscal edits), and ordering
 * (no partial effects after a refusal). Written before the guard was extracted into a port.
 */
@DisplayName("ShopInfo — closing duty characterisation")
class ShopInfoClosingDutyCharacterisationTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun service() = newService()

    private fun adminId(): String =
        transaction {
            (Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin())
                .id.value
                .toString()
        }

    private fun seedShop(): Long =
        transaction {
            Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()
            TestData.createShopInfo(shopName = "Alt GmbH", shopAddress = "Hauptstr. 5").id.value
        }

    private fun seedClosingWithoutSnapshot(): Long =
        transaction {
            val admin = Employee.find { EmployeeTable.username eq "admin" }.first()
            val shop = ShopInfo.all().first()
            val now = currentTimeStamp()
            ZBon
                .new {
                    this.shopInfo = shop
                    this.employee = admin
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

    /** One unlocked Kassenbuch entry in the fiscal book — open business in ALL_BOOKS scope. */
    private fun seedOpenKassenbuchEntry(): Long =
        transaction {
            val admin = Employee.find { EmployeeTable.username eq "admin" }.first()
            val shop = ShopInfo.all().first()
            val book = CashBookRepositoryImpl().fiscalBookFor(shop)
            KassenbuchHeader
                .new {
                    this.shop = shop
                    this.cashBook = book
                    this.employee = admin
                    this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                    this.belegNr = "2026-07-0001"
                    this.belegDatum = "19.07.2026"
                    this.belegtext = "Privateinlage"
                    this.waehrung = "EUR"
                    this.totalAmount = BigDecimal("100.00")
                    this.currentBalance = BigDecimal("100.00")
                    this.createdAt = currentTimeStamp()
                }.id.value
        }

    private fun update(
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ) = runBlocking { service().updateShopInfo(adminId(), shopId, request) }

    private fun shopName(id: Long) = transaction { ShopInfo.findById(id)!!.shopName }

    private fun stammName() = transaction { ZBon.all().first().stammName }

    @Test
    fun `test_shop_info_fiscal_edit_refused_while_business_open_exact_error`() {
        val shopId = seedShop()
        seedOpenKassenbuchEntry()

        val ex =
            assertFailsWith<PosException> {
                update(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH"))
            }
        assertEquals(PosErrorCode.SHOP_INFO_CLOSING_REQUIRED, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
        assertEquals(
            "Fiscally relevant shop master data cannot be changed while cash book " +
                "'${transaction { CashBookRepositoryImpl().fiscalBookFor(ShopInfo.all().first()).name }}' " +
                "has an unclosed period. Create a Kassenabschluss (Z-Bon) first — DSFinV-K Tz. 3.2.",
            ex.message,
        )
    }

    @Test
    fun `test_shop_info_fiscal_edit_freezes_old_values_before_row_update`() {
        val shopId = seedShop()
        seedClosingWithoutSnapshot()

        update(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH", shopAddress = "Neumarkt 12"))

        assertEquals("Alt GmbH", stammName(), "snapshot carries the OLD name")
        assertEquals("Hauptstr. 5", transaction { ZBon.all().first().stammStrasse })
        assertEquals("Neu GmbH", shopName(shopId), "row carries the NEW name")
    }

    @Test
    fun `test_shop_info_non_fiscal_edit_skips_check_and_freeze`() {
        val shopId = seedShop()
        seedClosingWithoutSnapshot()
        seedOpenKassenbuchEntry()

        update(shopId, ShopInfoDto.UpdateShopInfoRequestDto(logoUrl = "/logo/new.png", shopPhone = "0351 999"))

        assertEquals("/logo/new.png", transaction { ShopInfo.findById(shopId)!!.logoUrl })
        assertNull(stammName(), "no freeze for a non-fiscal edit")
    }

    /**
     * Pins "no partial effects after a refusal". It cannot distinguish check-then-freeze from
     * freeze-then-check: the whole update runs in ONE dbQuery, so a freeze made before the throw
     * would be rolled back and is invisible here. That order is pinned by code structure
     * (FiscalShopIdentityGuard.beforeUpdate) and review only.
     */
    @Test
    fun `test_shop_info_refusal_precedes_freeze_and_update`() {
        val shopId = seedShop()
        seedClosingWithoutSnapshot()
        seedOpenKassenbuchEntry()

        val ex =
            assertFailsWith<PosException> {
                update(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH", shopAddress = "Neumarkt 12"))
            }
        assertEquals(PosErrorCode.SHOP_INFO_CLOSING_REQUIRED, ex.code)
        assertNull(stammName(), "a refused edit must not freeze any snapshot")
        assertEquals("Alt GmbH", shopName(shopId), "a refused edit must not change the row")
        assertEquals("Hauptstr. 5", transaction { ShopInfo.findById(shopId)!!.shopAddress })
    }

    @Test
    fun `test_shop_info_resubmitting_current_fiscal_values_neither_checks_nor_freezes`() {
        val shopId = seedShop()
        seedClosingWithoutSnapshot()
        seedOpenKassenbuchEntry()

        update(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Alt GmbH", logoUrl = "/l.png"))

        assertNull(stammName())
        assertEquals("/l.png", transaction { ShopInfo.findById(shopId)!!.logoUrl })
    }

    private fun newService() = ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard())
}
