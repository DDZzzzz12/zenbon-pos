package org.example.project.features.dsfinvk

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA finding NF-3 (rt44): on a shop with no designated signing till every Z-Bon leaves one Kassenbuch
 * aggregate row (`NOT_APPLICABLE`, no TSE transaction). The export drops that row
 * (`!isZbonAggregate`) but the pre-export check counted it as a missing TSE signature, so every
 * closing warned "1 transactions are missing TSE signatures (1 cash book entries)" about a signature
 * that never existed. A real Kassenbuch entry without a signature must still be counted.
 */
@DisplayName("DSFinV-K validation — the Z-Bon aggregate row is not a missing signature (NF-3)")
class DsfinvkValidateAggregateRowTest {
    @Test
    fun `an unsigned Z-Bon aggregate row is not reported as a missing TSE signature`() =
        testAppWithClosing(aggregate = true) { client ->
            val result = validate(client)

            assertEquals(0, result.missingTseCount, result.toString())
            assertTrue(result.warnings.none { it.contains("missing TSE signatures") }, result.warnings.toString())
        }

    @Test
    fun `a real Kassenbuch entry without a TSE transaction is still counted`() =
        testAppWithClosing(aggregate = false) { client ->
            val result = validate(client)

            assertEquals(1, result.missingTseCount, result.toString())
            assertTrue(result.warnings.any { it.contains("1 cash book entries") }, result.warnings.toString())
        }

    private suspend fun validate(client: HttpClient): DsfinvkExportDto.ValidationResultDto {
        val token = TestAuthHelper.loginAsAdmin(client)
        val response = client.get("/api/v1/dsfinvk/validate?startDate=2026-09-25&endDate=2026-09-25") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(response.bodyAsText())
    }

    private fun testAppWithClosing(
        aggregate: Boolean,
        block: suspend (HttpClient) -> Unit,
    ) = testApp(
        setupData = {
            transaction {
                val now = currentTimeStamp()
                Terminal.new {
                    deviceId = "unknown"
                    clientId = "TERM-TEST"
                    registeredAt = now
                    lastSeenAt = now
                }
                val admin = Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()
                val shop = TestData.createShopInfo()
                val z1 =
                    ZBon.new {
                        this.shopInfo = shop
                        this.employee = admin
                        this.startTime = LocalDateTime(2026, 9, 25, 8, 0)
                        this.endTime = LocalDateTime(2026, 9, 25, 21, 0)
                        this.cancelledAmount = BigDecimal.ZERO
                        this.totalDiscounts = BigDecimal.ZERO
                        this.totalPosRevenue = BigDecimal.ZERO
                        this.adminTip = BigDecimal.ZERO
                        this.openingBalance = BigDecimal.ZERO
                        this.expenses = BigDecimal.ZERO
                        this.cashRevenue = BigDecimal.ZERO
                        this.closingBalance = BigDecimal.ZERO
                        this.createdAt = LocalDateTime(2026, 9, 25, 21, 0)
                        this.trainingRevenue = BigDecimal.ZERO
                        this.trainingBillCount = 0
                        this.refundAmount = BigDecimal.ZERO
                        this.stornoBillCount = 0
                        this.zbonNummer = 1
                    }
                KassenbuchHeader.new {
                    this.shop = shop
                    this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                    this.employee = admin
                    this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                    this.belegNr = "2026-09-0001"
                    this.belegDatum = "25.09.2026"
                    this.belegtext = if (aggregate) "ZBon Nr. 1" else "Einlage"
                    this.waehrung = "EUR"
                    this.totalAmount = BigDecimal("5.00")
                    this.currentBalance = BigDecimal("5.00")
                    this.createdAt = LocalDateTime(2026, 9, 25, 21, 0)
                    this.isLocked = true
                    this.zBon = z1
                    this.isZbonAggregate = aggregate
                    this.tseSigningStatus = if (aggregate) TseSigningStatus.NOT_APPLICABLE else TseSigningStatus.PENDING
                }
            }
        },
    ) { client -> block(client) }
}
