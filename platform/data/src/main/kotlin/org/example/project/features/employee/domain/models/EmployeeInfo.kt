/**
 * EmployeeInfo entity representing detailed employee personal and employment information.
 *
 * Separated from core Employee entity to maintain clean data organization where authentication
 * and role data is kept separate from personal details. Uses a 1:1 relationship with Employee.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.employee.domain.models

import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*
import java.math.BigDecimal

/**
 * EmployeeInfo entity for storing detailed employee information.
 *
 * Contains personal information and employment details in a separate table
 * from the core Employee authentication data.
 */
class EmployeeInfo(
    id: EntityID<Int>,
) : IntEntity(id) {
    companion object : IntEntityClass<EmployeeInfo>(EmployeeInfoTable)

    /** Referenced employee (1:1 relationship) */
    var employee by Employee referencedOn EmployeeInfoTable.employeeId

    /** Employee's first name */
    var firstName by EmployeeInfoTable.firstName

    /** Employee's last name */
    var lastName by EmployeeInfoTable.lastName

    /** Employee's email address (optional) */
    var email by EmployeeInfoTable.email

    /** Employee's phone number (optional) */
    var phoneNumber by EmployeeInfoTable.phoneNumber

    /** Street address including house number and street name */
    var street by EmployeeInfoTable.street

    /** City name */
    var city by EmployeeInfoTable.city

    /** State or province */
    var state by EmployeeInfoTable.state

    /** ZIP or postal code */
    var zipCode by EmployeeInfoTable.zipCode

    /** Country name */
    var country by EmployeeInfoTable.country

    /** When this address record was created */
    var createdAt by EmployeeInfoTable.createdAt

    /** When this address record was last updated */
    var updatedAt by EmployeeInfoTable.updatedAt

    /** Employee's date of birth (optional) */
    var dateOfBirth by EmployeeInfoTable.dateOfBirth

    /** Date when employee was hired (optional) */
    var hireDate by EmployeeInfoTable.hireDate

    /** Employee's job title (optional) */
    var jobTitle by EmployeeInfoTable.jobTitle

    /** Hourly wage rate (optional) */
    var hourlyRate by EmployeeInfoTable.hourlyRate

    /** Annual salary (optional) */
    var salary by EmployeeInfoTable.salary

    /** Contracted hours (optional) */
    var contractedHours by EmployeeInfoTable.contractedHours

    /** Period for contracted hours (WEEKLY or MONTHLY) */
    var contractedHoursPeriod by EmployeeInfoTable.contractedHoursPeriod

    /** Contracted hours normalized to weekly (approximate, for display only) */
    val contractedWeeklyHours: Double?
        get() {
            val hours = contractedHours?.toDouble() ?: return null
            return when (contractedHoursPeriod) {
                ContractedHoursPeriod.MONTHLY -> hours / (52.0 / 12.0)
                else -> hours
            }
        }

    // Computed properties
    /** Employee's full name (first + last) */
    val fullName: String
        get() = "$firstName $lastName"

    /**
     * Validates the employee information data.
     *
     * @throws IllegalArgumentException if validation fails
     */
    fun validate() {
        require(firstName.isNotBlank()) { "First name cannot be blank" }
        require(lastName.isNotBlank()) { "Last name cannot be blank" }

        // Validate email format if provided
        email?.let { emailAddress ->
            require(emailAddress.contains("@") && emailAddress.contains(".")) {
                "Invalid email format"
            }
        }

        // Validate compensation amounts are positive
        hourlyRate?.let { rate ->
            require(rate > BigDecimal.ZERO) { "Hourly rate must be positive" }
        }

        salary?.let { salaryAmount ->
            require(salaryAmount > BigDecimal.ZERO) { "Salary must be positive" }
        }
    }

    override fun toString(): String =
        """
        EmployeeInfo(
            id=${id.value},
            employeeNumber=${employee.id.value},
            fullName='$fullName',
            email='$email',
            phoneNumber='$phoneNumber',
            jobTitle='$jobTitle'
        )
        """.trimIndent()
}
