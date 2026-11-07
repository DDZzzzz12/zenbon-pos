package org.example.project.features.zBon

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.service.ShopInfoServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.CashScope
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
import org.example.project.features.zBon.domain.OpenBusinessCheck
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-7 C9 — [OpenBusinessCheck] reasoned only over bills, so a booked Kassenbuch Vorgang
 * (Privateinlage, Barausgabe, Geldtransit …) did not count as open business.
 *
 * DSFinV-K Tz. 3.2 (p.23): „Werden Änderungen an den … Stammdaten vorgenommen, ist zuvor
 * automatisch ein Abschluss zu erstellen." A Kassenbuch entry is recorded cash movement under the
 * Stammdaten identity in force when it was booked, exactly as a bill is. Pre-fix, a shop that had
 * booked only Kassenbuch entries since its last closing could rewrite its fiscal identity with no
 * Kassenabschluss, and that turnover would then export under the new identity.
 *
 * The predicate has **two callers with opposite polarity**, so every test here fixes one of them:
 *
 *  * [ShopInfoServiceImpl.updateShopInfo] refuses a fiscal edit while something IS open, and
 *  * `ZBonServiceImpl.createReport` refuses a closing while NOTHING is open
 *    (covered by `ZBonCreateReportKassenbuchOnlyTest`, which needs the HTTP stack).
 *
 * **The predicate is `isLocked == false`, not `zBonId == null`.** Both columns are written in the
 * same sweep in `ZBonServiceImpl.createReport` and are equivalent in practice, but `isLocked` is
 * the flag the closing itself asserts as its own post-condition (`unLockedCount > 0` → reject).
 * That post-condition is what makes the KB predicate need **no time window**: a successful closing
 * leaves zero unlocked entries anywhere, so any unlocked entry is necessarily younger than the
 * last closing. Bills need the explicit `startTime > previousEnd` window precisely because an
 * unassigned pre-boundary bill IS reachable there (a data defect for the repair endpoint), and
 * treating it as open business would deadlock both callers.
 */
@DisplayName("OpenBusinessCheck — Kassenbuch entries are open business (C9)")
class OpenBusinessCheckTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // ------------------------------------------------------------ the predicate

    @Test
    fun `an unlocked Kassenbuch entry is open business`() {
        seedShop()
        seedKassenbuchEntry()

        assertTrue(
            transaction { OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS) },
            "a booked Kassenbuch Vorgang not yet swept into a Kassenabschluss is open business",
        )
    }

    @Test
    fun `a locked Kassenbuch entry is NOT open business`() {
        seedShop()
        val id = seedKassenbuchEntry()
        transaction { KassenbuchHeader.findById(id)!!.isLocked = true }

        assertFalse(
            transaction { OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS) },
            "an entry already swept into a closing must not keep the shop permanently open",
        )
    }

    @Test
    fun `an empty cash book with no bills is NOT open business`() {
        seedShop()

        assertFalse(
            transaction { OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS) },
            "widening the predicate must not make every shop permanently open",
        )
    }

    @Test
    fun `an unassigned bill is still open business`() {
        // The bill arm must survive the widening untouched.
        seedShop()
        seedOpenBill()

        assertTrue(transaction { OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS) })
    }

    // -------------------------------------------- ShopInfo direction (blocks the edit)

    @Test
    fun `a fiscal shop edit is rejected while only a Kassenbuch entry is open`() {
        val shopId = seedShop()
        seedKassenbuchEntry()

        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard()).updateShopInfo(
                        employeeId = adminId(),
                        shopId = shopId,
                        request = ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH"),
                    )
                }
            }
        assertEquals(PosErrorCode.SHOP_INFO_CLOSING_REQUIRED, ex.code)
        assertEquals(
            "Alt GmbH",
            transaction { ShopInfo.findById(shopId)!!.shopName },
            "the rejected edit must not have been applied",
        )
        Unit
    }

    @Test
    fun `a fiscal shop edit is allowed once the Kassenbuch entry is locked`() {
        val shopId = seedShop()
        val id = seedKassenbuchEntry()
        transaction { KassenbuchHeader.findById(id)!!.isLocked = true }

        runBlocking {
            ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard()).updateShopInfo(
                employeeId = adminId(),
                shopId = shopId,
                request = ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH"),
            )
        }
        assertEquals("Neu GmbH", transaction { ShopInfo.findById(shopId)!!.shopName })
    }

    @Test
    fun `a Stammdaten edit blocked by a Tresor names the Kassensturz, not a Z-Bon (T9-11)`() {
        // A Z-Bon locks only the Ladenkasse. Telling the operator to "create a Z-Bon" for an open
        // Tresor sent them to an action that cannot clear the block.
        val shopId = seedShop()
        seedKassenbuchEntry(tresorName = "Tresor")

        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard()).updateShopInfo(
                        employeeId = adminId(),
                        shopId = shopId,
                        request = ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH"),
                    )
                }
            }
        assertEquals(PosErrorCode.SHOP_INFO_CLOSING_REQUIRED, ex.code)
        assertTrue(ex.message!!.contains("Kassensturz of 'Tresor'"), ex.message)
        assertFalse(ex.message!!.contains("(Z-Bon) first"), ex.message)
    }

    // ---------------------------------------------------------------- helpers

    private fun seedShop(): Long =
        transaction {
            Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()
            TestData.createShopInfo(shopName = "Alt GmbH").id.value
        }

    private fun adminId(): String =
        transaction {
            (Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin())
                .id.value
                .toString()
        }

    /** One ordinary operator-booked Kassenbuch Vorgang, not yet swept into any closing. */
    private fun seedKassenbuchEntry(tresorName: String? = null): Long =
        transaction {
            val admin = Employee.find { EmployeeTable.username eq "admin" }.first()
            val shop = ShopInfo.all().first()
            val books = CashBookRepositoryImpl()
            val book =
                tresorName?.let { books.create(shop, it, isFiscal = false, datevAccount = "1010") }
                    ?: books.fiscalBookFor(shop)
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

    private fun seedOpenBill() =
        transaction {
            val admin = Employee.find { EmployeeTable.username eq "admin" }.first()
            val tws = TestData.createTableWithSession("OPEN")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "OPEN-1"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
            }
            Unit
        }
}
