package org.example.project.features.kassenbuch

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The drawer physically holds the book balance PLUS the day's cash takings, because cash sales
 * reach the Kassenbuch only as the Z-Bon's CASH_SALE aggregate — booked by the closing, AFTER the
 * count. A count that compares the drawer against the bare book balance therefore books the
 * unbooked takings as a phantom CASH_SURPLUS on every honest closing, signs it on the TSE, and the
 * closing's Geldtransit then moves takings PLUS phantom to the safe. Found on the 2026-09-11
 * upgrade rehearsal: 67.00 of "surplus" on a day with no surplus at all.
 *
 * The expected figure must be `book + unbooked cash takings`, derived by the SAME rule the closing
 * uses for its aggregate, so count and closing can never disagree about what the drawer holds.
 */
@DisplayName("POST /api/v1/kassenbuch/cash-count counts against book balance plus unbooked cash takings")
class CashCountUnbookedTakingsTest {
    private val deviceId = "cash-count-takings-device"

    /**
     * Seeds an admin, a signing terminal, a fiscal Ladenkasse holding exactly 100.00 in the book,
     * and one unclosed cash bill of 30.00 — a bill without a Z-Bon, paid in cash, not yet in the
     * Kassenbuch. The drawer therefore holds 130.00.
     */
    private fun seedTillWithUnbookedCashSale() {
        val employee = TestData.createAdmin()
        val shop = TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType("Cash", isCash = true)
        val tws = TestData.createTableWithSession("CCT1")
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-TEST-CCT")

        val repo = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        val books = CashBookRepositoryImpl()
        transaction {
            val bookId = books.fiscalBookFor(shop).id.value
            repo.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_DEPOSIT,
                        belegNr = repo.nextBelegNrBlocking(bookId),
                        vorzBetrag = "100.00",
                        belegDatum = "01.08.2026",
                        belegtext = "Anfangsbestand",
                        cashBookId = bookId,
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "100.00", ustSatz = null)),
                tseTransaction = null,
            )

            val now = currentTimeStamp()
            val session = TableSession.findById(tws.session.id)!!
            val bill =
                Bill.new {
                    this.employeeId = employee
                    this.tableSession = session
                    this.billNumber = "CCT-UNBOOKED-1"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.isTraining = false
                }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vats[0]
                this.netAmount = BigDecimal("25.21")
                this.vatAmount = BigDecimal("4.79")
                this.totalAmount = BigDecimal("30.00")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("30.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }
    }

    private fun countBody(amount: String) =
        TextContent(
            Json.encodeToString(
                KassenbuchDto.CashCountRequestDto.serializer(),
                KassenbuchDto.CashCountRequestDto(countedAmount = amount),
            ),
            ContentType.Application.Json,
        )

    @Test
    fun `a drawer holding book plus unbooked cash takings is a matching count and books nothing`() =
        testApp(setupData = { seedTillWithUnbookedCashSale() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("130.00"))
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.body<KassenbuchDto.CashCountResponseDto>()
            assertEquals("100.00", body.bookBalance, "the book itself still reads 100.00")
            assertEquals("30.00", body.unbookedCashTakings, "the unclosed cash bill is what the book does not show yet")
            assertEquals("130.00", body.expectedCash, "expected = book + unbooked cash takings")
            assertEquals("0.00", body.difference, "a drawer holding exactly book + takings has no difference")
            assertNull(body.entryId, "a matching count must book nothing — no phantom surplus")
            transaction {
                assertEquals(
                    emptyList<KassenbuchEntryType>(),
                    KassenbuchHeader.all().map { it.entryType }.filter { it == KassenbuchEntryType.CASH_SURPLUS },
                    "no CASH_SURPLUS may be booked for takings the closing is about to book itself",
                )
            }
        }

    @Test
    fun `only the cash beyond book plus unbooked takings is a surplus`() =
        testApp(setupData = { seedTillWithUnbookedCashSale() }) { client ->
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)

            val response =
                client.post("/api/v1/kassenbuch/cash-count") {
                    bearerAuth(token)
                    setBody(countBody("140.00"))
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.body<KassenbuchDto.CashCountResponseDto>()
            assertEquals("130.00", body.expectedCash)
            assertEquals("10.00", body.difference, "only what exceeds book + takings is a genuine surplus")
            val entryId = assertNotNull(body.entryId, "a genuine surplus is booked")
            transaction {
                val entry = KassenbuchHeader.findById(entryId)!!
                assertEquals(KassenbuchEntryType.CASH_SURPLUS, entry.entryType)
                assertEquals(0, BigDecimal("10.00").compareTo(entry.totalAmount), "the surplus entry carries the real difference, got ${entry.totalAmount}")
            }
        }
}
