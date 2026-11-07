package org.example.project.features.dsfinvk

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.domain.models.TseExportArchiveRecord
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checklist 28.4: after a restart without re-commissioning, the virtual TSE is reachable but EMPTY,
 * and the export shipped its 2048-byte `info.csv`-only TAR with 200 — a TAR that verifies PASS with
 * zero transactions while the database holds the period's signed ones. The same shape arises on
 * real hardware whenever the attached TSE is not the one that signed the period (device swap, wiped
 * or wrong device). The package must carry a TAR covering the period's signed transactions, or
 * refuse with TSE_EXPORT_INCOMPLETE naming the uncovered device.
 */
@DisplayName("DSFinV-K TSE TAR must cover the period's signed transactions (28.4)")
class DsfinvkTseTarCoverageTest {
    private val serialA = "AB".repeat(32)
    private val serialB = "CD".repeat(32)

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `an empty TAR is refused while the period holds signed transactions`() {
        seedSignedBill("9101", serialA, tanr = 11L)

        val ex = assertFailsWith<PosException> { export(tarWith(infoCsv())) }

        assertEquals(PosErrorCode.TSE_EXPORT_INCOMPLETE, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
        assertTrue(ex.message!!.contains(serialA), "the refusal names the device: ${ex.message}")
        assertTrue(ex.message!!.contains("1 of 1 signed transaction(s) missing (TANR 11–11)"), ex.message)
        Unit
    }

    @Test
    fun `a TAR from a different device is refused and names the device that signed the period`() {
        seedSignedBill("9102", serialA, tanr = 11L)

        val ex = assertFailsWith<PosException> { export(tarWith(logMessage(serialByte = 0xCD, tanr = 11L, counter = 5L))) }

        assertEquals(PosErrorCode.TSE_EXPORT_INCOMPLETE, ex.code)
        assertTrue(ex.message!!.contains(serialA), ex.message)
        assertFalse(ex.message!!.contains(serialB), "the device the TAR does come from is not what is missing: ${ex.message}")
        assertTrue(ex.message!!.contains("the archive holds no transaction of this device"), ex.message)
        // A real serial mismatch keeps the swapped-device explanation (rt14 changed only the deleted case).
        assertTrue(ex.message!!.contains("swapped"), ex.message)
        assertFalse(ex.message!!.contains("deleted from this TSE"), ex.message)
        Unit
    }

    @Test
    fun `a TAR holding the device but not every signed TANR is refused`() {
        seedSignedBill("9103", serialA, tanr = 11L)
        seedSignedBill("9104", serialA, tanr = 12L)

        val ex = assertFailsWith<PosException> { export(tarWith(logMessage(serialByte = 0xAB, tanr = 11L, counter = 5L))) }

        assertTrue(ex.message!!.contains("1 of 2 signed transaction(s) missing (TANR 12–12)"), ex.message)
        assertTrue(ex.message!!.contains("the archive holds this device but not all of its transactions"), ex.message)
        Unit
    }

    @Test
    fun `a device swap inside the period is refused for the device the attached TSE cannot export`() {
        seedSignedBill("9105", serialA, tanr = 31L)
        seedSignedBill("9106", serialB, tanr = 1L)

        // The attached device is B; its full TAR covers B's transaction, and A's is on the old card.
        val ex = assertFailsWith<PosException> { export(tarWith(logMessage(serialByte = 0xCD, tanr = 1L, counter = 3L))) }

        assertEquals(PosErrorCode.TSE_EXPORT_INCOMPLETE, ex.code)
        assertTrue(ex.message!!.contains(serialA), ex.message)
        assertFalse(ex.message!!.contains(serialB), "B is covered and must not be reported: ${ex.message}")
        Unit
    }

    @Test
    fun `a TAR covering every signed transaction ships unchanged`() {
        seedSignedBill("9107", serialA, tanr = 11L)
        seedSignedBill("9108", serialA, tanr = 12L)
        val tar =
            tarWith(
                logMessage(serialByte = 0xAB, tanr = 11L, counter = 5L) +
                    logMessage(serialByte = 0xAB, tanr = 12L, counter = 6L),
            )

        val zip = export(tar)

        val shipped = assertNotNull(zipEntryBytes(zip, "tse-export.tar"), "the TAR is in the package")
        assertContentEquals(tar, shipped, "the TAR is shipped byte for byte")
    }

    @Test
    fun `a period without signed transactions ships the TAR without looking inside it`() {
        // A SIMULATION-mode signature never touched a device: nothing to cover.
        seedSignedBill("9109", serialA, tanr = 11L, mode = TseMode.SIMULATION)
        val notATar = byteArrayOf(1, 2, 3)

        val zip = export(notATar)

        assertContentEquals(notATar, zipEntryBytes(zip, "tse-export.tar"))
    }

    @Test
    fun `allowMissingTseTar ships WITHOUT the uncovering TAR instead of refusing`() {
        seedSignedBill("9110", serialA, tanr = 11L)

        val zip = export(tarWith(infoCsv()), allowMissingTseTar = true)

        assertNull(zipEntryBytes(zip, "tse-export.tar"), "an uncovering TAR is never shipped, even on the opt-out")
    }

    // --- export-and-delete archives (real-TSE QA rt14 c) -------------------------------------
    // After an export-and-delete the device holds only what was signed since; the erased entries
    // live only in the archive the delete wrote. The package must ship that archive (checked against
    // its registered SHA-256), and without it must say the data was deleted — not that the device
    // was swapped.

    @TempDir
    lateinit var archiveDir: File

    /** Registers [bytes] as an export-and-delete archive of serial A, the way the register stores it. */
    private fun registerArchive(
        bytes: ByteArray,
        name: String = "tse_export_before_delete_20260506_172401_874.tar",
    ): File {
        val file = File(archiveDir, name).apply { writeBytes(bytes) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        transaction {
            TseExportArchiveRepositoryImpl().insert(
                TseExportArchiveRecord(
                    fileName = name,
                    filePath = file.path,
                    tseSerialHex = serialA,
                    tanrFrom = 11L,
                    tanrTo = 11L,
                    signatureCounterFrom = 5L,
                    signatureCounterTo = 5L,
                    logTimeFrom = 1_784_000_000L,
                    logTimeTo = 1_784_000_000L,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha,
                    origin = TseExportArchiveOrigin.EXPORT_AND_DELETE,
                    createdAt = currentTimeStamp(),
                    createdByEmployeeId = null,
                ),
            )
        }
        return file
    }

    @Test
    fun `entries erased by export-and-delete are shipped from the registered archive beside the device TAR`() {
        seedSignedBill("9111", serialA, tanr = 11L)
        seedSignedBill("9112", serialA, tanr = 12L)
        val archive = tarWith(logMessage(serialByte = 0xAB, tanr = 11L, counter = 5L))
        registerArchive(archive)
        // The device was erased after TANR 11; it now holds only TANR 12.
        val device = tarWith(logMessage(serialByte = 0xAB, tanr = 12L, counter = 9L))

        val zip = export(device)

        assertContentEquals(device, zipEntryBytes(zip, "tse-export.tar"), "the device TAR ships unchanged")
        assertContentEquals(
            archive,
            zipEntryBytes(zip, "tse/tse_export_before_delete_20260506_172401_874.tar"),
            "the archive ships under its own name in tse/",
        )
    }

    @Test
    fun `a device covering the period ships alone even when archives are registered`() {
        seedSignedBill("9113", serialA, tanr = 12L)
        registerArchive(tarWith(logMessage(serialByte = 0xAB, tanr = 11L, counter = 5L)))

        val zip = export(tarWith(logMessage(serialByte = 0xAB, tanr = 12L, counter = 9L)))

        assertNull(zipEntryBytes(zip, "tse_export_before_delete_20260506_172401_874.tar"), "an archive the period does not need is not shipped")
    }

    @Test
    fun `erased entries without a registered archive are refused as deleted, never as a swapped device`() {
        seedSignedBill("9114", serialA, tanr = 11L)
        seedSignedBill("9115", serialA, tanr = 12L)

        val ex = assertFailsWith<PosException> { export(tarWith(logMessage(serialByte = 0xAB, tanr = 12L, counter = 9L))) }

        assertEquals(PosErrorCode.TSE_EXPORT_INCOMPLETE, ex.code)
        assertEquals(HttpStatusCode.Conflict, ex.httpStatus)
        val message = ex.message!!
        assertTrue(message.contains("TANR 11–11"), message)
        assertTrue(message.contains("signed "), "the refusal names when the missing transactions were signed: $message")
        assertTrue(message.contains("deleted from this TSE"), message)
        assertTrue(message.contains("register"), message)
        assertFalse(message.contains("swapped"), message)
        Unit
    }

    @Test
    fun `a registered archive whose file changed is not shipped and the refusal says why`() {
        seedSignedBill("9116", serialA, tanr = 11L)
        seedSignedBill("9117", serialA, tanr = 12L)
        val file = registerArchive(tarWith(logMessage(serialByte = 0xAB, tanr = 11L, counter = 5L)))
        file.appendBytes(ByteArray(512))

        val ex = assertFailsWith<PosException> { export(tarWith(logMessage(serialByte = 0xAB, tanr = 12L, counter = 9L))) }

        assertEquals(PosErrorCode.TSE_EXPORT_INCOMPLETE, ex.code)
        assertTrue(ex.message!!.contains("SHA-256 differs"), ex.message)
        Unit
    }

    @Test
    fun `a registered archive whose file is gone is named in the refusal`() {
        seedSignedBill("9118", serialA, tanr = 11L)
        seedSignedBill("9119", serialA, tanr = 12L)
        registerArchive(tarWith(logMessage(serialByte = 0xAB, tanr = 11L, counter = 5L))).delete()

        val ex = assertFailsWith<PosException> { export(tarWith(logMessage(serialByte = 0xAB, tanr = 12L, counter = 9L))) }

        assertTrue(ex.message!!.contains("is missing at"), ex.message)
        Unit
    }

    // --- fixtures -----------------------------------------------------------------------------

    private fun export(
        tar: ByteArray,
        allowMissingTseTar: Boolean = false,
    ): ByteArray {
        val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink(), tseAdminProvider = providerReturning(tar))
        return runBlocking {
            service.generateExport(
                DsfinvkExportDto.ExportRequestDto(
                    startDate = "2000-01-01",
                    endDate = "2100-01-01",
                    includeTseTar = true,
                    allowMissingTseTar = allowMissingTseTar,
                ),
            )
        }
    }

    /** A provider whose only live method is exportFull, answering with [tar] like a reachable device. */
    private fun providerReturning(tar: ByteArray): TseAdminProvider {
        val unused =
            Proxy.newProxyInstance(
                TseAdminProvider::class.java.classLoader,
                arrayOf(TseAdminProvider::class.java),
            ) { _, method, _ -> throw NotImplementedError("TseAdminProvider.${method.name} is unused here") } as TseAdminProvider
        return object : TseAdminProvider by unused {
            override suspend fun exportFull(outputPath: String): TseDto.ExportResultDto {
                val file = File.createTempFile("tse", ".tar").apply { writeBytes(tar) }
                return TseDto.ExportResultDto(
                    success = true,
                    exportType = "FULL",
                    filePath = file.absolutePath,
                    fileName = "tse-export.tar",
                )
            }
        }
    }

    private fun seedSignedBill(
        number: String,
        serialHex: String,
        tanr: Long,
        mode: TseMode = TseMode.NORMAL,
    ) {
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
                    .firstOrNull() ?: TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tse =
                TseTransaction.new {
                    this.transactionNumber = tanr
                    this.signatureCounter = tanr
                    this.signature = "sig-$number"
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.tseSerialNumber = serialHex
                    this.tseSerialHex = serialHex
                    this.clientId = "POS-1"
                    this.processType = "Kassenbeleg-V1"
                    this.startTime = currentTimeStamp()
                    this.endTime = currentTimeStamp()
                    this.tseMode = mode
                    this.state = TseTransactionState.FINISHED
                }
            val tws = TestData.createTableWithSession("COV-$number")
            val now = currentTimeStamp()
            Bill.new {
                this.employeeId = admin
                this.tableSession = TableSession.findById(tws.session.id)!!
                this.billNumber = number
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
                this.tseTransaction = tse
                this.tseSigningStatus = TseSigningStatus.SIGNED
            }
        }
    }

    private fun zipEntryBytes(
        zip: ByteArray,
        nameSuffix: String,
    ): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry ->
                if (entry.name.endsWith(nameSuffix)) return zis.readBytes()
            }
        }
        return null
    }

    private fun infoCsv(): ByteArray = "component:,device\n".toByteArray()

    /**
     * A USTAR archive (the reader parses member content, not names). Concatenated DER log messages
     * are split into one member each, as a device export stores them; anything else is one member.
     */
    private fun tarWith(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        splitMembers(content).forEachIndexed { index, member -> out.write(tarMember("m$index.log", member)) }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun tarMember(
        name: String,
        content: ByteArray,
    ): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000644 ".toByteArray().copyInto(header, 100)
        (content.size.toString(8).padStart(11, '0') + " ").toByteArray().copyInto(header, 124)
        "        ".toByteArray().copyInto(header, 148)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        (checksum.toString(8).padStart(6, '0') + "  ").toByteArray().copyInto(header, 148)
        return header + content + ByteArray((512 - content.size % 512) % 512)
    }

    /** Splits concatenated top-level DER SEQUENCEs back into one member each; anything else is one member. */
    private fun splitMembers(content: ByteArray): List<ByteArray> {
        if (content.isEmpty() || content[0] != 0x30.toByte()) return listOf(content)
        val members = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < content.size) {
            val (length, headerSize) = derLength(content, offset + 1)
            val end = offset + 1 + headerSize + length
            members += content.copyOfRange(offset, end)
            offset = end
        }
        return members
    }

    private fun derLength(
        bytes: ByteArray,
        at: Int,
    ): Pair<Int, Int> {
        val first = bytes[at].toInt() and 0xFF
        if (first < 0x80) return first to 1
        val count = first and 0x7F
        var length = 0
        repeat(count) { length = (length shl 8) or (bytes[at + 1 + it].toInt() and 0xFF) }
        return length to 1 + count
    }

    // --- Minimal DER encoder for one TR-03151 transaction LogMessage (same shape as
    // TseVerificationServiceTest's fixture; pos-tse's own test fixture is not visible here). ---

    private fun len(n: Int): ByteArray =
        when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }

    private fun tlv(
        tag: Int,
        value: ByteArray,
    ): ByteArray = byteArrayOf(tag.toByte()) + len(value.size) + value

    private fun unsigned(v: Long): ByteArray {
        if (v == 0L) return byteArrayOf(0)
        var x = v
        val out = ArrayList<Byte>()
        while (x > 0) {
            out.add(0, (x and 0xFF).toByte())
            x = x shr 8
        }
        if (out[0].toInt() and 0x80 != 0) out.add(0, 0)
        return out.toByteArray()
    }

    private fun int(v: Long): ByteArray = tlv(0x02, unsigned(v))

    /** A FinishTransaction log message of TANR [tanr] from the device whose serial is 32 × [serialByte]. */
    private fun logMessage(
        serialByte: Int,
        tanr: Long,
        counter: Long,
    ): ByteArray {
        val body =
            int(2) +
                tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x03, 0x07, 0x01, 0x01)) +
                tlv(0x80, "FinishTransaction".toByteArray()) +
                tlv(0x81, "POS-1".toByteArray()) +
                tlv(0x82, byteArrayOf(0xDE.toByte(), 0xAD.toByte())) +
                tlv(0x83, "Kassenbeleg-V1".toByteArray()) +
                tlv(0x85, unsigned(tanr)) +
                tlv(0x04, ByteArray(32) { serialByte.toByte() }) +
                tlv(0x30, tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x01, 0x01, 0x04, 0x01, 0x03))) +
                int(counter) +
                int(1_784_000_000) +
                tlv(0x04, ByteArray(96) { 0xCD.toByte() })
        return tlv(0x30, body)
    }
}
