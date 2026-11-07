package org.example.project.features.kassenbuch

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelMappingGate
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.infrastructure.errors.PosException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import java.time.LocalDate as JavaLocalDate

/**
 * A DATEV Buchungsstapel for a period containing a **cash Forderungsausgleich** must export, and the
 * settlement must book against the tender that opened the receivable.
 *
 * `KassenbuchLineKind.RECEIVABLE_SETTLEMENT` declares `requiresVatRate = false`, so its line carries
 * no VAT rate. [org.example.project.features.kassenbuch.data.service.KassenbuchAccountResolver] used
 * to exempt only `NON_CASH_PAYMENT` from the rate lookup, so every other rate-less kind fell through
 * to `VatRateNormalizer.normalize(null)` -> null -> `Resolution.Legacy`, and
 * [KassenbuchBuchungsstapelRowMapper] throws 409 `DATEV_ACCOUNT_MAPPING_REQUIRED` on `Legacy` —
 * naming a mapping cell the operator cannot create, on a shop whose mapping is fully configured.
 * `getEntriesByDateRange` applies no `isZbonAggregate` filter, so **one** affected day failed the
 * whole month's export, and the Belege ZIP with it.
 *
 * The contra account needed no new mapping cell: the receivable's *opening* leg already books
 * through `mapping.paymentTypes[<receivable tender>]`, because `DrawerCashDerivation` groups
 * non-cash sale tender by payment type into `NON_CASH_PAYMENT` lines. The clearing's contra is that
 * same cell, so settling a receivable clears exactly the account opening it debited — which the
 * first test asserts directly by comparing the two legs' `gegenkonto`.
 *
 * **The Z-Bon is created by the real service, not hand-seeded**, for the reason
 * [DatevBuchungsstapelEmployeeTipTest] gives: a hand-built line could carry any `paymentTypeId` the
 * author imagines, including one production never emits. The pipeline below
 * (`getEntriesByDateRange` -> `mapHeader`) is exactly what
 * `DatevBuchungsstapelExportServiceImpl.generate` runs, minus the disk write and the export log.
 *
 * The mapping is passed straight to the mapper rather than seeded into settings: the
 * `ShopConfigurationManager` cache is process-wide and leaks between tests, and this gate must fail
 * for one reason only. It is a fully seeded SKR03 default, so this exercises the resolver and not
 * [org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelMappingGate]'s
 * null-mapping refusal — those are different 409s carrying the same error code.
 */
@DisplayName("DATEV Buchungsstapel — a day with a cash Forderungsausgleich still exports")
class DatevBuchungsstapelReceivableClearingTest {
    private val deviceId = "datev-clearing-device"

    /** Ids of the receivable tenders seeded by [setupDay]; `tenderBId` stays 0 in the single case. */
    private var tenderAId = 0L
    private var tenderBId = 0L

    /**
     * Deliberately DIFFERENT accounts per tender. Seeding both to the 1360 default would let a
     * regression that attributes every clearing to one tender still pass the two-tender assertions.
     */
    private val accountA = 1360
    private val accountB = 1361

    /**
     * The tipped-day sibling builds its mapping as a field, but the receivable tenders' ids only
     * exist once the fixture has run, so this is built per call instead.
     */
    private fun mapping(): DatevAccountMappingDto.MappingDto {
        val base = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, nonCashPaymentTypes = emptyList())
        return base.copy(
            paymentTypes =
                buildMap {
                    put(tenderAId, DatevAccountMappingDto.AccountCell(accountA))
                    if (tenderBId != 0L) put(tenderBId, DatevAccountMappingDto.AccountCell(accountB))
                },
        )
    }

    /**
     * One 50.00 receivable opened auf Rechnung and cleared in cash the same day.
     *
     * Before the fix this threw 409 at [exportPeriodRows] and the test failed in [fail] below — not
     * on an assertion, because no row was ever produced to assert on.
     */
    @Test
    fun `a cash receivable clearing exports and books against the tender that opened it`() =
        testApp(setupData = { setupDay(secondTender = false) }) { client ->
            createClosing(client)

            transaction {
                val rows = exportPeriodRows()

                val settlement =
                    assertNotNull(
                        settlementRows(rows).singleOrNull(),
                        "the clearing must book as its own row; got ${rows.map { it.buchungstext to it.umsatz }}",
                    )
                assertEquals(
                    0,
                    BigDecimal("50.00").compareTo(settlement.umsatz),
                    "the settlement row carries the cash actually cleared; got ${settlement.umsatz}",
                )
                assertEquals(1000, settlement.konto, "every Kassenbuch row books against the Kasse account")
                assertEquals(
                    accountA,
                    settlement.gegenkonto,
                    "the clearing clears the receivable tender's own cell, not Geldtransit and not an income account",
                )

                // The symmetry the fix rests on: the opening leg and the settling leg are the same
                // account with opposite signs, so the debtor actually closes. Booking the settlement
                // anywhere else would leave this -50.00 outstanding forever.
                val opening =
                    assertNotNull(
                        rows.singleOrNull { it.buchungstext.contains("Unbare Zahlung") },
                        "the receivable's opening leg must book too; got ${rows.map { it.buchungstext }}",
                    )
                assertEquals(
                    accountA,
                    opening.gegenkonto,
                    "opening and settling the same receivable must touch the SAME account",
                )
                assertEquals(
                    0,
                    BigDecimal("-50.00").compareTo(opening.umsatz),
                    "the opening leg is the negative of the settlement; got ${opening.umsatz}",
                )
            }
        }

    /**
     * Two invoices opened under two DIFFERENT receivable tenders, both cleared in cash on one day.
     *
     * This is the case a single summed clearing line cannot express at all: one line has one
     * `paymentTypeId`, so whichever tender it named, the other receivable would be cleared out of
     * the wrong account. One line per tender is the same shape `saleNonCashByType` already produces
     * for the opening legs.
     */
    @Test
    fun `a day clearing two receivable tenders emits one row per tender`() =
        testApp(setupData = { setupDay(secondTender = true) }) { client ->
            createClosing(client)

            transaction {
                val rows = exportPeriodRows()
                val settlements = settlementRows(rows)

                assertEquals(
                    2,
                    settlements.size,
                    "one settlement row per receivable tender; got ${settlements.map { it.buchungstext to it.umsatz }}",
                )
                val byAccount = settlements.associate { it.gegenkonto to it.umsatz }
                assertEquals(
                    setOf(accountA, accountB),
                    byAccount.keys,
                    "each clearing books to the account ITS OWN opening tender debited; got $byAccount",
                )
                assertEquals(
                    0,
                    BigDecimal("50.00").compareTo(byAccount.getValue(accountA)),
                    "the 50.00 invoice clears tender A; got ${byAccount.getValue(accountA)}",
                )
                assertEquals(
                    0,
                    BigDecimal("30.00").compareTo(byAccount.getValue(accountB)),
                    "the 30.00 invoice clears tender B; got ${byAccount.getValue(accountB)}",
                )

                // The invariant the clearing lines exist to preserve: 80.00 of turnover opened as
                // receivables (-80.00 of non-cash tender) and 80.00 of cash came back over the counter.
                assertEquals(
                    0,
                    BigDecimal("80.00").compareTo(rows.sumOf { it.umsatz }),
                    "the day's rows must still net to the cash the drawer took; got " +
                        "${rows.map { it.buchungstext to it.umsatz }}",
                )
            }
        }

    /**
     * ONE invoice whose sale was tendered across TWO receivable tenders, cleared in cash — the
     * shape `DrawerCashDerivation.receivableTenderOf` deliberately leaves unresolved.
     *
     * The export still refuses it, and that is CORRECT and unchanged: splitting one clearing across
     * several opening tenders has no ground truth in the data, so a guessed split would put a
     * plausible wrong contra account in the ledger, where nothing downstream flags it. What this
     * test pins is the SENTENCE. The refusal used to read
     *
     *     No DATEV account is configured for entry type CASH_SALE (line kind
     *     RECEIVABLE_SETTLEMENT, VAT rate -, payment type -) on Beleg <n>. Complete the account
     *     mapping under <the mapping screen> and export again.
     *
     * — a remedy its reader cannot perform. There is no "unknown tender" cell in
     * `mapping.paymentTypes` and none can be created; both tenders here ARE mapped (see [mapping]),
     * so an operator sent to the mapping screen finds it complete and hunts indefinitely. That is
     * the same unactionable-message defect the clearing fix itself was about.
     */
    @Test
    fun `an unresolvable opening tender is refused with a message about the invoice, not the mapping`() =
        testApp(setupData = { setupDay(secondTender = false, splitAcrossOneBill = true) }) { client ->
            createClosing(client)

            transaction {
                val ex = assertFailsWith<PosException> { mapPeriod() }
                assertEquals(
                    PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
                    ex.code,
                    "the refusal keeps its error code — only the message changes",
                )
                val message = assertNotNull(ex.message, "the refusal must carry a message at all")

                assertTrue(
                    message.contains("Forderungsausgleich"),
                    "the message must name the kind of line that failed; got: $message",
                )
                assertTrue(
                    message.contains("opening payment type could not be determined"),
                    "the message must state the real cause instead of a missing cell; got: $message",
                )
                assertTrue(
                    message.contains("invoice"),
                    "the message must point at the invoice, which is where the answer is; got: $message",
                )
                assertTrue(
                    message.contains("mapping is NOT at fault"),
                    "the message must clear the mapping configuration explicitly, or the reader goes " +
                        "to the settings screen anyway; got: $message",
                )

                // The two regression guards: the old sentence must be gone — both its claim and its
                // instruction. A reader following either spends exactly the time this fix removes.
                assertFalse(
                    message.contains("No DATEV account is configured"),
                    "this line's account is not unconfigured, it is unresolvable; got: $message",
                )
                assertFalse(
                    message.contains(DatevBuchungsstapelMappingGate.MAPPING_LOCATION),
                    "the mapping screen is a dead end for this refusal; got: $message",
                )
            }
        }

    // ============================================
    // Export pipeline
    // ============================================

    private fun settlementRows(rows: List<BuchungsstapelRowInput>): List<BuchungsstapelRowInput> =
        rows.filter { it.buchungstext.contains("Forderungsausgleich") }

    /**
     * [mapPeriod] for the cases where a refusal IS the defect: a [PosException] is reported with its
     * own message rather than thrown as an opaque failure.
     *
     * The ambiguous-tender case above deliberately does NOT use this — there the refusal is the
     * subject under test, not the defect, and only its wording is at issue.
     */
    private fun exportPeriodRows(): List<BuchungsstapelRowInput> =
        try {
            mapPeriod()
        } catch (e: PosException) {
            fail(
                "a fully configured shop must be able to export a day containing a cash " +
                    "Forderungsausgleich, but the Buchungsstapel export refused it: ${e.message}",
            )
        }

    /**
     * Runs the production Buchungsstapel pipeline over a window around today.
     *
     * The window is relative to `LocalDate.now()` because the Z-Bon stamps the aggregate with the
     * current instant and `getEntriesByDateRange` filters on `createdAt`, not the operator-typed
     * `belegDatum` — a hardcoded date would rot into an empty period rather than testing the export.
     */
    private fun mapPeriod(): List<BuchungsstapelRowInput> {
        val shop = ShopInfo.all().first()
        val today = JavaLocalDate.now()
        val headers =
            KassenbuchRepositoryImpl(testKassenbuchDocuments()).getEntriesByDateRange(
                shop = shop,
                startDate = today.minusDays(1).toString(),
                endDate = today.plusDays(1).toString(),
            )
        assertTrue(
            headers.any { it.isZbonAggregate },
            "fixture sanity: the Z-Bon aggregate must fall inside the exported period, or this " +
                "test would pass without ever mapping a clearing line",
        )
        return headers.flatMap { KassenbuchBuchungsstapelRowMapper.mapHeader(it, mapping = mapping()) }
    }

    // ============================================
    // Fixture helpers
    // ============================================

    private suspend fun createClosing(client: io.ktor.client.HttpClient) {
        val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
        val response = client.post("/api/z-bon") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")
    }

    /**
     * @param secondTender also open and clear a 30.00 receivable under a SECOND tender, so the day
     *   exercises the multi-tender split rather than a single summed clearing.
     * @param splitAcrossOneBill instead open ONE 50.00 receivable whose sale was tendered across
     *   BOTH receivable tenders, and clear that in cash. `receivableTenderOf` returns null for this
     *   shape, so the clearing line carries no paymentTypeId and the export refuses it. Replaces
     *   [secondTender]'s one-invoice-per-tender shape; both tenders are seeded and mapped either
     *   way, so the refusal can never be blamed on a missing mapping cell.
     */
    private fun setupDay(
        secondTender: Boolean,
        splitAcrossOneBill: Boolean = false,
    ) {
        val shop = TestData.createShopInfo()
        val admin = TestData.createAdmin(employeeNumber = "DBC_ADM", username = "admin")
        val vat19 = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType("Bar_DBC", isCash = true)
        val onAccountA = TestData.createPaymentType("Auf Rechnung A", isCash = false)
        val onAccountB = TestData.createPaymentType("Auf Rechnung B", isCash = false)
        val tws = TestData.createTableWithSession("DBC1")
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TERM-DBC")

        tenderAId = onAccountA.id.value
        // Mapped in BOTH multi-tender shapes. The ambiguous day must fail because the opening
        // tender is unresolvable, never because tender B has no cell.
        tenderBId = if (secondTender || splitAcrossOneBill) onAccountB.id.value else 0L

        transaction {
            // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")

            // `createsReceivable` is the flag that makes a tender open a receivable rather than take
            // money; TestData has no parameter for it, so it is set here like the sibling suites do.
            PaymentType.findById(onAccountA.id)!!.createsReceivable = true
            PaymentType.findById(onAccountB.id)!!.createsReceivable = true

            val now = currentTimeStamp()
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            if (splitAcrossOneBill) {
                openSplitAndClear(shop, admin, session, vat19, onAccountA, onAccountB, cash)
            } else {
                openAndClear(shop, admin, session, vat19, onAccountA, cash, gross = "50.00", net = "42.02", vat = "7.98", tag = "A")
                if (secondTender) {
                    openAndClear(shop, admin, session, vat19, onAccountB, cash, gross = "30.00", net = "25.21", vat = "4.79", tag = "B")
                }
            }
        }
    }

    /**
     * One receivable, end to end: an ordinary sale settled **auf Rechnung** (which is what opens the
     * receivable and books the `NON_CASH_PAYMENT` leg), the issued invoice, and the clearing bill
     * that takes the cash back over the counter.
     *
     * Both bills are left unattributed (`zBon == null`) so the closing sweeps them into the same
     * day — the realistic shape of an invoice raised and settled before the till is closed.
     */
    @Suppress("LongParameterList")
    private fun openAndClear(
        shop: ShopInfo,
        employee: Employee,
        session: TableSession,
        vatRate: Vat,
        receivableTender: PaymentType,
        cashTender: PaymentType,
        gross: String,
        net: String,
        vat: String,
        tag: String,
    ) {
        val now = currentTimeStamp()
        val amount = BigDecimal(gross)

        val originalBill =
            Bill.new {
                this.employeeId = employee
                this.tableSession = session
                this.billNumber = "DBC-OPEN-$tag"
                this.billAmount = amount
                this.paidAmount = amount
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
            }
        BillVatDetail.new {
            this.bill = originalBill
            this.vat = vatRate
            this.netAmount = BigDecimal(net)
            this.vatAmount = BigDecimal(vat)
            this.totalAmount = amount
        }
        Payment.new {
            this.bill = originalBill
            this.paymentType = receivableTender
            this.amount = amount
            this.currency = "EUR"
            this.paymentStatus = PaymentStatus.COMPLETED
            this.paymentTime = now
            // The leg that makes this a receivable at all — what `receivableTenderOf` reads back.
            this.createsReceivable = true
        }

        val invoice =
            SalesInvoice.new {
                this.shop = shop
                this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                this.status = SalesInvoiceStatus.ISSUED
                this.buyerName = "Catering $tag GmbH"
                this.issueDate = now
                this.serviceDate = now
                this.billId = originalBill.id
            }

        val clearingBill =
            Bill.new {
                this.employeeId = employee
                this.tableSession = session
                this.billNumber = "DBC-CLR-$tag"
                this.billAmount = amount
                this.paidAmount = amount
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.clearsSalesInvoiceId = invoice.id.value
            }
        Payment.new {
            this.bill = clearingBill
            this.paymentType = cashTender
            this.amount = amount
            this.currency = "EUR"
            this.paymentStatus = PaymentStatus.COMPLETED
            this.paymentTime = now
            // A clearing tender never opens a receivable — it settles one.
            this.createsReceivable = false
        }
    }

    /**
     * ONE 50.00 sale tendered across TWO receivable tenders (30.00 + 20.00), invoiced, and cleared
     * by a single 50.00 cash bill.
     *
     * This is the shape `receivableTenderOf` cannot resolve: it reads the original's
     * `createsReceivable` legs, finds two DIFFERENT payment types, and `singleOrNull()` returns
     * null. The clearing therefore reaches the Kassenbuch as a line with no paymentTypeId. It is a
     * separate helper rather than a flag on [openAndClear] because the two differ in what they
     * build, not in their amounts — one bill with two receivable legs, not two bills with one each.
     */
    @Suppress("LongParameterList")
    private fun openSplitAndClear(
        shop: ShopInfo,
        employee: Employee,
        session: TableSession,
        vatRate: Vat,
        receivableTenderA: PaymentType,
        receivableTenderB: PaymentType,
        cashTender: PaymentType,
    ) {
        val now = currentTimeStamp()
        val gross = BigDecimal("50.00")

        val originalBill =
            Bill.new {
                this.employeeId = employee
                this.tableSession = session
                this.billNumber = "DBC-OPEN-SPLIT"
                this.billAmount = gross
                this.paidAmount = gross
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
            }
        BillVatDetail.new {
            this.bill = originalBill
            this.vat = vatRate
            this.netAmount = BigDecimal("42.02")
            this.vatAmount = BigDecimal("7.98")
            this.totalAmount = gross
        }
        // The two legs that make the opening tender ambiguous. Distinct loop-variable names on
        // purpose: `Payment` has its own `amount`, and an Exposed builder's receiver shadows.
        listOf(
            receivableTenderA to BigDecimal("30.00"),
            receivableTenderB to BigDecimal("20.00"),
        ).forEach { (tender, legAmount) ->
            Payment.new {
                this.bill = originalBill
                this.paymentType = tender
                this.amount = legAmount
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
                this.createsReceivable = true
            }
        }

        val invoice =
            SalesInvoice.new {
                this.shop = shop
                this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                this.status = SalesInvoiceStatus.ISSUED
                this.buyerName = "Catering Split GmbH"
                this.issueDate = now
                this.serviceDate = now
                this.billId = originalBill.id
            }

        val clearingBill =
            Bill.new {
                this.employeeId = employee
                this.tableSession = session
                this.billNumber = "DBC-CLR-SPLIT"
                this.billAmount = gross
                this.paidAmount = gross
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.clearsSalesInvoiceId = invoice.id.value
            }
        Payment.new {
            this.bill = clearingBill
            this.paymentType = cashTender
            this.amount = gross
            this.currency = "EUR"
            this.paymentStatus = PaymentStatus.COMPLETED
            this.paymentTime = now
            this.createsReceivable = false
        }
    }
}
