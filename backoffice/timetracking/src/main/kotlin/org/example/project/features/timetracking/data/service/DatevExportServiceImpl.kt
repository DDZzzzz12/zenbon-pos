package org.example.project.features.timetracking.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.timetracking.DatevExportDto
import org.example.project.core.domain.enums.timetracking.TimeEntryStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.durationBetween
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.timetracking.domain.models.TimeEntry
import org.example.project.features.timetracking.domain.repository.TimeEntryRepository
import org.example.project.features.timetracking.domain.repository.WageTypeRepository
import org.example.project.features.timetracking.domain.service.DatevExportService
import org.example.project.features.timetracking.domain.service.TimeValidationService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.slf4j.LoggerFactory
import java.util.UUID

class DatevExportServiceImpl(
    private val timeEntryRepository: TimeEntryRepository,
    private val wageTypeRepository: WageTypeRepository,
    private val employeeRepository: EmployeeRepository,
    private val timeValidationService: TimeValidationService,
    private val configManager: IShopConfigurationManager,
) : DatevExportService {
    private val logger = LoggerFactory.getLogger(DatevExportServiceImpl::class.java)

    override suspend fun generatePreview(
        year: Int,
        month: Int,
    ): DatevExportDto.DatevPreviewDto {
        // Read DATEV config outside dbQuery (ShopConfigurationManager manages own transactions)
        val consultantNumber = configManager.getDatevXmlConsultantNumber() ?: ""
        val clientNumber = configManager.getDatevXmlClientNumber() ?: ""

        return dbQuery {
            val warnings = mutableListOf<String>()

            val defaultWageType = wageTypeRepository.findDefault()
            if (defaultWageType == null) {
                warnings.add("No default wage type configured — using code 200 as fallback")
            }
            val wageTypeCode = defaultWageType?.code ?: 200

            if (consultantNumber.isBlank()) {
                warnings.add("No DATEV consultant number (Beraternummer) configured in shop settings")
            }
            if (clientNumber.isBlank()) {
                warnings.add("No DATEV client number (Mandantennummer) configured in shop settings")
            }

            val entries =
                timeEntryRepository
                    .findByMonth(year, month)
                    .filter { it.status == TimeEntryStatus.APPROVED }

            val employeeGroups = entries.groupBy { it.employee.id.value }
            val lines = mutableListOf<DatevExportDto.DatevLineDto>()

            for ((_, empEntries) in employeeGroups) {
                val employee = empEntries.first().employee
                val personnelNumber = employee.personnelNumber ?: employee.employeeNumber

                if (employee.personnelNumber == null) {
                    warnings.add(
                        "Employee ${employee.fullName} (#${employee.employeeNumber}): No personnel number set, using employee number",
                    )
                }

                for (entry in empEntries) {
                    val netMinutes = calculateNetWorkMinutes(entry)
                    val hours = netMinutes / 60.0
                    val hoursFormatted = String.format("%.2f", hours).replace('.', ',')
                    val calendarDay = String.format("%02d", entry.date.day)
                    val costCenterCode = entry.costCenter?.code ?: ""

                    lines.add(
                        DatevExportDto.DatevLineDto(
                            personnelNumber = personnelNumber,
                            calendarDay = calendarDay,
                            absenceCode = "",
                            wageTypeCode = wageTypeCode,
                            hours = hoursFormatted,
                            days = "",
                            value = "",
                            deviatingFactor = "",
                            deviatingWageChange = "",
                            costCenterCode = costCenterCode,
                            costCarrier = "",
                        ),
                    )
                }
            }

            // Sort by personnelNumber then calendarDay
            val sortedLines = lines.sortedWith(compareBy({ it.personnelNumber }, { it.calendarDay }))

            DatevExportDto.DatevPreviewDto(
                lines = sortedLines,
                warnings = warnings,
                totalEmployees = employeeGroups.size,
                month = month,
                year = year,
                consultantNumber = consultantNumber,
                clientNumber = clientNumber,
            )
        }
    }

    override suspend fun generateCsv(
        year: Int,
        month: Int,
    ): ByteArray {
        val preview = generatePreview(year, month)
        val sb = StringBuilder()

        // Header row: consultantNumber;clientNumber;MM/YYYY
        sb.append(preview.consultantNumber).append(';')
        sb.append(preview.clientNumber).append(';')
        sb.append(String.format("%02d/%04d", preview.month, preview.year))
        sb.append("\r\n")

        for (line in preview.lines) {
            sb.append(line.personnelNumber).append(';')
            sb.append(line.calendarDay).append(';')
            sb.append(line.absenceCode).append(';')
            sb.append(line.wageTypeCode).append(';')
            sb.append(line.hours).append(';')
            sb.append(line.days).append(';')
            sb.append(line.value).append(';')
            sb.append(line.deviatingFactor).append(';')
            sb.append(line.deviatingWageChange).append(';')
            sb.append(line.costCenterCode).append(';')
            sb.append(line.costCarrier)
            sb.append("\r\n")
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    override suspend fun generatePdfTimesheet(
        employeeId: String,
        year: Int,
        month: Int,
    ): ByteArray =
        dbQuery {
            val empId = UUID.fromString(employeeId)
            val employee =
                employeeRepository.getEmployeeById(empId)
                    ?: throw PosException(
                        PosErrorCode.EMPLOYEE_NOT_FOUND,
                        HttpStatusCode.NotFound,
                        "Employee not found",
                    )

            val entries = timeEntryRepository.findByEmployeeAndMonth(empId, year, month)

            // Build simple text-based PDF content (placeholder - would use iText in production)
            val content =
                buildString {
                    appendLine("Arbeitszeitdokumentation")
                    appendLine("Name: ${employee.fullName}")
                    appendLine("Pers.-Nr.: ${employee.personnelNumber ?: employee.employeeNumber}")
                    appendLine("Monat/Jahr: ${String.format("%02d", month)}/$year")
                    appendLine("---")
                    for (entry in entries) {
                        val netMinutes = calculateNetWorkMinutes(entry)
                        appendLine(
                            "${entry.date} | ${entry.clockIn} - ${entry.clockOut ?: "laufend"} | ${netMinutes / 60}:${String.format(
                                "%02d",
                                netMinutes % 60,
                            )}",
                        )
                    }
                    val totalMinutes = entries.sumOf { calculateNetWorkMinutes(it) }
                    appendLine("---")
                    appendLine("Summe: ${totalMinutes / 60}:${String.format("%02d", totalMinutes % 60)}")
                }

            content.toByteArray()
        }

    private fun calculateNetWorkMinutes(entry: TimeEntry): Int {
        val clockOut = entry.clockOut ?: currentTimeStamp()
        val grossMinutes = minutesBetween(entry.clockIn, clockOut)
        val stempledBreak =
            entry.breaks.sumOf { b ->
                val bEnd = b.breakEnd ?: currentTimeStamp()
                minutesBetween(b.breakStart, bEnd)
            }
        val effectiveBreak = timeValidationService.calculateEffectiveBreak(grossMinutes, stempledBreak)
        return maxOf(0, grossMinutes - effectiveBreak)
    }

    private fun minutesBetween(
        start: kotlinx.datetime.LocalDateTime,
        end: kotlinx.datetime.LocalDateTime,
    ): Int = durationBetween(start, end).inWholeMinutes.toInt()
}
