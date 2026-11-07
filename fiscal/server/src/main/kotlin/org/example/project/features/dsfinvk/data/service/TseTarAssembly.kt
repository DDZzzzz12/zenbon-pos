package org.example.project.features.dsfinvk.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.export.dsfinvk.model.TseTarFile
import org.example.project.features.tse.domain.models.TseExportArchiveRecord
import org.example.project.features.tse.domain.repository.TseExportArchiveRepository
import org.example.project.features.tse.verification.sha256Hex
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.utils.TarLogMessageReader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Chooses the TSE TARs a DSFinV-K package ships so that together they hold every signed transaction
 * of the period (checklist 28.4).
 *
 * The attached device's full export comes first. When it does not cover the period, the registered
 * archives ([TseExportArchiveRepository]) of the uncovered devices are added — an export-and-delete
 * erases the device, and its archive is then the only copy of those entries (real-TSE QA rt14). An
 * archive is shipped only if its file still hashes to the SHA-256 it was registered with, and only if
 * it holds at least one transaction the device export lacks.
 *
 * What stays uncovered is refused with 409 `TSE_EXPORT_INCOMPLETE`, worded by what the device export
 * shows: entries missing BELOW the lowest transaction the attached device still holds were deleted from
 * it, so the operator is told to register or restore the archive; a device the attached TSE's export
 * does not contain at all keeps the swapped-device explanation. Requires an ambient transaction.
 */
class TseTarAssembly(
    private val archives: TseExportArchiveRepository,
    private val readFile: (String) -> ByteArray? = { path -> File(path).takeIf(File::isFile)?.readBytes() },
) {
    private val logger = LoggerFactory.getLogger(TseTarAssembly::class.java)

    /**
     * @param device the attached device's full export
     * @param required the period's signed TANRs by upper-case hex serial
     * @param recordedAt when the POS recorded a signed `(serial, TANR)`, to name the uncovered time range
     * @return [device] followed by the archives needed to cover [required]
     */
    fun assemble(
        device: TseTarFile,
        required: Map<String, Set<Long>>,
        recordedAt: (serial: String, tanr: Long) -> LocalDateTime?,
    ): List<TseTarFile> {
        if (required.isEmpty()) return listOf(device)
        val deviceRecords = read(device.bytes, device.fileName)
        val deviceGaps = TseTarCoverage.gaps(required, deviceRecords)
        if (deviceGaps.isEmpty()) return listOf(device)

        val problems = mutableListOf<String>()
        val shipped = mutableListOf<TseTarFile>()
        var records = deviceRecords
        deviceGaps.forEach { gap ->
            candidatesFor(gap).forEach { archive ->
                val stillMissing = TseTarCoverage.gaps(mapOf(gap.serialHex to gap.missingTanrs.toSet()), records)
                if (stillMissing.isEmpty()) return@forEach
                val tar = intactFile(archive, problems) ?: return@forEach
                val archiveRecords = read(tar.bytes, tar.fileName)
                val adds = TseTarCoverage.gaps(mapOf(gap.serialHex to stillMissing.single().missingTanrs.toSet()), archiveRecords)
                val coversSome = adds.isEmpty() || adds.single().missingTanrs.size < stillMissing.single().missingTanrs.size
                if (coversSome) {
                    shipped += tar
                    records = records + archiveRecords
                }
            }
        }

        val remaining = TseTarCoverage.gaps(required, records)
        if (remaining.isEmpty()) {
            logger.info("TseTarAssembly: device export completed by archive(s) {}", shipped.map { it.fileName })
            return listOf(device) + shipped
        }
        throw incomplete(remaining, deviceRecords, recordedAt, problems)
    }

    /** The registered archives of [gap]'s device whose TANR range reaches the missing transactions. */
    private fun candidatesFor(gap: TseTarCoverageGap): List<TseExportArchiveRecord> {
        val lowest = gap.missingTanrs.first()
        val highest = gap.missingTanrs.last()
        return archives.findBySerial(gap.serialHex).filter { archive ->
            val from = archive.tanrFrom ?: return@filter false
            val to = archive.tanrTo ?: return@filter false
            from <= highest && to >= lowest
        }
    }

    /** The archive's bytes when its file is there and unchanged; otherwise null, with the reason in [problems]. */
    private fun intactFile(
        archive: TseExportArchiveRecord,
        problems: MutableList<String>,
    ): TseTarFile? {
        val bytes = readFile(archive.filePath)
        if (bytes == null) {
            problems += "registered archive ${archive.fileName} is missing at ${archive.filePath}"
            return null
        }
        if (!sha256Hex(bytes).equals(archive.sha256, ignoreCase = true)) {
            logger.warn("TseTarAssembly: archive {} no longer matches its registered SHA-256; not shipped", archive.fileName)
            problems += "registered archive ${archive.fileName} has changed since it was registered (SHA-256 differs) and is not shipped"
            return null
        }
        return TseTarFile(archive.fileName, bytes)
    }

    private fun read(
        bytes: ByteArray,
        fileName: String,
    ): List<TseDto.ParsedLogMessageDto> =
        try {
            TarLogMessageReader.read(bytes)
        } catch (e: Exception) {
            logger.warn("TseTarAssembly: {} could not be parsed as a TSE TAR", fileName, e)
            emptyList()
        }

    private fun incomplete(
        gaps: List<TseTarCoverageGap>,
        deviceRecords: List<TseDto.ParsedLogMessageDto>,
        recordedAt: (String, Long) -> LocalDateTime?,
        problems: List<String>,
    ): PosException {
        val attached = deviceRecords.mapNotNull { it.serialNumberHex?.uppercase() }.toSet()
        val lowestOnDevice =
            deviceRecords
                .filter { it.transactionNumber != null }
                .groupBy({ it.serialNumberHex!!.uppercase() }, { it.transactionNumber!! })
                .mapValues { (_, tanrs) -> tanrs.min() }
        val parts =
            gaps.map { gap ->
                val signed = signedRange(gap, recordedAt)
                val base = TseTarCoverage.describe(listOf(gap)) + signed
                val lowest = lowestOnDevice[gap.serialHex]
                val deleted = gap.serialHex in attached && (lowest == null || gap.missingTanrs.last() < lowest)
                when {
                    deleted ->
                        "$base. These entries were deleted from this TSE (export-and-delete) and no registered, " +
                            "intact archive holds them: keep that export-and-delete archive (tse_export_before_delete_*.tar) " +
                            "in the TSE export folder and register it (TSE → Export → Archiv registrieren), or restore " +
                            "the registered file unchanged"
                    gap.serialHex in attached ->
                        "$base. The attached TSE's export does not hold them and no registered, intact archive does"
                    else ->
                        "$base. The attached TSE is not the device that signed these transactions (swapped, wiped, " +
                            "or a virtual TSE restarted without its log). Export the TAR from the signing device and " +
                            "deliver it with the package, or register that device's archive"
                }
            }
        val notes = if (problems.isEmpty()) "" else " (${problems.joinToString("; ")})"
        val description = parts.joinToString("; ")
        logger.warn("TseTarAssembly: TSE TARs do not cover the period: {}{}", description, notes)
        return PosException(
            PosErrorCode.TSE_EXPORT_INCOMPLETE,
            HttpStatusCode.Conflict,
            "TSE TAR does not cover the exported period: $description$notes. Or request the export with " +
                "allowMissingTseTar=true to explicitly ship WITHOUT TSE data",
        )
    }

    /** ", signed 2026-05-06T09:12 – 2026-05-06T17:20" from the POS's records of the missing TANRs; empty when unknown. */
    private fun signedRange(
        gap: TseTarCoverageGap,
        recordedAt: (String, Long) -> LocalDateTime?,
    ): String {
        val times = gap.missingTanrs.mapNotNull { recordedAt(gap.serialHex, it) }
        if (times.isEmpty()) return ""
        return ", signed ${times.min()} – ${times.max()}"
    }
}
