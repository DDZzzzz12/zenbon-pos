package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchLineItemTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two fiscal look-alikes — **Kassenbelege** and **Kassenbuch** — run end to end through the CSV
 * writer.
 *
 * Neither is a compliance export and neither is shaped like one; what these tests hold is the part a
 * catalogue assertion cannot reach: the produced bytes. Exact lines, exact row counts, money at scale
 * 2 with a decimal comma, and the two refusals these subjects are the first in the catalogue able to
 * provoke — the row cap, and two relation paths reaching one table by different foreign keys.
 */
@DisplayName("Kassenbelege and Kassenbuch, end to end")
class FiscalSubjectRunTest {
    private val service = CustomExportServiceImpl()

    private val billHeld = setOf(Capability.BILL_READ)
    private val cashBookHeld = setOf(Capability.CASHBOOK_READ)

    /**
     * A bill list with the money columns an operator actually asks for.
     *
     * Three of the four data columns are MONEY over `decimal(19,4)`, so this is where the renderer's
     * contract shows: `23.8000` must come out `23,80`, and `0.0000` must come out `0,00` rather than
     * blank — a bill with no tip is a bill that was tipped nothing, which the file has to be able to
     * say.
     */
    @Test
    fun `a Kassenbelege export writes one row per bill with its totals`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val till = till()
                bill(till, number = "B-1", gross = "23.8000", paid = "25.0000", tips = "1.2000")
                bill(till, number = "B-2", gross = "4.5000", paid = "4.5000", tips = "0.0000")

                val saved = service.save(billRequest(), billHeld, "admin")
                val result = service.run(saved.id, billHeld)
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount, "one row per bill")
                assertEquals(3, lines.size, "heading plus two data rows, counted in the produced file")
                assertEquals("Beleg;Datum;Brutto;Gezahlt;Trinkgeld", lines.first())
                assertEquals(
                    setOf(
                        "B-1;2026-03-01;23,80;25,00;1,20",
                        "B-2;2026-03-01;4,50;4,50;0,00",
                    ),
                    lines.drop(1).toSet(),
                    "every amount at scale 2 with a decimal comma, and a tip of nothing reads 0,00 " +
                        "rather than as an empty cell",
                )
            }
        }
    }

    /**
     * The VAT split of a bill is to-many — one row per rate — so it arrives aggregated and the bill
     * stays **one** row.
     *
     * The bill below carries two rates. Joined, it would come out twice with its Bruttobetrag repeated
     * and any total over the column doubled; aggregated, it comes out once with a `2` and the summed
     * tax. The bill without a split reads `0` and a blank, which is the same distinction the invoice
     * aggregates make: a count of nothing is zero, a sum of nothing is not `0,00`.
     */
    @Test
    fun `the VAT split is aggregated, so a two-rate bill stays one row`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val till = till()
                val vat = TestData.createDefaultVatRates().first().id
                val split = bill(till, number = "B-1", gross = "119.0000", paid = "119.0000", tips = "0.0000")
                bill(till, number = "B-2", gross = "10.0000", paid = "10.0000", tips = "0.0000")
                vatDetail(split, vat, net = "84.0336", tax = "15.9664")
                vatDetail(split, vat, net = "18.6916", tax = "0.3084")

                val saved = service.save(billVatRequest(), billHeld, "admin")
                val result = service.run(saved.id, billHeld)
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount, "two bills: the two VAT rows must not become a third row")
                assertEquals(
                    setOf("B-1;2;16,27", "B-2;0;"),
                    lines.drop(1).toSet(),
                    "the split is counted and summed per bill, and a bill without one reads 0 and a blank",
                )
            }
        }
    }

    /**
     * A training bill is not a sale, so a column of Bruttobeträge that silently included it would total
     * to a number nobody can reconcile. The toggle is what removes it — and the file with the toggle
     * off still shows both, flagged, because a shorter file must never be the default.
     */
    @Test
    fun `the Ohne Schulungsbelege toggle removes the training bills`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val till = till()
                bill(till, number = "B-1", gross = "10.0000", paid = "10.0000", tips = "0.0000")
                bill(till, number = "B-TRAIN", gross = "10.0000", paid = "10.0000", tips = "0.0000", training = true)

                val all = service.run(service.save(trainingRequest(enabled = false), billHeld, "admin").id, billHeld)
                val real = service.run(service.save(trainingRequest(enabled = true), billHeld, "admin").id, billHeld)

                assertEquals(
                    setOf("B-1;Nein", "B-TRAIN;Ja"),
                    all.bytes
                        .asCsvLines()
                        .drop(1)
                        .toSet(),
                    "without the toggle nothing is dropped, and the flag column says which is which",
                )
                assertEquals(
                    listOf("B-1;Nein"),
                    real.bytes.asCsvLines().drop(1),
                    "with it, only the real sale is left",
                )
            }
        }
    }

    /**
     * The row cap, proved with an injected `rowCap` rather than with a hundred thousand bills.
     *
     * `bill` is the largest table in this schema, so this is the subject where the production cap will
     * genuinely bite — and the only thing standing between an operator and a **silently short file** is
     * `RunResultDto.truncated`. Both directions are asserted in one test so neither half can rot: at a
     * cap of 2 over three bills the flag is set and exactly two rows are written; at a cap of 5 over the
     * same three it is clear and all three are. Raise the first cap above the seeded count and this test
     * goes red, which is what keeps it from passing by accident.
     */
    @Test
    fun `a run past the row cap reports truncated and writes exactly the cap`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val till = till()
                listOf("B-1", "B-2", "B-3").forEach {
                    bill(till, number = it, gross = "10.0000", paid = "10.0000", tips = "0.0000")
                }

                val capped = CustomExportServiceImpl(rowCap = 2)
                val roomy = CustomExportServiceImpl(rowCap = 5)
                val definition = capped.save(billRequest(), billHeld, "admin").id

                val short = capped.run(definition, billHeld)
                val whole = roomy.run(definition, billHeld)

                assertTrue(short.truncated, "three bills past a cap of two: the operator must be told")
                assertEquals(2, short.rowCount, "and exactly the cap is written, not the cap plus the probe row")
                assertEquals(3, short.bytes.asCsvLines().size, "heading plus two data rows in the file itself")
                assertFalse(whole.truncated, "the same definition under a cap of five is not truncated")
                assertEquals(3, whole.rowCount, "and carries every bill, which is what makes the flag above mean something")
            }
        }
    }

    /**
     * The cash book, with its book name reached over a to-one relation and its line items aggregated.
     *
     * The book name is the whole reason `cash_book` is registered: without it a shop running a Tresor
     * beside the Ladenkasse gets a file that merges two books and looks complete.
     */
    @Test
    fun `a Kassenbuch export names the book and aggregates the line items`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val clerk = TestData.createCashier(employeeNumber = "KB1", username = "kb1").id
                val laden = cashBook(shop, name = "Ladenkasse", fiscal = true)
                val tresor = cashBook(shop, name = "Tresor", fiscal = false)
                val einkauf = header(shop, laden, clerk, belegNr = "2026-03-0001", text = "Blumen", amount = "-25.0000")
                header(shop, tresor, clerk, belegNr = "2026-03-0002", text = "Einlage", amount = "100.0000")
                line(einkauf, no = 0, amount = "-20.00")
                line(einkauf, no = 1, amount = "-5.00")

                val saved = service.save(kassenbuchRequest(), cashBookHeld, "admin")
                val result = service.run(saved.id, cashBookHeld)
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount, "two entries: the two line items must not become a third row")
                assertEquals("Beleg;Text;Betrag;Kasse;Positionen;Positionssumme", lines.first())
                assertEquals(
                    setOf(
                        "2026-03-0001;Blumen;-25,00;Ladenkasse;2;-25,00",
                        "2026-03-0002;Einlage;100,00;Tresor;0;",
                    ),
                    lines.drop(1).toSet(),
                    "the book arrives by name, the outflow keeps its minus sign unguarded so the " +
                        "column still sums, and the entry without line items reads 0 and a blank",
                )
            }
        }
    }

    /**
     * The pairwise restriction that comes with registering `cash_book`, proved rather than described.
     *
     * `kassenbuch_header` holds two foreign keys into `cash_book` — the entry's own book and the
     * Geldtransit counterparty — so the picker offers **two blocks both headed "Kasse"**. One FROM
     * clause holds a table once, so joining both would keep one route and silently make the other
     * block's column read the surviving route's row: a wrong value in a cell, with nothing in the file
     * to say so. `requireOneRoutePerTable` refuses instead, and this subject is the first the catalogue
     * ships that can reach it.
     *
     * `assertFailsWith` takes no suspending block, and a JUnit 5 test that ends in one is silently
     * skipped — hence [failureOf].
     */
    @Test
    fun `selecting a column from both Kasse blocks is refused rather than answered wrongly`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val clerk = TestData.createCashier(employeeNumber = "KB2", username = "kb2").id
                val laden = cashBook(shop, name = "Ladenkasse", fiscal = true)
                header(shop, laden, clerk, belegNr = "2026-03-0001", text = "Blumen", amount = "-25.0000")

                val saved = service.save(bothCashBookRoutesRequest(), cashBookHeld, "admin")
                val thrown = failureOf { service.run(saved.id, cashBookHeld) }

                assertTrue(thrown is PosException, "expected a refusal naming the problem, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    "cash_book",
                    message = "and the refusal names the table one export cannot join twice",
                )
            }
        }
    }

    /** `assertFailsWith` takes no suspending block; a test ending in one is silently skipped. */
    private fun failureOf(block: suspend () -> Unit): Throwable? = runBlocking { runCatching { block() }.exceptionOrNull() }

    private fun billRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Tagesbelege",
            subjectKey = "bill",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("bill.bill_number", 0, "Beleg"),
                    CustomExportDto.DefinitionFieldDto("bill.end_time", 1, "Datum"),
                    CustomExportDto.DefinitionFieldDto("bill.bill_amount", 2, "Brutto"),
                    CustomExportDto.DefinitionFieldDto("bill.paid_amount", 3, "Gezahlt"),
                    CustomExportDto.DefinitionFieldDto("bill.tips", 4, "Trinkgeld"),
                ),
        )

    private fun billVatRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Belege mit Steuersaetzen",
            subjectKey = "bill",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("bill.bill_number", 0, "Beleg"),
                    CustomExportDto.DefinitionFieldDto("bill_vat_detail:by:bill_id~count", 1, "Steuersätze"),
                    CustomExportDto.DefinitionFieldDto("bill_vat_detail:by:bill_id~sum", 2, "Steuer"),
                ),
        )

    private fun trainingRequest(enabled: Boolean) =
        CustomExportDto.SaveDefinitionRequestDto(
            name = if (enabled) "Ohne Schulung" else "Alle Belege",
            subjectKey = "bill",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("bill.bill_number", 0, "Beleg"),
                    CustomExportDto.DefinitionFieldDto("bill.is_training", 1, "Schulung"),
                ),
            enabledToggles = if (enabled) listOf("bill.exclude_training") else emptyList(),
        )

    private fun kassenbuchRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Kassenbuch Maerz",
            subjectKey = "kassenbuch",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("kassenbuch.beleg_nr", 0, "Beleg"),
                    CustomExportDto.DefinitionFieldDto("kassenbuch.belegtext", 1, "Text"),
                    CustomExportDto.DefinitionFieldDto("kassenbuch.total_amount", 2, "Betrag"),
                    CustomExportDto.DefinitionFieldDto("cash_book:via:cash_book_id.name", 3, "Kasse"),
                    CustomExportDto.DefinitionFieldDto("kassenbuch_line_item:by:header_id~count", 4, "Positionen"),
                    CustomExportDto.DefinitionFieldDto("kassenbuch_line_item:by:header_id~sum", 5, "Positionssumme"),
                ),
        )

    private fun bothCashBookRoutesRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Beide Kassen",
            subjectKey = "kassenbuch",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("kassenbuch.beleg_nr", 0, "Beleg"),
                    CustomExportDto.DefinitionFieldDto("cash_book:via:cash_book_id.name", 1, "Kasse"),
                    CustomExportDto.DefinitionFieldDto("cash_book:via:counterparty_book_id.name", 2, "Gegenkasse"),
                ),
        )

    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    /**
     * The employee and the table session a bill needs, both NOT NULL on `bill`.
     *
     * The prerequisites come from the fixtures; the exported rows themselves are inserted through the
     * table below, so their timestamps are this test's rather than a `clientDefault`'s.
     */
    private fun till(): Till {
        val session = TestData.createTableWithSession(tableNumber = "EXP1").session
        return transaction { Till(employee = session.employeeId.id, session = session.id) }
    }

    private data class Till(
        val employee: EntityID<UUID>,
        val session: EntityID<Long>,
    )

    private fun bill(
        till: Till,
        number: String,
        gross: String,
        paid: String,
        tips: String,
        training: Boolean = false,
    ): EntityID<Long> =
        transaction {
            BillTable
                .insert {
                    it[BillTable.employeeId] = till.employee
                    it[BillTable.tableSession] = till.session
                    it[BillTable.billNumber] = number
                    it[BillTable.billAmount] = BigDecimal(gross)
                    it[BillTable.paidAmount] = BigDecimal(paid)
                    it[BillTable.tips] = BigDecimal(tips)
                    it[BillTable.startTime] = STAMP
                    it[BillTable.endTime] = STAMP
                    it[BillTable.isTraining] = training
                }[BillTable.id]
        }

    private fun vatDetail(
        bill: EntityID<Long>,
        vat: EntityID<String>,
        net: String,
        tax: String,
    ) {
        transaction {
            BillVatDetailTable.insert {
                it[BillVatDetailTable.billId] = bill
                it[BillVatDetailTable.vatId] = vat
                it[BillVatDetailTable.netAmount] = BigDecimal(net)
                it[BillVatDetailTable.vatAmount] = BigDecimal(tax)
                it[BillVatDetailTable.totalAmount] = BigDecimal(net).add(BigDecimal(tax))
            }
        }
    }

    private fun cashBook(
        shop: ShopInfo,
        name: String,
        fiscal: Boolean,
    ): EntityID<Long> =
        transaction {
            CashBookTable
                .insert {
                    it[CashBookTable.shopId] = shop.id
                    it[CashBookTable.name] = name
                    it[CashBookTable.isFiscal] = fiscal
                    it[CashBookTable.datevAccount] = if (fiscal) "1000" else "1010"
                    it[CashBookTable.createdAt] = STAMP
                }[CashBookTable.id]
        }

    private fun header(
        shop: ShopInfo,
        book: EntityID<Long>,
        employee: EntityID<UUID>,
        belegNr: String,
        text: String,
        amount: String,
    ): EntityID<Long> =
        transaction {
            KassenbuchHeaderTable
                .insert {
                    it[KassenbuchHeaderTable.shopId] = shop.id
                    it[KassenbuchHeaderTable.cashBookId] = book
                    it[KassenbuchHeaderTable.employeeId] = employee
                    it[KassenbuchHeaderTable.entryType] = KassenbuchEntryType.CASH_EXPENSE
                    it[KassenbuchHeaderTable.belegNr] = belegNr
                    it[KassenbuchHeaderTable.belegDatum] = "01.03.2026"
                    it[KassenbuchHeaderTable.belegtext] = text
                    it[KassenbuchHeaderTable.totalAmount] = BigDecimal(amount)
                    it[KassenbuchHeaderTable.currentBalance] = BigDecimal(amount)
                    it[KassenbuchHeaderTable.createdAt] = STAMP
                }[KassenbuchHeaderTable.id]
        }

    private fun line(
        header: EntityID<Long>,
        no: Int,
        amount: String,
    ) {
        transaction {
            KassenbuchLineItemTable.insert {
                it[KassenbuchLineItemTable.headerId] = header
                it[KassenbuchLineItemTable.lineNumber] = no
                it[KassenbuchLineItemTable.vorzBetrag] = BigDecimal(amount)
                it[KassenbuchLineItemTable.subBalance] = BigDecimal(amount)
            }
        }
    }

    private companion object {
        /** One fixed instant for every seeded row, so a date cell is an assertion rather than a guess. */
        val STAMP: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00")
    }
}
