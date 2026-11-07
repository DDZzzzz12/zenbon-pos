package org.example.project.features.tse.verification

import kotlinx.coroutines.test.runTest
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity
import org.example.project.core.domain.dto.tse.TseVerificationDto.Verdict
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.domain.models.TseExportArchiveRecord
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.example.project.tse.utils.TarLogMessageReader
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TseVerificationServiceTest {
    /**
     * A [TseExportDirectory] resolving to `<path>/tse`, backed by a fake IShopConfigurationManager
     * that answers only getDatevXmlExportPath (dynamic proxy — the interface has ~40 members, and
     * a resolver that reads exactly one of them doesn't warrant implementing the rest by hand).
     */
    private fun fakeExportDir(path: String): TseExportDirectory {
        @Suppress("UNCHECKED_CAST")
        val config =
            Proxy.newProxyInstance(
                IShopConfigurationManager::class.java.classLoader,
                arrayOf(IShopConfigurationManager::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    // No restore awaiting review: the export folder may be used.
                    "getRestoredPathsPendingReview" -> emptyMap<Any, Any>()
                    "getDatevXmlExportPath" -> path
                    else -> throw NotImplementedError(
                        "Stub: IShopConfigurationManager.${method.name} should not be called in TseVerificationService tests",
                    )
                }
            } as IShopConfigurationManager
        return TseExportDirectory(config)
    }

    /**
     * A minimal valid USTAR archive with one non-log member. It decodes to zero log-message
     * records, so verification FAILS with EMPTY_ARCHIVE. Built inline because pos-tse's
     * UstarArchive is internal and not visible here.
     */
    private fun tarWithInfoCsv(): ByteArray {
        val content = "component:,device\n".toByteArray()
        val header = ByteArray(512)
        "info.csv".toByteArray().copyInto(header, 0)
        "0000644 ".toByteArray().copyInto(header, 100)
        (content.size.toString(8).padStart(11, '0') + " ").toByteArray().copyInto(header, 124)
        "        ".toByteArray().copyInto(header, 148) // checksum field = spaces during compute
        header[156] = '0'.code.toByte() // typeflag: regular file
        "ustar".toByteArray().copyInto(header, 257)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        (checksum.toString(8).padStart(6, '0') + "  ").toByteArray().copyInto(header, 148)
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512)) // pad content to a full block
        out.write(ByteArray(1024)) // two zero blocks terminate
        return out.toByteArray()
    }

    @Test
    fun `verifies a file inside the exports directory`(
        @TempDir dir: File,
    ) {
        runTest {
            val exportDir = fakeExportDir(dir.path)
            File(exportDir.resolve(), "export.tar").writeBytes(tarWithInfoCsv())
            val service = TseVerificationService(exportDir)
            val report = service.verifyExport("export.tar")
            // An info.csv-only archive holds no log message: it proves nothing and must not pass.
            assertEquals(Verdict.FAIL, report.verdict)
            assertTrue(report.findings.any { it.code == FindingCode.EMPTY_ARCHIVE })
        }
    }

    @Test
    fun `a 0-byte file never verifies as PASS`(
        @TempDir dir: File,
    ) {
        runTest {
            // What a failed filtered export used to leave behind (real-TSE QA rt12: /verify → PASS).
            val exportDir = fakeExportDir(dir.path)
            File(exportDir.resolve(), "empty.tar").writeBytes(ByteArray(0))

            val report = TseVerificationService(exportDir).verifyExport("empty.tar")

            assertEquals(Verdict.FAIL, report.verdict)
            assertEquals(FindingCode.EMPTY_ARCHIVE, report.findings.single { it.severity == Severity.CRITICAL }.code)
        }
    }

    @Test
    fun `a file that is not a TAR is an empty archive, not a 500`(
        @TempDir dir: File,
    ) {
        runTest {
            val exportDir = fakeExportDir(dir.path)
            File(exportDir.resolve(), "junk.tar").writeBytes("not a tar archive at all".toByteArray())

            val report = TseVerificationService(exportDir).verifyExport("junk.tar")

            assertEquals(Verdict.FAIL, report.verdict)
            assertTrue(report.findings.any { it.code == FindingCode.EMPTY_ARCHIVE })
        }
    }

    @Test
    fun `rejects a path that escapes the exports directory`(
        @TempDir dir: File,
    ) {
        runTest {
            val service = TseVerificationService(fakeExportDir(dir.path))
            assertFailsWith<PosException> { service.verifyExport("../secret.tar") }
        }
    }

    @Test
    fun `rejects a missing file`(
        @TempDir dir: File,
    ) {
        runTest {
            val service = TseVerificationService(fakeExportDir(dir.path))
            assertFailsWith<PosException> { service.verifyExport("nope.tar") }
        }
    }

    // ---------------------------------------------------------------------------------------
    // POS reconciliation. PosReconciliationTest covers the pure comparison; these two prove the
    // service actually appends its findings to the report and re-derives the verdict from the
    // combined list — a wiring nobody would notice breaking otherwise.
    // ---------------------------------------------------------------------------------------

    /** The serial the synthetic transaction log carries: 32 bytes of 0xAB, as the parser reports it. */
    private val fixtureSerialHex = "AB".repeat(32)

    private fun recordTanr(
        tanr: Long,
        client: String = "TER-001",
    ) {
        transaction {
            TseTransaction.new {
                tseSerialNumber = fixtureSerialHex
                tseSerialHex = fixtureSerialHex
                transactionNumber = tanr
                tseMode = TseMode.NORMAL
                startTime = currentTimeStamp()
                clientId = client
            }
        }
    }

    @Test
    fun `a transaction number the POS never recorded is appended as a finding and lowers the verdict`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                // The device is known to the POS (TANR 1), but 42 — the one in the export — is not.
                recordTanr(1)
                val exportDir = fakeExportDir(dir.path)
                File(exportDir.resolve(), "export.tar").writeBytes(tarWith(buildTransactionLogMessage()))

                val report = TseVerificationService(exportDir).verifyExport("export.tar")

                // The log alone is clean: one transaction, no counter gaps, no regression.
                assertEquals(
                    Verdict.PASS,
                    LogSequenceVerifier.verify(TarLogMessageReader.read(File(exportDir.resolve(), "export.tar").readBytes())).verdict,
                    "the sequence checks alone find nothing — the verdict below comes from the reconciliation",
                )
                val reconciled = report.findings.filter { it.code == FindingCode.TANR_WITHOUT_POS_RECORD }
                assertEquals(1, reconciled.size, "the POS-reconciliation finding is appended to the report")
                assertTrue(reconciled.single().detail!!.contains("tanr=42"), reconciled.single().detail!!)
                assertEquals(Verdict.PASS_WITH_WARNINGS, report.verdict, "the verdict is re-derived from the combined findings")
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `a transaction number the POS did record produces no reconciliation finding`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                recordTanr(42)
                val exportDir = fakeExportDir(dir.path)
                File(exportDir.resolve(), "export.tar").writeBytes(tarWith(buildTransactionLogMessage()))

                val report = TseVerificationService(exportDir).verifyExport("export.tar")

                assertEquals(emptyList(), report.findings.filter { it.code == FindingCode.TANR_WITHOUT_POS_RECORD })
                assertEquals(Verdict.PASS, report.verdict)
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `POS-signed transactions inside the export's TANR span but missing from it fail the export`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                // The POS signed 41..44 on this device; the export carries only 41 and 44 — the
                // shape of a filtered export that kept another client's transactions (QA rt12).
                (41L..44L).forEach { recordTanr(it) }
                val exportDir = fakeExportDir(dir.path)
                File(exportDir.resolve(), "export.tar").writeBytes(
                    tarWithAll(
                        listOf(
                            buildTransactionLogMessage(tanr = 41, signatureCounter = 10),
                            buildTransactionLogMessage(tanr = 44, signatureCounter = 11),
                        ),
                    ),
                )

                val report = TseVerificationService(exportDir).verifyExport("export.tar")

                val missing = report.findings.single { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT }
                assertEquals(Severity.CRITICAL, missing.severity)
                assertTrue(missing.detail!!.contains("missing=42,43"), missing.detail!!)
                assertEquals(Verdict.FAIL, report.verdict)
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Filtered exports (real-TSE QA rt14 a). TANRs and signature counters are shared by every client
    // of a TSE, so a correct single-client export has holes in both. The filter recorded beside the
    // file tells verify what the export was asked for.
    // ---------------------------------------------------------------------------------------

    /**
     * The POS signed 41..44 alternately on TER-001 (41, 43) and TER-003 (42, 44); TER-001's export
     * holds its own two, with the signature counters the other client's transactions sit between.
     */
    private suspend fun singleClientExport(
        exportDir: TseExportDirectory,
        withSidecar: TseExportSidecars.Filter?,
        tanrs: List<Int> = listOf(41, 43),
    ): String {
        recordTanr(41, "TER-001")
        recordTanr(42, "TER-003")
        recordTanr(43, "TER-001")
        recordTanr(44, "TER-003")
        val tar = File(exportDir.resolve(), "ter001.tar")
        tar.writeBytes(tarWithAll(tanrs.map { buildTransactionLogMessage(tanr = it, signatureCounter = 10L + 4 * (it - 41), client = "TER-001") }))
        if (withSidecar != null) {
            TseExportSidecars().record(
                TseDto.ExportResultDto(success = true, exportType = "filtered_transaction", filePath = tar.path, fileName = tar.name),
                withSidecar,
            )
        }
        return tar.name
    }

    private val ter001Tanr41To44 = TseExportSidecars.Filter(clientId = "TER-001", startTransaction = 41, endTransaction = 44)

    @Test
    fun `a correct single-client export verifies PASS and says which filter it was judged under`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                val exportDir = fakeExportDir(dir.path)
                val name = singleClientExport(exportDir, withSidecar = ter001Tanr41To44)

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.PASS, report.verdict, report.findings.toString())
                val applied = report.findings.single { it.code == FindingCode.EXPORT_FILTER_APPLIED }
                assertEquals(Severity.INFO, applied.severity)
                assertTrue(applied.message.contains("client TER-001 only"), applied.message)
                assertTrue(applied.message.contains("TANR 41–44"), applied.message)
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `the same file without its filter record is judged as an all-client export, as before`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                val exportDir = fakeExportDir(dir.path)
                val name = singleClientExport(exportDir, withSidecar = null)

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.FAIL, report.verdict)
                assertTrue(report.findings.any { it.code == FindingCode.TRANSACTION_NUMBER_GAP })
                assertTrue(report.findings.any { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT })
                assertTrue(report.findings.none { it.code == FindingCode.EXPORT_FILTER_APPLIED })
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `an all-client filter keeps today's strictness`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                val exportDir = fakeExportDir(dir.path)
                val allClients = TseExportSidecars.Filter(clientId = null, startTransaction = 41, endTransaction = 44)
                val name = singleClientExport(exportDir, withSidecar = allClients)

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.FAIL, report.verdict)
                assertTrue(report.findings.any { it.code == FindingCode.TRANSACTION_NUMBER_GAP })
                val missing = report.findings.single { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT }
                assertTrue(missing.detail!!.contains("missing=42"), missing.detail!!)
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `a single-client export missing one of its own transactions is CRITICAL`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                recordTanr(45, "TER-001")
                val exportDir = fakeExportDir(dir.path)
                // TER-001 signed 41, 43 and 45 inside the requested 41..45; the file lacks 45, its last —
                // outside the span the file itself reaches, so only the requested range can catch it.
                val filter = TseExportSidecars.Filter(clientId = "TER-001", startTransaction = 41, endTransaction = 45)
                val name = singleClientExport(exportDir, withSidecar = filter)

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.FAIL, report.verdict)
                val missing = report.findings.single { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT }
                assertEquals(Severity.CRITICAL, missing.severity)
                assertTrue(missing.detail!!.endsWith("missing=45"), missing.detail!!)
                assertTrue(report.findings.none { it.code == FindingCode.TRANSACTION_NUMBER_GAP }, "other clients' TANRs are not holes")
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `a filter record that does not match its file is ignored, with a warning`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                val exportDir = fakeExportDir(dir.path)
                val name = singleClientExport(exportDir, withSidecar = ter001Tanr41To44)
                // The file changes after the filter was recorded.
                File(exportDir.resolve(), name).appendBytes(ByteArray(512))

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.FAIL, report.verdict)
                assertTrue(report.findings.any { it.code == FindingCode.FILTER_SIDECAR_MISMATCH && it.severity == Severity.WARNING })
                assertTrue(report.findings.any { it.code == FindingCode.TRANSACTION_NUMBER_GAP })
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Real-TSE QA finding (e): a single-client export's requested range reached back into entries an
    // export-and-delete erased. The all-client check reads its range from the file and never asked for
    // them; the single-client check asks for the whole requested range and failed CRITICAL.
    // ---------------------------------------------------------------------------------------

    private fun registerArchive(
        tanrs: LongRange,
        name: String = "tse_export_before_delete_20260506_172401_874.tar",
    ) {
        transaction {
            TseExportArchiveRepositoryImpl().insert(
                TseExportArchiveRecord(
                    fileName = name,
                    filePath = "/nowhere/$name",
                    tseSerialHex = fixtureSerialHex,
                    tanrFrom = tanrs.first,
                    tanrTo = tanrs.last,
                    signatureCounterFrom = null,
                    signatureCounterTo = null,
                    logTimeFrom = null,
                    logTimeTo = null,
                    sizeBytes = 1,
                    sha256 = "0".repeat(64),
                    origin = TseExportArchiveOrigin.EXPORT_AND_DELETE,
                    createdAt = currentTimeStamp(),
                    createdByEmployeeId = null,
                ),
            )
        }
    }

    /** TER-001 signed [signed]; the device (after an erase) still holds — and the file has — [inFile]. */
    private suspend fun ter001ExportAfterErase(
        exportDir: TseExportDirectory,
        signed: List<Long>,
        inFile: List<Int>,
    ): String {
        signed.forEach { recordTanr(it, "TER-001") }
        val tar = File(exportDir.resolve(), "ter001-after-erase.tar")
        tar.writeBytes(tarWithAll(inFile.map { buildTransactionLogMessage(tanr = it, signatureCounter = 100L + it, client = "TER-001") }))
        TseExportSidecars().record(
            TseDto.ExportResultDto(success = true, exportType = "filtered_transaction", filePath = tar.path, fileName = tar.name),
            TseExportSidecars.Filter(clientId = "TER-001", startTransaction = 41, endTransaction = 50),
        )
        return tar.name
    }

    @Test
    fun `a single-client export whose range reaches into archived erased entries passes and names the archive`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                registerArchive(0L..44L)
                val exportDir = fakeExportDir(dir.path)
                val name = ter001ExportAfterErase(exportDir, signed = listOf(41, 43, 47, 49), inFile = listOf(47, 49))

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.PASS, report.verdict, report.findings.toString())
                val info = report.findings.single { it.code == FindingCode.ERASED_ENTRIES_IN_ARCHIVE }
                assertEquals(Severity.INFO, info.severity)
                assertTrue(info.message.contains("tse_export_before_delete_20260506_172401_874.tar"), info.message)
                assertTrue(info.message.contains("TANR 41–43"), info.message)
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `a single-client export missing an own transaction no archive holds is still CRITICAL`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                // 41 and 43 were erased and archived; 45 was not erased and should still be on the device.
                registerArchive(0L..43L)
                val exportDir = fakeExportDir(dir.path)
                val name = ter001ExportAfterErase(exportDir, signed = listOf(41, 43, 45, 47, 49), inFile = listOf(47, 49))

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.FAIL, report.verdict)
                val missing = report.findings.single { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT }
                assertTrue(missing.detail!!.endsWith("missing=45"), missing.detail!!)
                assertTrue(report.findings.any { it.code == FindingCode.ERASED_ENTRIES_IN_ARCHIVE })
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `without a registered archive the erased transactions are CRITICAL`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                val exportDir = fakeExportDir(dir.path)
                val name = ter001ExportAfterErase(exportDir, signed = listOf(41, 43, 47, 49), inFile = listOf(47, 49))

                val report = TseVerificationService(exportDir).verifyExport(name)

                assertEquals(Verdict.FAIL, report.verdict)
                val missing = report.findings.single { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT }
                assertTrue(missing.detail!!.endsWith("missing=41,43"), missing.detail!!)
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    @Test
    fun `an all-client export is judged as before, archives or not`(
        @TempDir dir: File,
    ) {
        TestDatabase.setup()
        try {
            runTest {
                registerArchive(0L..44L)
                (47L..49L).forEach { recordTanr(it) }
                val exportDir = fakeExportDir(dir.path)
                val tar = File(exportDir.resolve(), "all-after-erase.tar")
                tar.writeBytes(tarWithAll(listOf(47, 49).map { buildTransactionLogMessage(tanr = it, signatureCounter = 100L + it) }))

                val report = TseVerificationService(exportDir).verifyExport(tar.name)

                // 48 lies inside the file's own span: required, missing, CRITICAL — no archive consulted.
                assertEquals(Verdict.FAIL, report.verdict)
                assertTrue(report.findings.none { it.code == FindingCode.ERASED_ENTRIES_IN_ARCHIVE })
                assertTrue(
                    report.findings
                        .single { it.code == FindingCode.POS_TRANSACTION_MISSING_FROM_EXPORT }
                        .detail!!
                        .endsWith("missing=48"),
                )
            }
        } finally {
            TestDatabase.teardown()
        }
    }

    /** A USTAR archive holding [content] as its single member (the member name is irrelevant — the reader parses content). */
    private fun tarWith(content: ByteArray): ByteArray = tarWithAll(listOf(content))

    /** A USTAR archive holding each of [contents] as a member. */
    private fun tarWithAll(contents: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        contents.forEachIndexed { i, content -> out.write(member("log$i.log", content)) }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun member(
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
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512))
        return out.toByteArray()
    }

    // --- Minimal DER encoder for one TR-03151 transaction LogMessage (mirrors pos-tse's own
    // Tr03153LogMessageParserTest fixture; that module's test code is not visible from here). ---

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

    private fun int(v: Long): ByteArray {
        if (v == 0L) return tlv(0x02, byteArrayOf(0))
        var x = v
        val out = ArrayList<Byte>()
        while (x > 0) {
            out.add(0, (x and 0xFF).toByte())
            x = x shr 8
        }
        if (out[0].toInt() and 0x80 != 0) out.add(0, 0)
        return tlv(0x02, out.toByteArray())
    }

    /** operationType StartTransaction, processType Kassenbeleg-V1, TANR [tanr] (< 128), serial 32×0xAB. */
    private fun buildTransactionLogMessage(
        tanr: Int = 42,
        logTime: Long = 1_784_000_000,
        signatureCounter: Long = 1234,
        client: String = "TER-001",
    ): ByteArray {
        val body =
            int(2) +
                tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x03, 0x07, 0x01, 0x01)) +
                tlv(0x80, "StartTransaction".toByteArray()) +
                tlv(0x81, client.toByteArray()) +
                tlv(0x82, byteArrayOf(0xDE.toByte(), 0xAD.toByte())) +
                tlv(0x83, "Kassenbeleg-V1".toByteArray()) +
                tlv(0x85, byteArrayOf(tanr.toByte())) +
                tlv(0x04, ByteArray(32) { 0xAB.toByte() }) +
                tlv(0x30, tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x01, 0x01, 0x04, 0x01, 0x03))) +
                int(signatureCounter) +
                int(logTime) +
                tlv(0x04, ByteArray(96) { 0xCD.toByte() })
        return tlv(0x30, body)
    }
}
