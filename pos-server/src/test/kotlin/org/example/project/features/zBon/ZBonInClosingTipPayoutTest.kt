package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.document.database.tables.DocumentTable
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F58: employee tips paid out INSIDE the closing — `POST /api/z-bon` with `tipPayouts`.
 *
 * The day: anna 3.00 cash + 2.00 card = 5.00 tips, bert 4.00 cash. Three 20.00 bills, so the drawer
 * took 23 + 24 = 47.00 in cash (the card bill tendered 22.00 by card) — the aggregate's netCashAmount.
 * Paying all 9.00 of tips out in the closing leaves 38.00 in the drawer.
 *
 * Every refusal asserts that NOTHING was written — no closing, no Kassenbuch entry, no Eigenbeleg, no
 * link row, and the bills still unclosed — because a refused closing that left a payout behind is the
 * failure that matters.
 */
@DisplayName("Employee-tip payout inside the Z-Bon (F58)")
class ZBonInClosingTipPayoutTest {
    private val deviceId = "tip-closing-device"
    private val fixture = TippedDayFixture(deviceId, "TIC")

    private val day =
        listOf(
            Sale("anna", "3.00"),
            Sale("anna", "2.00", byCard = true),
            Sale("bert", "4.00"),
        )

    private fun anna() = fixture.waiters.getValue("anna").toString()

    private fun bert() = fixture.waiters.getValue("bert").toString()

    private fun payout(
        employeeId: String,
        amount: String,
    ) = ZBonDto.CreateTipPayoutRequestDto(employeeId = employeeId, amount = amount)

    private fun everything() = listOf(payout(anna(), "5.00"), payout(bert(), "4.00"))

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    // ============================================
    // The dialog's preview
    // ============================================

    @Test
    fun `the open-period preview lists each employee's tips, split cash and card`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val response = fixture.openPeriodTips(client)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val tips =
                testJson
                    .decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ZBonDto.OpenPeriodTipDto.serializer()),
                        response.bodyAsText(),
                    ).associateBy { it.employeeId }

            assertEquals(setOf(anna(), bert()), tips.keys, "one row per employee with employee tips")
            assertDecimal("5.00", tips.getValue(anna()).tipTotal)
            assertDecimal("3.00", tips.getValue(anna()).cashTip)
            assertDecimal("2.00", tips.getValue(anna()).cardTip, "the card-paid bill's tip is the card part")
            assertDecimal("4.00", tips.getValue(bert()).cashTip)
            assertDecimal("0", tips.getValue(bert()).cardTip)

            // The preview IS what the closing then stores per employee.
            val zBon = fixture.close(client)
            zBon.tipPayouts.forEach { assertDecimal(tips.getValue(it.employeeId).tipTotal, it.tipTotal, it.employeeName) }
        }

    // ============================================
    // Payouts + closing in one call
    // ============================================

    @Test
    fun `payouts in the closing are booked, signed and swept into that same closing`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420"))
            val zBon =
                try {
                    closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = everything()))
                } finally {
                    ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420"))
                }

            // The closing's own answer already shows everything paid.
            zBon.tipPayouts.forEach {
                assertDecimal("0", it.remaining, "${it.employeeName}: nothing left after the in-closing payout")
                assertDecimal(it.tipTotal, it.paidOut)
            }

            transaction {
                val aggregate = KassenbuchHeader.all().single { it.isZbonAggregate }
                assertDecimal("47.00", aggregate.totalAmount.toPlainString(), "the aggregate still books the day's takings, tips included")
                assertDecimal(
                    "9.00",
                    aggregate.lineItems
                        .single { it.kind == KassenbuchLineKind.EMPLOYEE_TIP }
                        .vorzBetrag
                        .toPlainString(),
                )

                val payouts = payoutEntries()
                assertEquals(2, payouts.size)
                payouts.forEach { header ->
                    assertEquals(KassenbuchEntryType.CASH_WITHDRAWAL, header.entryType)
                    assertEquals(zBon.id, header.zBon?.id?.value, "${header.belegNr}: booked IN this closing")
                    assertTrue(header.isLocked, "${header.belegNr}: swept by this closing")
                    assertTrue(header.cashBook.isFiscal)
                    assertNotNull(header.linkedDocumentId, "${header.belegNr}: an Eigenbeleg, as for every payout")
                    assertNotNull(header.tseTransaction, "${header.belegNr}: TSE-signed in phase 2")
                    assertTrue(header.belegNr > aggregate.belegNr, "booked after the aggregate whose line credits the tips")
                    assertTrue(header.belegtext.startsWith("TA:Trinkgeld-Auszahlung"), header.belegtext)
                }
                val links = EmployeeTipPayoutTable.selectAll().toList()
                assertEquals(setOf(zBon.id), links.map { it[EmployeeTipPayoutTable.zBonId].value }.toSet(), "linked to the closing whose tips they pay")
                assertEquals(payouts.map { it.id.value }.toSet(), links.map { it[EmployeeTipPayoutTable.kassenbuchHeaderId]?.value }.toSet())

                val closing = ZBon.findById(zBon.id)!!
                assertDecimal("47.00", closing.cashRevenue.toPlainString(), "takings are unchanged by the payout")
                assertDecimal("-9.00", closing.expenses.toPlainString(), "the payouts leave the drawer in this closing")
                assertDecimal("38.00", closing.closingBalance.toPlainString(), "expected cash at closing excludes the paid-out tips")

                listOf(payouts[0], payouts[1]).forEach { header ->
                    val row =
                        TransactionAuditLogTable
                            .selectAll()
                            .where { TransactionAuditLogTable.operation eq "PAY_OUT_EMPLOYEE_TIP" }
                            .single { it[TransactionAuditLogTable.entityId] == header.id.value.toString() }
                    assertEquals(
                        header.tseTransaction!!
                            .id.value
                            .toString(),
                        row[TransactionAuditLogTable.fiscalRecordId],
                    )
                }
            }
            assertDecimal("38.00", kassenbuch().getCurrentBalance(null), "the book balance carries the payouts")
        }

    @Test
    fun `the drawer count is compared against the drawer after the payouts`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            closeOk(client, ZBonDto.CreateZBonRequestDto(countedAmount = "38.00", tipPayouts = everything()))
            assertEquals(0, differenceEntries(), "38.00 counted after paying out 9.00 of tips is an exact drawer")
        }

    /** Negative control for the count: without the payouts, the same 38.00 is a 9.00 shortage. */
    @Test
    fun `negative control - the same count without payouts books a shortage of the tips`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            closeOk(client, ZBonDto.CreateZBonRequestDto(countedAmount = "38.00"))
            transaction {
                val difference = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_SHORTAGE }
                assertDecimal("-9.00", difference.totalAmount.toPlainString())
            }
        }

    // ============================================
    // Refusals — nothing written
    // ============================================

    @Test
    fun `an over-limit payout refuses the closing and books nothing, not even the count`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val documents = documentCount()
            val response =
                fixture.closeWith(
                    client,
                    ZBonDto.CreateZBonRequestDto(countedAmount = "10.00", tipPayouts = listOf(payout(anna(), "5.01"), payout(bert(), "4.00"))),
                )
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING.name), response.bodyAsText())
            assertNothingWritten(documents)

            // Negative control: the exact amount goes through, so the guard is `>`, not `>=`.
            closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(anna(), "5.00"))))
            assertEquals(1, payoutRows())
        }

    @Test
    fun `an open table refuses the closing before any payout is booked`() =
        testApp(setupData = {
            fixture.seed(day)
            fixture.leaveTableOpen()
        }) { client ->
            val documents = documentCount()
            val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(countedAmount = "38.00", tipPayouts = everything()))
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains(PosErrorCode.ZBON_TABLES_OPEN.name), response.bodyAsText())
            assertNothingWritten(documents)
        }

    @Test
    fun `an operator without CASHBOOK_WRITE gets a 403 for payouts and nothing is written`() =
        testApp(setupData = {
            fixture.seed(day)
            moveToGroup("cashier", ZBON_CLOSER_ONLY, setOf(Capability.SELF_READ, Capability.ZBON_READ, Capability.ZBON_CLOSE))
        }) { client ->
            val documents = documentCount()
            val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = everything()), username = "cashier")
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertNothingWritten(documents)

            val preview = fixture.openPeriodTips(client, username = "cashier")
            assertEquals(HttpStatusCode.Forbidden, preview.status, "the preview needs at least the cash book's read capability")

            // Positive control: the same operator may close the day — it is the payouts that need the
            // cash-book capability, so the 403 above came from the service guard, not the route.
            val closed = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(), username = "cashier")
            assertEquals(HttpStatusCode.Created, closed.status, closed.bodyAsText())
            assertEquals(0, payoutRows())
        }

    @Test
    fun `a cash-book reader may see the open-period tips but not pay them out`() =
        testApp(setupData = {
            fixture.seed(day)
            moveToGroup(
                "cashier",
                CASHBOOK_READER,
                setOf(Capability.SELF_READ, Capability.ZBON_READ, Capability.ZBON_CLOSE, Capability.CASHBOOK_READ),
            )
        }) { client ->
            // The preview books nothing, so it is a read: CASHBOOK_READ, like the dialog's other lookups.
            val preview = fixture.openPeriodTips(client, username = "cashier")
            assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())

            // Paying out is still the cash book's write capability, refused before anything is booked.
            val documents = documentCount()
            val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = everything()), username = "cashier")
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertNothingWritten(documents)
        }

    @Test
    fun `an employee without tips, a repeated employee and a malformed amount are refused`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val cashier =
                transaction {
                    Employee
                        .all()
                        .single { it.username == "cashier" }
                        .id.value
                        .toString()
                }
            val documents = documentCount()

            val noTips = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(cashier, "1.00"))))
            assertEquals(HttpStatusCode.Conflict, noTips.status, noTips.bodyAsText())
            assertTrue(noTips.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE.name), noTips.bodyAsText())

            val twice = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(anna(), "1.00"), payout(anna(), "1.00"))))
            assertEquals(HttpStatusCode.BadRequest, twice.status, "one row per employee: a repeat could split the limit")

            listOf("0", "-1.00", "1.234", "abc").forEach { amount ->
                val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(anna(), amount))))
                assertEquals(HttpStatusCode.BadRequest, response.status, "amount='$amount'")
            }
            val badId = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout("not-a-uuid", "1.00"))))
            assertEquals(HttpStatusCode.BadRequest, badId.status)
            assertNothingWritten(documents)
        }

    // ============================================
    // Unchanged behaviour and the after-closing path
    // ============================================

    @Test
    fun `an empty payout list closes exactly as before`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = emptyList()))
            assertEquals(0, payoutRows())
            assertEquals(0, payoutEntries().size)
            assertDecimal("5.00", zBon.tipPayouts.single { it.employeeId == anna() }.remaining, "everything stays open")
            transaction { assertDecimal("47.00", ZBon.findById(zBon.id)!!.closingBalance.toPlainString()) }
        }

    @Test
    fun `the remainder is paid after the closing, into the next period, up to the tip and no further`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(anna(), "2.00"))))
            assertDecimal("3.00", zBon.tipPayouts.single { it.employeeId == anna() }.remaining, "the reduced row leaves 3.00 open")

            val later = fixture.payOut(client, zBon.id, anna(), "3.00")
            assertEquals(HttpStatusCode.Created, later.status, later.bodyAsText())
            val result = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), later.bodyAsText())
            assertDecimal("0", result.status.remaining)
            assertDecimal("5.00", result.status.paidOut, "in-closing and after-closing payouts count against the same tips")
            assertEquals(2, result.status.payouts.size)

            transaction {
                val header = KassenbuchHeader.findById(result.kassenbuchHeaderId!!)!!
                assertNull(header.zBon, "a payout after the closing is open business of the NEXT period")
                assertTrue(!header.isLocked)
            }

            val tooMuch = fixture.payOut(client, zBon.id, anna(), "0.01")
            assertEquals(HttpStatusCode.Conflict, tooMuch.status, "nothing is left to pay")
        }

    @Test
    fun `an in-closing payout can be stornoed after the closing, freeing its amount`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(bert(), "4.00"))))
            val record =
                zBon.tipPayouts
                    .single { it.employeeId == bert() }
                    .payouts
                    .single()

            val storno = fixture.reverse(client, zBon.id, record.kassenbuchHeaderId!!)
            assertEquals(HttpStatusCode.Created, storno.status, storno.bodyAsText())
            val result = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), storno.bodyAsText())
            assertDecimal("4.00", result.status.remaining, "open = tips - payouts + reversals")
        }

    /** One payload definition for both paths: the same amount signs the identical processData. */
    @Test
    fun `an in-closing payout signs the same payload as a payout after the closing`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420"))
            try {
                val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = listOf(payout(anna(), "2.00"))))
                assertEquals(HttpStatusCode.Created, fixture.payOut(client, zBon.id, anna(), "2.00").status)
            } finally {
                ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420"))
            }
            transaction {
                val (inClosing, after) = payoutEntries().sortedBy { it.id.value }.map { assertNotNull(it.tseTransaction) }
                assertEquals(inClosing.processType, after.processType)
                assertNotNull(inClosing.processDataHash)
                assertEquals(inClosing.processDataHash, after.processDataHash, "byte-identical signed processData")
            }
        }

    // ============================================
    // Helpers
    // ============================================

    private suspend fun closeOk(
        client: HttpClient,
        request: ZBonDto.CreateZBonRequestDto,
    ): ZBonDto.ZBonResponseDto {
        val response: HttpResponse = fixture.closeWith(client, request)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
    }

    private fun documentCount(): Long = transaction { DocumentTable.selectAll().count() }

    private fun assertNothingWritten(documentsBefore: Long) {
        transaction {
            assertEquals(0, ZBon.all().count(), "no closing")
            assertEquals(0, KassenbuchHeader.all().count(), "no Kassenbuch entry — payout, count difference or aggregate")
            assertEquals(0, EmployeeTipPayoutTable.selectAll().count(), "no payout link row")
            assertEquals(documentsBefore, DocumentTable.selectAll().count(), "no Eigenbeleg")
            assertEquals(3, BillTable.selectAll().count { it[BillTable.zBonId] == null }, "every bill still open")
        }
    }

    /** Kassenbuch entries carrying an EMPLOYEE_TIP line, excluding the closing's own aggregate. */
    private fun payoutEntries(): List<KassenbuchHeader> =
        transaction {
            KassenbuchHeader
                .all()
                .filter { !it.isZbonAggregate && it.lineItems.any { li -> li.kind == KassenbuchLineKind.EMPLOYEE_TIP } }
                .sortedBy { it.belegNr }
        }

    private fun differenceEntries(): Int =
        transaction {
            KassenbuchHeader.all().count {
                it.entryType == KassenbuchEntryType.CASH_SURPLUS || it.entryType == KassenbuchEntryType.CASH_SHORTAGE
            }
        }

    private fun payoutRows(): Int = transaction { EmployeeTipPayoutTable.selectAll().count().toInt() }

    private fun kassenbuch(): KassenbuchService = GlobalContext.get().get()

    /** Re-homes [username] into a fresh group holding exactly [capabilities]. */
    private fun moveToGroup(
        username: String,
        code: String,
        capabilities: Set<Capability>,
    ) {
        transaction {
            val now = currentTimeStamp()
            val groupId =
                PermissionGroupTable.insertAndGetId {
                    it[PermissionGroupTable.code] = code
                    it[name] = code
                    it[description] = "test group"
                    it[isSystem] = false
                    it[isDeletable] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            capabilities.forEach { capability ->
                PermissionGroupCapabilityTable.insert {
                    it[group] = groupId
                    it[this.capability] = capability.name
                }
            }
            val employee = Employee.all().single { it.username == username }
            EmployeePermissionGroupTable.update({ EmployeePermissionGroupTable.employee eq employee.id }) {
                it[group] = groupId
            }
        }
    }

    private fun assertDecimal(
        expected: String,
        actual: String,
        message: String? = null,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "${message ?: ""} expected $expected, got $actual")

    private companion object {
        const val ZBON_CLOSER_ONLY = "ZBON_CLOSER_ONLY"
        const val CASHBOOK_READER = "CASHBOOK_READER"
    }
}
