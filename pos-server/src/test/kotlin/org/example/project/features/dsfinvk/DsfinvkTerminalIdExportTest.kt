package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.kassenbuch.domain.models.KassenbuchLineItem
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.zBon.domain.KassenStammdatenSnapshot
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TERMINAL_ID (`transactions.csv`) and TERMINAL_SERIENNR (`slaves.csv`) end to end, one case per
 * Vorgang kind — DSFinV-K 2.4 Anhang E p. 85: „Die TERMINAL_ID darf in der Datei Bonkopf.csv nur
 * angegeben werden, wenn die zugehörige TERMINAL_SERIENNR in diesem abzusichernden Vorgang als
 * ClientID an die TSE übergeben wurde."
 *
 * Every till signs under its own TSE client named after its terminal code. A Vorgang names its
 * terminal exactly when a real (NORMAL-mode, finished, signed) TSE transaction carries that code as
 * its ClientID and the code is a terminal of the same closing.
 */
@DisplayName("DSFinV-K TERMINAL_ID / TERMINAL_SERIENNR from the signing TSE client")
class DsfinvkTerminalIdExportTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private class Fixture(
        val admin: Employee,
        val shop: ShopInfo,
        val zBon: ZBon,
        val session: TableSession,
    )

    private fun fixture(): Fixture {
        val admin = TestData.createAdmin()
        val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
        val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
        val session = TableSession.findById(TestData.createTableWithSession("TID1").session.id)!!
        return Fixture(admin, shop, zBon, session)
    }

    private fun terminal(
        code: String,
        hardwareSerial: String,
        role: TerminalRole = TerminalRole.CASHIER,
    ): Terminal {
        val now = currentTimeStamp()
        return Terminal.new {
            deviceId = "device-$code"
            clientId = code
            terminalSerialNr = hardwareSerial
            this.role = role.name
            tseClientRegistered = true
            registeredAt = now
            lastSeenAt = now
        }
    }

    private var nextTanr = 100L

    private fun tse(
        clientId: String,
        mode: TseMode = TseMode.NORMAL,
    ): TseTransaction {
        val tanr = nextTanr++
        val now = currentTimeStamp()
        return TseTransaction.new {
            this.tseSerialNumber = "A1B2C3D4".repeat(8)
            this.transactionNumber = tanr
            this.signatureCounter = tanr
            this.signature = "sig-$tanr"
            this.signatureAlgorithm = "ecdsa-plain-SHA384"
            this.publicKey = "pubkey"
            this.processType = "Kassenbeleg-V1"
            this.processDataHash = "pd-$tanr"
            this.clientId = clientId
            this.startTime = now
            this.endTime = now
            this.tseTimestamp = 1_759_000_000L + tanr
            this.tseMode = mode
        }
    }

    private fun bill(
        f: Fixture,
        number: String,
        recordedTerminal: Terminal?,
        signedBy: TseTransaction?,
        training: Boolean = false,
    ): String {
        val now = currentTimeStamp()
        val id =
            Bill
                .new {
                    this.employeeId = f.admin
                    this.tableSession = f.session
                    this.billNumber = number
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = f.zBon
                    this.terminal = recordedTerminal
                    this.isTraining = training
                    this.tseTransaction = signedBy
                    this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
                }.id.value
        return "B-$id"
    }

    @Test
    fun `every Vorgang kind is attributed only to the terminal whose ClientID signed it`() {
        val bonIds = mutableMapOf<String, String>()
        transaction {
            val f = fixture()
            val till1 = terminal("TER-001", "HW-001")
            val till2 = terminal("TER-002", "HW-002")
            terminal("TER-003", "HW-003") // registered, but signs nothing in this closing
            val station = terminal("TER-009", "HW-009", TerminalRole.ORDER_STATION)

            bonIds["signed"] = bill(f, "TID-SIGNED", till1, tse("TER-001"))
            bonIds["training"] = bill(f, "TID-TRAINING", till2, tse("TER-002"), training = true)
            bonIds["simulation"] = bill(f, "TID-SIM", till1, tse("TER-001", TseMode.SIMULATION))
            bonIds["unsigned"] = bill(f, "TID-FAILED", till1, null)
            // Nachsigniert: signed later, under TER-002 although the bill records TER-001 —
            // the signature is the authority.
            bonIds["lateSignedOtherClient"] = bill(f, "TID-LATE", till1, tse("TER-002"))
            bonIds["maintenanceClient"] = bill(f, "TID-MAINT", till1, tse("CLIENT-FOR-MAINTENANCE"))

            val now = currentTimeStamp()
            val order =
                Orders.new {
                    this.orderNumber = "TID-ORD-1"
                    this.employee = f.admin
                    this.tableSession = f.session
                    this.orderStatus = OrderStatus.CONFIRMED
                    this.orderTime = now
                    this.orderTotal = BigDecimal.ZERO
                    this.zBon = f.zBon
                    this.terminal = station
                    this.tseTransaction = tse("TER-009")
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            bonIds["stationOrder"] = "O-${order.id.value}"

            val vat19 = TestData.createDefaultVatRates()[0]
            val product = TestData.createProduct(TestData.createCategory("TID Cat").id.value, "Cola", "TID-COLA")
            val cancelledItem =
                OrderItem.new {
                    this.session = f.session
                    this.product = product
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.CANCELLED
                    this.takeAway = false
                }
            val cancellationTx = tse("TER-002")
            Cancellation.new {
                this.orderItem = cancelledItem
                this.reason = "TID storno"
                this.employeeId = f.admin
                this.createdAt = now
                this.zBon = f.zBon
                this.tseTransaction = cancellationTx
                this.tseSigningStatus = TseSigningStatus.SIGNED
            }
            bonIds["cancellation"] = "C-tx${cancellationTx.id.value}"

            fun kassenbuch(
                belegNr: String,
                signedBy: TseTransaction?,
            ): String {
                val entry =
                    KassenbuchHeader.new {
                        this.shop = f.shop
                        this.cashBook = CashBookRepositoryImpl().fiscalBookFor(f.shop)
                        this.employee = f.admin
                        this.entryType = KassenbuchEntryType.CASH_TRANSFER_IN
                        this.belegNr = belegNr
                        this.belegDatum = "07.10.2026"
                        this.belegtext = "Wechselgeld"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("50.00")
                        this.currentBalance = BigDecimal("50.00")
                        this.zBon = f.zBon
                        this.createdAt = now
                        this.tseTransaction = signedBy
                        this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
                    }
                return "KB-${entry.id.value}"
            }
            bonIds["kassenbuchSigned"] = kassenbuch("TID-KB-1", tse("TER-001"))
            bonIds["kassenbuchUnsigned"] = kassenbuch("TID-KB-2", null)
        }

        val csv = export()
        val terminalIdByBon = csv.getValue("transactions.csv").associate { it["BON_ID"] to it["TERMINAL_ID"].orEmpty() }

        val expected =
            mapOf(
                "signed" to "TER-001",
                "training" to "TER-002",
                "simulation" to "",
                "unsigned" to "",
                "lateSignedOtherClient" to "TER-002",
                "maintenanceClient" to "",
                "stationOrder" to "TER-009",
                "cancellation" to "TER-002",
                "kassenbuchSigned" to "TER-001",
                "kassenbuchUnsigned" to "",
            )
        assertEquals(expected, expected.mapValues { (case, _) -> terminalIdByBon.getValue(bonIds.getValue(case)) })

        val serialByTerminal = csv.getValue("slaves.csv").associate { it.getValue("TERMINAL_ID") to it.getValue("TERMINAL_SERIENNR") }
        assertEquals(
            mapOf("TER-001" to "TER-001", "TER-002" to "TER-002", "TER-003" to "HW-003", "TER-009" to "TER-009"),
            serialByTerminal,
            "a terminal's serial is its ClientID exactly when the closing proves it signed under it",
        )
    }

    @Test
    fun `a snapshotted closing is attributed against its own terminal set, not the live table`() {
        var signedBon = ""
        var lateTerminalBon = ""
        transaction {
            val f = fixture()
            val till1 = terminal("TER-001", "HW-001")
            KassenStammdatenSnapshot.applyTo(f.zBon, f.shop.id.value)
            // After the closing: the live row is re-serialed and a new till appears.
            till1.terminalSerialNr = "HW-001-REPLACED"
            val lateTill = terminal("TER-004", "HW-004")

            signedBon = bill(f, "TID-SNAP-1", till1, tse("TER-001"))
            lateTerminalBon = bill(f, "TID-SNAP-2", lateTill, tse("TER-004"))
        }

        val csv = export()
        val terminalIdByBon = csv.getValue("transactions.csv").associate { it["BON_ID"] to it["TERMINAL_ID"].orEmpty() }
        assertEquals("TER-001", terminalIdByBon.getValue(signedBon))
        assertEquals("", terminalIdByBon.getValue(lateTerminalBon), "TER-004 is no terminal of this closing's snapshot")
        assertEquals(
            listOf("TER-001" to "TER-001"),
            csv.getValue("slaves.csv").map { it.getValue("TERMINAL_ID") to it.getValue("TERMINAL_SERIENNR") },
            "only the snapshotted terminal is listed, and its proven serial is its ClientID",
        )
    }

    /**
     * POS_TERMINAL_ID (`lines.csv`) — Anhang E p. 93: „ID der Slave-Kasse (Terminal), an der die
     * Position (Line) erfasst wird." It is the slaves.csv TERMINAL_ID (the code), never the terminals
     * primary key, and it carries no ClientID condition: an unsigned order's lines still name their
     * till. A till that is no terminal of the closing's snapshot is left empty.
     */
    @Test
    fun `order lines name the terminal code of a slaves row of their closing, never the database id`() {
        val bonIds = mutableMapOf<String, String>()
        transaction {
            val f = fixture()
            val station = terminal("TER-009", "HW-009", TerminalRole.ORDER_STATION)
            KassenStammdatenSnapshot.applyTo(f.zBon, f.shop.id.value)
            val lateTill = terminal("TER-004", "HW-004")
            val vat19 = TestData.createDefaultVatRates()[0]
            val product = TestData.createProduct(TestData.createCategory("PTID Cat").id.value, "Cola", "PTID-COLA")

            fun order(
                number: String,
                till: Terminal,
                signedBy: TseTransaction?,
            ): String {
                val order =
                    Orders.new {
                        this.orderNumber = number
                        this.employee = f.admin
                        this.tableSession = f.session
                        this.orderStatus = OrderStatus.CONFIRMED
                        this.orderTime = currentTimeStamp()
                        this.orderTotal = BigDecimal("3.00")
                        this.zBon = f.zBon
                        this.terminal = till
                        this.tseTransaction = signedBy
                        this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
                    }
                OrderItem.new {
                    this.session = f.session
                    this.order = order
                    this.product = product
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.OPEN
                    this.takeAway = false
                }
                return "O-${order.id.value}"
            }
            bonIds["signed"] = order("PTID-1", station, tse("TER-009"))
            bonIds["unsigned"] = order("PTID-2", station, null)
            bonIds["notInClosing"] = order("PTID-3", lateTill, tse("TER-004"))
            bonIds["stationPk"] = station.id.value.toString()
        }

        val posTerminalIdByBon =
            export().getValue("lines.csv").associate { it.getValue("BON_ID") to it["POS_TERMINAL_ID"].orEmpty() }
        assertEquals(
            mapOf("signed" to "TER-009", "unsigned" to "TER-009", "notInClosing" to ""),
            listOf("signed", "unsigned", "notInClosing").associateWith { posTerminalIdByBon.getValue(bonIds.getValue(it)) },
        )
        assertTrue(bonIds.getValue("stationPk") !in posTerminalIdByBon.values, "the terminals primary key is never exported")
    }

    /**
     * POS_TERMINAL_ID on bill, storno, cancellation and Kassenbuch lines (Anhang E p. 93). An item
     * line names the terminal the item was entered on — the order's terminal, so the bill line equals
     * its order line, and so does the item-discount Rabatt line (the discount is entered with the
     * item); a GV line applied at settlement (here the Trinkgeld) names the terminal the Bon was
     * settled on, i.e. the Bon's own TERMINAL_ID, and is empty exactly when that is.
     */
    @Test
    fun `bill item lines name the order terminal, GV and Kassenbuch lines the Bon's TERMINAL_ID`() {
        val ids = mutableMapOf<String, String>()
        transaction {
            val f = fixture()
            val till1 = terminal("TER-001", "HW-001")
            val till2 = terminal("TER-002", "HW-002")
            val station = terminal("TER-009", "HW-009", TerminalRole.ORDER_STATION)
            val vat19 = TestData.createDefaultVatRates()[0]
            val product = TestData.createProduct(TestData.createCategory("PTID-B Cat").id.value, "Cola", "PTID-B-COLA")
            val now = currentTimeStamp()
            val order =
                Orders.new {
                    this.orderNumber = "PTID-B-1"
                    this.employee = f.admin
                    this.tableSession = f.session
                    this.orderStatus = OrderStatus.CONFIRMED
                    this.orderTime = now
                    this.orderTotal = BigDecimal("9.00")
                    this.zBon = f.zBon
                    this.terminal = station
                    this.tseTransaction = tse("TER-009")
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            ids["order"] = "O-${order.id.value}"

            fun billOn(
                number: String,
                signedBy: TseTransaction?,
                amount: String,
                reverses: Bill? = null,
            ) = Bill.new {
                this.employeeId = f.admin
                this.tableSession = f.session
                this.billNumber = number
                this.billAmount = BigDecimal(amount)
                this.paidAmount = BigDecimal(amount)
                this.tips = if (amount.startsWith("-")) BigDecimal("-1.00") else BigDecimal("1.00")
                this.tipRecipient = TipRecipient.EMPLOYEE
                this.startTime = now
                this.endTime = now
                this.zBon = f.zBon
                this.terminal = till1
                this.deletedBill = reverses
                this.tseTransaction = signedBy
                this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
            }

            fun item(
                bill: Bill?,
                status: ItemStatus,
            ) = OrderItem.new {
                this.session = f.session
                this.order = order
                this.bill = bill
                this.product = product
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("9.00")
                this.discount = BigDecimal("0.90")
                this.vat = vat19
                this.itemStatus = status
                this.takeAway = false
            }

            val signedBill = billOn("PTID-B-SIGNED", tse("TER-001"), "9.00")
            item(signedBill, ItemStatus.PAID)
            ids["signedBill"] = "B-${signedBill.id.value}"
            val storno = billOn("PTID-B-STORNO", tse("TER-002"), "-9.00", reverses = signedBill)
            storno.terminal = till2
            ids["storno"] = "B-${storno.id.value}"
            val unsignedBill = billOn("PTID-B-FAILED", null, "9.00")
            item(unsignedBill, ItemStatus.PAID)
            ids["unsignedBill"] = "B-${unsignedBill.id.value}"

            val cancelledTx = tse("TER-001")
            Cancellation.new {
                this.orderItem = item(null, ItemStatus.CANCELLED)
                this.reason = "PTID storno"
                this.employeeId = f.admin
                this.createdAt = now
                this.zBon = f.zBon
                this.tseTransaction = cancelledTx
                this.tseSigningStatus = TseSigningStatus.SIGNED
            }
            ids["cancellation"] = "C-tx${cancelledTx.id.value}"

            fun kassenbuch(
                belegNr: String,
                signedBy: TseTransaction?,
            ) = KassenbuchHeader
                .new {
                    this.shop = f.shop
                    this.cashBook = CashBookRepositoryImpl().fiscalBookFor(f.shop)
                    this.employee = f.admin
                    this.entryType = KassenbuchEntryType.CASH_TRANSFER_IN
                    this.belegNr = belegNr
                    this.belegDatum = "07.10.2026"
                    this.belegtext = "Wechselgeld"
                    this.waehrung = "EUR"
                    this.totalAmount = BigDecimal("50.00")
                    this.currentBalance = BigDecimal("50.00")
                    this.zBon = f.zBon
                    this.createdAt = now
                    this.tseTransaction = signedBy
                    this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
                }.also { entry ->
                    KassenbuchLineItem.new {
                        this.header = entry
                        this.lineNumber = 0
                        this.vorzBetrag = BigDecimal("50.00")
                        this.ustSatz = "0"
                        this.belegtext = "Wechselgeld"
                        this.kostmenge = null
                        this.skonto = null
                        this.subBalance = BigDecimal("50.0000")
                    }
                }
            ids["kbSigned"] = "KB-${kassenbuch("PTID-KB-1", tse("TER-002")).id.value}"
            ids["kbUnsigned"] = "KB-${kassenbuch("PTID-KB-2", null).id.value}"
        }

        val csv = export()
        val terminalIdByBon = csv.getValue("transactions.csv").associate { it.getValue("BON_ID") to it["TERMINAL_ID"].orEmpty() }
        val lines = csv.getValue("lines.csv")

        fun terminalsOf(
            case: String,
            gvTyp: String? = null,
        ) = lines
            .filter { it["BON_ID"] == ids.getValue(case) && (gvTyp == null || it["GV_TYP"] == gvTyp) }
            .map { it["POS_TERMINAL_ID"].orEmpty() }
            .toSet()

        val rabatt = DsfinvkExportDto.BusinessCaseTypes.RABATT
        val umsatz = DsfinvkExportDto.BusinessCaseTypes.UMSATZ
        val trinkgeld = DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AN
        assertEquals(setOf("TER-009"), terminalsOf("order"))
        assertEquals(terminalsOf("order"), terminalsOf("signedBill", umsatz), "a bill item line names the same terminal as its order line")
        assertEquals(setOf("TER-009"), terminalsOf("storno", umsatz), "the storno's item lines keep the order terminal")
        assertEquals(setOf("TER-009"), terminalsOf("unsignedBill", umsatz), "an item line carries no ClientID condition")
        assertEquals(setOf("TER-009"), terminalsOf("cancellation"))
        listOf("signedBill", "storno", "unsignedBill").forEach { case ->
            val gvTypes = lines.filter { it["BON_ID"] == ids.getValue(case) }.mapNotNull { it["GV_TYP"] }.toSet()
            assertTrue(rabatt in gvTypes && umsatz in gvTypes, "$case carries an item line and its Rabatt line")
            assertEquals(terminalsOf(case, umsatz), terminalsOf(case, rabatt), "$case: the item-discount Rabatt line names its item line's terminal")
        }
        assertEquals(
            mapOf("signedBill" to setOf("TER-001"), "storno" to setOf("TER-002"), "unsignedBill" to setOf("")),
            listOf("signedBill", "storno", "unsignedBill").associateWith { terminalsOf(it, trinkgeld) },
            "a GV line applied at settlement names the terminal its Bon was settled on",
        )
        listOf("signedBill", "storno", "unsignedBill").forEach { case ->
            assertEquals(setOf(terminalIdByBon.getValue(ids.getValue(case))), terminalsOf(case, trinkgeld), "$case: GV line == Bon TERMINAL_ID")
        }
        assertEquals(setOf("TER-002"), terminalsOf("kbSigned"))
        assertEquals(setOf(""), terminalsOf("kbUnsigned"))
    }

    /**
     * Historical orders carry no `terminal_id` (restored backup rt18: 8742 of 8879 lines empty), yet
     * each was signed under its till's own TSE client. The item lines therefore take the order's
     * signing client — on the order line and on the bill line alike — and still only when it is a
     * slaves.csv terminal of the closing. A recorded terminal that disagrees with the signature
     * loses to it.
     */
    @Test
    fun `item lines of an order without a recorded terminal name the client that signed the order`() {
        val ids = mutableMapOf<String, String>()
        transaction {
            val f = fixture()
            val till1 = terminal("TER-001", "HW-001")
            terminal("TER-005", "HW-005")
            val vat19 = TestData.createDefaultVatRates()[0]
            val product = TestData.createProduct(TestData.createCategory("FB Cat").id.value, "Cola", "FB-COLA")
            val now = currentTimeStamp()

            fun orderWithBilledItem(
                case: String,
                recorded: Terminal?,
                signedBy: TseTransaction?,
            ) {
                val order =
                    Orders.new {
                        this.orderNumber = "FB-$case"
                        this.employee = f.admin
                        this.tableSession = f.session
                        this.orderStatus = OrderStatus.CONFIRMED
                        this.orderTime = now
                        this.orderTotal = BigDecimal("3.00")
                        this.zBon = f.zBon
                        this.terminal = recorded
                        this.tseTransaction = signedBy
                        this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
                    }
                val bill =
                    Bill.new {
                        this.employeeId = f.admin
                        this.tableSession = f.session
                        this.billNumber = "FB-B-$case"
                        this.billAmount = BigDecimal("3.00")
                        this.paidAmount = BigDecimal("3.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = f.zBon
                        this.tseSigningStatus = TseSigningStatus.FAILED
                    }
                OrderItem.new {
                    this.session = f.session
                    this.order = order
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
                ids["$case-order"] = "O-${order.id.value}"
                ids["$case-bill"] = "B-${bill.id.value}"
            }
            orderWithBilledItem("signedNoTerminal", null, tse("TER-005"))
            orderWithBilledItem("unsignedNoTerminal", null, null)
            orderWithBilledItem("foreignClient", null, tse("CLIENT-FOR-MAINTENANCE"))
            orderWithBilledItem("disagreement", till1, tse("TER-005"))
            orderWithBilledItem("simulation", null, tse("TER-005", TseMode.SIMULATION))
        }

        val lines = export().getValue("lines.csv")
        val terminalByBon = lines.groupBy({ it.getValue("BON_ID") }, { it["POS_TERMINAL_ID"].orEmpty() }).mapValues { (_, v) -> v.toSet() }
        val expected =
            mapOf(
                "signedNoTerminal" to "TER-005",
                "unsignedNoTerminal" to "",
                "foreignClient" to "",
                "disagreement" to "TER-005",
                "simulation" to "",
            )
        expected.forEach { (case, terminal) ->
            assertEquals(setOf(terminal), terminalByBon.getValue(ids.getValue("$case-order")), "$case: order line")
            assertEquals(setOf(terminal), terminalByBon.getValue(ids.getValue("$case-bill")), "$case: bill item line equals its order line")
        }
    }

    /**
     * An item billed without any order (`orders_id` NULL — 67 lines on 12 bills and one cancellation
     * in backup rt18) was entered at the till that settled it, so its item and item-discount lines
     * take their own Bon's TERMINAL_ID: the bill's or the cancellation's signing client. An item that
     * HAS an order never falls back to the Bon, even when its order is unattributable.
     */
    @Test
    fun `an orderless item takes its own Bon's signing client, an ordered item never does`() {
        val ids = mutableMapOf<String, String>()
        transaction {
            val f = fixture()
            terminal("TER-005", "HW-005")
            val vat19 = TestData.createDefaultVatRates()[0]
            val product = TestData.createProduct(TestData.createCategory("OL Cat").id.value, "Cola", "OL-COLA")
            val now = currentTimeStamp()

            fun item(
                order: Orders?,
                bill: Bill?,
                status: ItemStatus,
            ) = OrderItem.new {
                this.session = f.session
                this.order = order
                this.bill = bill
                this.product = product
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("9.00")
                this.discount = BigDecimal("0.90")
                this.vat = vat19
                this.itemStatus = status
                this.takeAway = false
            }

            fun billWith(
                case: String,
                signedBy: TseTransaction?,
                withOrder: Boolean = false,
            ) {
                val bill =
                    Bill.new {
                        this.employeeId = f.admin
                        this.tableSession = f.session
                        this.billNumber = "OL-$case"
                        this.billAmount = BigDecimal("9.00")
                        this.paidAmount = BigDecimal("9.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = f.zBon
                        this.tseTransaction = signedBy
                        this.tseSigningStatus = if (signedBy == null) TseSigningStatus.FAILED else TseSigningStatus.SIGNED
                    }
                val order =
                    if (!withOrder) {
                        null
                    } else {
                        Orders.new {
                            this.orderNumber = "OL-O-$case"
                            this.employee = f.admin
                            this.tableSession = f.session
                            this.orderStatus = OrderStatus.CONFIRMED
                            this.orderTime = now
                            this.orderTotal = BigDecimal("9.00")
                            this.zBon = f.zBon
                            this.tseSigningStatus = TseSigningStatus.FAILED
                        }
                    }
                item(order, bill, ItemStatus.PAID)
                ids[case] = "B-${bill.id.value}"
            }
            billWith("signedOrderless", tse("TER-005"))
            billWith("unsignedOrderless", null)
            billWith("orderedUnattributable", tse("TER-005"), withOrder = true)

            val cancellationTx = tse("TER-005")
            Cancellation.new {
                this.orderItem = item(null, null, ItemStatus.CANCELLED)
                this.reason = "OL storno"
                this.employeeId = f.admin
                this.createdAt = now
                this.zBon = f.zBon
                this.tseTransaction = cancellationTx
                this.tseSigningStatus = TseSigningStatus.SIGNED
            }
            ids["cancellation"] = "C-tx${cancellationTx.id.value}"
        }

        val lines = export().getValue("lines.csv")

        fun terminalsOf(
            case: String,
            gvTyp: String,
        ) = lines
            .filter { it["BON_ID"] == ids.getValue(case) && it["GV_TYP"] == gvTyp }
            .map { it["POS_TERMINAL_ID"].orEmpty() }
            .toSet()

        val umsatz = DsfinvkExportDto.BusinessCaseTypes.UMSATZ
        val rabatt = DsfinvkExportDto.BusinessCaseTypes.RABATT
        val expected =
            mapOf(
                "signedOrderless" to "TER-005",
                "unsignedOrderless" to "",
                "orderedUnattributable" to "",
            )
        expected.forEach { (case, terminal) ->
            assertEquals(setOf(terminal), terminalsOf(case, umsatz), "$case: item line")
            assertEquals(setOf(terminal), terminalsOf(case, rabatt), "$case: item-discount line")
        }
        assertEquals(setOf("TER-005"), terminalsOf("cancellation", umsatz), "an orderless cancelled item takes the cancellation's client")
    }

    private fun export(): Map<String, List<Map<String, String>>> {
        val zip =
            runBlocking {
                service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
            }
        val files = mutableMapOf<String, List<Map<String, String>>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name.endsWith(".csv")) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    val header = lines.first().split(";").map { it.trim('"') }
                    files[name] = lines.drop(1).map { line -> header.zip(line.split(";").map { it.trim('"') }).toMap() }
                }
                entry = zis.nextEntry
            }
        }
        return files
    }
}
