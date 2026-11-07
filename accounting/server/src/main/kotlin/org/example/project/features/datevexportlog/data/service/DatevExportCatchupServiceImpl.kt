package org.example.project.features.datevexportlog.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.example.project.core.domain.dto.datevexportlog.CatchupFailureDto
import org.example.project.core.domain.dto.datevexportlog.CatchupPeriodDto
import org.example.project.core.domain.dto.datevexportlog.CatchupPreviewDto
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.error.PosErrorCode
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.repository.DatevExportLogRepository
import org.example.project.features.datevexportlog.domain.service.DatevExportCatchupService
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.kassenbuch.domain.service.DatevBuchungsstapelExportService
import org.example.project.features.kassenbuch.domain.service.DatevXmlExportService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import java.util.UUID

class DatevExportCatchupServiceImpl(
    private val logRepo: DatevExportLogRepository,
    private val xmlExport: DatevXmlExportService,
    private val apExport: DatevApExportService,
    private val buchExport: DatevBuchungsstapelExportService,
) : DatevExportCatchupService {
    override suspend fun preview(
        type: DatevExportType,
        cadence: DatevExportCadence,
    ): CatchupPreviewDto {
        requireSchedulable(type)
        val cursor = dbQuery { logRepo.findLatestEnd(type) }
        val now = currentTimeStamp()
        val periods = PeriodEnumerator.missingPeriods(cursor, now, cadence)
        return CatchupPreviewDto(periods.map(::periodDtoOf))
    }

    /**
     * Exports every period the log lacks, oldest first, and **stops at the first failure**: the
     * cursor is the log's latest `periodEnd`, so a later period exported past a failed one would
     * move the cursor over the gap and the failed period would never be retried (admin16 NOTE-7).
     * The untried periods come back as [CatchupResultDto.deferred].
     */
    override suspend fun execute(
        type: DatevExportType,
        cadence: DatevExportCadence,
        exportedByUserId: UUID,
    ): CatchupResultDto {
        requireSchedulable(type)
        val cursor = dbQuery { logRepo.findLatestEnd(type) }
        val now = currentTimeStamp()
        val periods = PeriodEnumerator.missingPeriods(cursor, now, cadence)

        val exported = mutableListOf<org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto>()
        val failed = mutableListOf<CatchupFailureDto>()

        for ((index, p) in periods.withIndex()) {
            // The existing export endpoints use ISO LocalDate strings (yyyy-MM-dd) where
            // endDate is the last included day (inclusive), while p.end is the exclusive
            // boundary. Subtract 1 day to convert.
            val startDate = p.start.date.toString()
            val endDate =
                p.end.date
                    .minus(DatePeriod(days = 1))
                    .toString()

            try {
                val dto =
                    when (type) {
                        DatevExportType.KASSENBUCH ->
                            xmlExport.export(
                                startDate,
                                endDate,
                                exportedByUserId,
                                p.label,
                                DatevExportSource.AUTO,
                            )
                        DatevExportType.AP_LEDGER -> apExport.export(startDate, endDate, exportedByUserId, p.label, DatevExportSource.AUTO)
                        DatevExportType.BUCHUNGSSTAPEL ->
                            buchExport.export(
                                startDate,
                                endDate,
                                exportedByUserId,
                                p.label,
                                DatevExportSource.AUTO,
                            )
                        // Refused up front by requireSchedulable; unreachable here.
                        DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN, DatevExportType.AR_LEDGER ->
                            throw notSchedulable(type)
                    }
                exported += dto
            } catch (e: PosException) {
                // A paused export folder (restored from another installation, not yet reviewed)
                // refuses every period alike; answer with the 409 instead of N identical failures.
                if (e.code == PosErrorCode.RESTORED_PATHS_NEED_REVIEW) throw e
                failed += failureOf(p, e)
            } catch (e: Exception) {
                failed += failureOf(p, e)
            }
            if (failed.isNotEmpty()) {
                return CatchupResultDto(exported, failed, deferred = periods.drop(index + 1).map(::periodDtoOf))
            }
        }
        return CatchupResultDto(exported, failed)
    }

    private fun periodDtoOf(period: Period) = CatchupPeriodDto(period.start.toString(), period.end.toString(), period.label)

    private fun failureOf(
        period: Period,
        e: Exception,
    ) = CatchupFailureDto(
        periodLabel = period.label,
        periodStart = period.start.toString(),
        periodEnd = period.end.toString(),
        error = e.message ?: e.toString(),
    )

    /**
     * Fail fast (23.5): a type with no schedule is refused before any period is enumerated, so the
     * caller gets a 400 instead of a 200 whose every period carries the same refusal.
     */
    private fun requireSchedulable(type: DatevExportType) {
        if (type in SCHEDULABLE_TYPES) return
        throw notSchedulable(type)
    }

    private fun notSchedulable(type: DatevExportType) =
        PosException(
            PosErrorCode.DATEV_EXPORT_TYPE_NOT_SCHEDULABLE,
            HttpStatusCode.BadRequest,
            when (type) {
                DatevExportType.BUCHUNGSSTAPEL_MIT_BELEGEN ->
                    "BUCHUNGSSTAPEL_MIT_BELEGEN is ad-hoc only: export it via GET /api/v1/datev/export/ad-hoc, " +
                        "it has no catch-up"
                else -> "$type has no scheduled export and therefore no catch-up"
            },
        )

    private companion object {
        /** The types a cadence can export — and therefore the only ones a catch-up can fill. */
        val SCHEDULABLE_TYPES =
            setOf(DatevExportType.KASSENBUCH, DatevExportType.AP_LEDGER, DatevExportType.BUCHUNGSSTAPEL)
    }
}
