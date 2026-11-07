package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import org.example.project.utils.formatter.TimeProvider
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.registerTimeProvider
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
import kotlin.time.Clock

/**
 * STAM-2/TSER-10 (round 3): the server-clock fallback for TSE_TA_START/TSE_TA_ENDE converts a
 * `startTime`/`endTime` LocalDateTime — written via currentTimeStamp() in the SHOP zone — to an
 * epoch instant. Interpreting that shop-local wall time as UTC shifted the emitted instant by the
 * UTC offset (+1/+2h for Europe/Berlin) while still stamping the value with a `Z` designator. The
 * fallback must convert with the write zone (getTimeZone()) so the exported `Z` instant is truthful.
 */
@DisplayName("DSFinV-K TSE log-time fallback converts with the write zone (STAM-2/TSER-10)")
class DsfinvkTseLogTimeFallbackTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    private val berlin = TimeZone.of("Europe/Berlin")

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        // Shop timezone = Europe/Berlin. startTime/endTime are written in this zone via
        // currentTimeStamp(); the export fallback must read them back in the same zone.
        registerTimeProvider(
            object : TimeProvider {
                override fun now(): LocalDateTime = Clock.System.now().toLocalDateTime(berlin)

                override fun getTimeZone(): TimeZone = berlin
            },
        )
    }

    @AfterEach
    fun teardown() {
        registerTimeProvider(TimeProvider.SYSTEM_DEFAULT)
        TestDatabase.teardown()
    }

    private fun seedBill(
        number: String,
        tse: TseTransaction,
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
            val tws = TestData.createTableWithSession("FBK-$number")
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

    /**
     * A NORMAL-mode transaction with NO device log times (tseStartTimestamp/tseTimestamp null),
     * forcing both TSE_TA_START and TSE_TA_ENDE onto the server-clock fallback. startTime/endTime
     * are shop-local wall times (14:00 / 15:00 CEST).
     */
    private fun seedTseNoDeviceLogTimes(): TseTransaction =
        transaction {
            TseTransaction.new {
                this.transactionNumber = 42L
                this.signatureCounter = 7L
                this.signature = "c2ln"
                this.signatureAlgorithm = "ecdsa-plain-SHA256"
                this.publicKey = "cHVibGljLWtleQ=="
                this.tseSerialNumber = "AB0912CD".repeat(8)
                this.clientId = "POS-1"
                this.processType = "Kassenbeleg-V1"
                this.processDataHash = "hash"
                this.startTime = LocalDateTime(2026, 7, 15, 14, 0, 0)
                this.endTime = LocalDateTime(2026, 7, 15, 15, 0, 0)
                this.tseStartTimestamp = null
                this.tseTimestamp = null
                this.tseMode = TseMode.NORMAL
            }
        }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `server-clock fallback interprets shop-local start-end as Berlin, emits truthful UTC Z instant`() {
        val tse = seedTseNoDeviceLogTimes()
        val billId = seedBill("9101", tse)

        val row = readRows(export(), "transactions_tse.csv").single { it["BON_ID"] == "B-$billId" }

        // 14:00 CEST (UTC+2) == 12:00 UTC. Previously exported (buggy) as 14:00:00.000Z.
        assertEquals("2026-07-15T12:00:00.000Z", row["TSE_TA_START"])
        // 15:00 CEST (UTC+2) == 13:00 UTC.
        assertEquals("2026-07-15T13:00:00.000Z", row["TSE_TA_ENDE"])
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
