package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
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
 * H8/STAM-4/5 (audit 2026-07-13): unsigned Vorgänge must not fabricate TSE_ID=1/TSE_TANR=0;
 * SIMULATION-mode pseudo-signatures are not genuine Absicherung and must be marked via
 * TSE_TA_FEHLER; tse.csv master data comes from the device that actually signed the
 * exported closing — never from an arbitrary first-ever row.
 */
@DisplayName("DSFinV-K TSE rows: honest unsigned/simulation handling (H8, STAM-4/5)")
class DsfinvkTseRowHonestyTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun seedBill(
        number: String,
        tse: TseTransaction?,
        status: TseSigningStatus,
    ): Long =
        transaction {
            val admin =
                org.example.project.features.employee.domain.models.Employee
                    .find { org.example.project.features.employee.database.tables.EmployeeTable.username eq "admin" }
                    .firstOrNull() ?: TestData.createAdmin()
            val shop =
                org.example.project.features.setup.domain.models.ShopInfo
                    .all()
                    .firstOrNull() ?: TestData.createShopInfo()
            val zBon =
                org.example.project.features.zBon.domain.models.ZBon
                    .all()
                    .firstOrNull()
                    ?: TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("HON-$number")
            val session = TableSession.findById(tws.session.id)!!
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
                    this.tseSigningStatus = status
                }.id.value
        }

    private fun seedTse(
        mode: TseMode,
        serialHex: String,
        payload: String = "hash",
    ): TseTransaction =
        transaction {
            TseTransaction.new {
                this.transactionNumber = 42L
                this.signatureCounter = 7L
                this.signature = "c2ln"
                this.signatureAlgorithm = "ecdsa-plain-SHA256"
                this.publicKey = "cHVibGljLWtleQ=="
                this.tseSerialNumber = serialHex
                this.clientId = "POS-1"
                this.processType = "Kassenbeleg-V1"
                this.processDataHash = payload
                this.startTime = currentTimeStamp()
                this.endTime = currentTimeStamp()
                this.tseTimestamp = 1751234570L
                this.tseMode = mode
            }
        }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `unsigned bill exports empty TSE_ID and TSE_TANR with error text`() {
        val billId = seedBill("7001", tse = null, status = TseSigningStatus.QUEUED)
        val row = readRows(export(), "transactions_tse.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("", row["TSE_ID"], "no device was used — TSE_ID must be empty, not 1")
        assertEquals("", row["TSE_TANR"], "no transaction exists — TSE_TANR must be empty, not 0")
        assertTrue(row["TSE_TA_FEHLER"]!!.isNotBlank())
    }

    @Test
    fun `stamm_tse exports the device's persisted log-time format, with a unixTime fallback (m3)`() {
        // TSE_ZEITFORMAT (Anhang E p. 28) is a DEVICE property, persisted per transaction (V102),
        // NOT hardcoded — a live device read cannot answer for a row a since-swapped TSE signed.
        val real =
            seedTse(TseMode.NORMAL, "M3-UTC").also { tx ->
                transaction { TseTransaction.findById(tx.id)!!.logTimeFormat = "utcTimeWithSeconds" }
            }
        seedBill("7101", tse = real, status = TseSigningStatus.SIGNED)
        assertEquals(
            "utcTimeWithSeconds",
            readRows(export(), "tse.csv").single()["TSE_ZEITFORMAT"],
            "TSE_ZEITFORMAT must reflect the format the device actually reported, not a constant",
        )
    }

    @Test
    fun `a pre-V102 row with no persisted format falls back to unixTime (m3)`() {
        // logTimeFormat stays null (as if signed before V102). The fallback is the Swissbit truth.
        val real = seedTse(TseMode.NORMAL, "M3-LEGACY")
        seedBill("7102", tse = real, status = TseSigningStatus.SIGNED)
        assertEquals("unixTime", readRows(export(), "tse.csv").single()["TSE_ZEITFORMAT"])
    }

    @Test
    fun `a signed bill exports its processData as TSE_VORGANGSDATEN (m7)`() {
        // Anhang E p. 105: „Optional können hier die Daten des Vorgangs (processData) … dargestellt
        // werden. Dies erleichtert die Fehlersuche für die Hersteller und die Finanzverwaltung."
        // The field used to be hardcoded null on a false premise (that pos-tse doesn't surface the
        // payload) — but processDataHash holds exactly the signed processData, and the QR already
        // reads it back. A Kassenbeleg-V1 payload has no CR/LF, so it round-trips verbatim.
        val real = seedTse(TseMode.NORMAL, "M7-SERIAL", payload = "Beleg^0.00_0.00_0.00_0.00_10.00^10.00:Bar")
        val billId = seedBill("7004", tse = real, status = TseSigningStatus.SIGNED)
        val row = readRows(export(), "transactions_tse.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals(
            "Beleg^0.00_0.00_0.00_0.00_10.00^10.00:Bar",
            row["TSE_VORGANGSDATEN"],
            "the exact signed processData must be recoverable for signature verification",
        )
    }

    @Test
    fun `leading and trailing spaces are stripped from TSE_VORGANGSDATEN (m7)`() {
        // Anhang E p. 105: „Vorgehende und/oder nachfolgende Leerzeichen sind nicht zulässig."
        val real = seedTse(TseMode.NORMAL, "M7-TRIM", payload = "  Beleg^0.00_0.00_0.00_0.00_5.00^5.00:Bar  ")
        val billId = seedBill("7005", tse = real, status = TseSigningStatus.SIGNED)
        val row = readRows(export(), "transactions_tse.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("Beleg^0.00_0.00_0.00_0.00_5.00^5.00:Bar", row["TSE_VORGANGSDATEN"])
    }

    @Test
    fun `a CR-bearing payload is omitted rather than mangled (m7)`() {
        // A Bestellung-V1 payload separates Bestellzeilen with \r. escapeString collapses CR/LF to
        // a space (CONT-7), which would emit a payload that no longer verifies against the
        // signature while LOOKING like it should. An honestly-absent optional field beats a
        // silently-corrupted one, so we omit it instead of exporting a lie.
        val real = seedTse(TseMode.NORMAL, "M7-CR", payload = "1;\"Cola\";3.00\r1;\"Wasser\";2.00")
        val billId = seedBill("7006", tse = real, status = TseSigningStatus.SIGNED)
        val row = readRows(export(), "transactions_tse.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals(
            "",
            row["TSE_VORGANGSDATEN"],
            "a payload whose \\r would be collapsed must be omitted, not emitted mangled",
        )
    }

    @Test
    fun `simulation transaction is marked and its pseudo-signature suppressed`() {
        val sim = seedTse(TseMode.SIMULATION, "SIM-SERIAL")
        val billId = seedBill("7002", tse = sim, status = TseSigningStatus.SIMULATION)
        val row = readRows(export(), "transactions_tse.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("TSE-Simulationsmodus", row["TSE_TA_FEHLER"])
        assertEquals("", row["TSE_TA_SIG"], "simulated signature must not export as genuine")
        assertEquals("", row["TSE_ID"])
    }

    @Test
    fun `stamm_tse uses the real device of the exported closing, never an older simulation row`() {
        val realHex = "AB0912CD".repeat(8)
        seedTse(TseMode.SIMULATION, "SIM-SERIAL") // older row — must never become master data
        val real = seedTse(TseMode.NORMAL, realHex)
        seedBill("7003", tse = real, status = TseSigningStatus.SIGNED)
        val rows = readRows(export(), "tse.csv")
        assertEquals(1, rows.size)
        assertEquals(realHex, rows.single()["TSE_SERIAL"])
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

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
                    val header = splitCsvLine(lines.first())
                    return lines.drop(1).map { header.zip(splitCsvLine(it)).toMap() }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found in export zip")
    }
}
