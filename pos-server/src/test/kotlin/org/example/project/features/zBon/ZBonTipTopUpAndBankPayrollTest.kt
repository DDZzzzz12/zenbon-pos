package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.ClosingTopUpRule
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.money.Decimal
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F59 in the Z-Bon: the Kassenminus guard on tip payouts, the top-up from another cash book (option A)
 * and the bank/payroll settlement (option C).
 *
 * The day: ONE bill, 20.00 + 5.00 employee tip, paid entirely by card. The drawer took nothing in cash
 * (aggregate netCashAmount = 20 − 25 + 5 = 0), yet anna holds 5.00 of employee tips — the drawer would
 * have to advance them. Where a test gives the Ladenkasse a float it is 2.00, so a 5.00 cash payout is
 * short by exactly 3.00.
 */
@DisplayName("Z-Bon tip payouts: Kassenminus, top-up, bank/payroll (F59)")
class ZBonTipTopUpAndBankPayrollTest {
    private val deviceId = "f59-device"
    private val fixture = TippedDayFixture(deviceId, "F59")
    private val cardDay = listOf(Sale("anna", "5.00", byCard = true))

    private fun anna() = fixture.waiters.getValue("anna").toString()

    private fun cash(amount: String) = ZBonDto.CreateTipPayoutRequestDto(employeeId = anna(), amount = amount)

    private fun bank(amount: String) = ZBonDto.CreateTipPayoutRequestDto(employeeId = anna(), amount = amount, method = TipPayoutMethod.BANK_PAYROLL)

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    // ============================================
    // Kassenminus on the in-closing payout
    // ============================================

    @Test
    fun `a cash payout the drawer cannot cover refuses the closing and writes nothing`() =
        testApp(setupData = { fixture.seed(cardDay) }) { client ->
            val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(cash("5.00"))))

            assertRefused(response, PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH)
            assertTrue(response.bodyAsText().contains("holds 0.00"), "the refusal names what the drawer holds: ${response.bodyAsText()}")
            assertNoClosing()
        }

    /** Negative control: the same payout with the float that covers it closes. */
    @Test
    fun `negative control - a float that covers the payout lets the closing through`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            fixture.fund(null, "5.00")
        }) { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(cash("5.00"))))
            assertDecimal("0", zBon.closingBalance, "the float went to anna")
        }

    // ============================================
    // Option A: top-up from another cash book
    // ============================================

    @Test
    fun `the exact shortfall is topped up from the Tresor before the payout, inside the closing`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            fixture.fund(null, "2.00")
        }) { client ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "10.00")

            val zBon =
                closeOk(
                    client,
                    ZBonDto.CreateZBonRequestDto(
                        tipPayouts = listOf(cash("5.00")),
                        topUp = ZBonDto.ClosingTopUpRequestDto(fromCashBookId = tresor, amount = "3.00"),
                    ),
                )

            assertDecimal("0", zBon.closingBalance, "2.00 float + 3.00 top-up − 5.00 payout: the exact shortfall leaves the drawer empty")
            transaction {
                assertDecimal("7.00", balance(tresor), "the Tresor gave 3.00")
                val inLeg = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_IN }
                val outLeg = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }
                assertEquals(inLeg.transferId, outLeg.transferId, "one Geldtransit, two legs")
                assertTrue(inLeg.cashBook.isFiscal)
                assertEquals(tresor, outLeg.cashBook.id.value)
                assertEquals(zBon.id, inLeg.zBon?.id?.value, "the Ladenkasse leg is booked IN this closing")
                assertTrue(inLeg.isLocked, "and swept by it")
                assertNotNull(inLeg.linkedDocumentId, "an Eigenbeleg, as for every Geldtransit")
                val aggregate = KassenbuchHeader.all().single { it.isZbonAggregate }
                val payout = payoutEntries().single()
                assertTrue(aggregate.id.value < inLeg.id.value, "the top-up after the aggregate")
                assertTrue(inLeg.id.value < payout.id.value, "and BEFORE the payout it covers")
            }
        }

    /**
     * The Geldtransit rule with a top-up: transfer = min(max(takings + topUp − payouts, 0), drawer).
     * A top-up of 7.00 for a 3.00 shortfall restores the 2.00 float and sends the remaining 2.00
     * straight back to the safe — the drawer never ends above its float.
     */
    @Test
    fun `a top-up above the need restores the float and the rest travels back to the safe`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            carryFloat("2.00")
        }) { client ->
            val tresor = fixture.routeTakingsToSafe()
            try {
                fixture.fund(tresor, "10.00")
                val zBon =
                    closeOk(
                        client,
                        ZBonDto.CreateZBonRequestDto(
                            tipPayouts = listOf(cash("5.00")),
                            topUp = ZBonDto.ClosingTopUpRequestDto(fromCashBookId = tresor, amount = "7.00"),
                        ),
                    )

                assertDecimal("2.00", zBon.closingBalance, "the drawer ends at its float")
                transaction {
                    assertDecimal("5.00", balance(tresor), "10.00 − 7.00 top-up + 2.00 Geldtransit")
                    val dailyTransfer =
                        KassenbuchHeader.all().filter { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT && it.cashBook.isFiscal }
                    assertEquals(1, dailyTransfer.size)
                    assertDecimal("-2.00", dailyTransfer.single().totalAmount.toPlainString())
                }
            } finally {
                fixture.stopRoutingToSafe()
            }
        }

    /**
     * The dialog's prefill — [ClosingTopUpRule.floatPreservingTopUp], payouts − takings = 5.00 − 0 — is
     * the one top-up that leaves the float untouched: no Geldtransit, the drawer ends at its 2.00 float.
     */
    @Test
    fun `the prefilled float-preserving top-up moves nothing to the safe and keeps the float`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            carryFloat("2.00")
        }) { client ->
            val tresor = fixture.routeTakingsToSafe()
            try {
                fixture.fund(tresor, "10.00")
                val topUp = ClosingTopUpRule.floatPreservingTopUp(Decimal.parse("5.00"), takings = Decimal.ZERO)
                assertEquals(Decimal.parse("5.00"), topUp)
                val zBon =
                    closeOk(
                        client,
                        ZBonDto.CreateZBonRequestDto(
                            tipPayouts = listOf(cash("5.00")),
                            topUp = ZBonDto.ClosingTopUpRequestDto(fromCashBookId = tresor, amount = topUp.toPlainString()),
                        ),
                    )

                assertDecimal("2.00", zBon.closingBalance, "the drawer ends at its float")
                transaction {
                    assertDecimal("5.00", balance(tresor), "10.00 − 5.00 top-up, nothing sent back")
                    assertTrue(
                        KassenbuchHeader.all().none { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT && it.cashBook.isFiscal },
                        "no Geldtransit out of the Ladenkasse",
                    )
                }
            } finally {
                fixture.stopRoutingToSafe()
            }
        }

    /** Without a top-up the Geldtransit rule is exactly F58's: nothing moves when the payouts exceed the takings. */
    @Test
    fun `a payout funded by the float moves nothing to the safe`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            carryFloat("5.00")
        }) { client ->
            fixture.routeTakingsToSafe()
            try {
                val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(cash("5.00"))))
                assertDecimal("0", zBon.closingBalance)
                transaction { assertTrue(KassenbuchHeader.all().none { it.transferId != null }, "no Geldtransit of nothing") }
            } finally {
                fixture.stopRoutingToSafe()
            }
        }

    @Test
    fun `a top-up from a book that does not hold it refuses the closing and writes nothing`() =
        testApp(setupData = { fixture.seed(cardDay) }) { client ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "1.00")
            val headersBefore = transaction { KassenbuchHeader.all().count() }

            val response =
                fixture.closeWith(
                    client,
                    ZBonDto.CreateZBonRequestDto(
                        tipPayouts = listOf(cash("5.00")),
                        topUp = ZBonDto.ClosingTopUpRequestDto(fromCashBookId = tresor, amount = "5.00"),
                    ),
                )

            assertRefused(response, PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH)
            assertTrue(response.bodyAsText().contains("Tresor"), "names the source book: ${response.bodyAsText()}")
            assertNoClosing(headersBefore)
            transaction { assertDecimal("1.00", balance(tresor)) }
        }

    /** Atomicity: the top-up is booked, then the payout guard refuses — the top-up must roll back with the rest. */
    @Test
    fun `a refusal after the top-up was booked leaves no top-up behind`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            fixture.fund(null, "2.00")
        }) { client ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "10.00")
            val headersBefore = transaction { KassenbuchHeader.all().count() }

            val response =
                fixture.closeWith(
                    client,
                    ZBonDto.CreateZBonRequestDto(
                        tipPayouts = listOf(cash("5.00")),
                        // 2.00 float + 1.00 still leaves 2.00 short
                        topUp = ZBonDto.ClosingTopUpRequestDto(fromCashBookId = tresor, amount = "1.00"),
                    ),
                )

            assertRefused(response, PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH)
            assertNoClosing(headersBefore)
            transaction {
                assertTrue(KassenbuchHeader.all().none { it.transferId != null }, "no transfer leg survived the rollback")
                assertDecimal("10.00", balance(tresor))
            }
        }

    @Test
    fun `a top-up without cash payouts, or from the Ladenkasse itself, is refused up front`() =
        testApp(setupData = { fixture.seed(cardDay) }) { client ->
            val tresor = fixture.createBook("Tresor")
            fixture.fund(tresor, "10.00")
            val fiscalId =
                transaction {
                    org.example.project.features.kassenbuch.data.repository
                        .CashBookRepositoryImpl()
                        .fiscalBookFor(ShopInfo.all().first())
                        .id.value
                }

            val withoutPayouts =
                fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(topUp = ZBonDto.ClosingTopUpRequestDto(tresor, "3.00")))
            assertEquals(HttpStatusCode.BadRequest, withoutPayouts.status, withoutPayouts.bodyAsText())

            val bankOnly =
                fixture.closeWith(
                    client,
                    ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(bank("5.00")), topUp = ZBonDto.ClosingTopUpRequestDto(tresor, "3.00")),
                )
            assertEquals(HttpStatusCode.BadRequest, bankOnly.status, "a bank/payroll settlement needs no cash: ${bankOnly.bodyAsText()}")

            val fromLadenkasse =
                fixture.closeWith(
                    client,
                    ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(cash("5.00")), topUp = ZBonDto.ClosingTopUpRequestDto(fiscalId, "5.00")),
                )
            assertEquals(HttpStatusCode.BadRequest, fromLadenkasse.status, fromLadenkasse.bodyAsText())
            assertNoClosing(headersBefore = 1)
        }

    // ============================================
    // Option C: bank / payroll
    // ============================================

    @Test
    fun `a bank-payroll settlement in the closing clears the tip with no cash and no Kassenbuch entry`() =
        testApp(setupData = { fixture.seed(cardDay) }) { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(bank("5.00"))))

            val anna = zBon.tipPayouts.single()
            assertDecimal("0", anna.remaining, "the settlement clears the open amount")
            assertDecimal("5.00", anna.paidOut)
            val record = anna.payouts.single()
            assertEquals(TipPayoutMethod.BANK_PAYROLL, record.method)
            assertNull(record.kassenbuchHeaderId)
            assertNull(record.belegNr)
            assertDecimal("0", zBon.closingBalance, "nothing left the drawer")
            transaction {
                assertEquals(1, KassenbuchHeader.all().count(), "only the aggregate — no withdrawal, no Geldtransit")
                val row = EmployeeTipPayoutTable.selectAll().single()
                assertEquals(TipPayoutMethod.BANK_PAYROLL, row[EmployeeTipPayoutTable.method])
                assertNull(row[EmployeeTipPayoutTable.kassenbuchHeaderId])
                assertEquals(1, auditRows("SETTLE_EMPLOYEE_TIP_BANK_PAYROLL"), "audited like a cash payout")
            }
        }

    @Test
    fun `after the closing - a cash payout the drawer lacks is refused, bank-payroll settles it, and it is reversible once`() =
        testApp(setupData = { fixture.seed(cardDay) }) { client ->
            val zBon = fixture.close(client)
            val headersAfterClosing = transaction { KassenbuchHeader.all().count() }
            val dsfinvkBefore = dsfinvkBusinessCases()

            val refused = fixture.payOut(client, zBon.id, anna(), "5.00")
            assertRefused(refused, PosErrorCode.KASSENBUCH_INSUFFICIENT_CASH)
            transaction { assertEquals(0, EmployeeTipPayoutTable.selectAll().count(), "no payout row for a refused payout") }

            val settled = fixture.payOut(client, zBon.id, anna(), "5.00", method = TipPayoutMethod.BANK_PAYROLL)
            assertEquals(HttpStatusCode.Created, settled.status, settled.bodyAsText())
            val result = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), settled.bodyAsText())
            assertEquals(TipPayoutMethod.BANK_PAYROLL, result.method)
            assertNull(result.kassenbuchHeaderId, "no Kassenbuch entry")
            assertDecimal("0", result.status.remaining)
            val payoutId = assertNotNull(result.payoutId)
            transaction {
                assertEquals(headersAfterClosing, KassenbuchHeader.all().count(), "no Kassenbuch entry, so no DSFinV-K or DATEV row either")
            }
            assertEquals(dsfinvkBefore, dsfinvkBusinessCases(), "the DSFinV-K Kassenbuch business cases are untouched")

            val storno = fixture.reverseSettlement(client, zBon.id, payoutId)
            assertEquals(HttpStatusCode.Created, storno.status, storno.bodyAsText())
            val reversed = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), storno.bodyAsText())
            assertDecimal("5.00", reversed.status.remaining, "open again")
            assertTrue(
                reversed.status.payouts
                    .single()
                    .reversed,
            )

            assertRefused(fixture.reverseSettlement(client, zBon.id, payoutId), PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED)
            transaction {
                assertEquals(headersAfterClosing, KassenbuchHeader.all().count(), "the storno books no Kassenbuch entry either")
                val rows = EmployeeTipPayoutTable.selectAll().toList()
                assertEquals(2, rows.size, "settlement + its reversal, append-only")
                assertEquals(payoutId, rows.single { it[EmployeeTipPayoutTable.reversesPayoutId] != null }[EmployeeTipPayoutTable.reversesPayoutId]?.value)
                assertEquals(1, auditRows("SETTLE_EMPLOYEE_TIP_BANK_PAYROLL"))
                assertEquals(2, auditRows("REVERSE_EMPLOYEE_TIP_SETTLEMENT"), "the storno and the refused second one are both audited")
                assertEquals(1, auditRows("REVERSE_EMPLOYEE_TIP_SETTLEMENT", succeeded = true), "only one storno succeeded")
            }
        }

    @Test
    fun `a cash payout is not reversible through the settlement route, and a covered one is booked`() =
        testApp(setupData = {
            fixture.seed(cardDay)
            fixture.fund(null, "5.00")
        }) { client ->
            val zBon = fixture.close(client)
            val paid = fixture.payOut(client, zBon.id, anna(), "2.00")
            assertEquals(HttpStatusCode.Created, paid.status, "the drawer holds 5.00: ${paid.bodyAsText()}")
            val result = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), paid.bodyAsText())
            assertEquals(TipPayoutMethod.CASH, result.method)
            val cashPayoutId = assertNotNull(result.payoutId)

            assertRefused(fixture.reverseSettlement(client, zBon.id, cashPayoutId), PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT)
        }

    // ============================================
    // Helpers
    // ============================================

    private suspend fun closeOk(
        client: HttpClient,
        request: ZBonDto.CreateZBonRequestDto,
    ): ZBonDto.ZBonResponseDto {
        val response = fixture.closeWith(client, request)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
    }

    private suspend fun assertRefused(
        response: HttpResponse,
        code: PosErrorCode,
    ) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertEquals(
            code.name,
            Json
                .parseToJsonElement(body)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content,
            body,
        )
    }

    /** No closing, no payout row, every bill still open, and no Kassenbuch entry beyond [headersBefore]. */
    private fun assertNoClosing(headersBefore: Long? = null) {
        transaction {
            assertEquals(0, ZBon.all().count(), "no closing")
            assertEquals(0, EmployeeTipPayoutTable.selectAll().count(), "no payout row")
            assertEquals(1, BillTable.selectAll().count { it[BillTable.zBonId] == null }, "the bill is still open")
            if (headersBefore != null) {
                assertEquals(headersBefore, KassenbuchHeader.all().count(), "no Kassenbuch entry was added")
            } else {
                assertTrue(KassenbuchHeader.all().none { it.isZbonAggregate || it.entryType == KassenbuchEntryType.CASH_WITHDRAWAL })
            }
        }
    }

    private fun balance(cashBookId: Long): String =
        KassenbuchRepositoryImpl(testKassenbuchDocuments()).getCurrentBalance(ShopInfo.all().first(), cashBookId).toPlainString()

    private fun payoutEntries(): List<KassenbuchHeader> =
        KassenbuchHeader
            .all()
            .filter { !it.isZbonAggregate && it.lineItems.any { li -> li.kind == KassenbuchLineKind.EMPLOYEE_TIP } }

    /** Audit rows of [operation]; with [succeeded], only those carrying an after-state (a refusal has none). */
    private fun auditRows(
        operation: String,
        succeeded: Boolean = false,
    ): Int =
        TransactionAuditLogTable
            .selectAll()
            .where { TransactionAuditLogTable.operation eq operation }
            .count { !succeeded || it[TransactionAuditLogTable.afterState] != null }

    /**
     * A float carried over from a PREVIOUS closing, as a real till has one: [amount] booked into the
     * Ladenkasse, swept (locked) by a prior Z-Bon whose closing balance it is, ended before the day's
     * bills. A float booked inside the period would instead be this period's Bareinlage — which the
     * closing counts as cash takings and moves to the safe (existing rule, `zBonBucket`).
     */
    private fun carryFloat(amount: String) {
        fixture.fund(null, amount)
        transaction {
            val firstBill = BillTable.selectAll().minOf { it[BillTable.startTime] }
            val end = kotlinx.datetime.LocalDateTime(firstBill.date, kotlinx.datetime.LocalTime(0, 0))
            val shop = ShopInfo.all().first()
            val admin =
                org.example.project.features.employee.domain.models.Employee
                    .all()
                    .first { it.username == "admin" }
            val prior =
                org.example.project.testutils.TestData
                    .createZBon(shop, admin, start = end, end = end, nummer = 1L)
            prior.closingBalance = BigDecimal(amount)
            KassenbuchHeader.all().forEach {
                it.isLocked = true
                it.zBon = prior
            }
        }
    }

    private fun dsfinvkBusinessCases(): String =
        transaction {
            val service =
                DsfinvkExportServiceImpl(
                    json = Json { ignoreUnknownKeys = true },
                    configRepository = ConfigurationRepository(),
                    invoices = SalesInvoiceBillInvoiceLink(),
                )
            service.aggregateKassenbuchBusinessCases(KassenbuchHeader.all().toList(), ZBon.all().toList(), "K1").toString()
        }

    private fun assertDecimal(
        expected: String,
        actual: String,
        message: String? = null,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "${message ?: ""} expected $expected, was $actual")
}
