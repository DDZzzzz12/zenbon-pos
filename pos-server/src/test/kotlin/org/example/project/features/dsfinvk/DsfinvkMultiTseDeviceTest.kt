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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-3 STAM-1 (audit 2026-07-16): tse.csv must describe EVERY physical device that signed the
 * exported closings, and transactions_tse.TSE_ID must point each Vorgang to ITS own device. A
 * single hardcoded TSE_ID=1 with one master-data row breaks on a TSE swap (mandatory at cert
 * expiry): old-device rows would claim the new device's master data and TSE_TANR would collide
 * across devices. Two devices signing bills inside ONE closing must yield two tse.csv row-sets
 * (TSE_ID 1 and 2) and each bill's transactions_tse row must carry the id of the device that
 * actually signed THAT bill.
 */
@DisplayName("DSFinV-K multi-device tse.csv: TSE_ID per serial (STAM-1)")
class DsfinvkMultiTseDeviceTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    // Two distinct 64-hex serials — the two physical devices.
    private val serialA = "A1B2C3D4".repeat(8)
    private val serialB = "E5F60789".repeat(8)

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun seedTse(
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

    private fun seedBill(
        number: String,
        tse: TseTransaction,
        zBonNummer: Long = 1L,
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
                    .firstOrNull { it.zbonNummer == zBonNummer }
                    ?: TestData.createZBonFullDay(shop, admin, nummer = zBonNummer)
            val tws = TestData.createTableWithSession("MTD-$number")
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
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }.id.value
        }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `two devices in one closing yield two tse_csv rows and per-bill TSE_ID`() {
        // Device A signs bill 5137; device B signs bill 6248 — same closing (ZBon nummer 1).
        val tseA = seedTse(serialA, txNumber = 11L, suffix = "256")
        val billA = seedBill("5137", tseA)
        val tseB = seedTse(serialB, txNumber = 22L, suffix = "384")
        val billB = seedBill("6248", tseB)

        val zip = export()

        // tse.csv: exactly two devices, TSE_ID {1,2}, each carrying its own hex-normalized serial.
        val tseRows = readRows(zip, "tse.csv")
        assertEquals(2, tseRows.size, "one tse.csv row-set per physical device that signed")
        assertEquals(setOf("1", "2"), tseRows.map { it["TSE_ID"] }.toSet(), "TSE_ID must be 1..N per device")
        assertEquals(
            setOf(serialA, serialB),
            tseRows.map { it["TSE_SERIAL"] }.toSet(),
            "each device's own hex-normalized serial must appear",
        )
        // TSE_ID -> TSE_SERIAL join map from tse.csv.
        val serialByTseId = tseRows.associate { it["TSE_ID"] to it["TSE_SERIAL"] }

        // transactions_tse.csv: each bill's TSE_ID resolves (via tse.csv) to THAT bill's device serial.
        val txRows = readRows(zip, "transactions_tse.csv")
        val rowA = txRows.single { it["BON_ID"] == "B-$billA" }
        val rowB = txRows.single { it["BON_ID"] == "B-$billB" }

        assertNotNull(rowA["TSE_ID"]?.takeIf { it.isNotBlank() }, "signed bill must carry a TSE_ID")
        assertNotNull(rowB["TSE_ID"]?.takeIf { it.isNotBlank() }, "signed bill must carry a TSE_ID")
        assertEquals(serialA, serialByTseId[rowA["TSE_ID"]], "bill A's TSE_ID must point to device A")
        assertEquals(serialB, serialByTseId[rowB["TSE_ID"]], "bill B's TSE_ID must point to device B")
        assertTrue(rowA["TSE_ID"] != rowB["TSE_ID"], "the two devices must have distinct TSE_IDs")
        // TSE_TANR is per-device — the raw transaction numbers survive without collision.
        assertEquals("11", rowA["TSE_TANR"])
        assertEquals("22", rowB["TSE_TANR"])
    }

    /**
     * RU-1: a TSE swap between two closings. tse.csv is keyed by Z_NR, so each closing lists only
     * the device that signed ITS Vorgänge — the old device must not appear under the closing taken
     * after the swap, nor the new one under the closing taken before it was commissioned.
     */
    @Test
    fun `a TSE swap between closings lists each device only under the closing it signed`() {
        seedBill("7001", seedTse(serialA, txNumber = 31L, suffix = "256"), zBonNummer = 1L)
        seedBill("7002", seedTse(serialB, txNumber = 1L, suffix = "384"), zBonNummer = 2L)

        val zip = export()

        val listed = readRows(zip, "tse.csv").map { it["Z_NR"] to it["TSE_SERIAL"] }.toSet()
        assertEquals(setOf("1" to serialA, "2" to serialB), listed, "one device per closing, the one that signed it")

        // The same pairing, seen from transactions_tse: every (Z_NR, TSE_ID) used there is listed in
        // tse.csv, and nothing else is.
        val tseRows = readRows(zip, "tse.csv")
        val used =
            readRows(zip, "transactions_tse.csv")
                .filter { !it["TSE_ID"].isNullOrBlank() }
                .map { it["Z_NR"] to it["TSE_ID"] }
                .toSet()
        assertEquals(used, tseRows.map { it["Z_NR"] to it["TSE_ID"] }.toSet(), "tse.csv must list exactly the devices each closing used")
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
