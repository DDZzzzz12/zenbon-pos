package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.RebookFixture.SaleSpec
import org.example.project.features.bill.RebookFixture.Tender
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A Zahlartwechsel (Storno + Neubuchung) on the Z-Bon: its Storno stays in the fiscal refund figure
 * like any Storno, and is broken out as "davon Zahlartwechsel" so the operator can tell a payment
 * fix from money handed back. The Neubuchung counts as the sale, with its new tender and tip.
 */
@DisplayName("Z-Bon — davon Zahlartwechsel")
class ZBonRebookTest {
    private val deviceId = "zbon-rebook-device"
    private val fixture = RebookFixture(deviceId)

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun assertMoney(
        expected: String,
        actual: String?,
        what: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(assertNotNull(actual, what))), "$what: expected $expected, got $actual")

    /** An ordinary Storno re-opens the sale's session for re-billing — close it again so the day is closable. */
    private fun closeSessionOf(billId: Long) =
        transaction {
            val session = Bill.findById(billId)!!.tableSession
            session.completed = true
            session.endTime = currentTimeStamp()
            session.table.currentSession = null
            session.table.tableStatus = TableStatus.FREE
        }

    /** H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation. */
    private fun aliasAdvisoryLock() =
        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }

    @Test
    fun `a Zahlartwechsel Storno is a refund on the Z-Bon and is broken out as davon Zahlartwechsel`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.DISABLED)
                TestData.createAdmin()
            },
        ) { client ->
            val rebooked = fixture.closeSale(TipRecipient.EMPLOYEE)
            fixture.rebook(
                rebooked,
                BillDto.RebookRequestDto(
                    payments = listOf(PaymentDto.PaymentRequest(amount = "25.00", paymentTypeId = rebooked.cardTypeId)),
                    tips = "2.00",
                ),
            )
            val stornoed = fixture.closeSale(SaleSpec(tender = Tender.CASH, items = listOf("10.00" to 0)))
            fixture.storno(stornoed)
            closeSessionOf(stornoed.billId)
            aliasAdvisoryLock()

            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
            val zBon = testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())

            assertEquals(2, zBon.stornoBillCount, "both Stornos are Stornos")
            assertMoney("33.00", zBon.refundAmount, "fiscal refund figure: both Stornos")
            assertEquals(1, zBon.rebookStornoCount, "one of them is a Zahlartwechsel")
            assertMoney("23.00", zBon.rebookStornoAmount, "the Zahlartwechsel Storno's amount")
            val card = zBon.paymentSummary.orEmpty().single { it.paymentName == "EC${rebooked.n}" }
            assertMoney("25.00", card.amount, "card revenue of the Neubuchung")
            assertMoney("2.00", zBon.totalEmployeeTips, "the Neubuchung's tip")
        }

    @Test
    fun `a later plain Storno of the Neubuchung - the day holds no sale, one of two Stornos is a Zahlartwechsel`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.DISABLED)
                TestData.createAdmin()
            },
        ) { client ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val neubuchung =
                fixture.rebook(
                    sale,
                    BillDto.RebookRequestDto(
                        payments = listOf(PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = sale.cashTypeId)),
                        tips = "0.00",
                    ),
                )
            fixture.storno(sale.copy(billId = neubuchung.billId))
            closeSessionOf(neubuchung.billId)
            aliasAdvisoryLock()

            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
            val zBon = testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())

            assertEquals(2, zBon.stornoBillCount, "the Zahlartwechsel Storno and the plain Storno of the Neubuchung")
            assertMoney("46.00", zBon.refundAmount, "fiscal refund figure: both Stornos")
            assertEquals(1, zBon.rebookStornoCount, "only the first is a Zahlartwechsel")
            assertMoney("23.00", zBon.rebookStornoAmount, "the Zahlartwechsel Storno's amount")
            assertMoney("0.00", zBon.totalRevenue, "nothing of the sale stands")
        }

    /** A FIXED 2.00 bill-wide promotion, valid for the next minute. */
    private fun seedFixedPromotion(): Long {
        val author = TestData.createAdmin(employeeNumber = "ZBP_A", username = "zbp_a")
        return transaction {
            val now = currentTimeStamp()
            Promotion
                .new {
                    this.name = "Happy Hour"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("2.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.combinable = true
                    this.validFrom = now
                    this.validUntil = now.toJavaLocalDateTime().plusSeconds(60).toKotlinLocalDateTime()
                    this.isActive = true
                    this.createdBy = Employee.findById(author.id)!!
                    this.createdAt = now
                }.id.value
        }
    }

    private suspend fun closeDay(client: HttpClient): ZBonDto.ZBonResponseDto {
        aliasAdvisoryLock()
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
        return testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())
    }

    @Test
    fun `a rebooked promotion counts once - the guest got it once`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.DISABLED)
                TestData.createAdmin()
            },
        ) { client ->
            val promotionId = seedFixedPromotion()
            val sale = fixture.closeSale(SaleSpec(paid = "21.00", promotionIds = listOf(promotionId)))
            fixture.rebook(
                sale,
                BillDto.RebookRequestDto(
                    payments = listOf(PaymentDto.PaymentRequest(amount = "21.00", paymentTypeId = sale.cashTypeId)),
                    tips = "0.00",
                ),
            )

            val zBon = closeDay(client)

            val row = zBon.promotionBreakdown.orEmpty().single { it.promotionId == promotionId }
            assertEquals(1, row.timesApplied, "the stornoed original's promotion is not a second grant")
            assertMoney("2.00", row.totalDeducted, "one discount of 2.00")
            assertMoney("2.00", zBon.totalPromotions, "Aktionen gesamt")
        }

    @Test
    fun `an ordinary Storno of a promotion sale leaves no promotion granted`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.DISABLED)
                TestData.createAdmin()
            },
        ) { client ->
            val promotionId = seedFixedPromotion()
            val sale = fixture.closeSale(SaleSpec(paid = "21.00", promotionIds = listOf(promotionId)))
            fixture.storno(sale)
            closeSessionOf(sale.billId)

            val zBon = closeDay(client)

            assertEquals(
                emptyList(),
                zBon.promotionBreakdown.orEmpty().filter { it.promotionId == promotionId },
                "a reversed sale granted nothing",
            )
            assertMoney("0.00", zBon.totalPromotions, "Aktionen gesamt")
        }
}
