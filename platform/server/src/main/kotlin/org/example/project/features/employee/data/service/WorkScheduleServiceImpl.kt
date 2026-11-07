/**
 * Implementation of WorkScheduleService.
 *
 * Throws [PosException] on errors. Migrated from Result<T, DataError> in Stage B Batch 10.
 */
package org.example.project.features.employee.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.models.WorkSchedule
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.employee.domain.repository.WorkScheduleRepository
import org.example.project.features.employee.domain.service.WorkScheduleService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory
import java.util.UUID

class WorkScheduleServiceImpl(
    private val workScheduleRepository: WorkScheduleRepository,
    private val employeeRepository: EmployeeRepository,
    private val employeeInfoRepository: EmployeeInfoRepository,
) : WorkScheduleService {
    private val logger = LoggerFactory.getLogger(WorkScheduleServiceImpl::class.java)

    override suspend fun getWeeklySchedule(employeeId: UUID): WorkScheduleDto.WeeklyScheduleResponseDto =
        dbQuery {
            employeeRepository.getEmployeeById(employeeId)
                ?: throw employeeNotFound(employeeId.toString())
            val schedules = workScheduleRepository.findByEmployeeId(employeeId)
            val info = employeeInfoRepository.getEmployeeInfoByEmployeeId(employeeId)
            mapToWeeklyResponse(employeeId, info?.contractedWeeklyHours, schedules)
        }

    override suspend fun updateDaySchedule(
        employeeId: UUID,
        request: WorkScheduleDto.UpdateDayRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid schedule data")
        }
        return dbQuery {
            employeeRepository.getEmployeeById(employeeId)
                ?: throw employeeNotFound(employeeId.toString())
            workScheduleRepository.deleteByEmployeeIdAndDay(employeeId, request.dayOfWeek)
            request.shifts.forEach { shift ->
                workScheduleRepository.create(
                    employeeId = employeeId,
                    dayOfWeek = request.dayOfWeek,
                    startTime = shift.startTime,
                    endTime = shift.endTime,
                    breakDuration = shift.breakDuration,
                )
            }
            val allSchedules = workScheduleRepository.findByEmployeeId(employeeId)
            val info = employeeInfoRepository.getEmployeeInfoByEmployeeId(employeeId)
            logger.info("updateDaySchedule: updated day={} for employeeId={}", request.dayOfWeek, employeeId)
            mapToWeeklyResponse(employeeId, info?.contractedWeeklyHours, allSchedules)
        }
    }

    override suspend fun updateWeekSchedule(
        employeeId: UUID,
        request: WorkScheduleDto.UpdateWeekRequestDto,
    ): WorkScheduleDto.WeeklyScheduleResponseDto {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid schedule data")
        }
        return dbQuery {
            employeeRepository.getEmployeeById(employeeId)
                ?: throw employeeNotFound(employeeId.toString())
            workScheduleRepository.deleteAllByEmployeeId(employeeId)
            request.days.forEach { day ->
                day.shifts.forEach { shift ->
                    workScheduleRepository.create(
                        employeeId = employeeId,
                        dayOfWeek = day.dayOfWeek,
                        startTime = shift.startTime,
                        endTime = shift.endTime,
                        breakDuration = shift.breakDuration,
                    )
                }
            }
            val allSchedules = workScheduleRepository.findByEmployeeId(employeeId)
            val info = employeeInfoRepository.getEmployeeInfoByEmployeeId(employeeId)
            logger.info("updateWeekSchedule: updated full week for employeeId={}", employeeId)
            mapToWeeklyResponse(employeeId, info?.contractedWeeklyHours, allSchedules)
        }
    }

    private fun mapToWeeklyResponse(
        employeeId: UUID,
        contractedHours: Double?,
        schedules: List<WorkSchedule>,
    ): WorkScheduleDto.WeeklyScheduleResponseDto {
        val dayGroups =
            (1..7).map { day ->
                val shifts = schedules.filter { it.dayOfWeek == day }
                WorkScheduleDto.DayScheduleDto(
                    dayOfWeek = day,
                    shifts =
                        shifts.map { ws ->
                            WorkScheduleDto.ShiftDto(
                                id = ws.id.value.toString(),
                                startTime = ws.startTime,
                                endTime = ws.endTime,
                                breakDuration = ws.breakDuration,
                            )
                        },
                )
            }
        val totalMinutes =
            schedules.sumOf { ws ->
                val startParts = ws.startTime.split(":")
                val endParts = ws.endTime.split(":")
                val startMin = startParts[0].toInt() * 60 + startParts[1].toInt()
                val endMin = endParts[0].toInt() * 60 + endParts[1].toInt()
                (endMin - startMin) - ws.breakDuration
            }
        return WorkScheduleDto.WeeklyScheduleResponseDto(
            employeeId = employeeId.toString(),
            contractedWeeklyHours = contractedHours,
            scheduledWeeklyMinutes = totalMinutes,
            days = dayGroups,
        )
    }

    private fun employeeNotFound(id: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee $id not found",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
