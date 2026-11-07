package org.example.project.features.scheduler.data.service.executors

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.dto.datevexportlog.CatchupResultDto
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportCatchupService
import org.example.project.features.scheduler.domain.service.JobPartiallyFailedException
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.features.setup.domain.service.ConfigurationService
import java.util.UUID

class DatevAutoExportJobExecutor(
    private val catchup: DatevExportCatchupService,
    private val config: ConfigurationService,
    private val systemUserId: UUID,
    private val shopId: Long = 1L,
) : ScheduledJobExecutor {
    override val type = ScheduledJobType.DATEV_AUTO_EXPORT
    override val historyRetention = 60
    override val labelKey = "SCHEDULER_JOB_TYPE_DATEV_AUTO_EXPORT"
    override val restoredPathsWritten = setOf(RestoredPathSetting.DATEV_EXPORT_PATH)

    /**
     * Runs the catch-up for every type whose cadence is not OFF. One type failing does not stop the
     * others, but the run then ends in [JobPartiallyFailedException] — recorded as FAILED with the
     * failed types and periods listed — never as a SUCCESS whose text mentions the failures
     * (admin16 NOTE-7). The failed periods stay unlogged, so the next run retries them.
     */
    override suspend fun execute(parametersJson: String): String {
        val cfg = config.getDatevXmlConfig(shopId)
        val plan =
            listOf(
                DatevExportType.KASSENBUCH to cfg.kassenbuchCadence,
                DatevExportType.AP_LEDGER to cfg.apLedgerCadence,
                DatevExportType.BUCHUNGSSTAPEL to cfg.buchungsstapelCadence,
            )
        val outcomes =
            plan.map { (type, cadence) ->
                when (cadence) {
                    DatevAutoCadence.OFF -> TypeOutcome("$type: skipped(OFF)", emptyList())
                    else -> runType(type, cadence)
                }
            }
        val summary = outcomes.joinToString(", ") { it.summary }
        val failures = outcomes.flatMap { it.failures }
        if (failures.isNotEmpty()) throw JobPartiallyFailedException(summary, failures)
        return summary
    }

    private suspend fun runType(
        type: DatevExportType,
        cadence: DatevAutoCadence,
    ): TypeOutcome =
        try {
            val mapped =
                when (cadence) {
                    DatevAutoCadence.WEEKLY -> DatevExportCadence.WEEKLY
                    DatevAutoCadence.MONTHLY -> DatevExportCadence.MONTHLY
                    DatevAutoCadence.OFF -> error("unreachable")
                }
            outcomeOf(type, catchup.execute(type, mapped, systemUserId))
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.simpleName
            TypeOutcome("$type: failed($reason)", listOf("$type: $reason"))
        }

    private fun outcomeOf(
        type: DatevExportType,
        result: CatchupResultDto,
    ): TypeOutcome {
        val summary =
            buildString {
                append("$type: ${result.exported.size} periods")
                if (result.failed.isNotEmpty()) append(", ${result.failed.size} failed")
                if (result.deferred.isNotEmpty()) append(", ${result.deferred.size} deferred")
            }
        val failures =
            result.failed.map { "$type ${it.periodLabel}: ${it.error}" } +
                result.deferred.map { "$type ${it.periodLabel}: not attempted (an earlier period failed)" }
        return TypeOutcome(summary, failures)
    }

    /** One export type's line in the run's result, and what of it failed. */
    private data class TypeOutcome(
        val summary: String,
        val failures: List<String>,
    )

    override fun validateParameters(parametersJson: String) { /* no parameters */ }

    override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
}
