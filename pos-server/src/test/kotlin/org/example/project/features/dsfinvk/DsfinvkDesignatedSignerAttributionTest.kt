package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A closing's Kassendifferenz signed by the DESIGNATED till (spec 2026-10-09-zbon-close-anywhere)
 * is attributed to that till by the exporter as it stands: TERMINAL_ID follows the signing ClientID
 * (DSFinV-K Anhang E p. 85), not the till that triggered the closing, and the unsigned `CASH_SALE`
 * aggregate contributes no Vorgang at all.
 */
@DisplayName("DSFinV-K: movements signed by the designated till name that till")
class DsfinvkDesignatedSignerAttributionTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private var nextTanr = 100L

    private fun till(code: String): Terminal {
        val now = currentTimeStamp()
        return Terminal.new {
            deviceId = "device-$code"
            clientId = code
            terminalSerialNr = "HW-$code"
            role = TerminalRole.CASHIER.name
            tseClientRegistered = true
            registeredAt = now
            lastSeenAt = now
        }
    }

    private fun tse(clientId: String): TseTransaction {
        val tanr = nextTanr++
        val now = currentTimeStamp()
        return TseTransaction.new {
            tseSerialNumber = "A1B2C3D4".repeat(8)
            transactionNumber = tanr
            signatureCounter = tanr
            signature = "sig-$tanr"
            signatureAlgorithm = "ecdsa-plain-SHA384"
            publicKey = "pubkey"
            processType = "Kassenbeleg-V1"
            processDataHash = "pd-$tanr"
            this.clientId = clientId
            startTime = now
            endTime = now
            tseTimestamp = 1_759_000_000L + tanr
            tseMode = org.example.project.core.domain.enums.setup.TseMode.NORMAL
        }
    }

    @Test
    fun `the Kassendifferenz names the designated till and the aggregate is absent`() {
        var differenceBon = ""
        var aggregateBon = ""
        var billBon = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val session = TableSession.findById(TestData.createTableWithSession("DSIG").session.id)!!
            val till1 = till("TER-001")
            val designated = till("TER-002")
            val book = CashBookRepositoryImpl().fiscalBookFor(shop)
            book.signingTerminal = designated
            val now = currentTimeStamp()

            billBon =
                "B-" +
                Bill
                    .new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "DSIG-1"
                        this.billAmount = BigDecimal("10.00")
                        this.paidAmount = BigDecimal("10.00")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                        this.terminal = till1
                        this.tseTransaction = tse("TER-001")
                        this.tseSigningStatus = TseSigningStatus.SIGNED
                    }.id.value

            fun entry(
                type: KassenbuchEntryType,
                number: String,
                aggregate: Boolean,
                signedBy: TseTransaction?,
            ): String =
                "KB-" +
                    KassenbuchHeader
                        .new {
                            this.shop = shop
                            this.cashBook = book
                            this.employee = admin
                            this.entryType = type
                            this.belegNr = number
                            this.belegDatum = "09.10.2026"
                            this.belegtext = number
                            this.waehrung = "EUR"
                            this.totalAmount = BigDecimal("1.00")
                            this.currentBalance = BigDecimal("1.00")
                            this.zBon = zBon
                            this.createdAt = now
                            this.isZbonAggregate = aggregate
                            this.tseTransaction = signedBy
                            this.tseSigningStatus = if (signedBy == null) TseSigningStatus.NOT_APPLICABLE else TseSigningStatus.SIGNED
                        }.id.value

            // Closing triggered at TER-001 (or the adminApp); the difference is signed by the designated TER-002.
            differenceBon = entry(KassenbuchEntryType.CASH_SHORTAGE, "DSIG-DIFF", aggregate = false, signedBy = tse("TER-002"))
            aggregateBon = entry(KassenbuchEntryType.CASH_SALE, "DSIG-AGG", aggregate = true, signedBy = null)
        }

        val csv = export()
        val terminalIdByBon = csv.getValue("transactions.csv").associate { it["BON_ID"] to it["TERMINAL_ID"].orEmpty() }
        assertEquals("TER-002", terminalIdByBon.getValue(differenceBon))
        assertEquals("TER-001", terminalIdByBon.getValue(billBon))
        assertFalse(aggregateBon in terminalIdByBon.keys, "the unsigned aggregate is no Vorgang")
        assertTrue(
            csv.getValue("slaves.csv").any { it["TERMINAL_SERIENNR"] == "TER-002" },
            "slaves.csv lists the designated till under its proven ClientID",
        )
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
