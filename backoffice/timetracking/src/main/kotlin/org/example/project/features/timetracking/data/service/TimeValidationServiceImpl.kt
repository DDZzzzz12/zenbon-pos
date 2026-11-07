package org.example.project.features.timetracking.data.service

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import kotlinx.serialization.json.Json
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.utils.durationBetween
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.timetracking.domain.repository.TimeEntryRepository
import org.example.project.features.timetracking.domain.service.TimeValidationService
import org.slf4j.LoggerFactory
import java.util.UUID

class TimeValidationServiceImpl(
    private val timeEntryRepository: TimeEntryRepository,
    private val configManager: IShopConfigurationManager,
) : TimeValidationService {
    private val logger = LoggerFactory.getLogger(TimeValidationServiceImpl::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun validateDailyHours(
        employeeId: UUID,
        date: LocalDate,
        totalMinutes: Int,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (totalMinutes > 600) {
            warnings.add("Daily work time exceeds 10 hours (${totalMinutes / 60}h ${totalMinutes % 60}min). ArbZG §3 maximum is 10 hours.")
        }
        return warnings
    }

    override fun validateRestPeriod(
        employeeId: UUID,
        clockIn: LocalDateTime,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val yesterday =
            LocalDate(clockIn.date.year, clockIn.date.month.number, clockIn.date.day)
                .let { date ->
                    val epochDays = date.toEpochDays() - 1
                    LocalDate.fromEpochDays(epochDays)
                }

        val previousEntries =
            timeEntryRepository
                .findByEmployeeAndMonth(
                    employeeId,
                    yesterday.year,
                    yesterday.month.number,
                ).filter { it.date == yesterday && it.clockOut != null }

        if (previousEntries.isNotEmpty()) {
            val lastClockOut = previousEntries.maxOf { it.clockOut!! }
            val restMinutes = durationBetween(lastClockOut, clockIn).inWholeMinutes
            if (restMinutes < 660) {
                warnings.add("Rest period is only ${restMinutes / 60}h ${restMinutes % 60}min. ArbZG §5 requires at least 11 hours.")
            }
        }

        return warnings
    }

    override fun calculateEffectiveBreak(
        grossWorkMinutes: Int,
        stempledBreakMinutes: Int,
    ): Int {
        val rules = getBreakRules()

        val requiredBreak =
            rules
                .filter { grossWorkMinutes >= it.thresholdMinutes }
                .maxOfOrNull { rule ->
                    if (rule.repeating) {
                        rule.breakMinutes * (grossWorkMinutes / rule.thresholdMinutes)
                    } else {
                        rule.breakMinutes
                    }
                } ?: 0

        return maxOf(stempledBreakMinutes, requiredBreak)
    }

    private fun getBreakRules(): List<ShopConfigurationDto.BreakRuleEntry> =
        try {
            val configStr = configManager.getCachedSettingString(ConfigKey.BREAK_RULES_CONFIG)
            if (configStr != null) {
                json.decodeFromString<ShopConfigurationDto.BreakRulesConfig>(configStr).rules
            } else {
                defaultBreakRules()
            }
        } catch (e: Exception) {
            logger.warn("TimeValidationServiceImpl.getBreakRules: failed to load break rules from config, using defaults", e)
            defaultBreakRules()
        }

    private fun defaultBreakRules(): List<ShopConfigurationDto.BreakRuleEntry> =
        json
            .decodeFromString<ShopConfigurationDto.BreakRulesConfig>(
                ConfigKey.BREAK_RULES_CONFIG.defaultValue,
            ).rules
}
