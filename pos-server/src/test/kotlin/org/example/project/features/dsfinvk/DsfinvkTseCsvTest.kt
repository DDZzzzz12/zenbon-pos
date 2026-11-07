package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
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
 * Guards DSFinV-K compliance item C3: `tse.csv` (Stamm_TSE) declares `TSE_ZEITFORMAT`
 * and `transactions_tse.csv` (TSE_Transaktionen) must emit `TSE_TA_START`/`TSE_TA_ENDE`
 * values that actually parse under that declared format. A DSFinV-K validator parses
 * those fields per the DECLARED format, so a mismatch (declaring `unixTime` while
 * emitting an ISO datetime string) makes every TSE row misread.
 */
@DisplayName("DSFinV-K tse.csv / transactions_tse.csv — TSE_ZEITFORMAT agreement")
class DsfinvkTseCsvTest {
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
    fun `TSE_TA_START and TSE_TA_ENDE parse under the declared TSE_ZEITFORMAT`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL"
                    this.transactionNumber = 42L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 7L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-A"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        val tseCsv = readZipEntry(zip, "tse.csv")
        assertNotNull(tseCsv, "tse.csv must be present in the export")
        val tseDataLine = tseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val tseZeitformat = tseDataLine.split(";")[6].trim('"')

        val transactionsTseCsv = readZipEntry(zip, "transactions_tse.csv")
        assertNotNull(transactionsTseCsv, "transactions_tse.csv must be present in the export")
        val row = transactionsTseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = row.trimEnd().split(";").map { it.trim('"') }
        val tseTaStart = columns[6]
        val tseTaEnde = columns[7]

        assertTrue(tseTaStart.isNotBlank(), "TSE_TA_START must not be blank")
        assertTrue(tseTaEnde.isNotBlank(), "TSE_TA_ENDE must not be blank")

        // Anhang E p. 104 fixes the CSV rendering UNCONDITIONALLY to ISO 8601
        // „YYYY-MM-DDThh:mm:ss.fffZ" (audit H1). TSE_ZEITFORMAT (Stamm_TSE) documents the
        // TSE's INTERNAL log-time format (the TAR), never the CSV representation.
        val isoLogTime = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
        assertTrue(
            tseTaStart.matches(isoLogTime),
            "TSE_TA_START='$tseTaStart' must be ISO 8601 YYYY-MM-DDThh:mm:ss.fffZ regardless of TSE_ZEITFORMAT ('$tseZeitformat')",
        )
        assertTrue(
            tseTaEnde.matches(isoLogTime),
            "TSE_TA_ENDE='$tseTaEnde' must be ISO 8601 YYYY-MM-DDThh:mm:ss.fffZ regardless of TSE_ZEITFORMAT ('$tseZeitformat')",
        )
    }

    @Test
    fun `TSE_SIG_ALGO reflects the persisted signatureAlgorithm, not a hardcoded literal`() {
        val storedAlgorithm = "ecdsa-plain-SHA256"

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE2")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-SIGALGO-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-2"
                    this.transactionNumber = 43L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 8L
                    this.signatureAlgorithm = storedAlgorithm
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-B"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        val tseCsv = readZipEntry(zip, "tse.csv")
        assertNotNull(tseCsv, "tse.csv must be present in the export")
        val tseDataLine = tseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val tseSigAlgo = tseDataLine.split(";")[5].trim('"')

        assertEquals(
            storedAlgorithm,
            tseSigAlgo,
            "TSE_SIG_ALGO must reflect the persisted TseTransaction.signatureAlgorithm, not a hardcoded literal",
        )
    }

    @Test
    fun `TSE_ZERTIFIKAT_I and TSE_ZERTIFIKAT_II losslessly reconstruct a certificate that fits the 2000-char budget`() {
        val certificate =
            ('A'..'Z').toList().let { alphabet ->
                (0 until 2000).map { alphabet[it % alphabet.size] }.joinToString("")
            }

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE3")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-CERT-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-3"
                    this.transactionNumber = 44L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 9L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    this.certificate = certificate
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-C"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val tseCsv = readZipEntry(zip, "tse.csv")
        assertNotNull(tseCsv, "tse.csv must be present in the export")
        val tseDataLine = tseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = tseDataLine.split(";").map { it.trim('"') }
        val tseZertifikatI = columns[9]
        val tseZertifikatII = columns[10]

        assertEquals(1000, tseZertifikatI.length, "TSE_ZERTIFIKAT_I must carry the first 1000 chars")
        assertEquals(
            certificate,
            tseZertifikatI + tseZertifikatII,
            "TSE_ZERTIFIKAT_I + TSE_ZERTIFIKAT_II must losslessly reconstruct the full certificate",
        )
    }

    @Test
    fun `a certificate over 2000 chars exports across TSE_ZERTIFIKAT_I to V and the index_xml declares them`() {
        // Anhang E p. 78-79: a certificate longer than 2000 chars is carried across as many
        // TSE_ZERTIFIKAT_N fields (I, II, III, IV, V ...) as needed — 1000 base64 chars each.
        // A real production TSE certificate is 4316 chars = ceil(4316/1000) = 5 fields.
        // The export must COMPLETE (never throw) and the whole certificate must reconstruct
        // by concatenating the five parts. index.xml must declare exactly the five fields
        // tse.csv emits, or the package is formally invalid.
        val certificate =
            ('A'..'Z').toList().let { alphabet ->
                (0 until 4316).map { alphabet[it % alphabet.size] }.joinToString("")
            }

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE4")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-CERT-OVERFLOW-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-4"
                    this.transactionNumber = 45L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 10L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    this.certificate = certificate
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-D"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        // ---- tse.csv: five TSE_ZERTIFIKAT fields, no sixth ----
        val tseCsv = readZipEntry(zip, "tse.csv")
        assertNotNull(tseCsv, "tse.csv must be present in the export")
        val header =
            tseCsv
                .lineSequence()
                .first()
                .split(";")
                .map { it.trim('"') }
        val certColumns = header.filter { it.startsWith("TSE_ZERTIFIKAT_") }
        assertEquals(
            listOf("TSE_ZERTIFIKAT_I", "TSE_ZERTIFIKAT_II", "TSE_ZERTIFIKAT_III", "TSE_ZERTIFIKAT_IV", "TSE_ZERTIFIKAT_V"),
            certColumns,
            "tse.csv must declare exactly the five TSE_ZERTIFIKAT fields a 4316-char certificate requires",
        )

        val tseDataLine = tseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = tseDataLine.split(";").map { it.trim('"') }
        val firstCertIdx = header.indexOf("TSE_ZERTIFIKAT_I")
        val parts = (0 until 5).map { columns[firstCertIdx + it] }
        assertEquals(1000, parts[0].length, "each part but the last carries 1000 chars")
        assertEquals(316, parts[4].length, "the last part carries the 316-char remainder")
        assertEquals(
            certificate,
            parts.joinToString(""),
            "the five TSE_ZERTIFIKAT parts must losslessly reconstruct the full certificate",
        )

        // ---- index.xml: must declare the SAME five fields for Stamm_TSE ----
        assertEquals(
            listOf("TSE_ZERTIFIKAT_I", "TSE_ZERTIFIKAT_II", "TSE_ZERTIFIKAT_III", "TSE_ZERTIFIKAT_IV", "TSE_ZERTIFIKAT_V"),
            stammTseCertColumnsFromIndexXml(zip),
            "index.xml must declare exactly the five TSE_ZERTIFIKAT fields tse.csv emits",
        )
    }

    @Test
    fun `two TSE devices with different certificate lengths share one column count and both reconstruct`() {
        // Device A's certificate fits two fields (1200 chars); device B's needs five (4316).
        // tse.csv is one CSV, so N = max over devices = 5; A leaves III/IV/V empty, both reconstruct.
        val certA =
            ('a'..'z').toList().let { alphabet ->
                (0 until 1200).map { alphabet[it % alphabet.size] }.joinToString("")
            }
        val certB =
            ('A'..'Z').toList().let { alphabet ->
                (0 until 4316).map { alphabet[it % alphabet.size] }.joinToString("")
            }

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-MIX-TEST"
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

            val tseA =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-MIX-SERIAL-A"
                    this.transactionNumber = 60L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 60L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL
                    this.certificate = certA
                }
            val tseB =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-MIX-SERIAL-B"
                    this.transactionNumber = 61L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 61L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL
                    this.certificate = certB
                }

            val sessionA = TableSession.findById(TestData.createTableWithSession("MIXA").session.id)!!
            val sessionB = TableSession.findById(TestData.createTableWithSession("MIXB").session.id)!!

            val saleA =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = sessionA
                    this.billNumber = "MIX-A"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseA
                }
            Payment.new {
                this.bill = saleA
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            val saleB =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = sessionB
                    this.billNumber = "MIX-B"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseB
                }
            Payment.new {
                this.bill = saleB
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val tseCsv = readZipEntry(zip, "tse.csv")
        assertNotNull(tseCsv, "tse.csv must be present in the export")
        val lines = tseCsv.split("\r\n").filter { it.isNotBlank() }
        val header = lines.first().split(";").map { it.trim('"') }
        val certColumns = header.filter { it.startsWith("TSE_ZERTIFIKAT_") }
        assertEquals(
            listOf("TSE_ZERTIFIKAT_I", "TSE_ZERTIFIKAT_II", "TSE_ZERTIFIKAT_III", "TSE_ZERTIFIKAT_IV", "TSE_ZERTIFIKAT_V"),
            certColumns,
            "N is per-file: the 4316-char device forces five columns even though the other device needs two",
        )

        val serialIdx = header.indexOf("TSE_SERIAL")
        val firstCertIdx = header.indexOf("TSE_ZERTIFIKAT_I")
        val rows = lines.drop(1).map { it.split(";").map { c -> c.trim('"') } }
        val rowA = rows.single { it[serialIdx] == "TSE-MIX-SERIAL-A" }
        val rowB = rows.single { it[serialIdx] == "TSE-MIX-SERIAL-B" }

        val partsA = (0 until 5).map { rowA[firstCertIdx + it] }
        assertEquals(certA, partsA.joinToString(""), "device A's certificate must reconstruct")
        assertEquals("", partsA[2], "device A must leave TSE_ZERTIFIKAT_III empty")
        assertEquals("", partsA[3], "device A must leave TSE_ZERTIFIKAT_IV empty")
        assertEquals("", partsA[4], "device A must leave TSE_ZERTIFIKAT_V empty")

        val partsB = (0 until 5).map { rowB[firstCertIdx + it] }
        assertEquals(certB, partsB.joinToString(""), "device B's certificate must reconstruct")
    }

    @Test
    fun `TSE_TA_ENDE prefers the TSE hardware log time over the server clock`() {
        // TSE's own FinishTransaction log time (tseTimestamp) is deliberately far from endTime
        // (the server clock) so the test can distinguish which one the export actually used.
        val tseHardwareLogTimeSeconds = 1_700_000_000L

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE5")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-LOGTIME-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-5"
                    this.transactionNumber = 46L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 11L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    // Server clock times — deliberately different from tseTimestamp below.
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    // The TSE hardware's own FinishTransaction log time.
                    this.tseTimestamp = tseHardwareLogTimeSeconds
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-E"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val transactionsTseCsv = readZipEntry(zip, "transactions_tse.csv")
        assertNotNull(transactionsTseCsv, "transactions_tse.csv must be present in the export")
        val row = transactionsTseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = row.trimEnd().split(";").map { it.trim('"') }
        val tseTaEnde = columns[7]

        assertEquals(
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochSecond(tseHardwareLogTimeSeconds)),
            tseTaEnde,
            "TSE_TA_ENDE must be the TSE hardware's own log time (tseTimestamp) rendered ISO 8601, not the server clock (endTime)",
        )
    }

    @Test
    fun `TSE_TA_ENDE falls back to the server clock when no TSE hardware log time is stored`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE6")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-LOGTIME-FALLBACK-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-6"
                    this.transactionNumber = 47L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 12L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    // tseTimestamp intentionally left null (e.g. legacy/simulated transaction).
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-F"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val transactionsTseCsv = readZipEntry(zip, "transactions_tse.csv")
        assertNotNull(transactionsTseCsv, "transactions_tse.csv must be present in the export")
        val row = transactionsTseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = row.trimEnd().split(";").map { it.trim('"') }
        val tseTaEnde = columns[7]

        assertTrue(
            tseTaEnde.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")),
            "TSE_TA_ENDE must fall back to the server clock (endTime) rendered ISO 8601 when tseTimestamp is absent",
        )
    }

    @Test
    fun `TSE_TA_START prefers the TSE hardware StartTransaction log time over the server clock`() {
        // TSE's own StartTransaction log time (tseStartTimestamp) is deliberately far from
        // startTime (the server clock) so the test can distinguish which one the export used.
        val tseHardwareStartLogTimeSeconds = 1_650_000_000L

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE7")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-START-LOGTIME-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-7"
                    this.transactionNumber = 48L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 13L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    // Server clock times — deliberately different from tseStartTimestamp below.
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    // The TSE hardware's own StartTransaction log time.
                    this.tseStartTimestamp = tseHardwareStartLogTimeSeconds
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-G"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val transactionsTseCsv = readZipEntry(zip, "transactions_tse.csv")
        assertNotNull(transactionsTseCsv, "transactions_tse.csv must be present in the export")
        val row = transactionsTseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = row.trimEnd().split(";").map { it.trim('"') }
        val tseTaStart = columns[6]

        assertEquals(
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochSecond(tseHardwareStartLogTimeSeconds)),
            tseTaStart,
            "TSE_TA_START must be the TSE hardware's own StartTransaction log time (tseStartTimestamp) rendered ISO 8601, not the server clock (startTime)",
        )
    }

    @Test
    fun `TSE_TA_START falls back to the server clock when no TSE hardware start log time is stored`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE8")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-START-LOGTIME-FALLBACK-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-8"
                    this.transactionNumber = 49L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 14L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    // tseStartTimestamp intentionally left null (e.g. legacy/quickSign transaction).
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-H"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val transactionsTseCsv = readZipEntry(zip, "transactions_tse.csv")
        assertNotNull(transactionsTseCsv, "transactions_tse.csv must be present in the export")
        val row = transactionsTseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val columns = row.trimEnd().split(";").map { it.trim('"') }
        val tseTaStart = columns[6]

        assertTrue(
            tseTaStart.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")),
            "TSE_TA_START must fall back to the server clock (startTime) rendered ISO 8601 when tseStartTimestamp is absent",
        )
    }

    @Test
    fun `deriveTseSerialHashHex computes SHA-256 hex of the decoded publicKey`() {
        val rawKeyBytes = "test-public-key-bytes".toByteArray(Charsets.UTF_8)
        val publicKeyBase64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(rawKeyBytes)
        val expectedHex =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(rawKeyBytes)
                .joinToString("") { "%02x".format(it) }

        assertEquals(expectedHex, service.deriveTseSerialHashHex(publicKeyBase64))
    }

    @Test
    fun `deriveTseSerialHashHex returns null for missing or invalid public key`() {
        assertEquals(null, service.deriveTseSerialHashHex(null))
        assertEquals(null, service.deriveTseSerialHashHex(""))
        assertEquals(null, service.deriveTseSerialHashHex("not-valid-base64!!!"))
    }

    @Test
    fun `TSE_SERIAL always emits the stored tseSerialNumber, never a guessed hash`() {
        // The stored serial deliberately does NOT match SHA-256(publicKey) — the export must
        // still emit the stored, vendor-provided value unchanged rather than substituting the
        // derived hash (fiscal identifiers must never be guessed).
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSE7")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-SERIAL-TEST"
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
            val session = TableSession.findById(tws.session.id)!!

            val tseTransaction =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL-7-NOT-A-HASH"
                    this.transactionNumber = 48L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 13L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // tests model a REAL device (H8: SIMULATION rows are no longer genuine Absicherung)
                    this.publicKey =
                        java.util.Base64
                            .getEncoder()
                            .encodeToString("some-ec-public-key".toByteArray())
                }

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-G"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = tseTransaction
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
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

        val tseCsv = readZipEntry(zip, "tse.csv")
        assertNotNull(tseCsv, "tse.csv must be present in the export")
        val tseDataLine = tseCsv.lineSequence().drop(1).first { it.isNotBlank() }
        val tseSerial = tseDataLine.split(";")[4].trim('"')

        assertEquals(
            "TSE-TEST-SERIAL-7-NOT-A-HASH",
            tseSerial,
            "TSE_SERIAL must always be the stored tseSerialNumber, never a derived/guessed value",
        )
    }

    /**
     * Reads a single CSV entry (matched by exact basename, e.g. "tse.csv") out of the export
     * ZIP as text, or null. Matching by suffix would wrongly let "tse.csv" match
     * "transactions_tse.csv" (which also ends with "tse.csv").
     */
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

    private fun readZipEntryBytes(
        zip: ByteArray,
        name: String,
    ): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) return zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Parses index.xml and returns the ordered TSE_ZERTIFIKAT_* VariableColumn names declared
     * for the Stamm_TSE (tse.csv) table — the descriptor auditor software parses tse.csv through.
     */
    private fun stammTseCertColumnsFromIndexXml(zip: ByteArray): List<String> {
        val xml = readZipEntryBytes(zip, "index.xml") ?: error("index.xml not found in export zip")
        val dbf =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val doc = dbf.newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml))
        val tables = doc.getElementsByTagName("Table")
        for (i in 0 until tables.length) {
            val table = tables.item(i) as org.w3c.dom.Element
            if (table.getElementsByTagName("Name").item(0).textContent == "Stamm_TSE") {
                val cols = table.getElementsByTagName("VariableColumn")
                return (0 until cols.length)
                    .map { (cols.item(it) as org.w3c.dom.Element).getElementsByTagName("Name").item(0).textContent }
                    .filter { it.startsWith("TSE_ZERTIFIKAT_") }
            }
        }
        error("Stamm_TSE table not found in index.xml")
    }
}
