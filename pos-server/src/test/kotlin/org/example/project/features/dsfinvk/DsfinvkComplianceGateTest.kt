package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.ProcessType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookTransferServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchTseSigner
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.data.repository.TableSessionRepositoryImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.domain.factory.TseServiceFactory
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.provider.TseSignProvider
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.data.service.ZBonServiceImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.models.ZBonPaymentSummary
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.serialHex
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 21 — the capstone standing DSFinV-K compliance gate test.
 *
 * The per-fix tests elsewhere in this package (C1-M7) each pin ONE defect from the 2026-07-11
 * compliance audit. This test re-derives the whole audit from a single rich fixture that
 * exercises every module (two VAT rates, a cash sale, a card sale, a tip, a voucher redemption,
 * a full storno, and an unsigned/TSE-failed bill) and asserts all 21 release-gate invariants
 * against the actual generated export. Any future regression in any exporter or in
 * [DsfinvkExportServiceImpl] fails this test. See `docs/dsfinvk_tse_release_audit.md`.
 *
 * **Round-5 extension (2026-07-18).** Invariants 15–16 and the `round5 C6` test were added after
 * round 5's central lesson: *producer agreement is not correctness*. C6 — Kassenbuch corrections
 * booked at UST_SCHLUESSEL 5 / USt 0,00 — was invisible to invariants 8 and 14 because all three
 * Kassenbuch VAT producers were wrong together, so the cross-producer comparison reported perfect
 * consistency both before and after the fix. Every round-5 assertion therefore derives at least
 * one side from the DSFinV-K spec (Anlage 2's fixed key→rate table, or the hand-computed
 * decomposition of a fixture) rather than from a second producer.
 *
 * **Signed processData — covered by a SIBLING gate, not here.** This gate stubs `processDataHash`
 * and constrains no ProcessData content: the 16 invariants below all read the CSV package, so a
 * green run here says NOTHING about the signed `Kassenbeleg-V1` string. That was coverage gap 4, and
 * it is why C1/C2 (entry-type-blind bucketing signing a false §12 Abs. 1 Umsatz declaration into the
 * WORM log) would have passed a fully green gate. The signed per-bucket placement is now pinned by
 * [DsfinvkSignedPayloadGateTest] (one Vorgang of each entry-type family, exact Anhang-I string,
 * per-bucket) and by `KassenbuchSignedProcessDataTest`; TSER-3 (promo-deduction scale) lives in the
 * `utils/builder` ProcessData suites. Treat those as part of the release gate alongside this file.
 *
 * **Known gate gaps — do not read a green run as covering these:**
 *  - **C7 (OPEN)** — invariant 15 is in place, but no fixture seeds the legacy
 *    `CLOSING_BALANCE`-with-line-items shape that actually breaks it, because C7 is unfixed and
 *    such a fixture would leave the release gate permanently RED. See
 *    [assertEveryLinesVatBonHasATransactionsVatParent].
 */
@DisplayName("DSFinV-K export — standing compliance gate (Task 21)")
class DsfinvkComplianceGateTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `rich fixture satisfies all 21 DSFinV-K release-gate invariants`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates() // [0]=19% Normal, [1]=7% Reduced
            val vat19 = vats[0]
            val vat7 = vats[1]
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val card = TestData.createPaymentType("Card", isCash = false)
            val voucherType = TestData.createPaymentType("Voucher", isCash = false, isVoucher = true)
            val category = TestData.createCategory("Gate Cat")
            val productA = TestData.createProduct(category.id.value, "Gate Coffee", "GATE-A")
            val productB = TestData.createProduct(category.id.value, "Gate Salad", "GATE-B")
            val tws = TestData.createTableWithSession("GATE1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-GATE-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }

            fun signedTse(txNumber: Long): TseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-GATE-SERIAL"
                    this.transactionNumber = txNumber
                    this.processType = ProcessType.KASSENBELEG_V1.value
                    this.processDataHash = "hash-$txNumber"
                    this.signature = "signature-bytes-$txNumber"
                    this.signatureCounter = txNumber
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.SIMULATION
                }

            // --- (1) Cash sale + an OWNER tip (taxable revenue), 19% VAT ---
            val billCash =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-CASH"
                    this.billAmount = BigDecimal("20.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.paidAmount = BigDecimal("23.00")
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = signedTse(1L)
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            OrderItem.new {
                this.session = session
                this.bill = billCash
                this.product = productA
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("20.00")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            BillVatDetail.new {
                this.bill = billCash
                this.vat = vat19
                this.netAmount = BigDecimal("19.33")
                this.vatAmount = BigDecimal("3.67")
                this.totalAmount = BigDecimal("23.00")
            }
            Payment.new {
                this.bill = billCash
                this.paymentType = cash
                this.amount = BigDecimal("23.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // --- (2) Card sale, 7% VAT, no tip/discount ---
            val billCard =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-CARD"
                    this.billAmount = BigDecimal("12.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = signedTse(2L)
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            OrderItem.new {
                this.session = session
                this.bill = billCard
                this.product = productB
                this.unitPrice = BigDecimal("12.00")
                this.finalPrice = BigDecimal("12.00")
                this.vat = vat7
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            BillVatDetail.new {
                this.bill = billCard
                this.vat = vat7
                this.netAmount = BigDecimal("11.21")
                this.vatAmount = BigDecimal("0.79")
                this.totalAmount = BigDecimal("12.00")
            }
            Payment.new {
                this.bill = billCard
                this.paymentType = card
                this.amount = BigDecimal("12.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // --- (3) Voucher-redeemed sale, 19% VAT ---
            val billVoucher =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-VOUCHER"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = signedTse(3L)
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            val voucherItem =
                OrderItem.new {
                    this.session = session
                    this.bill = billVoucher
                    this.product = productA
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            BillVatDetail.new {
                this.bill = billVoucher
                this.vat = vat19
                this.netAmount = BigDecimal("8.40")
                this.vatAmount = BigDecimal("1.60")
                this.totalAmount = BigDecimal("10.00")
            }
            val voucherPayment =
                Payment.new {
                    this.bill = billVoucher
                    this.paymentType = voucherType
                    this.amount = BigDecimal("10.00")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
            val voucher =
                Voucher.new {
                    this.orderItem = voucherItem
                    this.voucherNumber = "V-GATE-1"
                    this.originalValue = BigDecimal("25.00")
                    this.remainingValue = BigDecimal("15.00")
                    this.createdAt = now
                }
            VoucherRedeem.new {
                this.voucher = voucher
                this.payment = voucherPayment
                this.redeemAmount = BigDecimal("10.00")
                this.redeemedAt = now
                this.voucherType = VoucherSourceType.INTERNAL
                this.voucherNumber = "V-GATE-1"
            }

            // --- (4) Full storno: sale + reverse, 19% VAT, no tip/discount ---
            val billStornoOrig =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-STORNO-ORIG"
                    this.billAmount = BigDecimal("15.00")
                    this.paidAmount = BigDecimal("15.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = signedTse(4L)
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            val billStornoRev =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-STORNO-REV"
                    this.billAmount = BigDecimal("-15.00")
                    this.paidAmount = BigDecimal("-15.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = billStornoOrig
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = signedTse(5L)
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            OrderItem.new {
                this.session = session
                this.bill = billStornoOrig
                this.product = productA
                this.unitPrice = BigDecimal("15.00")
                this.finalPrice = BigDecimal("15.00")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            BillVatDetail.new {
                this.bill = billStornoOrig
                this.vat = vat19
                this.netAmount = BigDecimal("12.61")
                this.vatAmount = BigDecimal("2.39")
                this.totalAmount = BigDecimal("15.00")
            }
            Payment.new {
                this.bill = billStornoOrig
                this.paymentType = card
                this.amount = BigDecimal("15.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundTime = now
                this.refundedBillId = billStornoRev
            }

            // --- (5) Unsigned/TSE-failed bill, 19% VAT ---
            val billUnsigned =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-UNSIGNED"
                    this.billAmount = BigDecimal("5.00")
                    this.paidAmount = BigDecimal("5.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = null
                    this.tseSigningStatus = TseSigningStatus.FAILED
                }
            OrderItem.new {
                this.session = session
                this.bill = billUnsigned
                this.product = productA
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            BillVatDetail.new {
                this.bill = billUnsigned
                this.vat = vat19
                this.netAmount = BigDecimal("4.20")
                this.vatAmount = BigDecimal("0.80")
                this.totalAmount = BigDecimal("5.00")
            }
            Payment.new {
                this.bill = billUnsigned
                this.paymentType = cash
                this.amount = BigDecimal("5.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // --- payment.csv / cashpointclosing.csv reconciliation source ---
            // Bar: billCash(23.00) + billUnsigned(5.00) = 28.00
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("28.00")
            }
            // Unbar: billCard(12.00) + billStornoOrig(15.00, gross tender posted regardless of the
            // later refund — payment.csv is an independent Z-report aggregate, not a re-derivation
            // of individual Payment rows) = 27.00
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = card
                this.currency = "EUR"
                this.amount = BigDecimal("27.00")
            }
            // Voucher tender maps to ZAHLART_TYP="Keine" (not a cash-drawer movement).
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = voucherType
                this.currency = "EUR"
                this.amount = BigDecimal("10.00")
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }
        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        val entries = unzip(zip)
        val indexBytes = entries["index.xml"]
        assertNotNull(indexBytes, "index.xml must be present in the export")

        val csvFiles: Map<String, CsvFile> =
            entries
                .filterKeys { it.endsWith(".csv") }
                .mapValues { (name, bytes) -> parseCsvFile(name, bytes) }

        val referenceIndexBytes = javaClass.getResourceAsStream("/dsfinvk/reference_index.xml")!!.readBytes()
        val referenceTables = parseIndexTables(referenceIndexBytes)
        val generatedTables = parseIndexTables(indexBytes)

        assertAll(
            "DSFinV-K compliance gate — 13 release invariants",
            // 1. STRUCTURE
            {
                assertTrue(
                    indexBytes.decodeToString().contains("<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">"),
                    "index.xml must declare the GDPdU DTD",
                )
                assertEquals(
                    referenceTables,
                    generatedTables,
                    "generated index.xml table/column schema must match the reference exactly",
                )
            },
            // 2. HEADER <-> INDEX
            {
                generatedTables.forEach { table ->
                    val csv = csvFiles[table.url]
                    assertNotNull(csv, "index.xml references ${table.url} but it is missing from the ZIP")
                    assertEquals(
                        table.cols.map { it.name },
                        csv.header,
                        "${table.url} header must equal the index.xml VariableColumn order",
                    )
                }
            },
            // 3. FILES
            {
                val indexCsvUrls = generatedTables.map { it.url }.toSet()
                val zipCsvNames = entries.keys.filter { it.endsWith(".csv") }.toSet()
                assertEquals(indexCsvUrls, zipCsvNames, "index.xml CSV URLs must equal the ZIP's *.csv entries")
            },
            // 4. DECIMALS
            {
                generatedTables.forEach { table ->
                    val csv = csvFiles[table.url] ?: return@forEach
                    table.cols.forEach { col ->
                        if (col.type != "Numeric") return@forEach
                        val headerIdx = csv.header.indexOf(col.name)
                        if (headerIdx < 0) return@forEach
                        csv.rows.forEach { row ->
                            val field = row.getOrNull(headerIdx) ?: return@forEach
                            if (field.value.isBlank()) return@forEach
                            assertFalse(
                                field.value.contains('.'),
                                "${table.url}.${col.name} is Numeric and must use a comma decimal, got '${field.value}'",
                            )
                            assertFalse(
                                field.quoted,
                                "${table.url}.${col.name} is Numeric and must be unquoted, got quoted '${field.value}'",
                            )
                        }
                    }
                }
            },
            // 5. LINE ENDINGS
            {
                entries.filterKeys { it.endsWith(".csv") }.forEach { (name, bytes) ->
                    val raw = bytes.decodeToString()
                    assertFalse(
                        Regex("(?<!\r)\n").containsMatchIn(raw),
                        "$name must use CRLF line endings only (found a bare LF)",
                    )
                    assertTrue(raw.contains("\r\n"), "$name must contain at least a CRLF-terminated header line")
                }
            },
            // 6. ARITY
            {
                csvFiles.values.forEach { csv ->
                    csv.rows.forEachIndexed { i, row ->
                        assertEquals(
                            csv.header.size,
                            row.size,
                            "${csv.name} data row $i has ${row.size} fields, expected ${csv.header.size}: $row",
                        )
                    }
                }
            },
            // 7. VAT ROW
            {
                checkVatTriple(csvFiles, "transactions_vat.csv", "BON_BRUTTO", "BON_NETTO", "BON_UST")
                checkVatTriple(csvFiles, "lines_vat.csv", "POS_BRUTTO", "POS_NETTO", "POS_UST")
                checkVatTriple(csvFiles, "itemamounts.csv", "PF_BRUTTO", "PF_NETTO", "PF_UST")
            },
            // 8. BON RECON
            {
                val transactionsCsv = csvFiles.getValue("transactions.csv")
                val txByBonId =
                    transactionsCsv.rows.associate { row ->
                        val map = rowMap(transactionsCsv.header, row)
                        map.getValue("BON_ID") to parseAmount(map.getValue("UMS_BRUTTO"))
                    }
                val linesVatCsv = csvFiles.getValue("lines_vat.csv")
                val linesVatSumByBon =
                    linesVatCsv.rows
                        .map { rowMap(linesVatCsv.header, it) }
                        .groupBy { it["BON_ID"] }
                        .mapValues { (_, rows) -> rows.sumOf { parseAmount(it.getValue("POS_BRUTTO")) } }
                val transVatCsv = csvFiles.getValue("transactions_vat.csv")
                val transVatSumByBon =
                    transVatCsv.rows
                        .map { rowMap(transVatCsv.header, it) }
                        .groupBy { it["BON_ID"] }
                        .mapValues { (_, rows) -> rows.sumOf { parseAmount(it.getValue("BON_BRUTTO")) } }

                assertTrue(txByBonId.isNotEmpty(), "fixture must produce at least one Beleg")
                txByBonId.forEach { (bonId, umsBrutto) ->
                    val lvSum = linesVatSumByBon[bonId] ?: BigDecimal.ZERO
                    val tvSum = transVatSumByBon[bonId] ?: BigDecimal.ZERO
                    assertEquals(
                        0,
                        lvSum.compareTo(umsBrutto),
                        "BON_ID=$bonId: Σlines_vat.POS_BRUTTO ($lvSum) must equal UMS_BRUTTO ($umsBrutto)",
                    )
                    assertEquals(
                        0,
                        tvSum.compareTo(umsBrutto),
                        "BON_ID=$bonId: Σtransactions_vat.BON_BRUTTO ($tvSum) must equal UMS_BRUTTO ($umsBrutto)",
                    )
                }
            },
            // 9. PAYMENT RECON
            {
                val paymentCsv = csvFiles.getValue("payment.csv")
                val paymentRows = paymentCsv.rows.map { rowMap(paymentCsv.header, it) }
                val totalPayments = paymentRows.sumOf { parseAmount(it.getValue("Z_ZAHLART_BETRAG")) }
                val barPayments =
                    paymentRows
                        .filter { it["ZAHLART_TYP"] == DsfinvkExportDto.PaymentTypes.BAR }
                        .sumOf { parseAmount(it.getValue("Z_ZAHLART_BETRAG")) }

                val closingCsv = csvFiles.getValue("cashpointclosing.csv")
                assertEquals(1, closingCsv.rows.size, "fixture builds exactly one closing row")
                val closingRow = rowMap(closingCsv.header, closingCsv.rows.single())
                val zSeZahlungen = parseAmount(closingRow.getValue("Z_SE_ZAHLUNGEN"))
                val zSeBarzahlungen = parseAmount(closingRow.getValue("Z_SE_BARZAHLUNGEN"))

                assertEquals(
                    0,
                    totalPayments.compareTo(zSeZahlungen),
                    "Σ payment.Z_ZAHLART_BETRAG ($totalPayments) must equal cashpointclosing.Z_SE_ZAHLUNGEN ($zSeZahlungen)",
                )
                assertEquals(
                    0,
                    barPayments.compareTo(zSeBarzahlungen),
                    "Σ payment rows where ZAHLART_TYP=Bar ($barPayments) must equal Z_SE_BARZAHLUNGEN ($zSeBarzahlungen)",
                )
            },
            // 10. BON_ID UNIQUE + exactly one transactions_tse row
            {
                val transactionsCsv = csvFiles.getValue("transactions.csv")
                val bonIds = transactionsCsv.rows.map { rowMap(transactionsCsv.header, it).getValue("BON_ID") }
                assertEquals(bonIds.size, bonIds.distinct().size, "transactions.BON_ID must be globally unique; got $bonIds")

                val tseCsv = csvFiles.getValue("transactions_tse.csv")
                val tseRowsByBon =
                    tseCsv.rows
                        .map { rowMap(tseCsv.header, it) }
                        .groupBy { it["BON_ID"] }

                bonIds.forEach { bonId ->
                    val rows = tseRowsByBon[bonId]
                    assertNotNull(rows, "BON_ID=$bonId must have a transactions_tse.csv row")
                    assertEquals(1, rows.size, "BON_ID=$bonId must have exactly one transactions_tse.csv row; got $rows")
                    val row = rows.single()
                    val sig = row["TSE_TA_SIG"].orEmpty()
                    val fehler = row["TSE_TA_FEHLER"].orEmpty()
                    assertTrue(
                        sig.isNotBlank() || fehler.isNotBlank(),
                        "BON_ID=$bonId transactions_tse row must carry either a signature or TSE_TA_FEHLER",
                    )
                    assertFalse(
                        sig.isNotBlank() && fehler.isNotBlank(),
                        "BON_ID=$bonId transactions_tse row must not carry both a signature AND TSE_TA_FEHLER",
                    )
                }
            },
            // 11. BON_NR
            {
                val transactionsCsv = csvFiles.getValue("transactions.csv")
                val bonNrs = transactionsCsv.rows.map { rowMap(transactionsCsv.header, it).getValue("BON_NR") }
                assertEquals(bonNrs.size, bonNrs.distinct().size, "transactions.BON_NR must be unique within the closing; got $bonNrs")
                assertTrue(
                    bonNrs.all { it.toLongOrNull() != null && it.toLong() != 0L },
                    "transactions.BON_NR must be non-zero numeric for every row; got $bonNrs",
                )
            },
            // 12. Z RANGE
            {
                val transactionsCsv = csvFiles.getValue("transactions.csv")
                val closingCsv = csvFiles.getValue("cashpointclosing.csv")
                val closingRow = rowMap(closingCsv.header, closingCsv.rows.single())
                val zStartId = closingRow["Z_START_ID"].orEmpty()
                val zEndeId = closingRow["Z_ENDE_ID"].orEmpty()
                assertTrue(zStartId.isNotBlank(), "Z_START_ID must not be empty")
                assertTrue(zEndeId.isNotBlank(), "Z_ENDE_ID must not be empty")

                val orderedByBonNr =
                    transactionsCsv.rows
                        .map { rowMap(transactionsCsv.header, it) }
                        .sortedBy { it.getValue("BON_NR").toLong() }
                assertEquals(orderedByBonNr.first().getValue("BON_ID"), zStartId, "Z_START_ID must be the first BON_ID by BON_NR order")
                assertEquals(orderedByBonNr.last().getValue("BON_ID"), zEndeId, "Z_ENDE_ID must be the last BON_ID by BON_NR order")
            },
            // 13. ENUMS
            {
                val bonTypSet = constStringValues(DsfinvkExportDto.ReceiptTypes::class.java)
                val gvTypSet = constStringValues(DsfinvkExportDto.BusinessCaseTypes::class.java)
                val zahlartSet = constStringValues(DsfinvkExportDto.PaymentTypes::class.java)
                val refTypSet = constStringValues(DsfinvkExportDto.ReferenceTypes::class.java)
                val processTypeSet = ProcessType.entries.map { it.value }.toSet()

                val transactionsCsv = csvFiles.getValue("transactions.csv")
                transactionsCsv.rows.forEach { row ->
                    val bonTyp = rowMap(transactionsCsv.header, row)["BON_TYP"]
                    assertTrue(bonTyp in bonTypSet, "invalid BON_TYP in transactions.csv: $bonTyp")
                }

                val linesCsv = csvFiles.getValue("lines.csv")
                linesCsv.rows.forEach { row ->
                    val gvTyp = rowMap(linesCsv.header, row)["GV_TYP"]
                    assertTrue(gvTyp in gvTypSet, "invalid GV_TYP in lines.csv: $gvTyp")
                }

                val businessCasesCsv = csvFiles.getValue("businesscases.csv")
                businessCasesCsv.rows.forEach { row ->
                    val gvTyp = rowMap(businessCasesCsv.header, row)["GV_TYP"]
                    assertTrue(gvTyp in gvTypSet, "invalid GV_TYP in businesscases.csv: $gvTyp")
                }

                val paymentCsv = csvFiles.getValue("payment.csv")
                paymentCsv.rows.forEach { row ->
                    val zahlart = rowMap(paymentCsv.header, row)["ZAHLART_TYP"]
                    assertTrue(zahlart in zahlartSet, "invalid ZAHLART_TYP in payment.csv: $zahlart")
                }

                val dataPaymentCsv = csvFiles.getValue("datapayment.csv")
                dataPaymentCsv.rows.forEach { row ->
                    val zahlart = rowMap(dataPaymentCsv.header, row)["ZAHLART_TYP"]
                    assertTrue(zahlart in zahlartSet, "invalid ZAHLART_TYP in datapayment.csv: $zahlart")
                }

                val referencesCsv = csvFiles.getValue("references.csv")
                referencesCsv.rows.forEach { row ->
                    val refTyp = rowMap(referencesCsv.header, row)["REF_TYP"]
                    if (!refTyp.isNullOrBlank()) {
                        assertTrue(refTyp in refTypSet, "invalid REF_TYP in references.csv: $refTyp")
                    }
                }

                val tseCsv = csvFiles.getValue("transactions_tse.csv")
                tseCsv.rows.forEach { row ->
                    val vorgangsart = rowMap(tseCsv.header, row)["TSE_TA_VORGANGSART"]
                    if (!vorgangsart.isNullOrBlank()) {
                        assertTrue(vorgangsart in processTypeSet, "invalid TSE_TA_VORGANGSART in transactions_tse.csv: $vorgangsart")
                    }
                }
            },
            // 14. CROSS-FILE PER-KEY VAT (H3c) — per BON_ID and UST_SCHLUESSEL,
            //     Σlines_vat.POS_UST == Σtransactions_vat.BON_UST. This is the invariant that
            //     catches a tip modelled inconsistently across the two surfaces (the rich fixture's
            //     OWNER tip exercises the taxable/apportioned path).
            {
                assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
            },
            // 15. BONPOS_UST PARENTAGE (round-5 C7 shape) — every lines_vat BON_ID must have a
            //     transactions_vat parent, or an auditor's join silently drops the position VAT.
            {
                assertEveryLinesVatBonHasATransactionsVatParent(csvFiles)
            },
            // 16. ANLAGE-2 RATE CONFORMANCE (round-5) — the declared USt must be the
            //     decomposition of the declared brutto at the rate Anlage 2 fixes for that key.
            //     Unlike invariants 8/14 this compares the export against the SPEC, not against a
            //     second producer: C6 proved that three producers can agree and all be wrong.
            {
                assertUstDecomposesAtItsAnlage2Rate(csvFiles)
                assertVatCsvDeclaresTheAnlage2Rates(csvFiles)
            },
            // 17. BONKOPF_UST KEY UNIQUENESS (2026-09-16) — DSFinV-K 3.1.2.1 keys Bonkopf_USt on
            //     (Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, BON_ID, UST_SCHLUESSEL), so a split row is a
            //     MALFORMED export. Every other invariant here is a SUM, and a split row sums
            //     identically — this is the only one that can see it. This fixture is where it
            //     belongs: it is the one that exercises several key-5 row sources at once.
            {
                assertTransactionsVatKeysAreUnique(csvFiles)
            },
            // 18. BON_REFERENZEN UNIQUENESS (2026-10-04) — one Vorgang names one referenced Vorgang
            //     once per line. Four producers now feed references.csv for bills alone (Storno,
            //     Zahlartwechsel Neubuchung, re-bill after a re-opening Storno, clearing), and a
            //     Neubuchung's copies point at the same original its own link names — a duplicated
            //     row is the failure mode that merging them must rule out.
            {
                assertReferencesAreUnique(csvFiles)
            },
            // 19. CLOSING PER-KEY VAT (F43) — per (Z_KASSE_ID, Z_NR, UST_SCHLUESSEL),
            //     Σ businesscases == Σ transactions_vat. This fixture's Z-Bon is hand-seeded and has no
            //     tax_group rows; the comparison against the STORED tax_group made by the real
            //     createReport lives in `F43 closing per key - …` below.
            {
                assertBusinessCasesMatchTransactionsVatPerKey(csvFiles)
            },
            // 20. TERMINAL_ID ↔ TSE ClientID (2026-10-07, BKOPF-7/STAM-10) — Anhang E p. 85: a
            //     Bonkopf TERMINAL_ID may only name a slaves.csv row whose TERMINAL_SERIENNR was the
            //     ClientID this Vorgang was signed under. This fixture signs in SIMULATION mode, so it
            //     must carry no TERMINAL_ID at all; the attributed shape is
            //     `TERMINAL_ID - every attributed Bon resolves …` below.
            {
                assertEquals(0, assertTerminalIdsResolveInSlaves(csvFiles), "a SIMULATION-signed Vorgang names no terminal")
            },
            // 21. POS_TERMINAL_ID ↔ slaves.csv (2026-10-07) — Anhang E p. 93: „ID der Slave-Kasse
            //     (Terminal), an der die Position (Line) erfasst wird." Every non-empty lines.csv
            //     POS_TERMINAL_ID is a slaves.csv TERMINAL_ID of the same closing — never the
            //     terminals primary key — and a Kassenbuch line names exactly its Bon's
            //     TERMINAL_ID. The non-vacuous shape is `POS_TERMINAL_ID - …` below.
            {
                assertPosTerminalIdsResolveInSlaves(csvFiles)
            },
        )
    }

    /**
     * Invariant 21 on a fixture whose lines actually carry a terminal: two orders booked on two
     * tills whose primary keys (1, 2 on a fresh DB) differ from their codes — the real-data defect
     * exported the key, which slaves.csv does not contain.
     */
    @Test
    fun `POS_TERMINAL_ID - every line names a slaves row of its closing, never a database id`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val session = TableSession.findById(TestData.createTableWithSession("GATE-PTID").session.id)!!
            val now = currentTimeStamp()
            val vat19 = TestData.createDefaultVatRates()[0]
            val product = TestData.createProduct(TestData.createCategory("PTID Cat").id.value, "Cola", "PTID-COLA")
            listOf("TER-001", "TER-002").forEach { code ->
                val till =
                    Terminal.new {
                        deviceId = "device-$code"
                        clientId = code
                        terminalSerialNr = "HW-$code"
                        tseClientRegistered = true
                        registeredAt = now
                        lastSeenAt = now
                    }
                val order =
                    Orders.new {
                        this.orderNumber = "GATE-PTID-$code"
                        this.employee = admin
                        this.tableSession = session
                        this.orderStatus = OrderStatus.CONFIRMED
                        this.orderTime = now
                        this.orderTotal = BigDecimal("3.00")
                        this.zBon = zBon
                        this.terminal = till
                        this.tseSigningStatus = TseSigningStatus.PENDING
                    }
                val bill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "GATE-PTID-B-$code"
                        this.billAmount = BigDecimal("3.00")
                        this.paidAmount = BigDecimal("3.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                        this.terminal = till
                        this.tseSigningStatus = TseSigningStatus.FAILED
                    }
                OrderItem.new {
                    this.session = session
                    this.order = order
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            }
        }

        val csvFiles = generateAndParseCsv()
        assertEquals(4, assertPosTerminalIdsResolveInSlaves(csvFiles), "both order lines and both bill lines name the till they were booked on")
        val lines = rowsOf(csvFiles, "lines.csv")
        assertEquals(
            lines.filter { it.getValue("BON_ID").startsWith("O-") }.map { it["POS_TERMINAL_ID"] }.sortedBy { it },
            lines.filter { it.getValue("BON_ID").startsWith("B-") }.map { it["POS_TERMINAL_ID"] }.sortedBy { it },
            "a bill item line names the same terminal as the order line it settles",
        )
        assertEquals(setOf("TER-001", "TER-002"), lines.mapNotNull { it["POS_TERMINAL_ID"]?.takeIf(String::isNotBlank) }.toSet())
    }

    /**
     * Invariant 21 — every non-empty lines.csv POS_TERMINAL_ID is a slaves.csv TERMINAL_ID of the
     * same closing (Anhang E p. 93), and every Kassenbuch line (it records no ordered item, so it was
     * settled where its Bon was) carries exactly its Bon's TERMINAL_ID. Returns how many lines
     * carried one, so a fixture can prove the check was not vacuous.
     */
    private fun assertPosTerminalIdsResolveInSlaves(csvFiles: Map<String, CsvFile>): Int {
        fun rows(name: String) = if (name in csvFiles) rowsOf(csvFiles, name) else emptyList()
        val slaveKeys = rows("slaves.csv").map { it["Z_NR"] to it["TERMINAL_ID"] }.toSet()
        val terminalIdByBon = rows("transactions.csv").associate { (it["Z_NR"] to it["BON_ID"]) to it["TERMINAL_ID"].orEmpty() }
        rows("lines.csv").filter { it["BON_ID"].orEmpty().startsWith("KB-") }.forEach { line ->
            assertEquals(
                terminalIdByBon[line["Z_NR"] to line["BON_ID"]].orEmpty(),
                line["POS_TERMINAL_ID"].orEmpty(),
                "Kassenbuch BON_ID=${line["BON_ID"]}: POS_TERMINAL_ID must be the Bon's TERMINAL_ID",
            )
        }
        val named = rows("lines.csv").filter { !it["POS_TERMINAL_ID"].isNullOrBlank() }
        named.forEach { line ->
            assertTrue(
                (line["Z_NR"] to line["POS_TERMINAL_ID"]) in slaveKeys,
                "BON_ID=${line["BON_ID"]} POS_ZEILE=${line["POS_ZEILE"]} names POS_TERMINAL_ID=${line["POS_TERMINAL_ID"]}, " +
                    "which slaves.csv lacks for Z_NR=${line["Z_NR"]}",
            )
        }
        return named.size
    }

    /**
     * Invariant 20 on a fixture that actually attributes: NORMAL-mode Vorgänge signed under two
     * tills' own ClientIDs, plus one signed by a client that is no terminal. Asserting a nonzero
     * count keeps the invariant from passing vacuously.
     */
    @Test
    fun `TERMINAL_ID - every attributed Bon resolves to a slaves row whose serial is its signing ClientID`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val session = TableSession.findById(TestData.createTableWithSession("GATE-TID").session.id)!!
            val now = currentTimeStamp()
            val tills =
                listOf("TER-001", "TER-002").associateWith { code ->
                    Terminal.new {
                        deviceId = "device-$code"
                        clientId = code
                        terminalSerialNr = "HW-$code"
                        tseClientRegistered = true
                        registeredAt = now
                        lastSeenAt = now
                    }
                }
            listOf("TER-001", "TER-002", "TER-002", "CLIENT-FOR-MAINTENANCE").forEachIndexed { i, client ->
                val tanr = 500L + i
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GATE-TID-$i"
                    this.billAmount = BigDecimal("5.00")
                    this.paidAmount = BigDecimal("5.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.terminal = tills["TER-001"]
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                    this.tseTransaction =
                        TseTransaction.new {
                            this.tseSerialNumber = "A1B2C3D4".repeat(8)
                            this.transactionNumber = tanr
                            this.signatureCounter = tanr
                            this.signature = "sig-$tanr"
                            this.signatureAlgorithm = "ecdsa-plain-SHA384"
                            this.publicKey = "pubkey"
                            this.processType = ProcessType.KASSENBELEG_V1.value
                            this.processDataHash = "pd-$tanr"
                            this.clientId = client
                            this.startTime = now
                            this.endTime = now
                            this.tseTimestamp = 1_759_000_000L + tanr
                            this.tseMode = TseMode.NORMAL
                        }
                }
            }
        }

        assertEquals(3, assertTerminalIdsResolveInSlaves(generateAndParseCsv()), "three Bons were signed by a till of the closing")
    }

    /**
     * Invariant 20 — every non-empty transactions.csv TERMINAL_ID names a slaves.csv row of the same
     * closing, and both that row's TERMINAL_SERIENNR and the TERMINAL_ID equal the `client_id` of the
     * TSE transaction behind the Bon's signed transactions_tse.csv row (resolved via TSE_ID → tse.csv
     * TSE_SERIAL plus TSE_TANR — the CSV itself carries no ClientID column). Returns how many Bons
     * were attributed, so a fixture can prove the check was not vacuous.
     */
    private fun assertTerminalIdsResolveInSlaves(csvFiles: Map<String, CsvFile>): Int {
        fun rows(name: String) = if (name in csvFiles) rowsOf(csvFiles, name) else emptyList()
        val slaveByKey = rows("slaves.csv").associateBy { it["Z_NR"] to it["TERMINAL_ID"] }
        val serialByTseId = rows("tse.csv").associate { (it["Z_NR"] to it["TSE_ID"]) to it.getValue("TSE_SERIAL") }
        val signedTseRowsByBon =
            rows("transactions_tse.csv")
                .filter { !it["TSE_TA_SIG"].isNullOrBlank() }
                .groupBy { it["Z_NR"] to it["BON_ID"] }
        val attributed = rows("transactions.csv").filter { !it["TERMINAL_ID"].isNullOrBlank() }
        attributed.forEach { bon ->
            val bonId = bon["BON_ID"]
            val slave = slaveByKey[bon["Z_NR"] to bon["TERMINAL_ID"]]
            assertNotNull(slave, "BON_ID=$bonId names TERMINAL_ID=${bon["TERMINAL_ID"]}, which slaves.csv lacks for Z_NR=${bon["Z_NR"]}")
            val tseRow = signedTseRowsByBon[bon["Z_NR"] to bonId]?.singleOrNull()
            assertNotNull(tseRow, "BON_ID=$bonId carries a TERMINAL_ID but no single signed transactions_tse.csv row")
            val serial = serialByTseId[bon["Z_NR"] to tseRow["TSE_ID"]]
            val tanr = tseRow.getValue("TSE_TANR").toLong()
            val clientId =
                transaction {
                    TseTransaction
                        .all()
                        .single { it.serialHex() == serial && it.transactionNumber == tanr }
                        .clientId
                }
            assertEquals(clientId, slave["TERMINAL_SERIENNR"], "BON_ID=$bonId: TERMINAL_SERIENNR must be the signing ClientID")
            assertEquals(clientId, bon["TERMINAL_ID"], "BON_ID=$bonId: TERMINAL_ID must be the signing ClientID")
        }
        return attributed.size
    }

    /**
     * Focused H3c check for the EMPLOYEE-tip path (the rich fixture above covers the OWNER tip):
     * a non-taxable employee tip is a 0%/key-5 `BillVatDetail` row (as `BillServiceImpl` now emits),
     * so `lines_vat` and `transactions_vat` must still agree per (BON_ID, UST_SCHLUESSEL) — the tip
     * lands in key-5 with zero UST on both surfaces, and the item rate carries only the item VAT.
     */
    @Test
    fun `employee tip - lines_vat and transactions_vat agree per UST key`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            // Fetch-or-create the VAT rows this test needs (idempotent — robust to any DB-state
            // bleed across test classes, which would otherwise PK-collide on VAT id "A"/"C").
            val vat19 =
                org.example.project.features.payment.domain.models.Vat
                    .findById("A")
                    ?: org.example.project.features.payment.domain.models.Vat.new("A") {
                        this.vatClass = "Normal"
                        this.vatRate = BigDecimal("0.1900")
                    }
            // The 0% ("Nicht steuerbar", key-5) rate is seeded but deliberately NOT used by any
            // BillVatDetail row: its presence makes the absence of a 0% tip row a real finding
            // rather than an artifact of no 0% rate existing in this fixture.
            org.example.project.features.payment.domain.models.Vat
                .findById("C")
                ?: org.example.project.features.payment.domain.models.Vat.new("C") {
                    this.vatClass = "Zero"
                    this.vatRate = BigDecimal("0.0000")
                }
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val category = TestData.createCategory("Emp Tip Cat")
            val product = TestData.createProduct(category.id.value, "Emp Tip Coffee", "EMPTIP-A")
            val tws = TestData.createTableWithSession("EMPTIP1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-EMPTIP-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }

            // Item 20.00 @19% + a 3.00 EMPLOYEE tip (non-taxable), paid 23.00 cash. VAT details
            // mirror BillServiceImpl.createBillVat: the ITEM ROW ONLY. Since the 2026-09-16 P1.1
            // change a non-taxable employee tip is NOT a 0% BillVatDetail row — it is carried
            // OUTSIDE vatDetails (NonTaxableTipCarrier) and reaches transactions_vat through the
            // key-5 row generateBonkopfUst emits itself. Hand-seeding the old 0% row here made this
            // gate certify a shape production can no longer produce.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "EMPTIP-A"
                    this.billAmount = BigDecimal("20.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.EMPLOYEE
                    this.paidAmount = BigDecimal("23.00")
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("20.00")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.netAmount = BigDecimal("16.81")
                this.vatAmount = BigDecimal("3.19")
                this.totalAmount = BigDecimal("20.00")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("23.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }
        val csvFiles =
            unzip(zip)
                .filterKeys { it.endsWith(".csv") }
                .mapValues { (name, bytes) -> parseCsvFile(name, bytes) }

        // assertLinesVatMatchesTransactionsVatPerKey alone CANNOT fail on a MISSING key-5
        // transactions_vat row: it compares POS_UST against BON_UST, a non-taxable tip carries 0,00
        // USt on BOTH sides, and its own contract defaults a one-sided key to zero on the other. It
        // therefore passed before P1.2 and would pass again if P1.2 were reverted — this test
        // asserted nothing about the very row it is named for. The BRUTTO reconciliation is what
        // actually sees the missing row, and `@BeforeEach TestDatabase.setup()` gives this test its
        // own database, so it never reaches the rich fixture's copy of that check.
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
        assertTransactionsVatKeysAreUnique(csvFiles)
    }

    // ============================================
    // RA-1 — rounding drift across the three VAT surfaces on discount/promotion bills
    // ============================================
    //
    // UMS_BRUTTO (transactions.csv), lines_vat.csv and transactions_vat.csv round the SAME economic
    // amount via three independent paths. On discounted/promoted bills these must still agree:
    //  - GROSS: per BON_ID, UMS_BRUTTO == Σlines_vat.POS_BRUTTO == Σtransactions_vat.BON_BRUTTO.
    //  - PER-KEY UST/NET: per (BON_ID, UST_SCHLUESSEL), Σlines_vat.POS_UST == Σtransactions_vat.BON_UST.
    // The BillVatDetail single-netted-per-rate value (what transactions_vat reads AND what the TSE
    // signs) is the authoritative anchor.

    /**
     * (A) Line % discount: item unitPrice 10.10, factor 0.99 → finalPrice 10.00, 19%. lines_vat
     * emits item(10.10)+Rabatt(−0.10) as two independently-rounded rows; transactions_vat reads the
     * single-netted BillVatDetail(10.00). Gross agrees (10.00) but per-key UST/NET drift by 0.01.
     */
    @Test
    fun `RA1-A line percent discount - per-key UST agrees across lines_vat and transactions_vat`() {
        transaction {
            val (admin, shop, vat19, _, cash) = seedRa1Common("RA1A")
            val category = TestData.createCategory("RA1A Cat")
            val product = TestData.createProduct(category.id.value, "RA1A Coffee", "RA1A-A")
            val tws = TestData.createTableWithSession("RA1A1")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "RA1A-9101"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = currentTimeStamp()
                    this.endTime = currentTimeStamp()
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product
                this.unitPrice = BigDecimal("10.10")
                this.finalPrice = BigDecimal("10.00")
                this.discount = BigDecimal("0.99")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            // Mirrors BillServiceImpl.createBillVat on the single netted group total (Σ finalPrice).
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("10.00")
                this.netAmount = BigDecimal("8.40")
                this.vatAmount = BigDecimal("1.60")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = currentTimeStamp()
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("10.00")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (B) Item-specific promotion: item 10.00 @19%, promo deducts 0.20 → group 9.80. lines_vat emits
     * item(10.00)+promo-Rabatt(−0.20) rounded independently; transactions_vat reads BillVatDetail(9.80).
     * Gross agrees (9.80) but per-key UST drifts by 0.01.
     */
    @Test
    fun `RA1-B item-specific promotion - per-key UST agrees across lines_vat and transactions_vat`() {
        transaction {
            val (admin, shop, vat19, _, cash) = seedRa1Common("RA1B")
            val category = TestData.createCategory("RA1B Cat")
            val product = TestData.createProduct(category.id.value, "RA1B Coffee", "RA1B-A")
            val tws = TestData.createTableWithSession("RA1B1")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "RA1B-9102"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("9.80")
                    this.promotionAmount = BigDecimal("0.20")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val item =
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.discount = BigDecimal.ONE
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            // BillServiceImpl nets the item group MINUS the item-specific deduction: 10.00 − 0.20 = 9.80.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("9.80")
                this.netAmount = BigDecimal("8.24")
                this.vatAmount = BigDecimal("1.56")
            }
            val promotion =
                Promotion.new {
                    this.name = "0.20 off item"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("0.20")
                    this.scope = PromotionScope.ITEM_SPECIFIC
                    this.targetProduct = product
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = promotion
                this.orderItem = item
                this.deductedAmount = BigDecimal("0.20")
                this.appliedBy = admin
                this.appliedAt = now
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("9.80")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("9.80")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (C) Bill-wide promotion across TWO rates: 5.00@19% + 5.00@7%, bill-wide promo deducts 0.05.
     * BillServiceImpl splits scale-4 proportionally → 4.9750 per rate; transactions_vat then rounds
     * each to 4.98 (Σ 9.96). lines_vat rounds each Rabatt row to −0.03 (Σ 9.94). UMS_BRUTTO = 9.95.
     * All THREE surfaces disagree on GROSS — the most severe case.
     */
    @Test
    fun `RA1-C bill-wide promotion two rates - gross and per-key agree across all three surfaces`() {
        transaction {
            val (admin, shop, vat19, vat7, cash) = seedRa1Common("RA1C")
            val category = TestData.createCategory("RA1C Cat")
            val productA = TestData.createProduct(category.id.value, "RA1C A", "RA1C-A")
            val productB = TestData.createProduct(category.id.value, "RA1C B", "RA1C-B")
            val tws = TestData.createTableWithSession("RA1C1")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "RA1C-9103"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("9.95")
                    this.promotionAmount = BigDecimal("0.05")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = productA
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = productB
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat7
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            // BillServiceImpl's penny-exact largest-remainder split of 0.05 across equal 5.00/5.00
            // weights: floors 0.02 each, the leftover cent breaks the remainder tie on the lower rate
            // (7%), so shares are 19%→0.02, 7%→0.03. totalAmount = groupGross − share, Σ = 9.95 = paid.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("4.98")
                this.netAmount = BigDecimal("4.18")
                this.vatAmount = BigDecimal("0.80")
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.totalAmount = BigDecimal("4.97")
                this.netAmount = BigDecimal("4.64")
                this.vatAmount = BigDecimal("0.33")
            }
            val promotion =
                Promotion.new {
                    this.name = "0.05 off bill"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("0.05")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = promotion
                this.orderItem = null
                this.deductedAmount = BigDecimal("0.05")
                this.appliedBy = admin
                this.appliedAt = now
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("9.95")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("9.95")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (D) Multiple items at the SAME rate, no promotion: two plain 2.60@19% items PLUS two
     * percent-discounted 10.10->10.00@19% items (single group total 25.20). `generateLineVats`
     * used to round each item's (and each discount row's) net INDEPENDENTLY against its OWN
     * finalPrice, so `Σ round(finalPrice_i/rate)` landed at 21.16 while the single-netted
     * `BillVatDetail`/transactions_vat target is `round(25.20/1.19, 2) = 21.18` — a 0.02 per-key
     * drift with no promotion row anywhere to absorb it (unlike RA1-A/B/C, where the promotion's
     * own residual technique happens to absorb any per-item rounding slop into its own row).
     * The fix applies the SAME per-rate group-residual technique the bill-wide promotion path
     * already used, to the plain item/discount rows too.
     */
    @Test
    fun `RA1-D multiple same-rate items no promotion - per-key UST agrees across lines_vat and transactions_vat`() {
        transaction {
            val (admin, shop, vat19, _, cash) = seedRa1Common("RA1D")
            val category = TestData.createCategory("RA1D Cat")
            val productPlainA = TestData.createProduct(category.id.value, "RA1D Plain A", "RA1D-PA")
            val productPlainB = TestData.createProduct(category.id.value, "RA1D Plain B", "RA1D-PB")
            val productDiscA = TestData.createProduct(category.id.value, "RA1D Disc A", "RA1D-DA")
            val productDiscB = TestData.createProduct(category.id.value, "RA1D Disc B", "RA1D-DB")
            val tws = TestData.createTableWithSession("RA1D1")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "RA1D-9104"
                    this.billAmount = BigDecimal("25.20")
                    this.paidAmount = BigDecimal("25.20")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            listOf(productPlainA, productPlainB).forEach { product ->
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("2.60")
                    this.finalPrice = BigDecimal("2.60")
                    this.discount = BigDecimal.ONE
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            }
            listOf(productDiscA, productDiscB).forEach { product ->
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("10.10")
                    this.finalPrice = BigDecimal("10.00")
                    this.discount = BigDecimal("0.99")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            }
            // Single-netted group total (2.60+2.60+10.00+10.00 = 25.20) -> 25.20/1.19 = 21.18 net.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("25.20")
                this.netAmount = BigDecimal("21.18")
                this.vatAmount = BigDecimal("4.02")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("25.20")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("25.20")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (H3c-followup) Multi-rate OWNER tip: a 5.00@19% item + a 4.00@7% item + a 3.00 OWNER
     * (taxable, TrinkgeldAG) tip. `apportionOwnerTip` used to independently round each rate's
     * scale-4 percentage share (`5.00/9.00=0.5556`, `4.00/9.00=0.4444`), landing on 19%→1.67,
     * 7%→1.33 — coincidentally summing to 3.00 gross here, but netting each share directly
     * (`share/(1+rate)` rounded independently) drifted a cent from `BillVatDetail`'s single-fold
     * `totalAmount.divide(rate, 2)` (which nets the COMBINED item+tip total ONCE). The fix uses
     * `PromotionApportionment.apportion` (penny-exact largest-remainder, matching
     * `BillServiceImpl`'s identical split) for the gross shares, and group-residual nets the tip's
     * OWN net as the residual against the item rows' already-netted total, so
     * `Σ lines_vat.POS_UST == Σ transactions_vat.BON_UST` per (BON_ID, UST_SCHLUESSEL) even though
     * this bill spans two VAT rates (previously untested — the rich fixture's OWNER tip above is
     * single-rate only).
     */
    @Test
    fun `RA1-H3c multi-rate owner tip - per-key UST agrees across lines_vat and transactions_vat`() {
        transaction {
            val (admin, shop, vat19, vat7, cash) = seedRa1Common("RA1H3C")
            val category = TestData.createCategory("RA1H3C Cat")
            val product19 = TestData.createProduct(category.id.value, "RA1H3C Item19", "RA1H3C-19")
            val product7 = TestData.createProduct(category.id.value, "RA1H3C Item7", "RA1H3C-7")
            val tws = TestData.createTableWithSession("RA1H3C1")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "RA1H3C-9105"
                    this.billAmount = BigDecimal("9.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product19
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product7
                this.unitPrice = BigDecimal("4.00")
                this.finalPrice = BigDecimal("4.00")
                this.discount = BigDecimal.ONE
                this.vat = vat7
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            // PromotionApportionment.apportion(3.00, {19%->5.00, 7%->4.00}): ideal 19%=1.6666...67
            // (remainder 0.0067), ideal 7%=1.3333...33 (remainder 0.0033); floors 1.66+1.33=2.99
            // leaves 1 leftover cent, handed to the larger remainder (19%) -> shares 1.67 / 1.33
            // (sum EXACTLY 3.00). Combined-total netting: 19% (5.00+1.67)/1.19=6.67/1.19=5.61,
            // 7% (4.00+1.33)/1.07=5.33/1.07=4.98 -- these are the values BillServiceImpl's fixed
            // owner-tip fold now produces for BillVatDetail.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("6.67")
                this.netAmount = BigDecimal("5.61")
                this.vatAmount = BigDecimal("1.06")
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.totalAmount = BigDecimal("5.33")
                this.netAmount = BigDecimal("4.98")
                this.vatAmount = BigDecimal("0.35")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("12.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("12.00")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    // ============================================
    // A3 (2026-07-12 audit #3) — residual promotion/tip apportionment drifts
    // ============================================

    /**
     * (A3-1) ≥2 same-rate items + a bill-wide promo. Two 2.60@19% items (group gross 5.20) + a
     * bill-wide 1.00 promo. The item rows are group-residual-corrected to net round(5.20/1.19)=4.37,
     * but the bill-wide Rabatt row used a NAIVE per-item net sum Σround(2.60/1.19)=4.36 for its
     * residual — so lines_vat.POS_UST landed at 0.66 vs transactions_vat.BON_UST 0.67. Fix: net the
     * group value once (round(groupGross/(1+rate))) as the itemNetSum.
     */
    @Test
    fun `A3-1 two same-rate items plus bill-wide promo - per-key UST agrees`() {
        transaction {
            val (admin, shop, vat19, _, cash) = seedRa1Common("A31")
            val category = TestData.createCategory("A31 Cat")
            val productA = TestData.createProduct(category.id.value, "A31 A", "A31-A")
            val productB = TestData.createProduct(category.id.value, "A31 B", "A31-B")
            val tws = TestData.createTableWithSession("A311")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "A31-9131"
                    this.billAmount = BigDecimal("5.20")
                    this.paidAmount = BigDecimal("4.20")
                    this.promotionAmount = BigDecimal("1.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            listOf(productA, productB).forEach { product ->
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("2.60")
                    this.finalPrice = BigDecimal("2.60")
                    this.discount = BigDecimal.ONE
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            }
            // Single-netted group: (5.20 − 1.00)/1.19 = 4.20/1.19 → 3.53 net, 0.67 VAT.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("4.20")
                this.netAmount = BigDecimal("3.53")
                this.vatAmount = BigDecimal("0.67")
            }
            val promotion =
                Promotion.new {
                    this.name = "1.00 off bill"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("1.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = promotion
                this.orderItem = null
                this.deductedAmount = BigDecimal("1.00")
                this.appliedBy = admin
                this.appliedAt = now
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("4.20")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("4.20")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (A3-3) Item-specific AND bill-wide promo coexisting across two rates. 10.00@19% with a 2.00
     * item-specific promo (net-of-item-specific 8.00) + 5.00@7%, then a 1.00 bill-wide promo.
     * BillServiceImpl weights the bill-wide split by finalPrice MINUS item-specific deductions
     * ({19%:8.00, 7%:5.00} → 0.62/0.38); the export used to weight by RAW finalPrice
     * ({19%:10.00, 7%:5.00} → 0.67/0.33) — different shares, so lines_vat drifted from
     * transactions_vat both on gross and per-key. Fix: export mirrors the item-specific-net weights.
     */
    @Test
    fun `A3-3 item-specific plus bill-wide promo - gross and per-key agree`() {
        transaction {
            val (admin, shop, vat19, vat7, cash) = seedRa1Common("A33")
            val category = TestData.createCategory("A33 Cat")
            val product19 = TestData.createProduct(category.id.value, "A33 Item19", "A33-19")
            val product7 = TestData.createProduct(category.id.value, "A33 Item7", "A33-7")
            val tws = TestData.createTableWithSession("A331")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "A33-9133"
                    this.billAmount = BigDecimal("15.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.promotionAmount = BigDecimal("3.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val item19 =
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product19
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.discount = BigDecimal.ONE
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product7
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat7
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            // 19%: (10.00 − 2.00 item-specific − 0.62 bill-wide) = 7.38 → 6.20 net / 1.18 VAT.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("7.38")
                this.netAmount = BigDecimal("6.20")
                this.vatAmount = BigDecimal("1.18")
            }
            // 7%: (5.00 − 0.38 bill-wide) = 4.62 → 4.32 net / 0.30 VAT.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.totalAmount = BigDecimal("4.62")
                this.netAmount = BigDecimal("4.32")
                this.vatAmount = BigDecimal("0.30")
            }
            val itemPromo =
                Promotion.new {
                    this.name = "2.00 off item"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("2.00")
                    this.scope = PromotionScope.ITEM_SPECIFIC
                    this.targetProduct = product19
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = itemPromo
                this.orderItem = item19
                this.deductedAmount = BigDecimal("2.00")
                this.appliedBy = admin
                this.appliedAt = now
            }
            val billPromo =
                Promotion.new {
                    this.name = "1.00 off bill"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("1.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = billPromo
                this.orderItem = null
                this.deductedAmount = BigDecimal("1.00")
                this.appliedBy = admin
                this.appliedAt = now
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("12.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("12.00")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (A3-4) TWO bill-wide promos over two rates. Items 5.00@19% + 5.00@7%, two 0.03 bill-wide
     * promos (aggregate 0.06). BillServiceImpl apportions the AGGREGATE 0.06 once → 0.03/0.03; the
     * export apportioned EACH 0.03 separately then summed → 0.02/0.04 (largest-remainder isn't
     * additive). Fix: export aggregates all bill-wide deductions into ONE apportion call.
     */
    @Test
    fun `A3-4 two bill-wide promos - gross and per-key agree`() {
        transaction {
            val (admin, shop, vat19, vat7, cash) = seedRa1Common("A34")
            val category = TestData.createCategory("A34 Cat")
            val productA = TestData.createProduct(category.id.value, "A34 A", "A34-A")
            val productB = TestData.createProduct(category.id.value, "A34 B", "A34-B")
            val tws = TestData.createTableWithSession("A341")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "A34-9134"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("9.94")
                    this.promotionAmount = BigDecimal("0.06")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = productA
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = productB
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat7
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            // Aggregate 0.06 apportioned once over equal 5.00/5.00 weights → 0.03 each.
            // 19%: 4.97 → 4.18 net / 0.79 VAT; 7%: 4.97 → 4.64 net / 0.33 VAT.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("4.97")
                this.netAmount = BigDecimal("4.18")
                this.vatAmount = BigDecimal("0.79")
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.totalAmount = BigDecimal("4.97")
                this.netAmount = BigDecimal("4.64")
                this.vatAmount = BigDecimal("0.33")
            }
            val promotion =
                Promotion.new {
                    this.name = "0.03 off bill (multi)"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("0.03")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            repeat(2) {
                BillPromotion.new {
                    this.bill = bill
                    this.promotion = promotion
                    this.orderItem = null
                    this.deductedAmount = BigDecimal("0.03")
                    this.appliedBy = admin
                    this.appliedAt = now
                }
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("9.94")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("9.94")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /**
     * (A3-2) Multi-rate OWNER-tip STORNO. Original: 5.00@19% + 4.00@7% + a 3.00 OWNER tip, then a
     * full reverse. The reverse carries bill.tips = −3.00; `PromotionApportionment.apportion` used
     * to floor a negative leftover toward zero and hand out no cent, so the reverse tip shares
     * summed to −2.99, drifting Σlines_vat gross to −11.99 ≠ UMS_BRUTTO −12.00. Fix: apportion is
     * sign-symmetric and apportionOwnerTip nets on magnitude then re-applies the sign, so the
     * reverse Beleg is the exact negation of the original and both reconcile per-key and on gross.
     */
    @Test
    fun `A3-2 multi-rate owner tip storno - gross and per-key agree on both Belege`() {
        transaction {
            val (admin, shop, vat19, vat7, cash) = seedRa1Common("A32")
            val category = TestData.createCategory("A32 Cat")
            val product19 = TestData.createProduct(category.id.value, "A32 Item19", "A32-19")
            val product7 = TestData.createProduct(category.id.value, "A32 Item7", "A32-7")
            val tws = TestData.createTableWithSession("A321")
            val session = TableSession.findById(tws.session.id)!!
            val zBon = seedRa1ZBon(admin, shop)
            val now = currentTimeStamp()

            val orig =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "A32-9132"
                    this.billAmount = BigDecimal("9.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val rev =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "A32-9135"
                    this.billAmount = BigDecimal("-9.00")
                    this.paidAmount = BigDecimal("-12.00")
                    this.tips = BigDecimal("-3.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.deletedBill = orig
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.bill = orig
                this.product = product19
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.discount = BigDecimal.ONE
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            OrderItem.new {
                this.session = session
                this.bill = orig
                this.product = product7
                this.unitPrice = BigDecimal("4.00")
                this.finalPrice = BigDecimal("4.00")
                this.discount = BigDecimal.ONE
                this.vat = vat7
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            // Combined item+tip single-fold (matches BillServiceImpl's fixed owner-tip fold):
            // 19% (5.00+1.67)=6.67 → 5.61 net / 1.06 VAT; 7% (4.00+1.33)=5.33 → 4.98 net / 0.35 VAT.
            BillVatDetail.new {
                this.bill = orig
                this.vat = vat19
                this.totalAmount = BigDecimal("6.67")
                this.netAmount = BigDecimal("5.61")
                this.vatAmount = BigDecimal("1.06")
            }
            BillVatDetail.new {
                this.bill = orig
                this.vat = vat7
                this.totalAmount = BigDecimal("5.33")
                this.netAmount = BigDecimal("4.98")
                this.vatAmount = BigDecimal("0.35")
            }
            Payment.new {
                this.bill = orig
                this.paymentType = cash
                this.amount = BigDecimal("12.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundTime = now
                this.refundedBillId = rev
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("12.00")
            }
        }

        val csvFiles = generateAndParseCsv()
        assertGrossThreeWayPerBon(csvFiles)
        assertLinesVatMatchesTransactionsVatPerKey(csvFiles)
    }

    /** Seeds shop + admin + the 19%/7% VAT rates + a cash payment type + a terminal for an RA-1 fixture. */
    private data class Ra1Common(
        val admin: org.example.project.features.employee.domain.models.Employee,
        val shop: ShopInfo,
        val vat19: org.example.project.features.payment.domain.models.Vat,
        val vat7: org.example.project.features.payment.domain.models.Vat,
        val cash: org.example.project.features.payment.domain.models.PaymentType,
    )

    // ============================================================================
    // Round-3 CRITICAL/HIGH standing invariants (Task 26 gate extension)
    // ============================================================================

    /** Flattens a parsed CSV file into header-keyed row maps. */
    private fun rowsOf(
        csvFiles: Map<String, CsvFile>,
        name: String,
    ): List<Map<String, String>> {
        val csv = csvFiles.getValue(name)
        return csv.rows.map { rowMap(csv.header, it) }
    }

    /** Round-3 KABS-2: a full storno of a promoted sale nets the Rabatt businesscases aggregate to 0. */
    @Test
    fun `round3 KABS-2 - storno nets the Rabatt businesscases aggregate to zero`() =
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("GF-KABS2")
            val category = TestData.createCategory("Gate KABS2 Cat")
            val product = TestData.createProduct(category.id.value, "Gate KABS2 Product", "GK2001")
            val now = currentTimeStamp()
            val zBon = seedRa1ZBon(admin, shop)
            val session = TableSession.findById(tws.session.id)!!

            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GK2-1001"
                    this.billAmount = BigDecimal("100.00")
                    this.promotionAmount = BigDecimal("20.00")
                    this.paidAmount = BigDecimal("80.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GK2-2002"
                    this.billAmount = BigDecimal("-100.00")
                    this.promotionAmount = BigDecimal("-20.00")
                    this.paidAmount = BigDecimal("-80.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = originalBill
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = originalBill
                this.unitPrice = BigDecimal("100.00")
                this.finalPrice = BigDecimal("100.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vats[0]
                this.takeAway = false
            }
            Payment.new {
                this.bill = originalBill
                this.paymentType = cash
                this.amount = BigDecimal("80.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundTime = now
                this.refundedBillId = reverseBill
            }
            val promotion =
                Promotion.new {
                    this.name = "20 EUR off bill"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("20.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = originalBill
                this.promotion = promotion
                this.orderItem = null
                this.deductedAmount = BigDecimal("20.00")
                this.appliedBy = admin
                this.appliedAt = now
            }

            val businessCases = service.generateBusinessCases(zBons = listOf(zBon), cashRegisterId = "SN-1")
            val netRabatt =
                businessCases
                    .filter { it.gvTyp == DsfinvkExportDto.BusinessCaseTypes.RABATT }
                    .sumOf { BigDecimal(it.zUmsBrutto) }
            assertEquals(0, netRabatt.compareTo(BigDecimal.ZERO), "storno must net the Rabatt aggregate to zero; got $netRabatt")
        }

    /** Round-3 INV-F2: owner tip over a promo + two rates — lines_vat matches transactions_vat per key. */
    @Test
    fun `round3 INV-F2 - owner tip over promo and two rates agrees per key`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("GF-INVF2")
            val category = TestData.createCategory("Gate INVF2 Cat")
            val product = TestData.createProduct(category.id.value, "Gate INVF2 Product", "GIF2001")
            val now = currentTimeStamp()
            val zBon = seedRa1ZBon(admin, shop)
            val session = TableSession.findById(tws.session.id)!!
            val vat19 = vats[0]
            val vat7 = vats[1]

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "GIF2-A"
                    this.billAmount = BigDecimal("20.00")
                    this.promotionAmount = BigDecimal("5.00")
                    this.paidAmount = BigDecimal("16.00")
                    this.tips = BigDecimal("1.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val item19 =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.bill = bill
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }
            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = bill
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("10.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat7
                this.takeAway = false
            }
            val promotion =
                Promotion.new {
                    this.name = "5 EUR off the 19% item"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("5.00")
                    this.scope = PromotionScope.ITEM_SPECIFIC
                    this.targetProduct = product
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = promotion
                this.orderItem = item19
                this.deductedAmount = BigDecimal("5.00")
                this.appliedBy = admin
                this.appliedAt = now
            }
            // Authoritative transactions_vat side (post-promotion weights + largest-remainder tip).
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("5.33")
                this.netAmount = BigDecimal("4.48")
                this.vatAmount = BigDecimal("0.85")
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.totalAmount = BigDecimal("10.67")
                this.netAmount = BigDecimal("9.97")
                this.vatAmount = BigDecimal("0.70")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("16.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }
        assertLinesVatMatchesTransactionsVatPerKey(generateAndParseCsv())
    }

    /** Round-3 STAM-1: for a 2-device closing every transactions_tse.TSE_ID resolves in tse.csv. */
    @Test
    fun `round3 STAM-1 - every transactions_tse TSE_ID resolves in tse_csv`() {
        val serialA = "A1B2C3D4".repeat(8)
        val serialB = "E5F60789".repeat(8)

        fun seedTse(
            serialHex: String,
            txNumber: Long,
            suffix: String,
        ): TseTransaction =
            transaction {
                TseTransaction.new {
                    this.transactionNumber = txNumber
                    this.signatureCounter = txNumber
                    this.signature = "sig-$suffix"
                    this.signatureAlgorithm = "ecdsa-plain-SHA$suffix"
                    this.publicKey = "pubkey-$suffix"
                    this.certificate = "cert-$suffix"
                    this.tseSerialNumber = serialHex
                    this.clientId = "POS-1"
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash-$suffix"
                    this.startTime = currentTimeStamp()
                    this.endTime = currentTimeStamp()
                    this.tseTimestamp = 1751234570L
                    this.tseStartTimestamp = 1751234560L
                    this.tseMode = TseMode.NORMAL
                }
            }

        fun seedBill(
            number: String,
            tse: TseTransaction,
        ): Long =
            transaction {
                val admin =
                    org.example.project.features.employee.domain.models.Employee
                        .find { org.example.project.features.employee.database.tables.EmployeeTable.username eq "admin" }
                        .firstOrNull() ?: TestData.createAdmin()
                val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
                val zBon = ZBon.all().firstOrNull() ?: TestData.createZBonFullDay(shop, admin, nummer = 1L)
                val session = TableSession.findById(TestData.createTableWithSession(number).session.id)!!
                val now = currentTimeStamp()
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                        this.tseTransaction = tse
                        this.tseSigningStatus = TseSigningStatus.SIGNED
                    }.id.value
            }
        val billA = seedBill("GS15137", seedTse(serialA, 11L, "256"))
        val billB = seedBill("GS16248", seedTse(serialB, 22L, "384"))

        val csv = generateAndParseCsv()
        val tseRows = rowsOf(csv, "tse.csv")
        val serialByTseId = tseRows.associate { it["TSE_ID"] to it["TSE_SERIAL"] }
        assertEquals(2, tseRows.size, "one tse.csv row per signing device")

        val txRows = rowsOf(csv, "transactions_tse.csv")
        val rowA = txRows.single { it["BON_ID"] == "B-$billA" }
        val rowB = txRows.single { it["BON_ID"] == "B-$billB" }
        assertEquals(serialA, serialByTseId[rowA["TSE_ID"]], "bill A's TSE_ID must resolve to device A in tse.csv")
        assertEquals(serialB, serialByTseId[rowB["TSE_ID"]], "bill B's TSE_ID must resolve to device B in tse.csv")
        assertTrue(rowA["TSE_ID"] != rowB["TSE_ID"], "the two devices must carry distinct TSE_IDs")
    }

    /**
     * F43 (real Swissbit QA day, 12 bills): `businesscases.csv` was 1 ct off `transactions_vat.csv`
     * and the Z-Bon at BOTH 19 % (15.30/2.90 against 15.29/2.91) and 7 % (51.78/3.62 against
     * 51.77/3.63), with identical gross. Round-3 INV-F6 netted the closing's aggregated Umsatz
     * gross once. The Z-Bon instead freezes Σ stored `BillVatDetail` of its active bills into
     * `tax_group` (`ZBonServiceImpl.createReport`).
     *
     * The closing here is made by the REAL `createReport`, so the `tax_group` rows asserted against
     * are the ones production writes, not a recomputation. Per (Z_NR, UST_SCHLUESSEL) it pins
     * Σ businesscases == stored `tax_group` == Σ `transactions_vat` (training Belege excluded).
     *
     * Fixture, with hand-computed stored nets `round(gross / (1 + rate), 2)` unless marked legacy:
     *  - 19 %: 4.50 → 3.78, 2.60 → 2.18 (twice, plus once on the mixed bill), and 2.60
     *    discounted to 2.34 → 1.97. Σ 14.64 / 12.29 / 2.35. Closing-level netting gave 12.30.
     *  - 7 %: 12.00 → 11.21 (mixed bill and a plain bill), 3.50 → 3.27, and a LEGACY 12.00 whose
     *    stored net is 11.22 (older scale-4-then-2 formula). Σ 39.50 / 36.91 / 2.59. Closing-level
     *    netting gives 36.92 and fresh per-bill netting 36.90. Only the stored rows give 36.91.
     *  - A Storno pair (2.60 @ 19 % + 12.00 @ 7 %, legacy-stored 11.22). `activeBills` drops both
     *    halves, so their businesscases and transactions_vat rows must cancel to exactly zero per key.
     *  - A training bill (2.60 @ 19 %). Out of `tax_group` and businesscases, and present in
     *    transactions_vat only as AVTraining.
     */
    @Test
    fun `F43 closing per key - businesscases equals the stored tax_group and transactions_vat`() {
        var stornoBonIds: Set<String> = emptySet()
        try {
            val adminId =
                transaction {
                    val (admin, _, vat19, vat7, cash) = seedRa1Common("F43")
                    val category = TestData.createCategory("F43 Cat")
                    val product = TestData.createProduct(category.id.value, "F43 Product", "F43-P")
                    val tws = TestData.createTableWithSession("F43")
                    val session = TableSession.findById(tws.session.id)!!
                    val now = currentTimeStamp()
                    Terminal.new {
                        this.deviceId = closingDevice
                        this.clientId = "client-F43"
                        this.tseClientRegistered = true
                        this.isActive = true
                        this.registeredAt = now
                        this.lastSeenAt = now
                    }

                    data class Line(
                        val unit: String,
                        val final: String,
                        val vat: org.example.project.features.payment.domain.models.Vat,
                        val storedNet: String? = null,
                    )

                    fun bill(
                        tag: String,
                        lines: List<Line>,
                        sign: BigDecimal = BigDecimal.ONE,
                        configure: Bill.() -> Unit = {},
                    ): Bill {
                        val total = lines.sumOf { BigDecimal(it.final) } * sign
                        val created =
                            Bill.new {
                                this.employeeId = admin
                                this.tableSession = session
                                this.billNumber = tag
                                this.billAmount = total
                                this.paidAmount = total
                                this.tips = BigDecimal.ZERO
                                this.startTime = now
                                this.endTime = now
                                configure()
                            }
                        Payment.new {
                            this.bill = created
                            this.paymentType = cash
                            this.amount = total
                            this.currency = "EUR"
                            this.paymentStatus = PaymentStatus.COMPLETED
                            this.paymentTime = now
                        }
                        if (sign.signum() < 0) return created // a reverse bill has no items/VAT rows of its own
                        lines.forEach { line ->
                            OrderItem.new {
                                this.session = session
                                this.bill = created
                                this.product = product
                                this.unitPrice = BigDecimal(line.unit)
                                this.finalPrice = BigDecimal(line.final)
                                this.discount =
                                    if (line.unit == line.final) {
                                        BigDecimal.ONE
                                    } else {
                                        BigDecimal(line.final).divide(BigDecimal(line.unit), 4, java.math.RoundingMode.HALF_UP)
                                    }
                                this.vat = line.vat
                                this.itemStatus = ItemStatus.PAID
                                this.takeAway = false
                            }
                        }
                        lines.groupBy { it.vat }.forEach { (vat, rateLines) ->
                            val gross = rateLines.sumOf { BigDecimal(it.final) }
                            val net =
                                rateLines.singleOrNull()?.storedNet?.let { BigDecimal(it) }
                                    ?: gross.divide(BigDecimal.ONE + vat.vatRate, 2, java.math.RoundingMode.HALF_UP)
                            BillVatDetail.new {
                                this.bill = created
                                this.vat = vat
                                this.totalAmount = gross
                                this.netAmount = net
                                this.vatAmount = gross - net
                            }
                        }
                        return created
                    }

                    bill("F43-9201", listOf(Line("4.50", "4.50", vat19)))
                    bill("F43-9202", listOf(Line("2.60", "2.60", vat19)))
                    bill("F43-9203", listOf(Line("2.60", "2.60", vat19)))
                    bill("F43-9204", listOf(Line("2.60", "2.60", vat19), Line("12.00", "12.00", vat7)))
                    bill("F43-9205", listOf(Line("12.00", "12.00", vat7)))
                    bill("F43-9206", listOf(Line("3.50", "3.50", vat7)))
                    bill("F43-9207", listOf(Line("2.60", "2.34", vat19)))
                    bill("F43-9208", listOf(Line("12.00", "12.00", vat7, storedNet = "11.22")))
                    val stornoLines = listOf(Line("2.60", "2.60", vat19), Line("12.00", "12.00", vat7, storedNet = "11.22"))
                    val original = bill("F43-9209", stornoLines) { this.deletingReason = "F43 storno" }
                    val reverse = bill("F43-9210", stornoLines, sign = BigDecimal.ONE.negate()) { this.deletedBill = original }
                    bill("F43-9211", listOf(Line("2.60", "2.60", vat19))) { this.isTraining = true }

                    // The Z-Bon gate wants the session closed and its table free.
                    val table = RTable.findById(tws.table.id)!!
                    session.completed = true
                    session.endTime = now
                    table.currentSession = null
                    table.tableStatus = TableStatus.FREE
                    stornoBonIds = setOf("B-${original.id.value}", "B-${reverse.id.value}")
                    admin.id.value.toString()
                }

            // The harness sets the TSE config, which needs the shop the fixture just created.
            val zBonService = startClosingHarness()
            runBlocking { zBonService.createReport(adminId, closingDevice) }
        } finally {
            stopClosingHarness()
        }

        val csvFiles = generateAndParseCsv()
        val trainingBonIds =
            rowsOf(csvFiles, "transactions.csv")
                .filter { it["BON_TYP"] == DsfinvkExportDto.ReceiptTypes.AV_TRAINING }
                .map { it.getValue("BON_ID") }
                .toSet()
        assertEquals(1, trainingBonIds.size, "the training bill must be exported as AVTraining")

        val (zNr, taxGroup) =
            transaction {
                val zBon = ZBon.all().single()
                zBon.zbonNummer.toString() to
                    zBon.vatGroups
                        .toList()
                        .groupBy { DsfinvkExportDto.VatKeyMapping.getVatKey(it.vat.vatRate.toDouble()).toString() }
                        .mapValues { (_, rows) ->
                            Triple(rows.sumOf { it.grossAmount }, rows.sumOf { it.netAmount }, rows.sumOf { it.vatAmount })
                        }
            }
        val businessCases =
            sumPerClosingKey(rowsOf(csvFiles, "businesscases.csv").filter { it["Z_NR"] == zNr }, "Z_UMS_BRUTTO", "Z_UMS_NETTO", "Z_UST")
        val transactionsVat =
            sumPerClosingKey(
                rowsOf(csvFiles, "transactions_vat.csv").filter { it["Z_NR"] == zNr && it.getValue("BON_ID") !in trainingBonIds },
                "BON_BRUTTO",
                "BON_NETTO",
                "BON_UST",
            )

        // Hand-computed (see KDoc), independent of every producer and of createReport.
        val expected =
            mapOf(
                "1" to Triple(BigDecimal("14.64"), BigDecimal("12.29"), BigDecimal("2.35")),
                "2" to Triple(BigDecimal("39.50"), BigDecimal("36.91"), BigDecimal("2.59")),
            )
        assertEquals(expected.keys, taxGroup.keys, "stored tax_group keys")
        assertAll(
            expected.flatMap { (key, want) ->
                listOf(
                    Executable { assertTripleEquals(want, taxGroup.getValue(key), "stored tax_group key $key") },
                    Executable { assertTripleEquals(want, businessCases.getValue(key), "businesscases key $key") },
                    Executable { assertTripleEquals(want, transactionsVat.getValue(key), "transactions_vat key $key") },
                )
            },
        )

        // The Storno pair, on its own, cancels to exactly zero per key.
        val stornoVat =
            sumPerClosingKey(
                rowsOf(csvFiles, "transactions_vat.csv").filter { it.getValue("BON_ID") in stornoBonIds },
                "BON_BRUTTO",
                "BON_NETTO",
                "BON_UST",
            )
        stornoVat.forEach { (key, sums) ->
            assertTripleEquals(Triple(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), sums, "Storno pair key $key")
        }
        assertEquals(setOf("1", "2"), stornoVat.keys, "both halves of the Storno pair must be exported")
        assertBusinessCasesMatchTransactionsVatPerKey(csvFiles)
    }

    /**
     * F43 follow-up: businesscases takes its net from the STORED `BillVatDetail`, not from a fresh
     * netting. The legacy bill below carries the row an older formula wrote: 12,00 € at 7 %,
     * netted at scale 4 and re-rounded (11.2150 → 11.22 / 0.78). A fresh netting gives 11.21.
     * `transactions_vat` and the Z-Bon `VatGroup` read the stored row, so the closing must declare
     * 11.22 + 3.27 = 14.49 / 1.01. Re-netting per bill would give 14.48 / 1.02.
     */
    @Test
    fun `F43 businesscases follows a stored legacy BillVatDetail, not a fresh netting`() {
        transaction {
            val (admin, shop, _, vat7, cash) = seedRa1Common("F43L")
            val category = TestData.createCategory("F43L Cat")
            val product = TestData.createProduct(category.id.value, "F43L Product", "F43L-P")
            val zBon = seedRa1ZBon(admin, shop)

            fun sale(
                tag: String,
                gross: String,
                storedNet: String,
            ): Bill {
                val tws = TestData.createTableWithSession(tag)
                val session = TableSession.findById(tws.session.id)!!
                val now = currentTimeStamp()
                val amount = BigDecimal(gross)
                val bill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = tag
                        this.billAmount = amount
                        this.paidAmount = amount
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                    }
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = amount
                    this.finalPrice = amount
                    this.discount = BigDecimal.ONE
                    this.vat = vat7
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
                BillVatDetail.new {
                    this.bill = bill
                    this.vat = vat7
                    this.totalAmount = amount
                    this.netAmount = BigDecimal(storedNet)
                    this.vatAmount = amount - BigDecimal(storedNet)
                }
                Payment.new {
                    this.bill = bill
                    this.paymentType = cash
                    this.amount = amount
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                }
                return bill
            }

            // Legacy row: older scale-4-then-2 formula. A fresh netting of 12.00 / 1.07 is 11.21.
            sale("F43-9301", "12.00", "11.22")
            // Current formula: round(3.50 / 1.07, 2) = 3.27.
            sale("F43-9302", "3.50", "3.27")
        }

        val csvFiles = generateAndParseCsv()
        val businessCases = sumPerClosingKey(rowsOf(csvFiles, "businesscases.csv"), "Z_UMS_BRUTTO", "Z_UMS_NETTO", "Z_UST")
        val transactionsVat = sumPerClosingKey(rowsOf(csvFiles, "transactions_vat.csv"), "BON_BRUTTO", "BON_NETTO", "BON_UST")
        val want = Triple(BigDecimal("15.50"), BigDecimal("14.49"), BigDecimal("1.01"))
        assertTripleEquals(want, businessCases.getValue("2"), "businesscases key 2 (stored legacy net)")
        assertTripleEquals(want, transactionsVat.getValue("2"), "transactions_vat key 2")
        assertBusinessCasesMatchTransactionsVatPerKey(csvFiles)
    }

    /** Per UST_SCHLUESSEL: (Σ brutto, Σ netto, Σ USt) over [rows]. */
    private fun sumPerClosingKey(
        rows: List<Map<String, String>>,
        bruttoCol: String,
        nettoCol: String,
        ustCol: String,
    ): Map<String, Triple<BigDecimal, BigDecimal, BigDecimal>> =
        rows
            .groupBy { it.getValue("UST_SCHLUESSEL") }
            .mapValues { (_, group) ->
                Triple(
                    group.sumOf { parseAmount(it.getValue(bruttoCol)) },
                    group.sumOf { parseAmount(it.getValue(nettoCol)) },
                    group.sumOf { parseAmount(it.getValue(ustCol)) },
                )
            }

    private fun assertTripleEquals(
        want: Triple<BigDecimal, BigDecimal, BigDecimal>,
        got: Triple<BigDecimal, BigDecimal, BigDecimal>,
        what: String,
    ) {
        assertEquals(0, want.first.compareTo(got.first), "$what brutto: want ${want.first}, got ${got.first}")
        assertEquals(0, want.second.compareTo(got.second), "$what netto: want ${want.second}, got ${got.second}")
        assertEquals(0, want.third.compareTo(got.third), "$what USt: want ${want.third}, got ${got.third}")
    }

    /**
     * Invariant 19 (F43): per (Z_KASSE_ID, Z_NR, UST_SCHLUESSEL), Σ businesscases
     * Z_UMS_BRUTTO / Z_UMS_NETTO / Z_UST == Σ transactions_vat BON_BRUTTO / BON_NETTO / BON_UST.
     *
     * AVTraining Belege are left out of the transactions_vat side. Anhang B says they „haben keine
     * Auswirkungen auf den Kassenabschluss", so businesscases rightly omits them.
     */
    private fun assertBusinessCasesMatchTransactionsVatPerKey(csvFiles: Map<String, CsvFile>) {
        val trainingBonIds =
            rowsOf(csvFiles, "transactions.csv")
                .filter { it["BON_TYP"] == DsfinvkExportDto.ReceiptTypes.AV_TRAINING }
                .map { it.getValue("BON_ID") }
                .toSet()

        fun sumByClosingKey(
            rows: List<Map<String, String>>,
            cols: Triple<String, String, String>,
        ): Map<List<String>, List<BigDecimal>> =
            rows
                .groupBy { listOf(it.getValue("Z_KASSE_ID"), it.getValue("Z_NR"), it.getValue("UST_SCHLUESSEL")) }
                .mapValues { (_, group) ->
                    listOf(cols.first, cols.second, cols.third).map { col -> group.sumOf { parseAmount(it.getValue(col)) } }
                }

        val bc = sumByClosingKey(rowsOf(csvFiles, "businesscases.csv"), Triple("Z_UMS_BRUTTO", "Z_UMS_NETTO", "Z_UST"))
        val tv =
            sumByClosingKey(
                rowsOf(csvFiles, "transactions_vat.csv").filter { it.getValue("BON_ID") !in trainingBonIds },
                Triple("BON_BRUTTO", "BON_NETTO", "BON_UST"),
            )
        val zero = listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        (bc.keys + tv.keys).forEach { key ->
            val b = bc[key] ?: zero
            val t = tv[key] ?: zero
            listOf("BRUTTO", "NETTO", "UST").forEachIndexed { i, label ->
                assertEquals(
                    0,
                    b[i].compareTo(t[i]),
                    "(Z_KASSE_ID, Z_NR, UST_SCHLUESSEL)=$key: Σ businesscases $label (${b[i]}) must equal " +
                        "Σ transactions_vat $label (${t[i]})",
                )
            }
        }
    }

    // ----------------------------------------------------------------------------
    // Real-closing harness (F43): `ZBonServiceImpl.createReport` writes the stored `tax_group` rows.
    // Same wiring as `ZBonAggregateUnsignedTest`; TSE signing is captured, not performed.
    // ----------------------------------------------------------------------------

    private fun startClosingHarness(): ZBonServiceImpl {
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        startKoin { modules(serializationModule, httpClientModule, reposModule, servicesModule) }
        runBlocking {
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "12345"))
        }
        transaction {
            // H2 stand-in for the Postgres advisory lock `createReport` takes first.
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }
        val tseServiceFactory =
            TseServiceFactory(
                tseTransactionRepository = TseTransactionRepositoryImpl(),
                tseSignClient = closingSignProvider,
                signer = TseVorgangSigner(closingSignProvider, TseTransactionRepositoryImpl(), bestEffortProxy()),
            )
        return ZBonServiceImpl(
            repository = ZBonRepositoryImpl(),
            kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
            cashBookRepository = CashBookRepositoryImpl(),
            cashBookTransferService =
                CashBookTransferServiceImpl(
                    configManager = ShopConfigurationManager,
                    cashBookRepository = CashBookRepositoryImpl(),
                    kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                    employeeRepository = EmployeeRepositoryImpl(),
                    terminalRepository = TerminalRepositoryImpl(),
                    documents = testKassenbuchDocuments(pdfGenerationService = bestEffortProxy()),
                    tseSigner =
                        KassenbuchTseSigner(
                            configManager = ShopConfigurationManager,
                            tseServiceFactory = tseServiceFactory,
                            tseSigningQueueService = bestEffortProxy(),
                        ),
                ),
            tseServiceFactory = tseServiceFactory,
            tseSigningQueueService = bestEffortProxy(),
            terminalRepository = TerminalRepositoryImpl(),
            documents = testKassenbuchDocuments(pdfGenerationService = bestEffortProxy(), documentManagementService = bestEffortProxy()),
            favouriteProductService = bestEffortProxy(),
            configManager = ShopConfigurationManager,
            cardTerminalService = bestEffortProxy(),
            cardSettlementService = bestEffortProxy(),
            tableSessionRepository = TableSessionRepositoryImpl(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )
    }

    private fun stopClosingHarness() {
        runBlocking {
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "12345"))
        }
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
    }

    private val closingDevice = "f43-closing-device"

    private val closingSignProvider =
        object : TseSignProvider {
            private var txCounter = 0L

            override suspend fun quickSign(
                processType: String,
                processData: String,
                clientId: String,
            ): TseDto.SignResultDto {
                txCounter += 1
                return TseDto.SignResultDto(
                    success = true,
                    processType = processType,
                    transactionNumber = txCounter,
                    signature = "SIG-$txCounter",
                    timestamp = 1_752_000_000L,
                    serialNumber = "AA".repeat(32),
                    signatureCounter = txCounter,
                    signatureAlgorithm = "ecdsa-plain-SHA384",
                    startTimestamp = 1_752_000_000L,
                )
            }

            override suspend fun startTransaction(
                processType: String,
                clientId: String,
            ) = throw NotImplementedError("the closing signs via quickSign only")

            override suspend fun updateTransaction(
                transactionNumber: Long,
                processData: String,
                clientId: String,
            ) = throw NotImplementedError("the closing signs via quickSign only")

            override suspend fun finishTransaction(
                transactionNumber: Long,
                processData: String,
                clientId: String,
                processType: String,
            ) = throw NotImplementedError("the closing signs via quickSign only")

            override suspend fun checkHealth(): TseDto.HealthCheckResultDto =
                TseDto.HealthCheckResultDto(
                    healthy = true,
                    initialized = true,
                    validTime = true,
                    passedSelfTest = true,
                    remainingSignatures = 1_000L,
                    availableCapacity = 1_000L,
                )

            override fun isConnected(): Boolean = true

            override fun getCachedMode(): String = "SIMULATION"

            override fun registerConnectionListener() = Unit

            override suspend fun restartConnection(): TseDto.HealthCheckResultDto? = null

            override fun close() = Unit
        }

    /** Best-effort collaborator: throws an `Exception` that `createReport`'s optional steps swallow. */
    private inline fun <reified T : Any> bestEffortProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw UnsupportedOperationException("Stub: ${T::class.simpleName}.${method.name} is best-effort here")
        } as T
    }

    private fun seedRa1Common(suffix: String): Ra1Common {
        val admin = TestData.createAdmin()
        val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
        val vats = TestData.createDefaultVatRates()
        val cash = TestData.createPaymentType("Cash-$suffix", isCash = true)
        val now = currentTimeStamp()
        Terminal.new {
            deviceId = "unknown"
            clientId = "TERM-$suffix"
            registeredAt = now
            lastSeenAt = now
        }
        return Ra1Common(admin, shop, vats[0], vats[1], cash)
    }

    private fun seedRa1ZBon(
        admin: org.example.project.features.employee.domain.models.Employee,
        shop: ShopInfo,
    ): ZBon {
        val now = currentTimeStamp()
        return ZBon.new {
            this.shopInfo = shop
            this.employee = admin
            this.startTime = now
            this.endTime = now
            this.cancelledAmount = BigDecimal.ZERO
            this.totalDiscounts = BigDecimal.ZERO
            this.totalPosRevenue = BigDecimal.ZERO
            this.adminTip = BigDecimal.ZERO
            this.openingBalance = BigDecimal.ZERO
            this.expenses = BigDecimal.ZERO
            this.cashRevenue = BigDecimal.ZERO
            this.closingBalance = BigDecimal.ZERO
            this.createdAt = now
            this.trainingRevenue = BigDecimal.ZERO
            this.trainingBillCount = 0
            this.refundAmount = BigDecimal.ZERO
            this.stornoBillCount = 0
            this.zbonNummer = 1L
        }
    }

    private fun generateAndParseCsv(): Map<String, CsvFile> {
        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }
        return unzip(zip)
            .filterKeys { it.endsWith(".csv") }
            .mapValues { (name, bytes) -> parseCsvFile(name, bytes) }
    }

    /**
     * Per BON_ID: transactions.UMS_BRUTTO == Σlines_vat.POS_BRUTTO == Σtransactions_vat.BON_BRUTTO
     * (invariant 8, applied to the focused RA-1 fixtures).
     */
    private fun assertGrossThreeWayPerBon(csvFiles: Map<String, CsvFile>) {
        val transactionsCsv = csvFiles.getValue("transactions.csv")
        val umsByBon =
            transactionsCsv.rows.associate { row ->
                val map = rowMap(transactionsCsv.header, row)
                map.getValue("BON_ID") to parseAmount(map.getValue("UMS_BRUTTO"))
            }
        val linesVat = csvFiles.getValue("lines_vat.csv")
        val lvByBon =
            linesVat.rows
                .map { rowMap(linesVat.header, it) }
                .groupBy { it.getValue("BON_ID") }
                .mapValues { (_, rows) -> rows.sumOf { parseAmount(it.getValue("POS_BRUTTO")) } }
        val transVat = csvFiles.getValue("transactions_vat.csv")
        val tvByBon =
            transVat.rows
                .map { rowMap(transVat.header, it) }
                .groupBy { it.getValue("BON_ID") }
                .mapValues { (_, rows) -> rows.sumOf { parseAmount(it.getValue("BON_BRUTTO")) } }

        umsByBon.forEach { (bonId, ums) ->
            val lv = lvByBon[bonId] ?: BigDecimal.ZERO
            val tv = tvByBon[bonId] ?: BigDecimal.ZERO
            assertEquals(0, lv.compareTo(ums), "BON_ID=$bonId: Σlines_vat.POS_BRUTTO ($lv) must equal UMS_BRUTTO ($ums)")
            assertEquals(0, tv.compareTo(ums), "BON_ID=$bonId: Σtransactions_vat.BON_BRUTTO ($tv) must equal UMS_BRUTTO ($ums)")
        }
    }

    /**
     * DSFinV-K 3.1.2.1 makes (Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, BON_ID, UST_SCHLUESSEL) the KEY of
     * `Bonkopf_USt`, so a BON_ID must carry each UST_SCHLUESSEL at most once.
     *
     * `generateBonkopfUst` appends into one list from five independent sources — the `BillVatDetail`
     * loop, the Mehrzweckgutschein decomposition, the Forderungsentstehung block, the clearing block
     * and the employee-tip row — and FOUR of those can emit key 5. `BonkopfUstCsvExporter` writes
     * rows 1:1 with no grouping, so without a collapse both halves ship.
     *
     * This is the only assertion in the class that can see such a split: every other invariant is a
     * SUM, and two rows summing to the right total are indistinguishable from one. A sixth row
     * source trips this first.
     */
    private fun assertReferencesAreUnique(csvFiles: Map<String, CsvFile>) {
        val references = csvFiles.getValue("references.csv")
        val duplicated =
            references.rows
                .map { rowMap(references.header, it) }
                .groupBy {
                    listOf(
                        it["Z_KASSE_ID"],
                        it["Z_ERSTELLUNG"],
                        it["Z_NR"],
                        it["BON_ID"],
                        it["POS_ZEILE"],
                        it["REF_TYP"],
                        it["REF_Z_KASSE_ID"],
                        it["REF_Z_NR"],
                        it["REF_BON_ID"],
                    )
                }.filterValues { it.size > 1 }

        assertTrue(duplicated.isEmpty(), "references.csv must name each referenced Vorgang once per referencing line. Duplicated: $duplicated")
    }

    private fun assertTransactionsVatKeysAreUnique(csvFiles: Map<String, CsvFile>) {
        val transVat = csvFiles.getValue("transactions_vat.csv")
        val duplicated =
            transVat.rows
                .map { rowMap(transVat.header, it) }
                .groupBy {
                    listOf(
                        it.getValue("Z_KASSE_ID"),
                        it.getValue("Z_ERSTELLUNG"),
                        it.getValue("Z_NR"),
                        it.getValue("BON_ID"),
                        it.getValue("UST_SCHLUESSEL"),
                    )
                }.filterValues { it.size > 1 }

        assertTrue(
            duplicated.isEmpty(),
            "transactions_vat.csv must carry each (Z_KASSE_ID, Z_ERSTELLUNG, Z_NR, BON_ID, " +
                "UST_SCHLUESSEL) at most once — DSFinV-K 3.1.2.1 makes that tuple the key of " +
                "Bonkopf_USt. Duplicated: $duplicated",
        )
    }

    /**
     * Per (BON_ID, UST_SCHLUESSEL): Σ lines_vat.POS_UST == Σ transactions_vat.BON_UST. Keys present
     * on only one side default to 0 on the other (e.g. an employee-tip/voucher key-5 row carries
     * zero UST), so a one-sided key still reconciles.
     */
    private fun assertLinesVatMatchesTransactionsVatPerKey(csvFiles: Map<String, CsvFile>) {
        val linesVat = csvFiles.getValue("lines_vat.csv")
        val transVat = csvFiles.getValue("transactions_vat.csv")

        fun sumUstByBonKey(
            csv: CsvFile,
            ustCol: String,
        ): Map<Pair<String, String>, BigDecimal> =
            csv.rows
                .map { rowMap(csv.header, it) }
                .groupBy { it.getValue("BON_ID") to it.getValue("UST_SCHLUESSEL") }
                .mapValues { (_, rows) -> rows.sumOf { parseAmount(it.getValue(ustCol)) } }

        val lvByKey = sumUstByBonKey(linesVat, "POS_UST")
        val tvByKey = sumUstByBonKey(transVat, "BON_UST")

        (lvByKey.keys + tvByKey.keys).forEach { key ->
            val lv = lvByKey[key] ?: BigDecimal.ZERO
            val tv = tvByKey[key] ?: BigDecimal.ZERO
            assertEquals(
                0,
                lv.compareTo(tv),
                "(BON_ID, UST_SCHLUESSEL)=$key: Σlines_vat.POS_UST ($lv) must equal " +
                    "Σtransactions_vat.BON_UST ($tv)",
            )
        }
    }

    // ============================================================================
    // Round-5 standing invariants (Task 11 gate extension)
    //
    // Round 5's central lesson: PRODUCER AGREEMENT IS NOT CORRECTNESS. C6 (Kassenbuch
    // corrections booked at UST_SCHLUESSEL 5 / USt 0,00) was invisible to every
    // agreement-style check in this class, because all three Kassenbuch VAT producers were
    // wrong TOGETHER — cross-producer comparison reported perfect consistency before AND
    // after the fix. Every assertion below therefore derives at least one side from the
    // DSFinV-K spec (Anlage 2's fixed key→rate table, or the brutto/netto/USt decomposition
    // of a hand-computed fixture) rather than from a second producer.
    // ============================================================================

    /**
     * Round-5 C6 gate — a Kassenbuch correction must unwind the corrected entry's VAT, at the
     * CSV surface, in all three Kassenbuch VAT producers at once.
     *
     * `DsfinvkKassenbuchCorrectionVatTest` pins this at the *producer* level (it calls
     * `generateAvLineVats` / `appendKassenbuchBonkopfUst` / `aggregateKassenbuchBusinessCases`
     * directly). This gate pins the same claim at the *export* level — the bytes an auditor
     * actually receives — so a regression in the CSV writers, the closing attribution or the
     * BON_TYP filters is caught as well as one in the shared `KassenbuchTaxClassifier`
     * (round-7 replaced the entry-level `hasVatImplication` this originally referred to).
     *
     * **Fixture (hand-computed from the spec, not from any producer):** a 119,00 € Barausgabe at
     * 19 % and its full reversal. DSFinV-K Anlage 2 assigns UST_SCHLUESSEL 1 the 19 % general
     * rate, so the decomposition is fixed: 119,00 = 100,00 netto + 19,00 USt. The reversal of a
     * 19 % Barausgabe is itself a 19 % event — it unwinds the Vorsteuer — so it must book at
     * key 1 with the opposite sign, NOT at key 5 „nicht steuerbar" with USt 0,00 (the pre-fix
     * behaviour, which left the original's Vorsteuer standing and made the Kassenabschluss
     * over-declare by exactly 19,00 €).
     *
     * The `assertEquals("1", …)` key assertions and the ±19,00 amounts are the load-bearing
     * part: the netting assertions alone would ALSO pass on the broken code, where both rows sat
     * at key 5 with 0,00 and trivially summed to zero.
     */
    @Test
    @DisplayName("round5 C6 - a Kassenbuch correction unwinds the corrected entry's VAT in all three CSV producers")
    fun `round5 C6 - Kassenbuch correction pair books at the corrected rate key and nets to zero USt`() {
        var originalBon = ""
        var reversalBon = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            TestData.createDefaultVatRates()
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-GATE-C6"
                registeredAt = now
                lastSeenAt = now
            }
            val closing =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }

            fun seedKbEntry(
                belegNr: String,
                entryType: KassenbuchEntryType,
                amount: String,
                correctsHeaderId: Long? = null,
                correctionRole: CorrectionRole? = null,
            ): Long {
                val entry =
                    KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                        shop = shop,
                        employee = admin,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = entryType,
                                belegNr = belegNr,
                                vorzBetrag = amount,
                                belegDatum = "18.07.2026",
                                belegtext = "C6 gate $belegNr",
                                correctsHeaderId = correctsHeaderId,
                                correctionRole = correctionRole,
                            ),
                        lineItemsData =
                            listOf(
                                KassenbuchDto.LineItemData(
                                    vorzBetrag = amount,
                                    ustSatz = "19",
                                    kind = KassenbuchLineKind.STANDARD,
                                ),
                            ),
                    )
                entry.zBon = closing
                entry.createdAt = now
                return entry.id.value
            }

            // 119,00 € Barausgabe: cash leaves the drawer, so the stored cash-flow amount is
            // negative. Its full reversal is the sign-inverted CORRECTION_IN.
            val originalId = seedKbEntry("2026-07-9001", KassenbuchEntryType.CASH_EXPENSE, "-119.00")
            val reversalId =
                seedKbEntry(
                    belegNr = "2026-07-9002",
                    entryType = KassenbuchEntryType.CORRECTION_IN,
                    amount = "119.00",
                    correctsHeaderId = originalId,
                    correctionRole = CorrectionRole.REVERSAL,
                )
            originalBon = KassenbuchDsfinvkMapping.generateBonId(originalId)
            reversalBon = KassenbuchDsfinvkMapping.generateBonId(reversalId)
        }

        val csvFiles = generateAndParseCsv()

        // --- Producer 1: lines_vat.csv (generateAvLineVats) ---
        val linesVatPair =
            rowsOf(csvFiles, "lines_vat.csv")
                .filter { it.getValue("BON_ID") in setOf(originalBon, reversalBon) }
        assertVatPairSpecDecomposition(
            rows = linesVatPair,
            producer = "lines_vat.csv",
            originalBon = originalBon,
            reversalBon = reversalBon,
            bruttoCol = "POS_BRUTTO",
            nettoCol = "POS_NETTO",
            ustCol = "POS_UST",
        )

        // --- Producer 2: transactions_vat.csv (appendKassenbuchBonkopfUst) ---
        val transVatPair =
            rowsOf(csvFiles, "transactions_vat.csv")
                .filter { it.getValue("BON_ID") in setOf(originalBon, reversalBon) }
        assertVatPairSpecDecomposition(
            rows = transVatPair,
            producer = "transactions_vat.csv",
            originalBon = originalBon,
            reversalBon = reversalBon,
            bruttoCol = "BON_BRUTTO",
            nettoCol = "BON_NETTO",
            ustCol = "BON_UST",
        )

        // --- Producer 3: businesscases.csv (aggregateKassenbuchBusinessCases) ---
        // The pair lives in ONE closing, so it collapses into a single netted Auszahlung
        // aggregate. Load-bearing: it sits at key 1 (the corrected entry's rate) declaring
        // 0,00 USt, and there is NO stray key-5 aggregate carrying the reversal's 119,00.
        val kbBusinessCases =
            rowsOf(csvFiles, "businesscases.csv")
                .filter { it.getValue("GV_TYP") == DsfinvkExportDto.BusinessCaseTypes.AUSZAHLUNG }
        assertEquals(
            listOf("1"),
            kbBusinessCases.map { it.getValue("UST_SCHLUESSEL") },
            "C6: a fully reversed 19% Barausgabe must leave exactly one Auszahlung aggregate at " +
                "UST_SCHLUESSEL 1 (Anlage 2: the general rate), not a key-5 row; got $kbBusinessCases",
        )
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(parseAmount(kbBusinessCases.single().getValue("Z_UST"))),
            "C6: the netted Auszahlung aggregate must declare 0,00 USt — the Kassenabschluss must " +
                "not over-declare by the corrected entry's 19,00 €",
        )
        assertTrue(
            rowsOf(csvFiles, "businesscases.csv").none {
                it.getValue("UST_SCHLUESSEL") == "5" && parseAmount(it.getValue("Z_UMS_BRUTTO")).signum() != 0
            },
            "C6: no Kassenbuch amount may land in a key-5 „nicht steuerbar\" businesscases aggregate",
        )

        // C7-shaped structural closure — asserted on a fixture that actually produces
        // Kassenbuch Bonpos_USt rows, which is the surface C7 concerns.
        assertEveryLinesVatBonHasATransactionsVatParent(csvFiles)
        assertUstDecomposesAtItsAnlage2Rate(csvFiles)
    }

    /**
     * F51 (2026-10): an employee-tip payout leaves the register as `TrinkgeldAN` at UST_SCHLUESSEL 5
     * with 0,00 USt in EVERY Kassenbuch producer — never as the `Auszahlung` its `CASH_WITHDRAWAL`
     * header would make it, which is rate-governed.
     *
     * DSFinV-K 2.4 Anhang C p. 52: „Im Geschäftsvorfalltyp ‚TrinkgeldAN' werden alle
     * kassenwirksamen Ein- und Auszahlungen von Trinkgeldern an den Arbeitnehmer erfasst … Es ist
     * möglich, mit diesem Geschäftsvorfalltyp sowohl die Ein- als auch die Auszahlung an den
     * Arbeitnehmer darzustellen." The line is seeded with a DECLARED 19 % on purpose: the classifier
     * must discard it (TrinkgeldAN is one of the nine spec-pinned key-5 GV_TYPs), so a key-1 row
     * anywhere is the defect.
     */
    @Test
    @DisplayName("F51 - an employee-tip payout exports as TrinkgeldAN at key 5 in every Kassenbuch producer")
    fun `F51 employee-tip payout is TrinkgeldAN key 5 in all four Kassenbuch producers`() {
        var payoutBon = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            TestData.createDefaultVatRates()
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-GATE-F51"
                registeredAt = now
                lastSeenAt = now
            }
            val closing =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
            val entry =
                KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                    shop = shop,
                    employee = admin,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_WITHDRAWAL,
                            belegNr = "2026-10-9001",
                            vorzBetrag = "-12.50",
                            belegDatum = "04.10.2026",
                            belegtext = "TA:Trinkgeld-Auszahlung Gate",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "-12.50", ustSatz = "19", kind = KassenbuchLineKind.EMPLOYEE_TIP),
                        ),
                )
            entry.zBon = closing
            entry.createdAt = now
            payoutBon = KassenbuchDsfinvkMapping.generateBonId(entry.id.value)
        }

        val csvFiles = generateAndParseCsv()

        val line = rowsOf(csvFiles, "lines.csv").single { it.getValue("BON_ID") == payoutBon }
        assertEquals(DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN, line.getValue("GV_TYP"), "lines.csv: $line")

        listOf(
            Triple("lines_vat.csv", "POS_BRUTTO", "POS_UST"),
            Triple("transactions_vat.csv", "BON_BRUTTO", "BON_UST"),
        ).forEach { (file, bruttoCol, ustCol) ->
            val row = rowsOf(csvFiles, file).single { it.getValue("BON_ID") == payoutBon }
            assertEquals("5", row.getValue("UST_SCHLUESSEL"), "$file: a tip payout is nicht steuerbar; got $row")
            assertEquals(0, BigDecimal("-12.50").compareTo(parseAmount(row.getValue(bruttoCol))), "$file $bruttoCol")
            assertEquals(0, BigDecimal.ZERO.compareTo(parseAmount(row.getValue(ustCol))), "$file $ustCol")
        }

        val businessCase =
            rowsOf(csvFiles, "businesscases.csv").single {
                it.getValue("GV_TYP") == DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN
            }
        assertEquals("5", businessCase.getValue("UST_SCHLUESSEL"))
        assertEquals(0, BigDecimal("-12.50").compareTo(parseAmount(businessCase.getValue("Z_UMS_BRUTTO"))))
        assertTrue(
            rowsOf(csvFiles, "businesscases.csv").none { it.getValue("GV_TYP") == DsfinvkExportDto.BusinessCaseTypes.AUSZAHLUNG },
            "the payout must not surface as an Auszahlung",
        )

        assertEveryLinesVatBonHasATransactionsVatParent(csvFiles)
        assertUstDecomposesAtItsAnlage2Rate(csvFiles)
    }

    /**
     * Asserts the ±19,00 € decomposition of the C6 fixture for ONE producer, entirely from
     * spec-derived absolutes (Anlage 2 key 1 = 19 %; 119,00 = 100,00 + 19,00). Nothing here
     * compares one producer against another — that is precisely the check C6 defeated.
     */
    private fun assertVatPairSpecDecomposition(
        rows: List<Map<String, String>>,
        producer: String,
        originalBon: String,
        reversalBon: String,
        bruttoCol: String,
        nettoCol: String,
        ustCol: String,
    ) {
        fun row(bon: String): Map<String, String> =
            rows.singleOrNull { it.getValue("BON_ID") == bon }
                ?: error("$producer must carry exactly one VAT row for BON_ID=$bon; got $rows")

        fun check(
            bon: String,
            label: String,
            key: String,
            brutto: String,
            netto: String,
            ust: String,
        ) {
            val r = row(bon)
            assertEquals(
                key,
                r.getValue("UST_SCHLUESSEL"),
                "$producer/$label (BON_ID=$bon): a 19% Barausgabe and its reversal both book at " +
                    "UST_SCHLUESSEL $key per Anlage 2 — key 5 „nicht steuerbar\" leaves the " +
                    "original's Vorsteuer standing (round-5 C6); got row $r",
            )
            assertEquals(0, BigDecimal(brutto).compareTo(parseAmount(r.getValue(bruttoCol))), "$producer/$label $bruttoCol")
            assertEquals(0, BigDecimal(netto).compareTo(parseAmount(r.getValue(nettoCol))), "$producer/$label $nettoCol")
            assertEquals(0, BigDecimal(ust).compareTo(parseAmount(r.getValue(ustCol))), "$producer/$label $ustCol")
        }

        check(originalBon, "original Barausgabe", "1", "-119.00", "-100.00", "-19.00")
        check(reversalBon, "reversal", "1", "119.00", "100.00", "19.00")

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(rows.sumOf { parseAmount(it.getValue(ustCol)) }),
            "$producer: the storno pair must net to 0,00 USt",
        )
    }

    /**
     * Round-5 C7 (structural): every `lines_vat` (Bonpos_USt) BON_ID must have a
     * `transactions_vat` (Bonkopf_USt) parent.
     *
     * Spec-derived, not producer-comparison: Tz. 3.1.2 („Aus den Positionsdaten müssen die Daten
     * des Bonkopfes ermittelt … werden können") and the Anhang-E key model make Bonkopf_USt the
     * parent of Bonpos_USt — an auditor's join drops a fatherless Bonpos_USt row silently, so the
     * position VAT never reaches the Bon total. It is a one-sided containment check; no second
     * producer is consulted for the expected value.
     *
     * **Known residual gap (C7, OPEN):** the shape that actually breaks this today is a
     * legacy-persisted `CLOSING_BALANCE` Kassenbuch entry carrying line items —
     * `generateAvLineVats` and `generateKassenbuchLineItems` iterate the entries with no BON_TYP
     * filter while `appendKassenbuchBonkopfUst` filters to BELEG. No fixture here seeds that,
     * deliberately: C7 is unfixed, so such a fixture would make the release gate permanently RED.
     * When C7 is fixed, add a `CLOSING_BALANCE`-with-lines fixture and this assertion becomes the
     * regression pin.
     */
    private fun assertEveryLinesVatBonHasATransactionsVatParent(csvFiles: Map<String, CsvFile>) {
        val parents = rowsOf(csvFiles, "transactions_vat.csv").map { it.getValue("BON_ID") }.toSet()
        val orphans =
            rowsOf(csvFiles, "lines_vat.csv")
                .map { it.getValue("BON_ID") }
                .distinct()
                .filterNot { it in parents }
        assertTrue(
            orphans.isEmpty(),
            "every lines_vat.csv BON_ID must have a transactions_vat.csv parent (Bonpos_USt without " +
                "a Bonkopf_USt is dropped by an auditor's join); orphans: $orphans",
        )
    }

    /**
     * Anlage-2 conformance: for every VAT row, the declared USt must be the decomposition of the
     * declared brutto **at the rate Anlage 2 fixes for that UST_SCHLUESSEL**.
     *
     * This is the strongest available spec-derived check: [ANLAGE_2_FIXED_RATES] is transcribed
     * from `20241205_DSFinV_K_Anlage2_UStSchluessel.pdf`, not read from any application code, so
     * the expected value on one side of the equality has no shared origin with the export at all.
     * The pre-existing "7. VAT ROW" invariant only checks brutto == netto + USt, which a row
     * booked at the WRONG key still satisfies.
     *
     * Keys 1, 2, 3, 4 and 8 are skipped (absent from [ANLAGE_2_FIXED_RATES]): Anlage 2 leaves their
     * „USt-Satz" column blank — they mean "the current statutory rate at recording time", so there
     * is no spec constant to compare against. A ±0,01 tolerance absorbs the legitimate residue of
     * aggregating per-item rounded amounts.
     */
    private fun assertUstDecomposesAtItsAnlage2Rate(csvFiles: Map<String, CsvFile>) {
        listOf(
            Triple("lines_vat.csv", "POS_BRUTTO", "POS_UST"),
            Triple("transactions_vat.csv", "BON_BRUTTO", "BON_UST"),
        ).forEach { (file, bruttoCol, ustCol) ->
            rowsOf(csvFiles, file).forEach { row ->
                val key = row.getValue("UST_SCHLUESSEL")
                val rate = ANLAGE_2_FIXED_RATES[key] ?: return@forEach
                val brutto = parseAmount(row.getValue(bruttoCol))
                val ust = parseAmount(row.getValue(ustCol))
                val expected =
                    brutto -
                        brutto.divide(
                            BigDecimal.ONE + rate.divide(BigDecimal(100)),
                            2,
                            java.math.RoundingMode.HALF_UP,
                        )
                assertTrue(
                    (expected - ust).abs() <= BigDecimal("0.01"),
                    "$file: UST_SCHLUESSEL $key is $rate% per DSFinV-K Anlage 2, so $bruttoCol " +
                        "$brutto decomposes to about $expected USt, but $ustCol is $ust (row $row)",
                )
            }
        }
    }

    /**
     * Anlage-2 conformance for the Stammdaten side: `vat.csv` must declare, for every key it
     * emits, exactly the rate Anlage 2 fixes for that key. Transcribed from the PDF; compared
     * against the export. No producer comparison.
     */
    private fun assertVatCsvDeclaresTheAnlage2Rates(csvFiles: Map<String, CsvFile>) {
        rowsOf(csvFiles, "vat.csv").forEach { row ->
            val key = row.getValue("UST_SCHLUESSEL")
            val expected = ANLAGE_2_FIXED_RATES[key] ?: return@forEach
            assertEquals(
                0,
                expected.compareTo(parseAmount(row.getValue("UST_SATZ"))),
                "vat.csv: UST_SCHLUESSEL $key must declare $expected% per DSFinV-K Anlage 2 " +
                    "(Stand 05.12.2024); got row $row",
            )
        }
    }

    // ============================================
    // Shared parsing helpers
    // ============================================

    /** A single `;`-delimited CSV field, with whether it was `"`-quoted in the source line. */
    private data class Field(
        val value: String,
        val quoted: Boolean,
    )

    private data class CsvFile(
        val name: String,
        val header: List<String>,
        val rows: List<List<Field>>,
    )

    private data class IdxCol(
        val name: String,
        val type: String,
        val size: String,
    )

    private data class IdxTable(
        val url: String,
        val name: String,
        val cols: List<IdxCol>,
    )

    /** Unzips a DSFinV-K export into a map of basename -> raw file bytes. */
    private fun unzip(zip: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                out[entry.name.substringAfterLast('/')] = zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return out
    }

    /** Quote-aware split of a `;`-delimited DSFinV-K CSV row. */
    private fun splitCsvLine(line: String): List<Field> {
        val fields = mutableListOf<Field>()
        val current = StringBuilder()
        var inQuotes = false
        var wasQuoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    wasQuoted = true
                }
                c == ';' && !inQuotes -> {
                    fields.add(Field(current.toString(), wasQuoted))
                    current.clear()
                    wasQuoted = false
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(Field(current.toString(), wasQuoted))
        return fields
    }

    private fun parseCsvFile(
        name: String,
        bytes: ByteArray,
    ): CsvFile {
        val lines = bytes.decodeToString().split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return CsvFile(name, emptyList(), emptyList())
        val header = splitCsvLine(lines.first()).map { it.value }
        val rows = lines.drop(1).map { splitCsvLine(it) }
        return CsvFile(name, header, rows)
    }

    private fun rowMap(
        header: List<String>,
        row: List<Field>,
    ): Map<String, String> = header.indices.associate { i -> header[i] to (row.getOrNull(i)?.value ?: "") }

    /** Parses a comma-decimal DSFinV-K numeric field into a BigDecimal. */
    private fun parseAmount(value: String): BigDecimal = BigDecimal(value.replace(",", "."))

    /** Parses index.xml (or the golden reference) into its ordered Table/VariableColumn schema. */
    private fun parseIndexTables(xml: ByteArray): List<IdxTable> {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val tables = doc.getElementsByTagName("Table")
        return (0 until tables.length).map { i ->
            val t = tables.item(i) as Element
            val cols = t.getElementsByTagName("VariableColumn")
            IdxTable(
                url = t.getElementsByTagName("URL").item(0).textContent,
                name = t.getElementsByTagName("Name").item(0).textContent,
                cols =
                    (0 until cols.length).map { j ->
                        val c = cols.item(j) as Element
                        val colName = c.getElementsByTagName("Name").item(0).textContent
                        val numeric = c.getElementsByTagName("Numeric").length > 0
                        val acc = c.getElementsByTagName("Accuracy").let { if (it.length > 0) it.item(0).textContent else "" }
                        val len = c.getElementsByTagName("MaxLength").let { if (it.length > 0) it.item(0).textContent else "" }
                        IdxCol(colName, if (numeric) "Numeric" else "AlphaNumeric", if (numeric) acc else len)
                    },
            )
        }
    }

    /** Reads the `const val String` members of a DSFinV-K coded-field constants object via reflection. */
    private fun constStringValues(clazz: Class<*>): Set<String> =
        clazz.declaredFields
            .filter { it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as String }
            .toSet()

    private fun checkVatTriple(
        csvFiles: Map<String, CsvFile>,
        fileName: String,
        bruttoCol: String,
        nettoCol: String,
        ustCol: String,
    ) {
        val csv = csvFiles[fileName] ?: return
        csv.rows.forEach { row ->
            val map = rowMap(csv.header, row)
            val brutto = parseAmount(map.getValue(bruttoCol))
            val netto = parseAmount(map.getValue(nettoCol))
            val ust = parseAmount(map.getValue(ustCol))
            assertEquals(
                0,
                brutto.compareTo(netto + ust),
                "$fileName: $bruttoCol ($brutto) must equal $nettoCol + $ustCol (${netto + ust}) for row $map",
            )
        }
    }

    private companion object {
        /**
         * UST_SCHLUESSEL → rate, transcribed from DSFinV-K Anlage 2 „USt-Schlüssel"
         * (`20241205_DSFinV_K_Anlage2_UStSchluessel.pdf`, Stand 05.12.2024).
         *
         * Deliberately a literal transcription of the SPEC rather than a reference to
         * `DsfinvkExportDto.VatKeyMapping` — the whole point of the round-5 additions is that at
         * least one side of each new invariant must not share an origin with the code under test.
         *
         * **Round-7 C10 — this map now holds EXACTLY the keys Anlage 2 fixes a rate for.** Anlage 2
         * leaves the „USt-Satz" column BLANK for IDs 1, 2, 3, 4 and 8 — those denote „zum Zeitpunkt
         * der Erfassung des Geschäftsvorfalls geltender" rate (the current statutory §12 Abs. 1 /
         * §12 Abs. 2 / §24 Abs. 1 Nr. 3 / §24 Abs. 1 Nr. 1 / §12 Abs. 3 rate), so there is no spec
         * constant to assert for them and 19,00/7,00/5,50 would be an accidental match with today's
         * live rate, not a transcription. The prior map asserted 1→19,00 / 2→7,00 / 4→5,50 (three
         * keys the spec leaves unfixed) and omitted the fixed historical keys 11/12/33/43 — so it
         * both over-asserted and under-covered. Corrected against the Anlage-2 table verbatim:
         *  - 5, 6, 7  = 0,00 % („Nicht Steuerbar" / „Umsatzsteuerfrei" / „UmsatzsteuerNichtErmittelbar")
         *  - 11 = 19,00 %, 12 = 7,00 %  (historischer allgemeiner / ermäßigter Steuersatz)
         *  - 13 = 10,70 %, 23 = 9,50 %, 33 = 9,00 %, 43 = 8,40 %  (historischer § 24 Abs. 1 Nr. 3)
         *  - 21 = 16,00 %, 22 = 5,00 %  (historische Corona-Sätze 07–12/2020)
         */
        private val ANLAGE_2_FIXED_RATES: Map<String, BigDecimal> =
            mapOf(
                "5" to BigDecimal("0.00"), // Nicht Steuerbar
                "6" to BigDecimal("0.00"), // Umsatzsteuerfrei
                "7" to BigDecimal("0.00"), // UmsatzsteuerNichtErmittelbar
                "11" to BigDecimal("19.00"), // historisch: allgemeiner Steuersatz § 12 Abs. 1 (ab 01.07.2020)
                "12" to BigDecimal("7.00"), // historisch: ermäßigter Steuersatz § 12 Abs. 2 (ab 01.07.2020)
                "13" to BigDecimal("10.70"), // historisch: Durchschnittssatz § 24 Abs. 1 Nr. 3 (ab 01.01.2022)
                "21" to BigDecimal("16.00"), // historisch: allgemeiner Steuersatz 07–12/2020
                "22" to BigDecimal("5.00"), // historisch: ermäßigter Steuersatz 07–12/2020
                "23" to BigDecimal("9.50"), // historisch: Durchschnittssatz § 24 Abs. 1 Nr. 3 (ab 01.01.2023)
                "33" to BigDecimal("9.00"), // historisch: Durchschnittssatz § 24 Abs. 1 Nr. 3 (ab 06.12.2024)
                "43" to BigDecimal("8.40"), // historisch: Durchschnittssatz § 24 Abs. 1 Nr. 3 (ab 01.01.2025)
            )
    }
}
