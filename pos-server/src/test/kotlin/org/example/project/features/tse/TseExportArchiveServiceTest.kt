package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.data.service.TseExportArchiveService
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.verification.TseExportDirectory
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.TseTarFixture
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-TSE QA rt14 (c): the archive an export-and-delete writes is the only copy of the erased TSE
 * entries. It is entered in the archive register with what it holds and its SHA-256, and an archive
 * written before the register existed can be registered by its file name.
 */
@DisplayName("TSE archive register — export-and-delete archives and hand registration")
class TseExportArchiveServiceTest {
    @TempDir
    lateinit var dir: File

    private val serialHex = "AB".repeat(32)

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun exportDir(): TseExportDirectory {
        val config =
            Proxy.newProxyInstance(
                IShopConfigurationManager::class.java.classLoader,
                arrayOf(IShopConfigurationManager::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getRestoredPathsPendingReview" -> emptyMap<Any, Any>()
                    "getDatevXmlExportPath" -> dir.path
                    else -> throw NotImplementedError("IShopConfigurationManager.${method.name} is not used here")
                }
            } as IShopConfigurationManager
        return TseExportDirectory(config)
    }

    /** A device whose export-and-delete writes [archive] into the folder it is given. */
    private fun deviceWriting(archive: ByteArray): TseAdminProvider =
        Proxy.newProxyInstance(
            TseAdminProvider::class.java.classLoader,
            arrayOf(TseAdminProvider::class.java),
        ) { _, method, args ->
            when (method.name) {
                "deleteExportedData" -> {
                    val file = File(args!![0] as String, "tse_export_before_delete_20260506_172401_874.tar").apply { writeBytes(archive) }
                    TseDto.ExportAndDeleteResultDto(
                        success = true,
                        archiveFileName = file.name,
                        archiveFilePath = file.path,
                        archiveSizeBytes = file.length(),
                        usedBlocksBefore = 1526,
                        exportSizeBytesBefore = archive.size.toLong(),
                        message = "TSE exported and erased.",
                    )
                }
                "toString" -> "Device"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException("TseAdminProvider.${method.name} is not used here")
            }
        } as TseAdminProvider

    private val noDevice = deviceWriting(ByteArray(0))

    private fun archive(): ByteArray =
        TseTarFixture.tar(
            listOf(
                TseTarFixture.logMessage(0xAB, tanr = 0, counter = 1641, logTime = 1_778_000_000),
                TseTarFixture.logMessage(0xAB, tanr = 64, counter = 2000, logTime = 1_778_050_000),
                TseTarFixture.logMessage(0xAB, tanr = 100, counter = 2398, logTime = 1_778_081_041),
            ),
        )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stored() = transaction { TseExportArchiveRepositoryImpl().findBySerial(serialHex) }

    private fun auditRows() = transaction { TransactionAuditLog.all().filter { it.operation == "REGISTER_TSE_ARCHIVE" }.toList() }

    private fun actor(): String =
        transaction {
            TestData
                .createAdmin()
                .id.value
                .toString()
        }

    @Test
    fun `a registration is audited as REGISTER_TSE_ARCHIVE naming the register entry and the file`() {
        File(runBlocking { exportDir().resolve() }, "a.tar").writeBytes(archive())
        val actor = actor()

        val dto = runBlocking { TseExportArchiveService(noDevice, exportDir()).register("a.tar", actor) }

        val row = auditRows().single()
        assertEquals("TSE_ARCHIVE", row.entityType)
        assertEquals(dto.id.toString(), row.entityId)
        assertEquals("a.tar", row.entityName)
        assertEquals(actor, row.employeeId)
        assertTrue(row.changesSummary.orEmpty().startsWith("SUCCESS"), row.changesSummary)
    }

    @Test
    fun `a refused registration writes a FAILED row naming the file`() {
        File(runBlocking { exportDir().resolve() }, "notes.tar").writeBytes("not a tar".toByteArray())

        runCatching { runBlocking { TseExportArchiveService(noDevice, exportDir()).register("notes.tar", actor()) } }

        val row = auditRows().single()
        assertEquals("notes.tar", row.entityId)
        assertTrue(row.changesSummary.orEmpty().startsWith("FAILED"), row.changesSummary)
    }

    @Test
    fun `an existing archive is registered with its device, ranges, size and SHA-256`() {
        val bytes = archive()
        File(exportDir().let { runBlocking { it.resolve() } }, "tse_export_before_delete_old.tar").writeBytes(bytes)

        val dto = runBlocking { TseExportArchiveService(noDevice, exportDir()).register("tse_export_before_delete_old.tar", "emp-1") }

        assertEquals(serialHex, dto.tseSerialHex)
        assertEquals(0L, dto.tanrFrom)
        assertEquals(100L, dto.tanrTo)
        assertEquals(1641L, dto.signatureCounterFrom)
        assertEquals(2398L, dto.signatureCounterTo)
        assertEquals(1_778_000_000L, dto.logTimeFrom)
        assertEquals(1_778_081_041L, dto.logTimeTo)
        assertEquals(bytes.size.toLong(), dto.sizeBytes)
        assertEquals(sha256(bytes), dto.sha256)
        assertEquals(TseExportArchiveOrigin.REGISTERED, dto.origin)
        val row = stored().single()
        assertEquals("emp-1", row.createdByEmployeeId)
        assertTrue(row.filePath.endsWith("tse_export_before_delete_old.tar"), row.filePath)
    }

    @Test
    fun `the same archive cannot be registered twice`() {
        val folder = runBlocking { exportDir().resolve() }
        File(folder, "a.tar").writeBytes(archive())
        File(folder, "copy-of-a.tar").writeBytes(archive())
        val service = TseExportArchiveService(noDevice, exportDir())
        runBlocking { service.register("a.tar", null) }

        val e = assertFailsWith<PosException> { runBlocking { service.register("copy-of-a.tar", null) } }

        assertEquals(PosErrorCode.TSE_ARCHIVE_ALREADY_REGISTERED, e.code)
        assertEquals(HttpStatusCode.Conflict, e.httpStatus)
        assertEquals(1, stored().size)
    }

    @Test
    fun `a file holding no TSE log message is refused with 422 and nothing is stored`() {
        File(runBlocking { exportDir().resolve() }, "notes.tar").writeBytes("not a tar".toByteArray())

        val e = assertFailsWith<PosException> { runBlocking { TseExportArchiveService(noDevice, exportDir()).register("notes.tar", null) } }

        assertEquals(HttpStatusCode.UnprocessableEntity, e.httpStatus)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a name outside the export folder is refused`() {
        val e = assertFailsWith<PosException> { runBlocking { TseExportArchiveService(noDevice, exportDir()).register("../secret.tar", null) } }

        assertEquals(HttpStatusCode.BadRequest, e.httpStatus)
    }

    @Test
    fun `export-and-delete registers the archive it wrote`() {
        val bytes = archive()

        val result = runBlocking { TseExportArchiveService(deviceWriting(bytes), exportDir()).exportAndDelete("emp-2") }
        assertTrue(
            auditRows()
                .single()
                .changesSummary
                .orEmpty()
                .startsWith("SUCCESS"),
        )

        assertTrue(result.archiveRegistered)
        val row = assertNotNull(stored().singleOrNull())
        assertEquals(TseExportArchiveOrigin.EXPORT_AND_DELETE, row.origin)
        assertEquals(result.archiveFileName, row.fileName)
        assertEquals(sha256(bytes), row.sha256)
    }

    @Test
    fun `an archive that cannot be registered still answers the erase, flagged unregistered`() {
        // The device erased itself but the file it wrote holds no log message.
        val result = runBlocking { TseExportArchiveService(deviceWriting(ByteArray(2048)), exportDir()).exportAndDelete(null) }

        assertTrue(result.success)
        assertFalse(result.archiveRegistered)
        assertTrue(result.message.contains("register"), result.message)
        assertTrue(stored().isEmpty())
        // The erase happened, so the failed registration is on record, naming the archive file.
        val row = auditRows().single()
        assertEquals("tse_export_before_delete_20260506_172401_874.tar", row.entityId)
        assertTrue(row.changesSummary.orEmpty().startsWith("FAILED"), row.changesSummary)
    }
}
