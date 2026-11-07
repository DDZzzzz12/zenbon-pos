/**
 * Employee entity class representing employees in the POS system.
 *
 * Core entity for managing employee information, authentication, and access control.
 * Includes personal information, employment details, POS-specific data, and audit tracking.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.employee.domain.models

import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.WorkSchedules
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.*
import org.jetbrains.exposed.v1.dao.*
import org.jetbrains.exposed.v1.dao.java.*
import java.util.UUID

/**
 * Employee entity representing a user in the POS system.
 *
 * Manages all employee-related data including authentication credentials,
 * personal information, employment details, and system permissionEnums.
 */
class Employee(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<Employee>(EmployeeTable)

    // Authentication & Identity

    /** Unique username for system login */
    var username by EmployeeTable.username

    /** Hashed password for authentication */
    var passwordHash by EmployeeTable.passwordHash

    /** Unique employee identification number */
    var employeeNumber by EmployeeTable.employeeNumber

    /** Current employment status */
    var employmentStatus by EmployeeTable.employmentStatus

    /** Employee info details (1:1 relationship - optional) */
    var employeeInfo by EmployeeInfo optionalReferencedOn EmployeeTable.infoId

    // POS-Specific Data
    /** Employee's role in the system */
    var role by EmployeeTable.role

    /** PIN for quick access (optional) */
    var pin by EmployeeTable.pin

    // Audit & Tracking
    /** When the employee record was created */
    var createdAt by EmployeeTable.createdAt

    /** When the employee record was last updated */
    var updatedAt by EmployeeTable.updatedAt

    /** Last login timestamp (optional) */
    var lastLogin by EmployeeTable.lastLogin

    /** Additional notes about the employee (optional) */
    var notes by EmployeeTable.notes

    // Optional Advanced Features
    /** Employee's preferred language for the interface */
    var languagePreference by EmployeeTable.languagePreference

    /** Whether the employee is in training mode (DSFinV-K AVTraining) */
    var trainingMode by EmployeeTable.trainingMode

    // Time tracking
    var defaultCostCenterId by EmployeeTable.defaultCostCenterId
    var personnelNumber by EmployeeTable.personnelNumber

    /** Whether this employee is in the round-robin rotation pool */
    var inRotationPool by EmployeeTable.inRotationPool

    /** Employee's work schedules */
    val workSchedule by WorkSchedule referrersOn WorkSchedules.employeeId

    // Access shops this employee is assigned to

    // Access permissions assigned to this employee via the junction table

    // Computed properties
    /** Employee's full name (first + last) from employee info */
    val fullName: String
        get() = employeeInfo?.fullName ?: this.username

    /**
     * Computed property that determines if the employee is active.
     *
     * @return true if employment status is ACTIVE, false otherwise
     */
    val isActive: Boolean
        get() = employmentStatus == EmploymentStatus.ACTIVE

    /**
     * Gets the employee's effective role at a specific shop.
     * Falls back to global role if no shop-specific role is set.
     *
     * @param shopId The shop ID to check role for
     * @return The effective role for this shop
     */
    fun getEffectiveRoleForShop(shopId: Long): EmployeeRole {
        // This would typically involve querying ShopEmployeeTable
        // For now, return the global role
        return role
    }

    /**
     * Checks if employee has permission at a specific shop.
     *
     * @param shopId The shop ID
     * @param permission The permission to check
     * @return true if employee has the permission at this shop
     */
    fun hasShopPermission(
        shopId: Long,
        permission: String,
    ): Boolean {
        // This would typically involve querying ShopEmployeeTable
        // Implementation would go here
        return false
    }

    fun isManager(): Boolean = role == EmployeeRole.MANAGER

    fun isAdmin(): Boolean = role == EmployeeRole.ADMIN
}
