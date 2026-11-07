/**
 * Implementation of EmployeeInfoService.
 *
 * Throws [PosException] on errors. Migrated from Result<T, Error> in Stage B Batch 10.
 */
package org.example.project.features.employee.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.employee.domain.service.EmployeeInfoService
import org.example.project.features.employee.mapper.toResponseDto
import org.example.project.features.employee.mapper.toSummaryDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.AuditEntityRef
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

/** `entity_type` of the employee audit rows — the HR profile belongs to the employee record. */
private const val EMPLOYEE_ENTITY_TYPE = "EMPLOYEE"

/**
 * The audit row's `after_state` for an HR profile update: which fields were set and which were
 * cleared (sent blank). Field NAMES only — the values are personnel data and stay out of the log.
 */
private fun EmployeeInfoDto.UpdateEmployeeInfoRequestDto.auditStateJson(employeeId: String): String {
    val sent =
        listOf(
            "firstName" to firstName,
            "lastName" to lastName,
            "email" to email,
            "phoneNumber" to phoneNumber,
            "street" to street,
            "city" to city,
            "state" to state,
            "zipCode" to zipCode,
            "country" to country,
            "dateOfBirth" to dateOfBirth,
            "hireDate" to hireDate,
            "jobTitle" to jobTitle,
            "hourlyRate" to hourlyRate,
            "salary" to salary,
            "contractedHours" to contractedHours,
            "contractedHoursPeriod" to contractedHoursPeriod?.name,
        ).filter { it.second != null }
    return buildJsonObject {
        put("id", employeeId)
        put("scope", "HR_PROFILE")
        put("updatedFields", buildJsonArray { sent.filter { it.second!!.isNotBlank() }.forEach { add(it.first) } })
        put("clearedFields", buildJsonArray { sent.filter { it.second!!.isBlank() }.forEach { add(it.first) } })
    }.toString()
}

/**
 * The employment-term fields this request touches (set or cleared). Pay, contracted hours, hire
 * date and job title are the employer's to set: the self-service route refuses any of them.
 */
private fun EmployeeInfoDto.UpdateEmployeeInfoRequestDto.employmentTermFields(): List<String> =
    listOf(
        "hireDate" to hireDate,
        "jobTitle" to jobTitle,
        "hourlyRate" to hourlyRate,
        "salary" to salary,
        "contractedHours" to contractedHours,
        "contractedHoursPeriod" to contractedHoursPeriod?.name,
    ).filter { it.second != null }.map { it.first }

class EmployeeInfoServiceImpl(
    private val employeeInfoRepository: EmployeeInfoRepository,
    private val employeeRepository: EmployeeRepository,
) : EmployeeInfoService {
    private val logger = LoggerFactory.getLogger(EmployeeInfoServiceImpl::class.java)

    override suspend fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfoDto.EmployeeInfoResponseDto {
        validateEmployeeInfo(request)

        val employeeId =
            try {
                UUID.fromString(request.employeeId)
            } catch (e: IllegalArgumentException) {
                throw validationFailed("employeeId must be a valid UUID")
            }

        return dbQuery {
            val employee =
                employeeRepository.getEmployeeById(employeeId)
                    ?: throw employeeNotFound(employeeId.toString())

            if (employeeInfoRepository.employeeInfoExists(employeeId)) {
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.Conflict,
                    "Employee info already exists for employee ${request.employeeId}",
                )
            }

            val entity = employeeInfoRepository.createEmployeeInfo(request)
            employee.employeeInfo = entity
            entity.toResponseDto()
        }
    }

    override suspend fun getEmployeeInfoById(employeeInfoId: Int): EmployeeInfoDto.EmployeeInfoResponseDto =
        dbQuery {
            employeeInfoRepository.getEmployeeInfoById(employeeInfoId)?.toResponseDto()
                ?: throw notFound("EmployeeInfo $employeeInfoId not found")
        }

    override suspend fun getEmployeeInfoByEmployeeId(employeeId: UUID): EmployeeInfoDto.EmployeeInfoResponseDto =
        dbQuery {
            employeeInfoRepository.getEmployeeInfoByEmployeeId(employeeId)?.toResponseDto()
                ?: throw employeeNotFound(employeeId.toString())
        }

    override suspend fun updateEmployeeInfo(
        employeeInfoId: Int,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto {
        try {
            updateData.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid update data")
        }
        validateUpdateData(updateData)

        return dbQuery {
            val entity =
                employeeInfoRepository.updateEmployeeInfo(employeeInfoId, updateData)
                    ?: throw notFound("EmployeeInfo $employeeInfoId not found")
            entity.toResponseDto()
        }
    }

    override suspend fun updateEmployeeInfoByEmployeeId(
        employeeId: UUID,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
        actorId: String,
    ): EmployeeInfoDto.EmployeeInfoResponseDto =
        // The same operation name as the account-level staff change (whitelisted in ExceptionConverter):
        // the HR profile is part of the employee record, and a new name would be dropped silently.
        // The row names WHICH fields changed or were cleared, never their values (personnel data).
        ExceptionConverter.catchingThrows(
            context = "updateEmployeeInfoByEmployeeId(employeeId=$employeeId, actorId=$actorId)",
            employeeId = actorId,
            operation = "UPDATE_EMPLOYEE",
            entityType = EMPLOYEE_ENTITY_TYPE,
            failedEntityId = employeeId.toString(),
            auditEntity = { AuditEntityRef(id = it.employeeId, name = "HR_PROFILE") },
            auditState = { updateData.auditStateJson(it.employeeId) },
        ) {
            try {
                updateData.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid update data")
            }
            validateUpdateData(updateData)

            dbQuery {
                val entity =
                    employeeInfoRepository.updateEmployeeInfoByEmployeeId(employeeId, updateData)
                        ?: throw employeeNotFound(employeeId.toString())
                entity.toResponseDto()
            }
        }

    override suspend fun updateOwnEmployeeInfo(
        employeeId: UUID,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfoDto.EmployeeInfoResponseDto {
        val employmentTerms = updateData.employmentTermFields()
        if (employmentTerms.isNotEmpty()) {
            throw PosException(
                PosErrorCode.SELF_MODIFICATION_FORBIDDEN,
                HttpStatusCode.Forbidden,
                "Employment terms cannot be changed through the self-service profile: ${employmentTerms.joinToString()}",
            )
        }
        return updateEmployeeInfoByEmployeeId(employeeId, updateData, actorId = employeeId.toString())
    }

    override suspend fun deleteEmployeeInfo(employeeInfoId: Int) {
        dbQuery {
            employeeInfoRepository.getEmployeeInfoById(employeeInfoId)
                ?: throw notFound("EmployeeInfo $employeeInfoId not found")
            employeeInfoRepository.deleteEmployeeInfo(employeeInfoId)
        }
    }

    override suspend fun deleteEmployeeInfoByEmployeeId(employeeId: UUID) {
        dbQuery {
            // Pre-checked so a missing record is the 404 the route documents. Without it the
            // repository's NoSuchElementException escapes to StatusPages as a bare 500, which is how
            // the FK failure this method used to raise stayed unreadable.
            employeeInfoRepository.getEmployeeInfoByEmployeeId(employeeId)
                ?: throw employeeNotFound(employeeId.toString())
            employeeInfoRepository.deleteEmployeeInfoByEmployeeId(employeeId)
            logger.info("deleteEmployeeInfoByEmployeeId: deleted personnel record for employeeId={}", employeeId)
        }
    }

    override suspend fun getAllEmployeeInfo(
        limit: Int,
        offset: Int,
    ): List<EmployeeInfoDto.EmployeeInfoResponseDto> {
        if (limit !in 1..100) throw validationFailed("limit must be between 1 and 100")
        if (offset < 0) throw validationFailed("offset must be non-negative")
        return dbQuery {
            employeeInfoRepository.getAllEmployeeInfo(limit, offset).map { it.toResponseDto() }
        }
    }

    override suspend fun searchEmployeeInfoByName(
        searchTerm: String,
        limit: Int,
        offset: Int,
        includeInactive: Boolean,
    ): List<EmployeeInfoDto.EmployeeInfoResponseDto> {
        if (searchTerm.isBlank() || searchTerm.length < 2) {
            throw validationFailed("searchTerm must be at least 2 characters")
        }
        if (limit !in 1..100) throw validationFailed("limit must be between 1 and 100")
        if (offset < 0) throw validationFailed("offset must be non-negative")
        return dbQuery {
            employeeInfoRepository
                .searchEmployeeInfoByName(searchTerm.trim(), limit, offset, includeInactive)
                .map { it.toResponseDto() }
        }
    }

    override suspend fun getEmployeeInfoSummary(
        limit: Int,
        offset: Int,
    ): List<EmployeeInfoDto.EmployeeInfoSummaryDto> {
        if (limit !in 1..100) throw validationFailed("limit must be between 1 and 100")
        if (offset < 0) throw validationFailed("offset must be non-negative")
        return dbQuery {
            employeeInfoRepository.getAllEmployeeInfo(limit, offset).map { it.toSummaryDto() }
        }
    }

    override suspend fun employeeInfoExists(employeeId: UUID): Boolean =
        dbQuery {
            employeeInfoRepository.employeeInfoExists(employeeId)
        }

    override suspend fun getEmployeeInfoCount(): Long =
        dbQuery {
            employeeInfoRepository.getEmployeeInfoCount()
        }

    override suspend fun validateEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto) {
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid request data")
        }

        if (request.dateOfBirth != null && request.hireDate != null) {
            try {
                val birthDate = LocalDate.parse(request.dateOfBirth!!)
                val hireDate = LocalDate.parse(request.hireDate!!)
                if (hireDate.year - birthDate.year < 16) {
                    throw validationFailed("Employee must be at least 16 years old when hired")
                }
            } catch (e: PosException) {
                throw e
            } catch (e: Exception) {
                throw validationFailed("Invalid date format provided")
            }
        }

        if (request.hourlyRate != null && request.salary != null) {
            val hourlyRate = BigDecimal(request.hourlyRate)
            val salary = BigDecimal(request.salary)
            if (hourlyRate > BigDecimal.ZERO && salary > BigDecimal.ZERO) {
                throw validationFailed("Cannot specify both hourly rate and salary")
            }
        }

        request.hourlyRate?.let { rate ->
            val rv = BigDecimal(rate)
            if (rv < BigDecimal("7.25") || rv > BigDecimal("200.00")) {
                throw validationFailed("hourlyRate must be between 7.25 and 200.00")
            }
        }

        request.salary?.let { sal ->
            val sv = BigDecimal(sal)
            if (sv < BigDecimal("15000") || sv > BigDecimal("500000")) {
                throw validationFailed("salary must be between 15000 and 500000")
            }
        }
    }

    private fun validateUpdateData(updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto) {
        // A blank value is the "clear this field" marker and carries nothing to validate.
        val dateOfBirth = updateData.dateOfBirth?.takeIf { it.isNotBlank() }
        val hireDate = updateData.hireDate?.takeIf { it.isNotBlank() }
        val hourlyRate = updateData.hourlyRate?.takeIf { it.isNotBlank() }
        val salary = updateData.salary?.takeIf { it.isNotBlank() }

        if (dateOfBirth != null && hireDate != null) {
            try {
                val birth = LocalDate.parse(dateOfBirth)
                val hire = LocalDate.parse(hireDate)
                if (hire.year - birth.year < 16) {
                    throw validationFailed("Employee must be at least 16 years old when hired")
                }
            } catch (e: PosException) {
                throw e
            } catch (e: Exception) {
                throw validationFailed("Invalid date format provided")
            }
        }

        if (hourlyRate != null && salary != null) {
            if (BigDecimal(hourlyRate) > BigDecimal.ZERO && BigDecimal(salary) > BigDecimal.ZERO) {
                throw validationFailed("Cannot specify both hourly rate and salary")
            }
        }

        hourlyRate?.let { rate ->
            val rv = BigDecimal(rate)
            if (rv < BigDecimal("7.25") || rv > BigDecimal("200.00")) {
                throw validationFailed("hourlyRate must be between 7.25 and 200.00")
            }
        }

        salary?.let { sal ->
            val sv = BigDecimal(sal)
            if (sv < BigDecimal("15000") || sv > BigDecimal("500000")) {
                throw validationFailed("salary must be between 15000 and 500000")
            }
        }
    }

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun employeeNotFound(id: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Employee $id not found",
        )

    private fun notFound(msg: String) =
        PosException(
            PosErrorCode.EMPLOYEE_NOT_FOUND,
            HttpStatusCode.NotFound,
            msg,
        )
}
