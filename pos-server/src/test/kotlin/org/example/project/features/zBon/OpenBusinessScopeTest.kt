package org.example.project.features.zBon

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.CashScope
import org.example.project.features.zBon.domain.OpenBusinessCheck
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 13 — [OpenBusinessCheck] scope split by [CashScope].
 *
 * The predicate's two callers have opposite polarity AND, since cash books were introduced,
 * genuinely different scope: `ZBonServiceImpl.createReport` (FISCAL_ONLY) must not let a Tresor
 * entry justify an empty closing, while `ShopInfoServiceImpl.updateShopInfo` (ALL_BOOKS) must
 * still block a Stammdaten edit while a Tresor period is unclosed (DSFinV-K Tz. 3.2, round-7 C9).
 */
@DisplayName("OpenBusinessCheck — scope split by CashScope (Task 13)")
class OpenBusinessScopeTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `an open Tresor entry alone is not fiscal open business`() {
        seedShop()
        seedTresorEntry()

        assertFalse(
            transaction { OpenBusinessCheck.hasOpenBusiness(CashScope.FISCAL_ONLY) },
            "a Tresor entry is not business a Z-Bon can close",
        )
        assertTrue(
            transaction { OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS) },
            "an unclosed Tresor period must still block a Stammdaten edit",
        )
    }

    @Test
    fun `blockingBook names the Tresor`() {
        seedShop()
        seedTresorEntry()

        assertEquals(
            "Tresor",
            transaction { OpenBusinessCheck.blockingBook()?.name },
        )
    }

    @Test
    fun `blockingBook is null when everything is closed`() {
        seedShop()

        assertEquals(
            null,
            transaction { OpenBusinessCheck.blockingBook() },
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun seedShop(): Long =
        transaction {
            Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()
            TestData.createShopInfo(shopName = "Alt GmbH").id.value
        }

    /** A Tresor entry: booked on a non-fiscal cash book, not yet swept into any closing. */
    private fun seedTresorEntry(): Long =
        transaction {
            val admin = Employee.find { EmployeeTable.username eq "admin" }.first()
            val shop = ShopInfo.all().first()
            val tresor = CashBookRepositoryImpl().create(shop, name = "Tresor", isFiscal = false, datevAccount = "1990")
            KassenbuchHeader
                .new {
                    this.shop = shop
                    this.cashBook = tresor
                    this.employee = admin
                    this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                    this.belegNr = "2026-07-0001"
                    this.belegDatum = "19.07.2026"
                    this.belegtext = "Tresor-Einlage"
                    this.waehrung = "EUR"
                    this.totalAmount = BigDecimal("100.00")
                    this.currentBalance = BigDecimal("100.00")
                    this.createdAt = currentTimeStamp()
                }.id.value
        }
}
