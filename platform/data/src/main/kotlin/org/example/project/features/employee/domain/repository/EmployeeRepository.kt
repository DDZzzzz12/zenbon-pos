package org.example.project.features.employee.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.features.employee.domain.models.Employee
import java.util.UUID

/**
 * Repository interface for Employee entity CRUD operations.
 *
 * Defines the contract for employee data access operations including
 * standard CRUD operations and specialized queries for authentication
 * and employee management functionality. Returns plain entities or nullable
 * types; exceptions propagate naturally to the service layer.
 */
interface EmployeeRepository {
    /**
     * Creates a new employee record with the provided information.
     *
     * @param employeeRequest Employee data for creation
     * @param employeeNumber Generated employee number
     * @return Created Employee entity
     * @throws Exception on database failure
     */
    fun createEmployee(
        employeeRequest: EmployeeDto.CreateEmployeeRequestDto,
        employeeNumber: String,
    ): Employee

    /**
     * Retrieves an employee by their unique identifier.
     *
     * @param employeeId UUID of the employee to retrieve
     * @return Employee entity or null if not found
     */
    fun getEmployeeById(employeeId: UUID): Employee?

    /**
     * Retrieves an employee by their username for authentication.
     *
     * @param username Unique username of the employee
     * @return Employee entity or null if not found
     */
    fun getEmployeeByUsername(username: String): Employee?

    /**
     * Retrieves an employee by their employee number.
     *
     * @param employeeNumber Unique employee number
     * @return Employee entity or null if not found
     */
    fun getEmployeeByEmployeeNumber(employeeNumber: String): Employee?

    /**
     * Generates the next sequential employee number for a given prefix.
     *
     * @param prefix Role-based prefix (e.g., "A", "M", "K", "B")
     * @return Next employee number string
     */
    fun getNextEmployeeNumber(prefix: String): String

    /**
     * Retrieves all employees with optional filtering and pagination.
     *
     * @param limit Maximum number of records to return (default: 50)
     * @param offset Number of records to skip (default: 0)
     * @param active Filter by employment status (null for all)
     * @return List of Employee entities
     */
    fun getAllEmployees(
        limit: Int = 50,
        offset: Int = 0,
        active: Boolean? = null,
    ): List<Employee>

    /**
     * Updates an existing employee's information.
     *
     * @param employeeId UUID of the employee to update
     * @param updateData Updated employee information
     * @return Updated Employee entity or null if not found
     */
    fun updateEmployee(
        employeeId: UUID,
        updateData: EmployeeDto.UpdateEmployeeRequestDto,
    ): Employee?

    /**
     * Soft-deletes an employee by setting status to INACTIVE.
     *
     * @param employeeId UUID of the employee to delete
     * @param userId ID of the user performing the operation
     * @throws NoSuchElementException if employee not found
     * @throws IllegalArgumentException if employee is an admin
     */
    fun deleteEmployee(
        employeeId: UUID,
        userId: String,
    )

    /**
     * Updates employee's last login timestamp.
     *
     * @param employeeId UUID of the employee
     * @param loginTime Timestamp of login
     * @throws NoSuchElementException if employee not found
     */
    fun updateLastLogin(
        employeeId: UUID,
        loginTime: LocalDateTime,
    )

    /**
     * Sets the rotation pool flag for an employee.
     *
     * @param id UUID of the employee
     * @param enabled Whether to include in the rotation pool
     * @return Updated Employee entity or null if not found
     */
    fun setRotationPool(
        id: UUID,
        enabled: Boolean,
    ): Employee?

    /**
     * Point an employee at the permission group with this code.
     *
     * Returns false if no group carries the code, so the caller can reject a typo rather than leave
     * the employee unassigned — an unassigned employee resolves to NO capabilities and can reach
     * nothing, which fails closed but gives an operator nothing to go on.
     *
     * Unlike the role-derived assignment this is EXPLICIT, and `updateEmployee` deliberately leaves
     * it alone unless the employee's role actually changes.
     */
    fun setPermissionGroupByCode(
        id: UUID,
        groupCode: String,
    ): Boolean

    /** The code of the permission group this employee belongs to, or null if they belong to none. */
    fun getPermissionGroupCode(id: UUID): String?

    /**
     * The `(code, name)` of the permission group carrying this code, or null if none does.
     *
     * Reads the ROW rather than `CapabilityBundles`, which is only the seed. Once a group is
     * editable, an operator-created group exists in the table and not in the compiled definitions —
     * so anything answering from the definitions would reject it.
     */
    fun findPermissionGroup(code: String): Pair<String, String>?

    /**
     * Retrieves all active employees currently in the rotation pool.
     *
     * @return List of active Employee entities in the rotation pool, ordered by employee number
     */
    fun getActiveRotationPool(): List<Employee>
}
