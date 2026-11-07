package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.repository.TseExportArchiveRepositoryImpl
import org.example.project.features.tse.domain.models.TseExportArchiveRecord
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.features.tse.domain.repository.TseExportArchiveRepository
import org.example.project.features.tse.verification.TseArchiveSummary
import org.example.project.features.tse.verification.TseExportDirectory
import org.example.project.features.tse.verification.sha256Hex
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.utils.TarLogMessageReader
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.log.AuditEntityRef
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The TSE archive register (real-TSE QA rt14). After an export-and-delete the archive it wrote is the
 * only copy of the erased entries; the DSFinV-K export used to refuse such a period claiming the device
 * had been swapped. Recording every archive — its device, ranges and SHA-256 — lets that export ship it.
 *
 * [exportAndDelete] registers the archive it writes; [register] enters one that is already in the TSE
 * export folder (one written before the register existed, or restored from a backup).
 */
class TseExportArchiveService(
    private val adminProvider: TseAdminProvider,
    private val exportDir: TseExportDirectory,
    private val repository: TseExportArchiveRepository = TseExportArchiveRepositoryImpl(),
) {
    private val logger = LoggerFactory.getLogger(TseExportArchiveService::class.java)

    /**
     * Exports the TSE to an archive, erases it, and registers the archive. The erase has already
     * happened when the registration runs, so a registration failure never fails the call: the reply
     * says `archiveRegistered = false` and the operator registers the file by hand.
     */
    suspend fun exportAndDelete(employeeId: String?): TseDto.ExportAndDeleteResultDto {
        val result = adminProvider.deleteExportedData(outputPath = exportDir.resolve().path)
        val registered =
            try {
                registerFile(File(result.archiveFilePath), TseExportArchiveOrigin.EXPORT_AND_DELETE, employeeId)
                true
            } catch (e: Exception) {
                // The REGISTER_TSE_ARCHIVE FAILED row naming the file is written by registerFile.
                logger.error(
                    "TseExportArchiveService: the TSE was erased but archive {} could not be registered; register it by hand",
                    result.archiveFileName,
                    e,
                )
                false
            }
        if (registered) return result.copy(archiveRegistered = true)
        return result.copy(
            archiveRegistered = false,
            message =
                "${result.message} The archive could not be entered in the archive register; register " +
                    "${result.archiveFileName} by hand, or a DSFinV-K export of the deleted period cannot carry its TSE data.",
        )
    }

    /**
     * Registers the archive [fileName] in the TSE export folder.
     *
     * @throws PosException 400/404 for a name outside the folder or without a file, 422 for a file
     *   that is not one TSE's archive, 409 `TSE_ARCHIVE_ALREADY_REGISTERED` for one already registered.
     */
    suspend fun register(
        fileName: String,
        employeeId: String?,
    ): TseDto.ExportArchiveDto = registerFile(exportDir.resolveFile(fileName), TseExportArchiveOrigin.REGISTERED, employeeId)

    /**
     * Audited as REGISTER_TSE_ARCHIVE on success and on refusal: what the register holds decides what
     * a DSFinV-K package ships, so who entered which archive — or failed to — must be on record. A
     * failed row names the archive file, since no register id exists yet.
     */
    private suspend fun registerFile(
        file: File,
        origin: TseExportArchiveOrigin,
        employeeId: String?,
    ): TseDto.ExportArchiveDto =
        ExceptionConverter.catchingThrows(
            context = "registerTseArchive(file=${file.name}, origin=$origin)",
            employeeId = employeeId,
            operation = "REGISTER_TSE_ARCHIVE",
            entityType = "TSE_ARCHIVE",
            auditEntity = { dto: TseDto.ExportArchiveDto -> AuditEntityRef(id = dto.id.toString(), name = dto.fileName) },
            failedEntityId = file.name,
        ) { insertRecord(file, origin, employeeId) }

    private suspend fun insertRecord(
        file: File,
        origin: TseExportArchiveOrigin,
        employeeId: String?,
    ): TseDto.ExportArchiveDto {
        val bytes = file.readBytes()
        val sha256 = sha256Hex(bytes)
        val summary = summarize(file.name, bytes)
        return dbQuery {
            repository.findBySha256(sha256)?.let { throw alreadyRegistered(file.name, it) }
            repository
                .insert(
                    TseExportArchiveRecord(
                        fileName = file.name,
                        filePath = file.canonicalPath,
                        tseSerialHex = summary.serialHex,
                        tanrFrom = summary.tanrFrom,
                        tanrTo = summary.tanrTo,
                        signatureCounterFrom = summary.signatureCounterFrom,
                        signatureCounterTo = summary.signatureCounterTo,
                        logTimeFrom = summary.logTimeFrom,
                        logTimeTo = summary.logTimeTo,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = sha256,
                        origin = origin,
                        createdAt = currentTimeStamp(),
                        createdByEmployeeId = employeeId,
                    ),
                ).toDto()
        }.also { logger.info("TseExportArchiveService: registered {} ({}, TANR {}–{})", it.fileName, it.origin, it.tanrFrom, it.tanrTo) }
    }

    private fun summarize(
        fileName: String,
        bytes: ByteArray,
    ): TseArchiveSummary {
        val records =
            try {
                TarLogMessageReader.read(bytes)
            } catch (e: Exception) {
                logger.warn("TseExportArchiveService: {} does not parse as a TSE TAR", fileName, e)
                emptyList()
            }
        val summary =
            try {
                TseArchiveSummary.of(records)
            } catch (e: IllegalArgumentException) {
                throw notAnArchive(fileName, e.message ?: "it cannot be attributed to one TSE")
            }
        return summary ?: throw notAnArchive(fileName, "it holds no TSE log message")
    }

    private fun notAnArchive(
        fileName: String,
        reason: String,
    ) = PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.UnprocessableEntity,
        "$fileName is not a TSE archive that can be registered: $reason",
    )

    private fun alreadyRegistered(
        fileName: String,
        existing: TseExportArchiveRecord,
    ) = PosException(
        PosErrorCode.TSE_ARCHIVE_ALREADY_REGISTERED,
        HttpStatusCode.Conflict,
        "$fileName is already registered (as ${existing.fileName}, #${existing.id}, ${existing.origin})",
    )

    private fun TseExportArchiveRecord.toDto() =
        TseDto.ExportArchiveDto(
            id = id,
            fileName = fileName,
            tseSerialHex = tseSerialHex,
            tanrFrom = tanrFrom,
            tanrTo = tanrTo,
            signatureCounterFrom = signatureCounterFrom,
            signatureCounterTo = signatureCounterTo,
            logTimeFrom = logTimeFrom,
            logTimeTo = logTimeTo,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            origin = origin,
            createdAt = createdAt.toString(),
        )
}
