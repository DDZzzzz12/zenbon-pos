package org.example.project.features.scheduler.data.service.executors

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.features.contract.data.service.InstalmentGenerator
import org.example.project.features.scheduler.domain.service.ScheduledJobExecutor
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp

/**
 * Materialises the rolling window of contract instalments once a day.
 *
 * This is the only place a clock enters the feature: `today` comes from [currentTimeStamp], the
 * project's timezone-aware now, and everything below takes it as an argument so the arithmetic
 * stays testable without one.
 *
 * The generator is idempotent per `(contract_id, due_date)`, so a re-run after a restart reports
 * zero rather than doubling anybody's expected outgoings.
 */
class ContractInstalmentJobExecutor(
    private val generator: InstalmentGenerator,
) : ScheduledJobExecutor {
    override val type = ScheduledJobType.CONTRACT_INSTALMENTS
    override val historyRetention = 30
    override val labelKey = "SCHEDULER_JOB_TYPE_CONTRACT_INSTALMENTS"

    override suspend fun execute(parametersJson: String): String {
        val created = dbQuery { generator.generate(currentTimeStamp().date) }
        return "created $created contract instalments"
    }

    override fun validateParameters(parametersJson: String) { /* no params */ }

    override fun parameterSchema(): JsonElement = JsonObject(emptyMap())
}
