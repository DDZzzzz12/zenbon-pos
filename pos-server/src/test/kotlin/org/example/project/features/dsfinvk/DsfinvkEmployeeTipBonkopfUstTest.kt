package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E-2026-09-15 N1 / plan 2026-09-16 P1.2: an EMPLOYEE tip (TrinkgeldAN) is non-taxable and is
 * NOT turnover, so P1.1 stopped storing it as a 0 % [org.example.project.features.bill.domain.models.BillVatDetail]
 * row. `generateBonkopfUst` builds `transactions_vat.csv` FROM those rows, so without a
 * hand-built key-5 row of its own the per-Beleg gross is short by the tip while
 * `transactions.UMS_BRUTTO` (`billAmount − promotionAmount + tips − …`) is not — the exact
 * three-way disagreement audit H3/H4 fixed once before.
 *
 * `lines_vat` (`generateBonposUst`) and `businesscases` already emit the tip at key 5; of the
 * three views `transactions_vat` was the only one that disagreed.
 *
 * **The bill is closed through the real [BillService], not hand-seeded.** Every other tipped-bill
 * fixture in this package builds its `Bill` (and its `BillVatDetail` rows) by hand, which is
 * precisely why the whole suite stayed green through this break: a hand-seeded bill can carry any
 * VAT shape the fixture author imagines, including the 0 %-tip-row shape production can no longer
 * produce. Closing through `createBill` is what makes this a witness to the REAL post-P1.1 shape.
 * Only the Kassenabschluss is seeded — it is the export's selection key
 * (`Bill.find { zBonId inList … }`), not part of the claim under test.
 *
 * The four cases below are the four shapes the key-5 row can take, and the last three are the ones
 * where a wrong row is a fiscal error rather than a cosmetic one:
 *  - forward sale: the tip is the ONLY key-5 source, so it must appear;
 *  - 0 %-rated item + tip: TWO sources land on key 5 for one BON_ID, so they must COLLAPSE into one
 *    row — every other invariant in the suite is a sum, and a split row sums identically;
 *  - storno: the reverse Beleg carries no tip of its own, so the row is read off the original and
 *    negated;
 *  - pre-upgrade bill: its own 0 % row already produced the key-5 row, so emitting another would
 *    double-count the tip.
 */
@DisplayName("transactions_vat carries the employee tip as key 5 (E2E-2026-09-15 N1)")
class DsfinvkEmployeeTipBonkopfUstTest {
    private val deviceId = "emptip-bonkopf-device"
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    /**
     * The bill shape a test closes through the real [BillService].
     *
     * [zeroRated] sells the item at the 0 % rate, which maps to the SAME UST_SCHLUESSEL 5 the
     * non-taxable tip uses — the one configuration where two independent row sources collide on one
     * (BON_ID, UST_SCHLUESSEL).
     */
    private data class TippedBillSpec(
        val item: BigDecimal,
        val tip: BigDecimal,
        val zeroRated: Boolean = false,
    )

    @Test
    fun `employee tip - per-Beleg BRUTTO reconciles and the tip is key 5`() {
        var billId = 0L
        testApp(
            setupData = {
                // 20.00 item @19 %, 3.00 EMPLOYEE tip, 23.00 cash — closed by the real service.
                billId = closeTippedBillThroughTheService(TippedBillSpec(item = BigDecimal("20.00"), tip = BigDecimal("3.00")))
                attachToClosing(billId)
            },
        ) { _ ->
            // Fixture shape FIRST: without this the assertions below would pass vacuously against a
            // pre-P1.1 bill, whose own 0 % row would produce the key-5 row through the normal loop.
            transaction {
                val bill = Bill.findById(billId)!!
                assertTrue(
                    bill.vatDetails.none { it.vat.vatRate.compareTo(BigDecimal.ZERO) == 0 },
                    "fixture sanity: after P1.1 the employee tip is NOT a 0 % VAT row; got " +
                        "${bill.vatDetails.map { it.vat.vatRate to it.totalAmount }}",
                )
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal("20.00")),
                    "fixture sanity: vatDetails carry the taxable turnover only, not the tip",
                )
            }

            val zip = exportZip()
            val bonId = "B-$billId"
            val umsBrutto = umsBruttoOf(zip, bonId)
            assertEquals(
                0,
                umsBrutto.compareTo(BigDecimal("23.00")),
                "sanity: UMS_BRUTTO already carries the tip (20.00 item + 3.00 tip); got $umsBrutto",
            )

            val vatRows = vatRowsFor(zip, bonId)
            val sumBonBrutto = vatRows.sumOf { parseAmount(it["BON_BRUTTO"]!!) }
            assertEquals(
                0,
                sumBonBrutto.compareTo(BigDecimal("23.00")),
                "Σ transactions_vat must equal UMS_BRUTTO (20.00 + 3.00 tip); got $sumBonBrutto from $vatRows",
            )
            assertEquals(
                0,
                sumBonBrutto.compareTo(umsBrutto),
                "per-Beleg reconciliation: Σtransactions_vat.BON_BRUTTO ($sumBonBrutto) must equal " +
                    "transactions.UMS_BRUTTO ($umsBrutto)",
            )

            val tipRow = vatRows.single { it["UST_SCHLUESSEL"] == "5" }
            assertEquals(
                0,
                parseAmount(tipRow["BON_BRUTTO"]!!).compareTo(BigDecimal("3.00")),
                "the tip is booked at key 5 'nicht steuerbar' with its full gross; got $tipRow",
            )
            assertEquals(0, parseAmount(tipRow["BON_NETTO"]!!).compareTo(BigDecimal("3.00")), "netto == brutto: $tipRow")
            assertEquals(0, parseAmount(tipRow["BON_UST"]!!).compareTo(BigDecimal.ZERO), "a non-taxable tip carries no USt: $tipRow")
        }
    }

    /**
     * A 0 %-rated item maps to UST_SCHLUESSEL 5, the same key the non-taxable tip uses, so ONE
     * BON_ID now has TWO independent key-5 row sources: the `BillVatDetail` loop (the item) and the
     * [org.example.project.utils.NonTaxableTipCarrier] block (the tip).
     *
     * DSFinV-K 3.1.2.1 makes (Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, BON_ID, UST_SCHLUESSEL) the key of
     * `Bonkopf_USt`, so two rows are a MALFORMED export — and an invisible one: Σ BON_BRUTTO is
     * 8,00 either way, so every sum-shaped invariant in this package passes on the split. The row
     * COUNT is the only assertion that can tell the two apart, which is why it is the load-bearing
     * one here.
     */
    @Test
    fun `zero-rated item plus employee tip - one key-5 row, not two`() {
        var billId = 0L
        testApp(
            setupData = {
                // 5.00 item @0 %, 3.00 EMPLOYEE tip, 8.00 cash.
                billId =
                    closeTippedBillThroughTheService(
                        TippedBillSpec(item = BigDecimal("5.00"), tip = BigDecimal("3.00"), zeroRated = true),
                    )
                attachToClosing(billId)
            },
        ) { _ ->
            // Fixture shape FIRST: the claim is about a post-P1.1 bill whose SINGLE VAT row is the
            // 0 %-rated ITEM. On a pre-upgrade bill there would be a second 0 % row (the tip) and
            // the carrier would return ZERO, so the collision under test would not arise at all.
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(1, bill.vatDetails.count(), "fixture sanity: only the item's own 0 % row exists")
                assertTrue(
                    bill.vatDetails.all { it.vat.vatRate.compareTo(BigDecimal.ZERO) == 0 },
                    "fixture sanity: the item really is 0 %-rated, so it maps to key 5 like the tip",
                )
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(BigDecimal("5.00")),
                    "fixture sanity: Σ vatDetails is the item alone (5.00), not 8.00 — the tip is carried outside",
                )
            }

            val zip = exportZip()
            val bonId = "B-$billId"
            val vatRows = vatRowsFor(zip, bonId)
            val keyFiveRows = vatRows.filter { it["UST_SCHLUESSEL"] == "5" }

            assertEquals(
                1,
                keyFiveRows.size,
                "DSFinV-K 3.1.2.1 keys Bonkopf_USt on (…, BON_ID, UST_SCHLUESSEL), so the 0 %-rated " +
                    "item and the non-taxable tip must COLLAPSE into ONE key-5 row; got $keyFiveRows",
            )
            assertEquals(
                0,
                parseAmount(keyFiveRows.single()["BON_BRUTTO"]!!).compareTo(BigDecimal("8.00")),
                "the collapsed row carries both the 0 % item (5.00) and the tip (3.00); got ${keyFiveRows.single()}",
            )
            assertEquals(
                0,
                parseAmount(keyFiveRows.single()["BON_UST"]!!).compareTo(BigDecimal.ZERO),
                "neither a 0 %-rated item nor a non-taxable tip carries USt; got ${keyFiveRows.single()}",
            )
            // The sum is deliberately asserted LAST: it holds on the split row too, so on its own it
            // would certify the malformed export.
            assertEquals(
                0,
                vatRows.sumOf { parseAmount(it["BON_BRUTTO"]!!) }.compareTo(umsBruttoOf(zip, bonId)),
                "Σ transactions_vat must still equal UMS_BRUTTO; got $vatRows",
            )
        }
    }

    /**
     * The storno leg of the P1.2 block (`sourceBill`/negate). A reverse Beleg carries no tip of its
     * own, so the amount is read off the ORIGINAL bill and negated — exactly as the VAT rows are.
     *
     * `DsfinvkTipReconciliationTest` pins the storno at the `businesscases` surface only; this is
     * the `transactions_vat` witness, and it closes AND stornos through the real service rather than
     * hand-seeding a reverse bill, so it is a gate on `deleteBill`'s actual output.
     */
    @Test
    fun `storno of a tipped bill - the key-5 tip is reversed exactly once`() {
        var reversalId = 0L
        testApp(
            setupData = {
                val originalId =
                    closeTippedBillThroughTheService(TippedBillSpec(item = BigDecimal("20.00"), tip = BigDecimal("3.00")))
                reversalId = stornoOf(originalId)
                // BOTH bills must hang off the closing: the export selects on zBonId alone.
                attachToClosing(originalId, reversalId)
            },
        ) { _ ->
            transaction {
                val reversal = Bill.findById(reversalId)!!
                assertTrue(reversal.deletedBill != null, "fixture sanity: this really is a reverse Beleg")
            }

            val zip = exportZip()
            val bonId = "B-$reversalId"
            val vatRows = vatRowsFor(zip, bonId)
            val keyFiveRows = vatRows.filter { it["UST_SCHLUESSEL"] == "5" }

            assertEquals(1, keyFiveRows.size, "the reverse Beleg carries exactly ONE key-5 row; got $keyFiveRows")
            assertEquals(
                0,
                parseAmount(keyFiveRows.single()["BON_BRUTTO"]!!).compareTo(BigDecimal("-3.00")),
                "the tip must be reversed at its full gross (-3.00); got ${keyFiveRows.single()}",
            )
            assertEquals(
                0,
                vatRows.sumOf { parseAmount(it["BON_BRUTTO"]!!) }.compareTo(BigDecimal("-23.00")),
                "Σ transactions_vat on the reverse Beleg is the negated sale (-20.00 item, -3.00 tip); got $vatRows",
            )
            assertEquals(
                0,
                vatRows.sumOf { parseAmount(it["BON_BRUTTO"]!!) }.compareTo(umsBruttoOf(zip, bonId)),
                "per-Beleg reconciliation must hold on the reverse Beleg too; got $vatRows",
            )
        }
    }

    /**
     * The no-double-count leg. A bill closed BEFORE the P1.1 change stored its employee tip as a 0 %
     * `BillVatDetail` row, so its VAT rows already sum to `paidAmount` and the normal loop ALREADY
     * emits the key-5 row. [org.example.project.utils.NonTaxableTipCarrier] recognises that shape and
     * returns ZERO; if it did not, this Beleg would declare 26,00 against 23,00 of tender.
     */
    @Test
    fun `pre-upgrade bill whose tip is still a 0pct row - one key-5 row, not double-counted`() {
        var billId = 0L
        testApp(
            setupData = {
                billId = closeTippedBillThroughTheService(TippedBillSpec(item = BigDecimal("20.00"), tip = BigDecimal("3.00")))
                addLegacyZeroRateTipRow(billId)
                attachToClosing(billId)
            },
        ) { _ ->
            // Assert the LEGACY fixture shape first — an ineffective rewrite would leave a
            // post-change bill, and every assertion below would then pass for the wrong reason.
            transaction {
                val bill = Bill.findById(billId)!!
                assertEquals(2, bill.vatDetails.count(), "legacy shape: the 19 % row plus the 0 % tip row")
                assertEquals(
                    0,
                    bill.vatDetails.sumOf { it.totalAmount }.compareTo(bill.paidAmount),
                    "legacy shape: Σ vatDetails == paidAmount is exactly the discriminator the carrier keys on",
                )
            }

            val zip = exportZip()
            val bonId = "B-$billId"
            val vatRows = vatRowsFor(zip, bonId)
            val keyFiveRows = vatRows.filter { it["UST_SCHLUESSEL"] == "5" }

            assertEquals(
                1,
                keyFiveRows.size,
                "the legacy 0 % row already IS the key-5 row — the carrier must not add a second; got $keyFiveRows",
            )
            assertEquals(
                0,
                parseAmount(keyFiveRows.single()["BON_BRUTTO"]!!).compareTo(BigDecimal("3.00")),
                "the tip appears ONCE at 3.00; 6.00 here would declare 26.00 against 23.00 of tender; got ${keyFiveRows.single()}",
            )
            assertEquals(
                0,
                vatRows.sumOf { parseAmount(it["BON_BRUTTO"]!!) }.compareTo(BigDecimal("23.00")),
                "Σ transactions_vat must equal the 23.00 actually tendered; got $vatRows",
            )
        }
    }

    // ============================================
    // Fixture helpers
    // ============================================

    /**
     * Seeds a one-item session and closes it through the real [BillService] with [TippedBillSpec.tip]
     * as an EMPLOYEE tip, paid in full by cash. Returns the created bill id.
     *
     * The closer is a MANAGER on purpose: `BillRepositoryImpl` derives TrinkgeldAN for every
     * non-ADMIN closer and TrinkgeldAG for an ADMIN, and only the former is non-taxable.
     *
     * Deliberately does NOT attach a Kassenabschluss — [attachToClosing] does that, so a storno
     * fixture can close, reverse, and then hang BOTH bills off one closing.
     */
    private fun closeTippedBillThroughTheService(spec: TippedBillSpec): Long {
        val vat19 = TestData.createDefaultVatRates().first()
        // Always seed the 0 % rate, so its ABSENCE from a post-P1.1 bill's vatDetails is a real
        // finding rather than an artifact of no 0 % rate existing in this fixture.
        val zeroVat = ensureZeroRateVat()
        val itemVat = if (spec.zeroRated) zeroVat else vat19
        TestData.createShopInfo()
        val closer = TestData.createManager(employeeNumber = "ETB_MGR", username = "etb_mgr")
        val cash = TestData.createPaymentType("Cash_ETB", isCash = true)
        val tws = TestData.createTableWithSession("ETB1")
        val category = TestData.createCategory("Emp Tip Bonkopf Cat")
        val product = TestData.createProduct(category.id.value, "Emp Tip Bonkopf Product", "ETB", spec.item)

        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                this.deviceId = this@DsfinvkEmployeeTipBonkopfUstTest.deviceId
                this.clientId = "TERM-ETB"
                this.registeredAt = now
                this.lastSeenAt = now
            }
        }

        val itemId =
            transaction {
                val session = TableSession.findById(tws.session.id)!!
                // Authorize the closer as the session owner.
                session.employeeId = closer
                OrderItem
                    .new {
                        this.session = session
                        this.product = product
                        this.unitPrice = spec.item
                        this.finalPrice = spec.item
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = itemVat
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }.id.value
            }

        return runBlocking {
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
                            billAmount = spec.item.toPlainString(),
                            tips = spec.tip.toPlainString(),
                            payments =
                                listOf(
                                    PaymentDto.PaymentRequest(
                                        amount = (spec.item + spec.tip).toPlainString(),
                                        paymentTypeId = cash.id.value,
                                    ),
                                ),
                        ),
                ).billId
        }
    }

    /**
     * Stornos [billId] through the real [BillService] (by its own closer) and returns the reverse
     * bill's id. `deleteBill` guards only against an already-deleted bill, so this runs before the
     * Kassenabschluss is attached.
     */
    private fun stornoOf(billId: Long): Long {
        val closerId =
            transaction {
                val original = Bill.findById(billId)!!
                original.employeeId.id.value
            }
        runBlocking {
            GlobalContext
                .get()
                .get<BillService>()
                .deleteBill(
                    employeeId = closerId.toString(),
                    deviceId = deviceId,
                    billId = billId.toString(),
                    deletingReason = "employee tip bonkopf_ust storno test",
                )
        }
        return transaction {
            val reversal = Bill.find { BillTable.deletedBillId eq billId }.single()
            reversal.id.value
        }
    }

    /**
     * Hangs every [billIds] entry off one Kassenabschluss — the export's selection key
     * (`Bill.find { zBonId inList … }`), which carries no other filter, so a bill left unattached is
     * simply absent from the package.
     */
    private fun attachToClosing(vararg billIds: Long) {
        val shop = transaction { ShopInfo.all().first() }
        val employee = transaction { Bill.findById(billIds.first())!!.employeeId }
        val zBon = TestData.createZBonFullDay(shop, employee)
        transaction { billIds.forEach { Bill.findById(it)!!.zBon = zBon } }
    }

    /**
     * Rewrites [billId] into the shape a bill closed BEFORE the P1.1 change had: the employee tip
     * stored as a 0 % [BillVatDetail] row, so `Σ vatDetails == paidAmount`. Mirrors
     * `BillTipNotAVatRowTest.addLegacyZeroRateTipRow`, which pins the same shape at the signing
     * surface.
     */
    private fun addLegacyZeroRateTipRow(billId: Long) {
        val zeroVat = ensureZeroRateVat()
        transaction {
            val target = Bill.findById(billId)!!
            BillVatDetail.new {
                this.bill = target
                this.totalAmount = target.tips
                this.netAmount = target.tips
                this.vatAmount = BigDecimal.ZERO
                this.vat = zeroVat
            }
        }
    }

    /**
     * The 0 %/key-5 rate the pre-P1.1 tip row used, and the rate the zero-rated-item fixture sells
     * at — seeded so its ABSENCE from a post-P1.1 bill's `vatDetails` is a real finding rather than
     * an artifact of no 0 % rate existing in this fixture.
     */
    private fun ensureZeroRateVat(): Vat =
        transaction {
            Vat.findById("C") ?: Vat.new("C") {
                this.vatClass = "Zero"
                this.vatRate = BigDecimal("0.0000")
            }
        }

    // ============================================
    // Export readers
    // ============================================

    private fun exportZip(): ByteArray =
        runBlocking {
            service.generateExport(
                DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
            )
        }

    private fun vatRowsFor(
        zip: ByteArray,
        bonId: String,
    ): List<Map<String, String>> = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == bonId }

    private fun umsBruttoOf(
        zip: ByteArray,
        bonId: String,
    ): BigDecimal = parseAmount(readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }["UMS_BRUTTO"]!!)

    /** Reads one CSV entry out of the export ZIP as header->value maps (matches the sibling tests). */
    private fun readRows(
        zip: ByteArray,
        name: String,
    ): List<Map<String, String>> {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    if (lines.size < 2) return emptyList()
                    val header = lines.first().split(";").map { it.trim('"') }
                    return lines.drop(1).map { line ->
                        header.zip(line.split(";").map { it.trim('"') }).toMap()
                    }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found")
    }

    /** Numeric DSFinV-K fields use a comma decimal separator. */
    private fun parseAmount(value: String): BigDecimal = BigDecimal(value.replace(",", "."))
}
