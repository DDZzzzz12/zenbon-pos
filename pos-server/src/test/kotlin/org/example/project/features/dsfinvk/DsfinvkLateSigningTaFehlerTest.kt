package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AEAO zu § 146a Nr. 1.14.1 asks for the outage's times and cause to be documented; DSFinV-K 2.4
 * gives TSE_TA_FEHLER ("Erläuterungen von Problemen in der Kommunikation zwischen
 * Aufzeichnungssystem und TSE") for exactly that. A Vorgang signed late must therefore say so even
 * though it now carries a valid signature, and a still-pending one must name the cause and start.
 */
@DisplayName("DSFinV-K transactions_tse.csv — TSE_TA_FEHLER documents the Nachsignierung")
class DsfinvkLateSigningTaFehlerTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())
    private val queuedAt = LocalDateTime(2026, 9, 19, 18, 23, 49)

    /** The TSE that was replaced; its serial must still reach tse.csv. */
    private val OLD_DEVICE_SERIAL = "TSE-OLD-DEVICE"

    /** A cancellation signs per batch, so this — not a row id — is its TSE reference. */
    private val CANCELLATION_BATCH = "CANCEL-BATCH-A"

    private val signedAt = LocalDateTime(2026, 9, 19, 18, 40, 2)

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `late-signed, still pending and on-time bills each carry the right TSE_TA_FEHLER`() {
        lateinit var lateBonId: String
        lateinit var pendingBonId: String
        lateinit var onTimeBonId: String

        transaction {
            val fixture = Fixture.create()
            val lateSigning = TseLateSigningRepositoryImpl()

            val late = fixture.bill("LATE-A", signedTse(fixture.now, 11L), TseSigningStatus.SIGNED)
            lateSigning.recordQueued(
                TseEntityType.BILL,
                late.id.value.toString(),
                queuedAt,
                TseLateSigningReason.TSE_UNAVAILABLE,
                "TSE_UNREACHABLE: mount point missing",
            )
            lateSigning.markSigned(TseEntityType.BILL, late.id.value.toString(), signedAt, late.tseTransaction?.id?.value)

            val pending = fixture.bill("PEND-A", null, TseSigningStatus.QUEUED)
            lateSigning.recordQueued(
                TseEntityType.BILL,
                pending.id.value.toString(),
                queuedAt,
                TseLateSigningReason.TSE_UNAVAILABLE,
                "TSE_UNREACHABLE: mount point missing",
            )

            val onTime = fixture.bill("OK-A", signedTse(fixture.now, 12L), TseSigningStatus.SIGNED)

            lateBonId = "B-${late.id.value}"
            pendingBonId = "B-${pending.id.value}"
            onTimeBonId = "B-${onTime.id.value}"
        }

        val csv = exportTransactionsTse()

        assertEquals(
            "Nachsigniert am 19.09.2026 18:40:02 – TSE nicht erreichbar seit 19.09.2026 18:23:49 " +
                "(TSE_UNREACHABLE: mount point missing)",
            taFehler(csv, lateBonId),
        )
        assertEquals(
            "TSE-Signierung ausstehend (Nachsignierung) – TSE nicht erreichbar seit 19.09.2026 18:23:49 " +
                "(TSE_UNREACHABLE: mount point missing)",
            taFehler(csv, pendingBonId),
        )
        assertEquals("", taFehler(csv, onTimeBonId), "a Vorgang signed on time documents no problem")
    }

    @Test
    fun `a pending bill without a record keeps the plain text`() {
        lateinit var bonId: String
        transaction {
            val fixture = Fixture.create()
            bonId = "B-${fixture.bill("PEND-B", null, TseSigningStatus.QUEUED).id.value}"
        }

        assertEquals("TSE-Signierung ausstehend (Nachsignierung)", taFehler(exportTransactionsTse(), bonId))
    }

    @Test
    fun `an overlong failure text is cut to the field's 200 characters`() {
        lateinit var bonId: String
        transaction {
            val fixture = Fixture.create()
            val bill = fixture.bill("PEND-C", null, TseSigningStatus.QUEUED)
            TseLateSigningRepositoryImpl().recordQueued(
                TseEntityType.BILL,
                bill.id.value.toString(),
                queuedAt,
                TseLateSigningReason.TSE_ERROR,
                "x".repeat(500),
            )
            bonId = "B-${bill.id.value}"
        }

        val text = taFehler(exportTransactionsTse(), bonId)
        assertEquals(200, text.length)
        assertTrue(text.startsWith("TSE-Signierung ausstehend (Nachsignierung) – TSE-Fehler seit"))
    }

    @Test
    fun `a superseded TANR is an extra unsigned row on the same paid Bon, never a Belegabbruch`() {
        lateinit var bonId: String
        transaction {
            val fixture = Fixture.create()
            val replacement = signedTse(fixture.now, 12L)
            val bill = fixture.bill("SUPER-A", replacement, TseSigningStatus.SIGNED)
            replacement.referenceId = bill.id.value.toString()
            replacement.entityType = TseEntityType.BILL.name
            // The superseded TANR was signed on the PREVIOUS device: a TSE swap is the likeliest
            // reason a recorded transaction number stopped being open.
            TseTransaction.new {
                tseSerialNumber = OLD_DEVICE_SERIAL
                transactionNumber = 11L
                processType = "Kassenbeleg-V1"
                startTime = fixture.now
                tseStartTimestamp = 1_790_000_000L
                tseMode = TseMode.NORMAL
                referenceId = bill.id.value.toString()
                entityType = TseEntityType.BILL.name
                state = TseTransactionState.SUPERSEDED
                supersededById = replacement.id.value
            }
            bonId = "B-${bill.id.value}"
        }

        val rows =
            exportTransactionsTse()
                .lineSequence()
                .drop(1)
                .map { line -> line.trimEnd().split(";").map { it.trim('"') } }
                .filter { it.getOrNull(3) == bonId }
                .toList()

        assertEquals(listOf("12", "11"), rows.map { it[5] }, "the Bon carries its signed TANR and the superseded one")
        val superseded = rows.single { it[5] == "11" }
        assertEquals("", superseded[10], "no signature for the superseded TANR")
        assertEquals("", superseded[8], "no processType: the till never received the finish")
        assertEquals("TSE-Transaktion 11 abgeschlossen, Antwort nicht erhalten – ersetzt durch TANR 12", superseded[11])

        // Built only from the bills' LINKED transactions, the replaced device was missing from the
        // export: the superseded row carried an EMPTY TSE_ID pointing at nothing, and tse.csv named
        // one device. TSE_ID is column 4.
        val replacement = rows.single { it[5] == "12" }
        assertTrue(superseded[4].isNotBlank(), "a superseded row must name the device that signed it")
        assertTrue(replacement[4].isNotBlank())
        assertTrue(superseded[4] != replacement[4], "the two TANRs were signed by different devices")

        val stammTse = assertNotNull(readZipEntry(exportZip(), "tse.csv"))
        val serials =
            stammTse
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { line -> line.split(";").map { it.trim('"') } }
                .associate { it[3] to it[4] }
        assertEquals(2, serials.size, "both devices must appear in tse.csv, or the TSE_IDs dangle")
        assertEquals(OLD_DEVICE_SERIAL, serials[superseded[4]])
        assertEquals("TSE-TEST-SERIAL", serials[replacement[4]])
    }

    @Test
    fun `a cancellation's superseded TANR names its device too, with no replacement link to follow`() {
        transaction {
            val fixture = Fixture.create()
            val replacement = signedTse(fixture.now, 22L)
            fixture.cancellation(CANCELLATION_BATCH, replacement)
            replacement.referenceId = CANCELLATION_BATCH
            replacement.entityType = TseEntityType.CANCELLATION.name
            // A cancellation's TSE reference is its BATCH id, and this superseded row carries NO
            // supersededById — the link is exactly what is missing when the write that would have
            // set it is the one that failed. Collecting by link alone therefore misses it, and its
            // TSE_ID came out empty while the device was absent from tse.csv.
            TseTransaction.new {
                tseSerialNumber = OLD_DEVICE_SERIAL
                transactionNumber = 21L
                processType = "AVStorno"
                startTime = fixture.now
                tseStartTimestamp = 1_790_000_000L
                tseMode = TseMode.NORMAL
                referenceId = CANCELLATION_BATCH
                entityType = TseEntityType.CANCELLATION.name
                state = TseTransactionState.SUPERSEDED
            }
        }

        val rows =
            exportTransactionsTse()
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { line -> line.trimEnd().split(";").map { it.trim('"') } }
                .toList()

        val superseded = rows.single { it[5] == "21" }
        assertTrue(superseded[4].isNotBlank(), "the replaced device must be named on the cancellation's superseded row")
        assertEquals("", superseded[10], "no signature for the superseded TANR")

        val serials =
            assertNotNull(readZipEntry(exportZip(), "tse.csv"))
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { line -> line.split(";").map { it.trim('"') } }
                .associate { it[3] to it[4] }
        assertEquals(OLD_DEVICE_SERIAL, serials[superseded[4]], "and its TSE_ID must resolve in tse.csv, not dangle")
    }

    private fun exportZip(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    private fun exportTransactionsTse(): String = assertNotNull(readZipEntry(exportZip(), "transactions_tse.csv"), "no transactions_tse.csv")

    /** TSE_TA_FEHLER is column 11 of transactions_tse.csv; BON_ID is column 3. */
    private fun taFehler(
        csv: String,
        bonId: String,
    ): String {
        val row =
            csv.lineSequence().drop(1).firstOrNull { line ->
                line
                    .trimEnd()
                    .split(";")
                    .getOrNull(3)
                    ?.trim('"') == bonId
            }
        assertNotNull(row, "$bonId must have a row in transactions_tse.csv")
        return row
            .trimEnd()
            .split(";")
            .map { it.trim('"') }
            .getOrNull(11)
            .orEmpty()
    }

    private fun signedTse(
        now: LocalDateTime,
        number: Long,
    ): TseTransaction =
        TseTransaction.new {
            this.tseSerialNumber = "TSE-TEST-SERIAL"
            this.transactionNumber = number
            this.processType = "Kassenbeleg-V1"
            this.processDataHash = "hash"
            this.signature = "signature-bytes"
            this.signatureCounter = number
            this.signatureAlgorithm = "ecdsa-plain-SHA384"
            this.startTime = now
            this.endTime = now
            this.tseMode = TseMode.NORMAL
        }

    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private class Fixture(
        val now: LocalDateTime,
        private val admin: Employee,
        private val cash: PaymentType,
        private val session: TableSession,
        private val zBon: ZBon,
    ) {
        fun bill(
            number: String,
            tse: TseTransaction?,
            status: TseSigningStatus,
        ): Bill {
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = number
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = this@Fixture.zBon
                    this.tseTransaction = tse
                    this.tseSigningStatus = status
                }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            return bill
        }

        /** One cancel action, signed as a batch — the batch id is its TSE reference, not the row id. */
        fun cancellation(
            batch: String,
            tse: TseTransaction?,
        ): Cancellation {
            val item = TestData.createOrderItemInSession(session, ItemStatus.CANCELLED)
            return Cancellation.new {
                this.orderItem = item
                this.batchId = batch
                this.reason = "test storno"
                this.employeeId = admin
                this.createdAt = now
                this.zBon = this@Fixture.zBon
                this.tseTransaction = tse
                this.tseSigningStatus = TseSigningStatus.SIGNED
                this.isTraining = false
            }
        }

        companion object {
            fun create(): Fixture {
                val admin = TestData.createAdmin()
                val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
                val cash = TestData.createPaymentType("Cash", isCash = true)
                val tws = TestData.createTableWithSession("TSELATE1")
                val now = currentTimeStamp()
                Terminal.new {
                    deviceId = "unknown"
                    clientId = "TERM-TSE-LATE-TEST"
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
                return Fixture(now, admin, cash, TableSession.findById(tws.session.id)!!, zBon)
            }
        }
    }
}
