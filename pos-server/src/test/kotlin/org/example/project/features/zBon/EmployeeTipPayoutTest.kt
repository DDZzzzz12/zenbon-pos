package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /api/z-bon/{id}/tip-payouts` (F51): handing a closing's employee tips (TrinkgeldAN) to the
 * employee in cash after the closing, as a signed Kassenbuch withdrawal.
 *
 * Every guard is exercised through the real route, so the capability rule, the service guard and
 * the in-transaction re-check are all on the path. Each refusal also asserts that NOTHING was booked
 * — a refused payout that still wrote a withdrawal is the failure that matters.
 */
@DisplayName("Employee-tip payout after the Z-Bon (F51)")
class EmployeeTipPayoutTest {
    private val deviceId = "tip-payout-device"
    private val fixture = TippedDayFixture(deviceId, "ETP")

    /** anna: 3.00 cash + 2.00 card = 5.00 tips; bert: 4.00 cash. */
    private val day =
        listOf(
            Sale("anna", "3.00"),
            Sale("anna", "2.00", byCard = true),
            Sale("bert", "4.00"),
        )

    private fun anna() = fixture.waiters.getValue("anna").toString()

    private fun bert() = fixture.waiters.getValue("bert").toString()

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `the created closing lists each employee's tips, cash and card, as open`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)

            val positions = zBon.tipPayouts.associateBy { it.employeeId }
            assertEquals(setOf(anna(), bert()), positions.keys, "one position per employee with tips")
            assertDecimal("5.00", positions.getValue(anna()).tipTotal, "anna's cash AND card tips")
            assertDecimal("0", positions.getValue(anna()).paidOut)
            assertDecimal("5.00", positions.getValue(anna()).remaining)
            assertDecimal("4.00", positions.getValue(bert()).remaining)
        }

    @Test
    fun `a full payout books a signed fiscal withdrawal of kind EMPLOYEE_TIP and lowers the book balance`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val balanceBefore = BigDecimal(kassenbuch().getCurrentBalance(null))

            val response = fixture.payOut(client, zBon.id, anna(), "5.00")
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val result = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), response.bodyAsText())
            assertDecimal("0", result.status.remaining, "nothing left after a full payout")
            assertDecimal("5.00", result.status.paidOut)

            val balanceAfter = BigDecimal(kassenbuch().getCurrentBalance(null))
            assertEquals(0, balanceBefore.subtract(BigDecimal("5.00")).compareTo(balanceAfter), "the drawer's book drops by the payout")

            transaction {
                val header = KassenbuchHeader.findById(result.kassenbuchHeaderId!!)!!
                assertEquals(KassenbuchEntryType.CASH_WITHDRAWAL, header.entryType)
                assertTrue(header.cashBook.isFiscal, "paid from the fiscal Ladenkasse")
                assertTrue(header.belegtext.startsWith("TA:Trinkgeld-Auszahlung"), header.belegtext)
                assertEquals(result.belegNr, header.belegNr)
                assertTrue(header.linkedDocumentId != null, "an Eigenbeleg is linked, as for every Kassenbuch entry")
                val line = header.lineItems.single()
                assertEquals(KassenbuchLineKind.EMPLOYEE_TIP, line.kind)
                assertEquals(0, BigDecimal("-5.00").compareTo(line.vorzBetrag), "a withdrawal is cash OUT")
                assertEquals(null, line.ustSatz, "a tip is not a supply and carries no rate")
                assertTrue(!header.isZbonAggregate)

                val link = EmployeeTipPayoutTable.selectAll().single()
                assertEquals(zBon.id, link[EmployeeTipPayoutTable.zBonId].value)
                assertEquals(UUID.fromString(anna()), link[EmployeeTipPayoutTable.employeeId].value)
                assertEquals(result.kassenbuchHeaderId, link[EmployeeTipPayoutTable.kassenbuchHeaderId]?.value)
            }

            // The Z-Bon detail reflects it.
            val detail = getDetail(client, zBon.id)
            val anna = detail.tipPayouts.single { it.employeeId == anna() }
            assertDecimal("0", anna.remaining)
            assertDecimal("4.00", detail.tipPayouts.single { it.employeeId == bert() }.remaining, "bert is untouched")
        }

    @Test
    fun `partial payouts are allowed up to the total and never beyond`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)

            assertEquals(HttpStatusCode.Created, fixture.payOut(client, zBon.id, anna(), "1.50").status)
            assertEquals(HttpStatusCode.Created, fixture.payOut(client, zBon.id, anna(), "3.50").status)

            val third = fixture.payOut(client, zBon.id, anna(), "0.01")
            assertEquals(HttpStatusCode.Conflict, third.status)
            assertTrue(third.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING.name), third.bodyAsText())
            assertEquals(2, payoutCount(), "the refused third payout booked nothing")
            assertEquals(2, tipPayoutEntries(), "and wrote no Kassenbuch entry")
        }

    @Test
    fun `an over-payout is refused and books nothing`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)

            val response = fixture.payOut(client, zBon.id, bert(), "4.01")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_EXCEEDS_REMAINING.name))
            assertEquals(0, payoutCount())
            assertEquals(0, tipPayoutEntries())
        }

    /** Negative control for the over-payout: the exact remainder is accepted, so the guard is `>`, not `>=`. */
    @Test
    fun `negative control - the exact remaining amount is accepted`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            assertEquals(HttpStatusCode.Created, fixture.payOut(client, zBon.id, bert(), "4.00").status)
            assertEquals(1, payoutCount())
        }

    @Test
    fun `an employee without tips in that closing, an unknown employee and an unknown closing are refused`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val cashierId =
                transaction {
                    org.example.project.features.employee.domain.models.Employee
                        .all()
                        .single { it.username == "cashier" }
                        .id.value
                        .toString()
                }

            val noTips = fixture.payOut(client, zBon.id, cashierId, "1.00")
            assertEquals(HttpStatusCode.Conflict, noTips.status)
            assertTrue(noTips.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE.name), noTips.bodyAsText())

            assertEquals(HttpStatusCode.NotFound, fixture.payOut(client, zBon.id, UUID.randomUUID().toString(), "1.00").status)
            assertEquals(HttpStatusCode.NotFound, fixture.payOut(client, zBon.id + 999, anna(), "1.00").status)
            assertEquals(0, payoutCount())
            assertEquals(0, tipPayoutEntries())
        }

    @Test
    fun `malformed amounts and ids are a 400`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            listOf("0", "-1.00", "1.234", "abc", "").forEach { amount ->
                assertEquals(HttpStatusCode.BadRequest, fixture.payOut(client, zBon.id, anna(), amount).status, "amount='$amount'")
            }
            assertEquals(HttpStatusCode.BadRequest, fixture.payOut(client, zBon.id, "not-a-uuid", "1.00").status)
            assertEquals(0, payoutCount())
        }

    @Test
    fun `an operator without CASHBOOK_WRITE gets a 403 and books nothing`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val response = fixture.payOut(client, zBon.id, anna(), "1.00", username = "cashier")
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertEquals(0, payoutCount())
        }

    /**
     * A closing created before the EMPLOYEE_TIP kind booked its tips as a STANDARD line on revenue;
     * paying them out would debit an account the tip never reached. Simulated by downgrading the
     * fresh closing's tip line to its pre-F51 shape.
     */
    @Test
    fun `a closing from before employee-tip bookings offers and accepts no payout`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            transaction {
                KassenbuchHeader
                    .find { KassenbuchHeaderTable.isZbonAggregate eq true }
                    .single()
                    .lineItems
                    .single { it.kind == KassenbuchLineKind.EMPLOYEE_TIP }
                    .kind = KassenbuchLineKind.STANDARD
            }

            assertTrue(getDetail(client, zBon.id).tipPayouts.isEmpty(), "nothing is offered for a pre-F51 closing")
            val response = fixture.payOut(client, zBon.id, anna(), "1.00")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_NOT_AVAILABLE.name))
            assertEquals(0, payoutCount())
        }

    @Test
    fun `a tip payout cannot be corrected through the generic correction`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val payout =
                testJson.decodeFromString(
                    ZBonDto.TipPayoutResponseDto.serializer(),
                    fixture.payOut(client, zBon.id, anna(), "2.00").bodyAsText(),
                )
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val response =
                client.post("/api/v1/kassenbuch/entries/${payout.kassenbuchHeaderId}/correct") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        KassenbuchDto.CorrectEntryRequestDto(
                            reason = "falscher Mitarbeiter",
                            correctedEntry =
                                KassenbuchDto.CreateKassenbuchEntryRequestDto(
                                    entryType = KassenbuchEntryType.CASH_WITHDRAWAL,
                                    lines = listOf(KassenbuchDto.EntryLineDto(vatRate = "0", amount = "2.00")),
                                    belegtext = "Korrektur",
                                ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("tip-payouts/${payout.kassenbuchHeaderId}/reverse"), "points at the storno action")
            assertEquals(1, tipPayoutEntries(), "no reversal was booked")
        }

    @Test
    fun `a client cannot book an EMPLOYEE_TIP line through the ordinary Kassenbuch route`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            fixture.close(client)
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val response =
                client.post("/api/v1/kassenbuch") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        KassenbuchDto.CreateKassenbuchEntryRequestDto(
                            entryType = KassenbuchEntryType.CASH_WITHDRAWAL,
                            lines = listOf(KassenbuchDto.EntryLineDto(kind = KassenbuchLineKind.EMPLOYEE_TIP, amount = "1.00")),
                            belegtext = "Trinkgeld an mich",
                            documentId = 1L,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals(0, tipPayoutEntries())
        }

    // ============================================
    // Storno of a payout
    // ============================================

    @Test
    fun `a storno books a signed CORRECTION_IN reversal, restores the book and frees the amount`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val payout = payOutOk(client, zBon.id, anna(), "5.00")
            val balanceAfterPayout = BigDecimal(kassenbuch().getCurrentBalance(null))

            val response = reverse(client, zBon.id, payout.kassenbuchHeaderId!!)
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val result = testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), response.bodyAsText())
            assertDecimal("5.00", result.status.remaining, "the stornoed amount is open again")
            assertDecimal("0", result.status.paidOut)
            assertEquals(listOf(true), result.status.payouts.map { it.reversed }, "the payout shows as reversed")

            assertEquals(
                0,
                balanceAfterPayout.add(BigDecimal("5.00")).compareTo(BigDecimal(kassenbuch().getCurrentBalance(null))),
                "the cash is back in the book",
            )
            transaction {
                val storno = KassenbuchHeader.findById(result.kassenbuchHeaderId!!)!!
                assertEquals(KassenbuchEntryType.CORRECTION_IN, storno.entryType)
                assertEquals(CorrectionRole.REVERSAL, storno.correctionRole)
                assertEquals(payout.kassenbuchHeaderId, storno.correctsHeader?.id?.value, "references the original payout")
                assertTrue(storno.cashBook.isFiscal)
                assertTrue(storno.linkedDocumentId != null, "an Eigenbeleg is linked")
                val line = storno.lineItems.single()
                assertEquals(KassenbuchLineKind.EMPLOYEE_TIP, line.kind)
                assertEquals(0, BigDecimal("5.00").compareTo(line.vorzBetrag), "cash back IN")

                val original = KassenbuchHeader.findById(payout.kassenbuchHeaderId!!)!!
                assertEquals(0, BigDecimal("-5.00").compareTo(original.lineItems.single().vorzBetrag), "the payout is untouched")
                val reversalRow = EmployeeTipPayoutTable.selectAll().single { it[EmployeeTipPayoutTable.reversesPayoutId] != null }
                assertEquals(result.kassenbuchHeaderId, reversalRow[EmployeeTipPayoutTable.kassenbuchHeaderId]?.value)
            }

            // Pay again, correctly.
            assertEquals(HttpStatusCode.Created, fixture.payOut(client, zBon.id, anna(), "5.00").status)
            assertDecimal("0", getDetail(client, zBon.id).tipPayouts.single { it.employeeId == anna() }.remaining)
        }

    /**
     * F54 #4: both audit rows name the TSE transaction that signed their entry in
     * `fiscal_record_id` — the reference the TSE's own audit rows carry — instead of leaving it empty.
     */
    @Test
    fun `the payout and storno audit rows carry the signing TSE transaction as fiscal_record_id`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            // The harness signs nothing unless the TSE runs in simulation (as BillCreateResponseTseTest).
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.SIMULATION, timeAdminPin = "86420"))
            val (payout, storno) =
                try {
                    val payout = payOutOk(client, zBon.id, anna(), "5.00")
                    val reversed = reverse(client, zBon.id, payout.kassenbuchHeaderId!!)
                    assertEquals(HttpStatusCode.Created, reversed.status, reversed.bodyAsText())
                    payout to testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), reversed.bodyAsText())
                } finally {
                    ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "86420"))
                }

            transaction {
                listOf(
                    "PAY_OUT_EMPLOYEE_TIP" to payout.kassenbuchHeaderId,
                    "REVERSE_EMPLOYEE_TIP_PAYOUT" to storno.kassenbuchHeaderId,
                ).forEach { (operation, headerId) ->
                    val signedBy = KassenbuchHeader.findById(headerId!!)!!.tseTransaction
                    assertNotNull(signedBy, "$operation: the entry is TSE-signed")
                    val row =
                        TransactionAuditLogTable
                            .selectAll()
                            .where { TransactionAuditLogTable.operation eq operation }
                            .single()
                    assertEquals(headerId.toString(), row[TransactionAuditLogTable.entityId], operation)
                    assertEquals(signedBy.id.value.toString(), row[TransactionAuditLogTable.fiscalRecordId], operation)
                }
            }
        }

    @Test
    fun `a payout can be stornoed only once`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val payout = payOutOk(client, zBon.id, anna(), "2.00")
            assertEquals(HttpStatusCode.Created, reverse(client, zBon.id, payout.kassenbuchHeaderId!!).status)

            val second = reverse(client, zBon.id, payout.kassenbuchHeaderId!!)
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertTrue(second.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_ALREADY_REVERSED.name), second.bodyAsText())
            assertEquals(1, reversalEntries(), "the second storno booked nothing")
            assertDecimal("5.00", getDetail(client, zBon.id).tipPayouts.single { it.employeeId == anna() }.remaining)
        }

    @Test
    fun `only a payout of that closing can be stornoed`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = fixture.close(client)
            val payout = payOutOk(client, zBon.id, anna(), "2.00")
            val storno =
                testJson.decodeFromString(
                    ZBonDto.TipPayoutResponseDto.serializer(),
                    reverse(client, zBon.id, payout.kassenbuchHeaderId!!).bodyAsText(),
                )
            val aggregateId =
                transaction {
                    KassenbuchHeader
                        .find { KassenbuchHeaderTable.isZbonAggregate eq true }
                        .single()
                        .id.value
                }

            listOf(aggregateId, storno.kassenbuchHeaderId).forEach { headerId ->
                val response = reverse(client, zBon.id, headerId!!)
                assertEquals(HttpStatusCode.Conflict, response.status, "headerId=$headerId")
                assertTrue(response.bodyAsText().contains(PosErrorCode.ZBON_TIP_PAYOUT_NOT_A_PAYOUT.name), response.bodyAsText())
            }
            assertEquals(HttpStatusCode.NotFound, reverse(client, zBon.id + 999, payout.kassenbuchHeaderId!!).status)
            assertEquals(HttpStatusCode.BadRequest, reverse(client, zBon.id, payout.kassenbuchHeaderId!!, reason = " ").status)
            assertEquals(HttpStatusCode.Forbidden, reverse(client, zBon.id, payout.kassenbuchHeaderId!!, username = "cashier").status)
            assertEquals(1, reversalEntries())
        }

    // ============================================
    // Helpers
    // ============================================

    private suspend fun payOutOk(
        client: io.ktor.client.HttpClient,
        zBonId: Long,
        employeeId: String,
        amount: String,
    ): ZBonDto.TipPayoutResponseDto {
        val response = fixture.payOut(client, zBonId, employeeId, amount)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(ZBonDto.TipPayoutResponseDto.serializer(), response.bodyAsText())
    }

    private suspend fun reverse(
        client: io.ktor.client.HttpClient,
        zBonId: Long,
        payoutHeaderId: Long,
        reason: String = "falscher Mitarbeiter",
        username: String = "admin",
    ): io.ktor.client.statement.HttpResponse = fixture.reverse(client, zBonId, payoutHeaderId, reason, username)

    private fun reversalEntries(): Int =
        transaction {
            KassenbuchHeader
                .all()
                .count { it.correctionRole == CorrectionRole.REVERSAL && it.lineItems.any { li -> li.kind == KassenbuchLineKind.EMPLOYEE_TIP } }
        }

    private fun kassenbuch(): KassenbuchService = GlobalContext.get().get()

    private suspend fun getDetail(
        client: io.ktor.client.HttpClient,
        zBonId: Long,
    ): ZBonDto.ZBonResponseDto {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.get("/api/z-bon/$zBonId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
    }

    private fun payoutCount(): Int = transaction { EmployeeTipPayoutTable.selectAll().count().toInt() }

    /** Kassenbuch entries carrying an EMPLOYEE_TIP line, excluding the closing's own aggregate. */
    private fun tipPayoutEntries(): Int =
        transaction {
            KassenbuchHeader
                .all()
                .filter { !it.isZbonAggregate && it.lineItems.any { li -> li.kind == KassenbuchLineKind.EMPLOYEE_TIP } }
                .size
        }

    private fun assertDecimal(
        expected: String,
        actual: String,
        message: String? = null,
    ) = assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "${message ?: ""} expected $expected, got $actual")
}
