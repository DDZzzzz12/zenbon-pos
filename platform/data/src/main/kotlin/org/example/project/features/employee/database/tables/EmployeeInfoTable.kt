package org.example.project.features.employee.database.tables

import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime

object EmployeeInfoTable : IntIdTable("employee_info", "employee_info_id") {
    /**
     * The authoritative link to the employee. Unique because an employee has at most one personnel
     * record: `createEmployeeInfo` already rejects a second one with 409, and both delete paths read
     * the record with `singleOrNull()`, which throws when the row is ambiguous. Without the index
     * that invariant lived only in application code (added in V90).
     */
    val employeeId = reference("employee_id", EmployeeTable).uniqueIndex()

    // Personal Information
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val email = varchar("email", 100).uniqueIndex().nullable()
    val phoneNumber = varchar("phone_number", 20).nullable()
    val dateOfBirth = date("date_of_birth").nullable()
    val street = varchar("street", 200).nullable()
    val city = varchar("city", 100).nullable()
    val state = varchar("state", 50).nullable()
    val zipCode = varchar("zip_code", 20).nullable()
    val country = varchar("country", 50).default("DE").nullable()

    // Employment Details
    val hireDate = date("hire_date").nullable()
    val jobTitle = varchar("job_title", 100).nullable()
    val hourlyRate = decimal("hourly_rate", 10, 2).nullable()
    val salary = decimal("salary", 12, 2).nullable()
    val contractedHours = decimal("contracted_hours", 5, 2).nullable()
    val contractedHoursPeriod =
        enumerationByName("contracted_hours_period", 10, ContractedHoursPeriod::class)
            .default(ContractedHoursPeriod.WEEKLY)
            .nullable()

    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}
