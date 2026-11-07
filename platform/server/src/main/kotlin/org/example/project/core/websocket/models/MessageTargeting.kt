/**
 * Message targeting strategies for WebSocket message queue.
 *
 * Defines how messages should be routed to employees based on
 * various criteria including employee ID, role, or custom predicates.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.websocket.models

import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * Sealed interface for message targeting strategies.
 *
 * Supports multiple targeting approaches:
 * - Broadcast to all employees
 * - Target specific employee(s) by ID
 * - Target employees by role
 * - Custom predicate-based filtering
 */
sealed interface MessageTargeting {
    /**
     * Broadcast message to all connected employees.
     */
    data object BroadcastAll : MessageTargeting

    /**
     * Target a specific employee by ID.
     *
     * @property employeeId Target employee ID
     */
    data class TargetEmployee(
        val employeeId: String,
    ) : MessageTargeting

    /**
     * Target multiple specific employees by ID.
     *
     * @property employeeIds Set of target employee IDs
     */
    data class TargetEmployees(
        val employeeIds: Set<String>,
    ) : MessageTargeting

    /**
     * Target a specific physical device by its device id, independent of which employee
     * (if any) is logged in. Used for terminal lifecycle pushes such as revoke/deactivate.
     */
    data class TargetDevice(
        val deviceId: String,
    ) : MessageTargeting

    /**
     * Target all employees with a specific role.
     *
     * @property role Target employee role (e.g., MANAGER, ADMIN)
     */
    data class TargetRole(
        val role: EmployeeRole,
    ) : MessageTargeting

    /**
     * Target employees with any of the specified roles.
     *
     * @property roles Set of target employee roles
     */
    data class TargetRoles(
        val roles: Set<EmployeeRole>,
    ) : MessageTargeting

    /**
     * Target employees using a custom predicate function.
     *
     * Allows for complex targeting logic such as:
     * - Employees on current shift
     * - Employees in specific location
     * - Employees with specific permissions
     *
     * @property description Human-readable description of the targeting criteria
     * @property predicate Function that evaluates whether an employee should receive the message
     */
    data class TargetPredicate(
        val description: String,
        val predicate: (employeeId: String, role: EmployeeRole?) -> Boolean,
    ) : MessageTargeting
}
