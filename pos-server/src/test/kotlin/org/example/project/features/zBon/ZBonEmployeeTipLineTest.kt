package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.NonTaxableTipCarrier
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E-2026-09-15 N1 / plan 2026-09-16 P1.3: the Z-Bon leg of the non-taxable-tip change.
 *
 * An EMPLOYEE tip (TrinkgeldAN) is non-taxable and is NOT turnover, so P1.1 stopped storing it as a
 * 0 % [org.example.project.features.bill.domain.models.BillVatDetail] row. Three consequences meet
 * here, and they pull in opposite directions:
 *
 *  1. `DrawerCashDerivation.revenueGross` sums `BillVatDetail.totalAmount`, so it drops by the tip —
 *     but the cash **is** in the drawer. Without adding it back the closing understates the till by
 *     the tip and the operator counts a phantom shortage.
 *  2. `report.vatGroups` no longer carries a 0 %-rate group, which is the point: a tip is not
 *     zero-rated turnover and must not appear as a VAT group on the Z-Bon at all.
 *  3. The Kassenbuch line the tip used to surface as — `"TE0%"`, emitted from the `vatGroups` loop —
 *     therefore vanishes, so the aggregate entry's lines would no longer sum to its own header.
 *     It is replaced by an explicit Trinkgeld line.
 *
 * **The bill is closed through the real [BillService], not hand-seeded.** Every other tipped-bill
 * fixture in this repo builds `Bill.new { ... }` (and its VAT rows) by hand, which is exactly why
 * the suite stayed green through this whole class of break: a hand-seeded bill can carry any VAT
 * shape the author imagines, including the 0 %-tip-row shape production can no longer produce.
 * Closing through `createBill` is what makes this a witness to the REAL post-P1.1 shape.
 *
 * The closer is a MANAGER on purpose: `BillRepositoryImpl` derives TrinkgeldAN for every non-ADMIN
 * closer and TrinkgeldAG for an ADMIN, and only the former is non-taxable. The Z-Bon itself is
 * created by the admin, which is a separate concern from who closed the bill.
 */
@DisplayName("Z-Bon — a non-taxable employee tip stays in the drawer (E2E-2026-09-15 N1)")
class ZBonEmployeeTipLineTest {
    private val deviceId = "zbon-employee-tip-device"

    /** Ids produced by [setupTippedCashDay], read inside the test bodies. */
    private data class Fixture(
        val billId: Long,
        val sessionId: Long,
        val tableId: Long,
    )

    private var fixture: Fixture? = null

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /**
     * The drawer leg. 20.00 item + 3.00 employee tip, all 23.00 tendered in cash, so every cent is
     * physically in the till. `revenueGross` sees only the 20.00 taxable turnover, so the aggregate
     * CASH_SALE entry is short by the tip unless it is added back explicitly.
     */
    @Test
    fun `a cash employee tip stays in the drawer and in the closing balance`() =
        testApp(setupData = { setupTippedCashDay() }) { client ->
            assertFixtureIsPostP11()

            val dto = createClosing(client)

            assertEquals(
                "23.00",
                dto.closingBalance,
                "the drawer physically holds 23.00 (20.00 item + 3.00 cash tip); 20.00 here is the " +
                    "tip going missing from the till with the 0 % VAT row P1.1 removed",
            )
            transaction {
                assertEquals(
                    0,
                    BigDecimal("23.00").compareTo(aggregateEntry().totalAmount),
                    "the aggregate CASH_SALE entry books the full takings including the tip",
                )
            }
        }

    /**
     * The turnover leg. A tip is not zero-rated turnover, so it must not show up as a VAT group —
     * that fake 0 % group is what leaked into Z-Bon VAT reporting and revenue figures before P1.1.
     */
    @Test
    fun `an employee tip produces no ZERO-rate VAT group on the report`() =
        testApp(setupData = { setupTippedCashDay() }) { client ->
            assertFixtureIsPostP11()

            val dto = createClosing(client)
            val vatGroups = assertNotNull(dto.vatGroups, "the closing should still report its taxable turnover")

            assertFalse(
                vatGroups.any { BigDecimal(it.vatRate).compareTo(BigDecimal.ZERO) == 0 },
                "a non-taxable tip must not appear as a 0 %-rate VAT group; got $vatGroups",
            )
            assertEquals(1, vatGroups.size, "only the 19 % item turnover is a VAT group; got $vatGroups")
            assertEquals(
                0,
                BigDecimal(vatGroups.single().grossAmount).compareTo(BigDecimal("20.00")),
                "the VAT group carries the taxable turnover alone (20.00), not the 23.00 tendered; got $vatGroups",
            )
        }

    /**
     * The Kassenbuch leg. The tip used to ride out of the `vatGroups` loop as a `"TE0%"` line; with
     * no 0 % group it would simply disappear, leaving the aggregate's lines short of its own header.
     * It must instead be named for what it is.
     */
    @Test
    fun `the Kassenbuch aggregate carries a Trinkgeld line and no TE0pct line`() =
        testApp(setupData = { setupTippedCashDay() }) { client ->
            assertFixtureIsPostP11()

            createClosing(client)

            transaction {
                val entry = aggregateEntry()
                val lines = entry.lineItems.sortedBy { it.lineNumber }
                val texts = lines.map { it.belegtext }

                assertFalse(
                    texts.any { it != null && it.startsWith("TE0") },
                    "the tip is not zero-rated turnover, so no TE0% line may be emitted; got $texts",
                )
                val tipLine =
                    assertNotNull(
                        lines.singleOrNull { it.belegtext?.contains("Trinkgeld") == true },
                        "the tip must surface as its own Trinkgeld line, or the aggregate's lines no " +
                            "longer sum to its header; got $texts",
                    )
                assertEquals(
                    0,
                    BigDecimal("3.00").compareTo(tipLine.vorzBetrag),
                    "the Trinkgeld line carries the full tip; got ${tipLine.vorzBetrag}",
                )
                // "0", not null, and deliberately so (P1.3a): a rate-less line resolves to
                // Resolution.Legacy in KassenbuchAccountResolver and makes the DATEV Buchungsstapel
                // export throw 409 for the whole period — see the call site in ZBonServiceImpl and
                // DatevBuchungsstapelEmployeeTipTest. "0" is the pre-P1.3 status quo and books
                // through the same placeholder cell the old TE0% line did. It is imprecise (a
                // non-taxable tip is not zero-RATED turnover); the correct model is a filed
                // follow-up. Pinned here so the null cannot come back unnoticed.
                assertEquals("0", tipLine.ustSatz, "the Trinkgeld line carries the placeholder 0 rate; got ${tipLine.ustSatz}")

                // The invariant the line exists to preserve.
                assertEquals(
                    0,
                    lines.sumOf { it.vorzBetrag }.compareTo(entry.totalAmount),
                    "the aggregate's lines must sum to its header (${entry.totalAmount}); got " +
                        "${lines.map { it.belegtext to it.vorzBetrag }}",
                )
            }
        }

    /**
     * The OWNER branch (TrinkgeldAG), which had **no Z-Bon-level coverage at all** — the hole that
     * let `ZBonCreateReportMixedDayTest` hand-seed an owner-tipped bill whose VAT rows were 5.00
     * short of the cash the same test asserted was tendered, a shape `createBill` would reject.
     *
     * An owner tip is taxed into the item rates, so it is already inside `vatDetails` and
     * [NonTaxableTipCarrier] must contribute ZERO. If it did not, `revenueGross` and the carrier
     * would BOTH carry the tip and the closing would overstate the drawer by it — the mirror image
     * of the understatement the employee-tip tests above pin.
     */
    @Test
    fun `an owner tip is inside the VAT breakdown and the drawer still holds the full tender`() =
        testApp(setupData = { setupOwnerTippedCashDay() }) { client ->
            transaction {
                val bill = Bill.findById(assertNotNull(fixture).billId)!!
                assertEquals(
                    TipRecipient.OWNER,
                    bill.tipRecipient,
                    "fixture sanity: an ADMIN closer must yield TrinkgeldAG",
                )
                assertEquals(
                    0,
                    BigDecimal.ZERO.compareTo(NonTaxableTipCarrier.outsideVatDetails(bill)),
                    "an owner tip already sits in vatDetails; carrying it again would double the tip",
                )
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal("23.00")),
                    "the owner tip is folded into the item rates, so the VAT rows carry the full tender",
                )
            }

            val dto = createClosing(client)

            assertEquals(
                "23.00",
                dto.closingBalance,
                "the drawer holds every cent tendered (20.00 item + 3.00 owner tip)",
            )
            val vatGroups = assertNotNull(dto.vatGroups)
            assertEquals(1, vatGroups.size, "one rate, with the tip folded into it; got $vatGroups")
            assertEquals(
                0,
                BigDecimal(vatGroups.single().grossAmount).compareTo(BigDecimal("23.00")),
                "an owner tip IS turnover at the item rate, unlike an employee tip; got $vatGroups",
            )
        }

    // ============================================
    // Shared assertions
    // ============================================

    /**
     * Pins the fixture's shape BEFORE the closing runs. Without this the assertions above would pass
     * vacuously against a pre-P1.1 bill, whose own 0 % row would still feed both the drawer and the
     * `TE0%` line through the normal `vatGroups` loop.
     */
    private fun assertFixtureIsPostP11() {
        val f = assertNotNull(fixture)
        transaction {
            val bill = Bill.findById(f.billId)!!
            assertEquals(
                TipRecipient.EMPLOYEE,
                bill.tipRecipient,
                "fixture sanity: a MANAGER closer must yield TrinkgeldAN — only that tip is non-taxable",
            )
            assertEquals(0, BigDecimal("3.00").compareTo(bill.tips), "fixture sanity: the bill carries a 3.00 tip")
            assertTrue(
                bill.vatDetails.none { it.vat.vatRate.compareTo(BigDecimal.ZERO) == 0 },
                "fixture sanity: after P1.1 the employee tip is NOT a 0 % VAT row; got " +
                    "${bill.vatDetails.map { it.vat.vatRate to it.totalAmount }}",
            )
            assertEquals(
                0,
                bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal("20.00")),
                "fixture sanity: vatDetails carry the taxable turnover only (20.00), not the 23.00 tendered",
            )
        }
    }

    // ============================================
    // Fixture helpers
    // ============================================

    private suspend fun createClosing(client: io.ktor.client.HttpClient): ZBonDto.ZBonResponseDto {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
        return testJson.decodeFromString<ZBonDto.ZBonResponseDto>(response.bodyAsText())
    }

    /** The takings booked into the fiscal cash book — the aggregate CASH_SALE entry. */
    private fun aggregateEntry(): KassenbuchHeader =
        assertNotNull(
            KassenbuchHeader
                .find { KassenbuchHeaderTable.isZbonAggregate eq true }
                .orderBy(KassenbuchHeaderTable.id to SortOrder.DESC)
                .firstOrNull(),
            "the Z-Bon should have booked its aggregate cash entry",
        )

    /**
     * One 20.00 item closed by a MANAGER with a 3.00 tip, fully tendered in cash, through the real
     * [BillService]. The session is then closed and the table freed so the closing's
     * `validateSessionAndTableStatus` passes.
     */
    private fun setupTippedCashDay() = setupTippedDay(closeAsAdmin = false)

    /**
     * The same day closed by an ADMIN, which `BillRepositoryImpl` derives TrinkgeldAG (OWNER) for.
     */
    private fun setupOwnerTippedCashDay() = setupTippedDay(closeAsAdmin = true)

    /**
     * @param closeAsAdmin close through the ADMIN (an OWNER tip) instead of the MANAGER (an EMPLOYEE
     *   tip). The Z-Bon itself is always created by the admin — a separate concern from who closed
     *   the bill.
     */
    private fun setupTippedDay(closeAsAdmin: Boolean) {
        TestData.createShopInfo()
        val admin = TestData.createAdmin(employeeNumber = "ZET_ADM", username = "admin")
        val closer =
            if (closeAsAdmin) admin else TestData.createManager(employeeNumber = "ZET_MGR", username = "zet_mgr")
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Cash_ZET", isCash = true)
        val tws = TestData.createTableWithSession("ZET1")
        val category = TestData.createCategory("ZET Cat")
        val product = TestData.createProduct(category.id.value, "ZET Product", "ZET_P", BigDecimal("20.0000"))
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-ZET")

        val itemId =
            transaction {
                // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

                val session = TableSession.findById(tws.session.id)!!
                // Authorize the closer as the session owner.
                session.employeeId = closer
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("20.00")
                        this.finalPrice = BigDecimal("20.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = vat19
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        val billId =
            runBlocking {
                GlobalContext
                    .get()
                    .get<BillService>()
                    .createBill(
                        employeeId = closer.id.value.toString(),
                        deviceId = deviceId,
                        request =
                            BillDto.CreateBillRequestDto(
                                tableSessionId = tws.session.id.value,
                                orderItems = listOf(itemId),
                                billAmount = "20.00",
                                tips = "3.00",
                                payments =
                                    listOf(
                                        PaymentDto.PaymentRequest(amount = "23.00", paymentTypeId = cash.id.value),
                                    ),
                            ),
                    ).billId
            }

        transaction {
            val now = currentTimeStamp()
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE
        }

        fixture = Fixture(billId = billId, sessionId = tws.session.id.value, tableId = tws.table.id.value)
    }
}
