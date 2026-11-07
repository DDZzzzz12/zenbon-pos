package org.example.project.features.employee.data.repository

import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.models.EmployeeInfo
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.mapper.toBigDecimalOrNull
import org.example.project.features.employee.mapper.toLocalDateOrNull
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

/**
 * Implementation of EmployeeInfoRepository using Exposed ORM.
 *
 * Returns plain entities; exceptions propagate naturally to the service layer.
 */
class EmployeeInfoRepositoryImpl : EmployeeInfoRepository {
    override fun createEmployeeInfo(request: EmployeeInfoDto.CreateEmployeeInfoRequestDto): EmployeeInfo {
        request.validate()

        val employeeId = UUID.fromString(request.employeeId)
        val employee =
            Employee.Companion.findById(employeeId)
                ?: throw NoSuchElementException("Employee not found: ${request.employeeId}")

        // Check if employee info already exists for this employee
        val existingInfo =
            EmployeeInfo.Companion
                .find {
                    EmployeeInfoTable.employeeId eq employeeId
                }.singleOrNull()

        if (existingInfo != null) {
            throw IllegalStateException("Employee info already exists for employee: ${request.employeeId}")
        }

        val timestamp = currentTimeStamp()
        val info =
            EmployeeInfo.new {
                this.employee = employee
                firstName = request.firstName
                lastName = request.lastName
                email = request.email
                phoneNumber = request.phoneNumber
                street = request.street
                city = request.city
                state = request.state
                zipCode = request.zipCode
                country = request.country
                dateOfBirth = request.dateOfBirth.toLocalDateOrNull()
                hireDate = request.hireDate.toLocalDateOrNull()
                jobTitle = request.jobTitle
                hourlyRate = request.hourlyRate.toBigDecimalOrNull()
                salary = request.salary.toBigDecimalOrNull()
                contractedHours = request.contractedHours.toBigDecimalOrNull()
                contractedHoursPeriod = request.contractedHoursPeriod
                createdAt = timestamp
                updatedAt = timestamp
            }
        return info
    }

    override fun getEmployeeInfoById(employeeInfoId: Int): EmployeeInfo? = EmployeeInfo.findById(employeeInfoId)

    override fun getEmployeeInfoByEmployeeId(employeeId: UUID): EmployeeInfo? =
        EmployeeInfo
            .find {
                EmployeeInfoTable.employeeId eq employeeId
            }.singleOrNull()

    override fun updateEmployeeInfo(
        employeeInfoId: Int,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfo? {
        updateData.validate()

        val employeeInfo = EmployeeInfo.findById(employeeInfoId) ?: return null

        employeeInfo.apply {
            updateData.firstName?.let { firstName = it }
            updateData.lastName?.let { lastName = it }
            updateData.email?.let { email = it.blankToNull() }
            updateData.phoneNumber?.let { phoneNumber = it.blankToNull() }
            updateData.street?.let { street = it.blankToNull() }
            updateData.city?.let { city = it.blankToNull() }
            updateData.state?.let { state = it.blankToNull() }
            updateData.zipCode?.let { zipCode = it.blankToNull() }
            updateData.country?.let { country = it.blankToNull() }
            updateData.dateOfBirth?.let { dateOfBirth = it.toLocalDateOrNull() }
            updateData.hireDate?.let { hireDate = it.toLocalDateOrNull() }
            updateData.jobTitle?.let { jobTitle = it.blankToNull() }
            updateData.hourlyRate?.let { hourlyRate = it.toBigDecimalOrNull() }
            updateData.salary?.let { salary = it.toBigDecimalOrNull() }
            updateData.contractedHours?.let { contractedHours = it.toBigDecimalOrNull() }
            updateData.contractedHoursPeriod?.let { contractedHoursPeriod = it }
            updatedAt = currentTimeStamp()
        }

        return employeeInfo
    }

    override fun updateEmployeeInfoByEmployeeId(
        employeeId: UUID,
        updateData: EmployeeInfoDto.UpdateEmployeeInfoRequestDto,
    ): EmployeeInfo? {
        updateData.validate()

        val employeeInfo =
            EmployeeInfo.Companion
                .find {
                    EmployeeInfoTable.employeeId eq employeeId
                }.singleOrNull() ?: return null

        employeeInfo.apply {
            updateData.firstName?.let { firstName = it }
            updateData.lastName?.let { lastName = it }
            updateData.email?.let { email = it.blankToNull() }
            updateData.phoneNumber?.let { phoneNumber = it.blankToNull() }
            updateData.street?.let { street = it.blankToNull() }
            updateData.city?.let { city = it.blankToNull() }
            updateData.state?.let { state = it.blankToNull() }
            updateData.zipCode?.let { zipCode = it.blankToNull() }
            updateData.country?.let { country = it.blankToNull() }
            updateData.dateOfBirth?.let { dateOfBirth = it.toLocalDateOrNull() }
            updateData.hireDate?.let { hireDate = it.toLocalDateOrNull() }
            updateData.jobTitle?.let { jobTitle = it.blankToNull() }
            updateData.hourlyRate?.let { hourlyRate = it.toBigDecimalOrNull() }
            updateData.salary?.let { salary = it.toBigDecimalOrNull() }
            updateData.contractedHours?.let { contractedHours = it.toBigDecimalOrNull() }
            updateData.contractedHoursPeriod?.let { contractedHoursPeriod = it }
            updatedAt = currentTimeStamp()
        }

        return employeeInfo
    }

    override fun deleteEmployeeInfo(employeeInfoId: Int) {
        val employeeInfo =
            EmployeeInfo.findById(employeeInfoId)
                ?: throw NoSuchElementException("EmployeeInfo not found: $employeeInfoId")
        detachBackReference(employeeInfoId)
        employeeInfo.delete()
    }

    override fun deleteEmployeeInfoByEmployeeId(employeeId: UUID) {
        val employeeInfo =
            EmployeeInfo
                .find {
                    EmployeeInfoTable.employeeId eq employeeId
                }.singleOrNull()
                ?: throw NoSuchElementException("EmployeeInfo not found for employee: $employeeId")
        detachBackReference(employeeInfo.id.value)
        employeeInfo.delete()
    }

    /**
     * Clears `employees.info_id` before the personnel record goes away.
     *
     * The relationship is stored on both sides and creation writes both
     * (`EmployeeInfoServiceImpl` sets `employee.employeeInfo = entity`), so a delete that tears down
     * only one side leaves the back-reference dangling. V90 makes the constraint `ON DELETE SET NULL`
     * so the database can no longer reject the delete, but doing it explicitly keeps the DAO's
     * in-memory `Employee.employeeInfo` honest within the same transaction — a cached entity would
     * otherwise still point at a deleted row.
     *
     * Runs in the caller's transaction; both callers are already inside the service's `dbQuery`.
     */
    private fun detachBackReference(employeeInfoId: Int) {
        EmployeeTable.update({ EmployeeTable.infoId eq employeeInfoId }) { it[infoId] = null }
    }

    override fun getAllEmployeeInfo(
        limit: Int,
        offset: Int,
    ): List<EmployeeInfo> =
        EmployeeInfo.Companion
            .all()
            .limit(limit)
            .offset(offset.toLong())
            .toList()

    override fun searchEmployeeInfoByName(
        searchTerm: String,
        limit: Int,
        offset: Int,
        includeInactive: Boolean,
    ): List<EmployeeInfo> {
        val nameFilter =
            (EmployeeInfoTable.firstName.lowerCase() like "%${searchTerm.lowercase()}%") or
                (EmployeeInfoTable.lastName.lowerCase() like "%${searchTerm.lowercase()}%")

        val fullFilter =
            if (includeInactive) {
                nameFilter
            } else {
                nameFilter and (EmployeeTable.employmentStatus eq EmploymentStatus.ACTIVE)
            }

        return EmployeeInfo
            .wrapRows(
                EmployeeInfoTable
                    .innerJoin(EmployeeTable)
                    .select(EmployeeInfoTable.columns)
                    .where(fullFilter)
                    .limit(limit)
                    .offset(offset.toLong()),
            ).toList()
    }

    override fun employeeInfoExists(employeeId: UUID): Boolean =
        EmployeeInfo
            .find {
                EmployeeInfoTable.employeeId eq employeeId
            }.count() > 0

    override fun getEmployeeInfoCount(): Long = EmployeeInfo.all().count()
}

/** A blank string is the "clear this field" marker of an update request; it is stored as null, never as `''`. */
private fun String.blankToNull(): String? = ifBlank { null }
