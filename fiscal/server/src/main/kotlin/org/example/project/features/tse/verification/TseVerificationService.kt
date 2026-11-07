package org.example.project.features.tse.verification

import kotlinx.datetime.TimeZone
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingCode
import org.example.project.core.domain.dto.tse.TseVerificationDto.FindingDto
import org.example.project.core.domain.dto.tse.TseVerificationDto.Severity
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.domain.repository.TseExportArchiveRepository
import org.example.project.features.tse.findings.TseTanrRepository
import org.example.project.features.tse.findings.TseTanrRepository.Companion.bySerial
import org.example.project.tse.utils.TarLogMessageReader
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.getTimeZone
import org.slf4j.LoggerFactory
import kotlin.time.Instant

/**
 * Verifies a TSE export TAR that lives inside the resolved TSE exports directory
 * ([TseExportDirectory]). The file is named, not path-supplied, and must resolve within that
 * directory — anything escaping it is rejected.
 *
 * Beyond the log's own sequence ([LogSequenceVerifier]) the export is compared with the POS both
 * ways: TSE transactions the POS never recorded ([PosReconciliation], WARNING) and POS-signed
 * transactions inside the export's range that the export lacks ([ExportCoverage], CRITICAL). A file
 * holding no log message at all is CRITICAL (`EMPTY_ARCHIVE`).
 *
 * A filtered export carries its filter in a sidecar ([TseExportSidecars]). When the sidecar matches
 * the file, a single-client export is judged as one: the TANR and signature-counter holes the other
 * clients leave are not findings, and completeness is checked against that client's POS records over
 * the requested range. Without a (matching) sidecar the file is judged as an unfiltered export.
 */
class TseVerificationService(
    private val exportDir: TseExportDirectory,
    private val tanrs: TseTanrRepository = TseTanrRepository(),
    private val zone: () -> TimeZone = { getTimeZone() },
    private val sidecars: TseExportSidecars = TseExportSidecars(),
    private val archives: TseExportArchiveRepository = TseExportArchiveRepositoryImpl(),
) {
    private val logger = LoggerFactory.getLogger(TseVerificationService::class.java)

    suspend fun verifyExport(fileName: String): TseVerificationDto.ReportDto {
        val target = exportDir.resolveFile(fileName)
        val bytes = target.readBytes()
        val records = readRecords(bytes)
        if (records.isEmpty()) return emptyArchiveReport(target.length())
        val sidecar = sidecars.read(target)
        val filter = sidecar?.takeIf { it.sha256.equals(sha256Hex(bytes), ignoreCase = true) }
        val base = LogSequenceVerifier.verify(records, singleClient = filter?.isSingleClient == true)
        val posRows = dbQuery { tanrs.rows() }
        val archived = if (filter?.isSingleClient == true) archivedRanges(records) else emptyList()
        val findings =
            base.findings +
                PosReconciliation.reconcile(records, posRows.bySerial()) +
                ExportCoverage.missingPosTransactions(records, posRows, zone(), filter, archived) +
                listOfNotNull(filter?.let(::filterAppliedFinding), sidecar?.takeIf { filter == null }?.let(::sidecarMismatchFinding))
        return base.copy(findings = findings, verdict = LogSequenceVerifier.verdictOf(findings))
    }

    /** What the registered archives hold for the devices in [records] (real-TSE QA finding e). */
    private suspend fun archivedRanges(records: List<TseDto.ParsedLogMessageDto>): List<ArchivedTanrRange> {
        val serials = records.mapNotNull { it.serialNumberHex?.uppercase()?.takeIf(String::isNotBlank) }.toSet()
        return dbQuery {
            serials.flatMap { serial ->
                archives.findBySerial(serial).mapNotNull { archive ->
                    val from = archive.tanrFrom ?: return@mapNotNull null
                    val to = archive.tanrTo ?: return@mapNotNull null
                    ArchivedTanrRange(archive.tseSerialHex.uppercase(), from..to, archive.fileName)
                }
            }
        }
    }

    /** Says which filter the verdict was reached under, so a PASS is never read as "this is the whole device". */
    private fun filterAppliedFinding(filter: TseExportFilterSidecar): FindingDto {
        val client = filter.clientId?.takeIf(String::isNotBlank)
        val range =
            when {
                filter.startTransaction != null -> "TANR ${filter.startTransaction}–${filter.endTransaction}"
                filter.startEpochMillis != null -> "time ${Instant.fromEpochMilliseconds(
                    filter.startEpochMillis,
                )}–${filter.endEpochMillis?.let(Instant::fromEpochMilliseconds)}"
                else -> "no range"
            }
        val scope =
            if (client != null) {
                "client $client only; TANR and signature-counter holes left by other clients are expected and were not " +
                    "reported, and completeness was checked against the POS's own transactions of $client"
            } else {
                "all clients; checked as strictly as an unfiltered export"
            }
        return FindingDto(
            Severity.INFO,
            FindingCode.EXPORT_FILTER_APPLIED,
            "Filtered export ($range, requested ${filter.requestedAt}): $scope.",
            "clientId=${client ?: "ALL"};startTransaction=${filter.startTransaction};endTransaction=${filter.endTransaction};" +
                "startEpochMillis=${filter.startEpochMillis};endEpochMillis=${filter.endEpochMillis}",
        )
    }

    private fun sidecarMismatchFinding(sidecar: TseExportFilterSidecar): FindingDto =
        FindingDto(
            Severity.WARNING,
            FindingCode.FILTER_SIDECAR_MISMATCH,
            "The filter record beside this file does not match it (SHA-256 differs); the file was verified as an unfiltered export.",
            "sidecarSha256=${sidecar.sha256}",
        )

    /** An archive that does not parse as a TAR holds no log message we can show — same as an empty one. */
    private fun readRecords(bytes: ByteArray): List<TseDto.ParsedLogMessageDto> =
        try {
            TarLogMessageReader.read(bytes)
        } catch (e: Exception) {
            logger.warn("TseVerificationService: export does not parse as a TSE TAR", e)
            emptyList()
        }

    /**
     * A file with no TSE log message — 0 bytes (what a failed filtered export used to leave behind),
     * not a TAR, or a TAR carrying only `info.csv` — proves nothing about any transaction. It used to
     * verify PASS, the one verdict it must never get.
     */
    private fun emptyArchiveReport(size: Long): TseVerificationDto.ReportDto {
        val findings =
            LogSequenceVerifier.verify(emptyList()).findings +
                FindingDto(
                    Severity.CRITICAL,
                    FindingCode.EMPTY_ARCHIVE,
                    "The file holds no TSE log message ($size bytes); it proves nothing about any transaction.",
                    "bytes=$size",
                )
        return TseVerificationDto.ReportDto(
            verdict = LogSequenceVerifier.verdictOf(findings),
            recordCount = 0,
            transactionCount = 0,
            findings = findings,
        )
    }
}
