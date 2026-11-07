package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Z-Bon closing ignores non-fiscal cash books")
class ZBonNonFiscalExclusionTest {
    private companion object {
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"
        const val TRESOR_BELEG = "2026-07-9001"
    }

    @Test
    fun `the closing excludes Tresor cash and leaves its entries unlocked`() =
        testAppWithKasseAndTresor { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "the post-condition must not trip on the unlocked Tresor entry: ${response.bodyAsText()}",
            )

            transaction {
                val report = ZBon.all().first()

                // 100.00 from the Ladenkasse only — the 500.00 in the safe is not till cash and
                // must not appear in the Kassenbestand reported through DSFinV-K.
                assertEquals(
                    0,
                    BigDecimal("100.00").compareTo(report.cashRevenue),
                    "cashRevenue must exclude the Tresor deposit, got ${report.cashRevenue}",
                )

                val tresorEntry = KassenbuchHeader.all().first { it.belegNr == TRESOR_BELEG }
                assertFalse(tresorEntry.isLocked, "a register closing must not close the safe book")
                assertNull(tresorEntry.zBon, "a Tresor entry belongs to no Z-Bon")

                val fiscalEntries = KassenbuchHeader.all().filter { it.cashBook.isFiscal }
                assertTrue(
                    fiscalEntries.all { it.isLocked },
                    "every fiscal entry must still be swept and locked",
                )
            }
        }

    @Test
    fun `the Z-Bon aggregate entry lands in the fiscal book`() =
        testAppWithKasseAndTresor { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            client.post("/api/z-bon") { bearerAuth(token) }

            transaction {
                val aggregate = KassenbuchHeader.all().first { it.isZbonAggregate }
                assertTrue(
                    aggregate.cashBook.isFiscal,
                    "the closing's own aggregate entry is register turnover, not safe cash",
                )
            }
        }

    // ---------------------------------------------------------------- fixture

    private fun testAppWithKasseAndTresor(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        org.example.project.testutils.testApp(
            setupData = {
                transaction {
                    exec(ADVISORY_LOCK_ALIAS)
                    val now = currentTimeStamp()
                    Terminal.new {
                        deviceId = "unknown"
                        clientId = "TERM-TEST"
                        registeredAt = now
                        lastSeenAt = now
                    }
                    val admin =
                        Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                            ?: TestData.createAdmin()
                    val shop = if (ShopInfo.all().empty()) TestData.createShopInfo() else ShopInfo.all().first()

                    val books = CashBookRepositoryImpl()
                    val kasse = books.fiscalBookFor(shop)
                    val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")

                    KassenbuchHeader.new {
                        this.shop = shop
                        this.employee = admin
                        this.cashBook = kasse
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = "2026-07-0001"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Einlage Ladenkasse"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("100.00")
                        this.currentBalance = BigDecimal("100.00")
                        this.createdAt = now
                    }
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.employee = admin
                        this.cashBook = tresor
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = TRESOR_BELEG
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Einlage Tresor"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("500.00")
                        this.currentBalance = BigDecimal("500.00")
                        this.createdAt = now
                    }
                }
            },
        ) { client -> block(client) }
}
