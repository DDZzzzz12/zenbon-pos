package org.example.project.core.domain.dto.employee

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for Employee API operations.
 *
 * Container object for all Employee-related DTOs including request validation,
 * response formatting, and data transformation utilities. Follows the standard
 * object container pattern for organized DTO management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all Employee-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object EmployeeDto {
    @Serializable
    data class EmployeeInfoDto(
        val employeeNumber: String,
        val employeeId: String? = null,
        val lastName: String?,
        val isConnected: Boolean = false,
    )

    /**
     * Request DTO for creating new employees.
     *
     * Contains only core authentication and role data for employee creation.
     * Personal information should be created separately using EmployeeInfoDto.
     */
    @Serializable
    data class CreateEmployeeRequestDto(
        val username: String,
        val password: String,
        val role: String,
        val email: String? = null,
        val pin: String? = null,
        val notes: String? = null,
        val languagePreference: String = "de",
        val employmentStatus: String = "ACTIVE",
        val trainingMode: Boolean = false,
        /** Payroll key (`employees.personnel_number`, varchar(20)); null = none entered. */
        val personnelNumber: String? = null,
    ) {
        /**
         * Validates the create employee request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(username.isNotBlank()) { "Username is required and cannot be blank" }
            require(username.length >= 3) { "Username must be at least 3 characters long" }
            require(username.length <= 50) { "Username must not exceed 50 characters" }

            require(password.isNotBlank()) { "Password is required and cannot be blank" }
            require(password.length >= 6) { "Password must be at least 6 characters long" }

            require(role.isNotBlank()) { "Role is required and cannot be blank" }

            pin?.let { pinCode ->
                require(pinCode.length >= 4) { "PIN must be at least 4 characters long" }
                require(pinCode.all { it.isDigit() }) { "PIN must contain only digits" }
            }

            personnelNumber?.let { number ->
                require(number.length <= 20) { "Personnel number must not exceed 20 characters" }
            }
        }
    }

    /**
     * Request DTO for employee address information.
     *
     * Contains address fields for employee location data with validation
     * requirements and default values.
     */
    @Serializable
    data class AddressRequestDto(
        val street: String,
        val city: String,
        val state: String,
        val zipCode: String,
        val country: String = "US",
    ) {
        /**
         * Validates the address request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(street.isNotBlank()) { "Street address is required and cannot be blank" }
            require(city.isNotBlank()) { "City is required and cannot be blank" }
            require(state.isNotBlank()) { "State is required and cannot be blank" }
            require(zipCode.isNotBlank()) { "ZIP code is required and cannot be blank" }
            require(country.isNotBlank()) { "Country is required and cannot be blank" }
        }
    }

    /**
     * Response DTO for employee address information.
     *
     * Contains address data for API responses with all required fields
     * for display purposes.
     */
    @Serializable
    data class AddressResponseDto(
        val street: String,
        val city: String,
        val state: String,
        val zipCode: String,
        val country: String,
    )

    /**
     * Response DTO for employee data retrieval.
     *
     * Contains core employee authentication and role information.
     * Personal information is available separately via EmployeeInfo API.
     */
    @Serializable
    data class EmployeeResponseDto(
        val id: String,
        val username: String,
        val employeeNumber: String,
        val lastName: String? = null,
        val employmentStatus: String,
        val role: String,
        /**
         * The permission group this employee belongs to — the authority for what they may reach.
         *
         * Null means they belong to none, which resolves to NO capabilities: they can reach nothing
         * at all. That is a broken row rather than a normal state, and a client showing it should
         * say so rather than render an empty permission list as if it were a choice.
         *
         * Only the code. The display name comes from the group list a permissions screen already
         * loads, so the employee response does not have to carry a copy that can go stale.
         */
        val permissionGroupCode: String? = null,
        val isActive: Boolean,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
        val lastLogin: LocalDateTime?,
        val languagePreference: String,
        val notes: String?,
        val pin: String?, // Should be null in responses for security
        val hasEmployeeInfo: Boolean, // Indicates if employee has associated personal information
        val trainingMode: Boolean = false,
        val personnelNumber: String? = null,
        val defaultCostCenterId: String? = null,
        val inRotationPool: Boolean = false,
    )

    /**
     * Request DTO for employee authentication.
     *
     * Contains credentials for login authentication with username and password.
     */
    @Serializable
    data class AuthenticationRequestDto(
        val username: String,
        val password: String,
    ) {
        /**
         * Validates the authentication request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(username.isNotBlank()) { "Username is required and cannot be blank" }
            require(password.isNotBlank()) { "Password is required and cannot be blank" }
        }
    }

    /*
     * `PermissionsUpdateRequestDto` used to live here. Stage 2a retired per-employee grants, its
     * route and service went with them, and nothing has sent one since. Its `validate()` was the
     * last thing in `shared` that referenced `PermissionEnum`.
     */

    /**
     * Response DTO for simplified employee information.
     *
     * Contains basic core employee data for list views and search results
     * without sensitive or personal information.
     */
    @Serializable
    data class EmployeeSummaryResponseDto(
        val id: String,
        val username: String,
        val employeeNumber: String,
        val role: String,
        val employmentStatus: String,
        val isActive: Boolean,
        val hasEmployeeInfo: Boolean,
        val trainingMode: Boolean = false,
        val inRotationPool: Boolean = false,
        /**
         * The HR profile's first and last name joined, or null when the employee has no HR record
         * or the caller may not read HR data (names are personnel data: the server fills this only
         * for a caller holding `EMPLOYEE_READ`). Defaults to null so an older server's response
         * still decodes; clients show `displayName ?: username` (see [shownName]).
         */
        val displayName: String? = null,
    ) {
        /** The name to show for this employee: the HR name when present, else the login name. */
        fun shownName(): String = displayName?.takeIf { it.isNotBlank() } ?: username

        /** Whether [query] (case-insensitive, already trimmed) hits the shown name, login name or number. */
        fun matches(query: String): Boolean =
            query.isBlank() ||
                shownName().contains(query, ignoreCase = true) ||
                username.contains(query, ignoreCase = true) ||
                employeeNumber.contains(query, ignoreCase = true)
    }

    /**
     * Request DTO for employee password change operations.
     *
     * Contains current and new password for secure password updates.
     */
    @Serializable
    data class PasswordChangeRequestDto(
        val currentPassword: String,
        val newPassword: String,
    ) {
        /**
         * Validates the password change request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(currentPassword.isNotBlank()) { "Current password is required and cannot be blank" }
            require(newPassword.isNotBlank()) { "New password is required and cannot be blank" }
            require(newPassword.length >= 6) { "New password must be at least 6 characters long" }
            require(currentPassword != newPassword) { "New password must be different from current password" }
        }
    }

    /** Manager/admin reset of an employee's password — no current password required. */
    @Serializable
    data class AdminResetPasswordRequestDto(
        val newPassword: String,
    ) {
        fun validate() {
            require(newPassword.isNotBlank()) { "New password is required and cannot be blank" }
            require(newPassword.length >= 6) { "New password must be at least 6 characters long" }
        }
    }

    /** Manager/admin reset of an employee's PIN. */
    @Serializable
    data class AdminResetPinRequestDto(
        val pin: String,
    ) {
        fun validate() {
            require(pin.length in 4..6 && pin.all { it.isDigit() }) { "PIN must be 4-6 digits" }
        }
    }

    /**
     * Request DTO for employee status updates.
     *
     * Contains employment status for employee activation/deactivation.
     */
    @Serializable
    data class EmploymentStatusUpdateRequestDto(
        val employmentStatus: String,
        val reason: String? = null,
    ) {
        /**
         * Validates the employment status update request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            reason?.let { reasonText ->
                require(reasonText.length <= 500) { "Reason must not exceed 500 characters" }
            }
        }
    }

    /**
     * Request DTO for updating existing employees.
     *
     * Contains only core authentication and role fields for employee modification.
     * Personal information should be updated separately using EmployeeInfoDto.
     * All fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateEmployeeRequestDto(
        val role: String? = null,
        val pin: String? = null,
        val notes: String? = null,
        val languagePreference: String? = null,
        val employmentStatus: String? = null,
        val trainingMode: Boolean? = null,
        val personnelNumber: String? = null,
        val defaultCostCenterId: String? = null,
    ) {
        /**
         * Validates the update employee request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            pin?.let { pinCode ->
                require(pinCode.length >= 4) { "PIN must be at least 4 characters long" }
                require(pinCode.all { it.isDigit() }) { "PIN must contain only digits" }
            }
        }
    }

    /**
     * Request DTO for updating an employee's rotation pool membership.
     *
     * Used by the manager-only endpoint to include or exclude an employee
     * from the round-robin auto-assignment pool.
     */
    @Serializable
    data class RotationPoolUpdateRequestDto(
        val inRotationPool: Boolean,
    )

    /**
     * Point an employee at an existing permission group.
     *
     * The group is the authority for what they may reach; `role` survives only for the JWT claim and
     * client display. Assigning a group whose code is not one of the seeded ones is rejected rather
     * than ignored — a typo would otherwise leave the employee in no group, which fails closed but
     * inexplicably.
     *
     * This does NOT create or edit groups; that is the Stage 5 group API.
     */
    @Serializable
    data class AssignPermissionGroupRequestDto(
        val groupCode: String,
    )

    /** What an employee's group assignment looks like after a change. */
    @Serializable
    data class PermissionGroupAssignmentDto(
        val employeeId: String,
        val groupCode: String,
        val groupName: String,
    )
}
