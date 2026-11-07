package org.example.project.core.domain.dto.employee
import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.employee.ContractedHoursPeriod
import org.example.project.core.utils.DsfinvkFieldLimits

/**
 * Container object for all EmployeeInfo-related DTOs.
 *
 * Organizes DTOs for employee personal and employment information management,
 * maintaining clear separation between core employee authentication data
 * and detailed personal information.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all EmployeeInfo-related DTOs.
 *
 * Handles personal information, employment details, and address data
 * separately from core employee authentication information.
 */
object EmployeeInfoDto {
    /**
     * Response DTO for EmployeeInfo data.
     *
     * Contains complete employee personal and employment information
     * for API responses and data display.
     */
    @Serializable
    data class EmployeeInfoResponseDto(
        val id: Int,
        val employeeId: String,
        val firstName: String,
        val lastName: String,
        val fullName: String,
        val email: String?,
        val phoneNumber: String?,
        val street: String?,
        val city: String?,
        val state: String?,
        val zipCode: String?,
        val country: String?,
        val dateOfBirth: String?,
        val hireDate: String?,
        val jobTitle: String?,
        val hourlyRate: String?,
        val salary: String?,
        val contractedHours: String?,
        val contractedHoursPeriod: ContractedHoursPeriod?,
        val createdAt: String,
        val updatedAt: String,
    )

    /**
     * Request DTO for creating new employee information.
     *
     * Contains all required and optional fields for creating
     * detailed employee personal and employment information.
     */
    @Serializable
    data class CreateEmployeeInfoRequestDto(
        val employeeId: String,
        val firstName: String,
        val lastName: String,
        val email: String? = null,
        val phoneNumber: String? = null,
        val street: String? = null,
        val city: String? = null,
        val state: String? = null,
        val zipCode: String? = null,
        val country: String? = null,
        val dateOfBirth: String? = null,
        val hireDate: String? = null,
        val jobTitle: String? = null,
        val hourlyRate: String? = null,
        val salary: String? = null,
        val contractedHours: String? = null,
        val contractedHoursPeriod: ContractedHoursPeriod? = null,
    ) {
        /**
         * Validates the create request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(firstName.isNotBlank()) { "First name is required" }
            require(lastName.isNotBlank()) { "Last name is required" }
            require(employeeId.isNotBlank()) { "Employee ID is required" }

            // M1: the exporter emits `first last` as BEDIENER_NAME on EVERY Bonkopf row, so the
            // COMBINED string is what has to fit — checking the halves separately would let
            // 25 + 25 characters through as a 51-character value. Rejected here rather than
            // truncated at export, per the round-7 C7 ruling: a fiscal record must not be
            // silently rewritten.
            DsfinvkFieldLimits.requireMaxLength(
                DsfinvkFieldLimits.displayName(firstName, lastName),
                DsfinvkFieldLimits.BEDIENER_NAME,
                "Employee name (BEDIENER_NAME)",
            )

            // Validate email format if provided
            email?.let { emailAddress ->
                require(emailAddress.contains("@") && emailAddress.contains(".")) {
                    "Invalid email format"
                }
            }
        }
    }

    /**
     * Request DTO for updating existing employee information.
     *
     * All fields are optional to support partial updates.
     * Only provided fields will be updated in the database. A field sent as `""` (blank) is CLEARED
     * (stored as null) — the way an operator erases a phone number, address or pay figure (GDPR
     * erasure); `null` leaves the stored value untouched. The two name fields cannot be cleared.
     */
    @Serializable
    data class UpdateEmployeeInfoRequestDto(
        val firstName: String? = null,
        val lastName: String? = null,
        val email: String? = null,
        val phoneNumber: String? = null,
        val street: String? = null,
        val city: String? = null,
        val state: String? = null,
        val zipCode: String? = null,
        val country: String? = null,
        val dateOfBirth: String? = null,
        val hireDate: String? = null,
        val jobTitle: String? = null,
        val hourlyRate: String? = null,
        val salary: String? = null,
        val contractedHours: String? = null,
        val contractedHoursPeriod: ContractedHoursPeriod? = null,
    ) {
        /**
         * Validates the update request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Validate non-blank fields if provided
            firstName?.let { name ->
                require(name.isNotBlank()) { "First name cannot be blank" }
            }

            lastName?.let { name ->
                require(name.isNotBlank()) { "Last name cannot be blank" }
            }

            // M1: BEDIENER_NAME is the combined `first last`. A partial update only carries the
            // half being changed, so the strict combined check is only possible when BOTH are
            // present; when one is, it alone must still fit (the other half can only add to it).
            // The service applying the patch is the exact boundary — this catches the egregious
            // cases at the API edge.
            if (firstName != null && lastName != null) {
                DsfinvkFieldLimits.requireMaxLength(
                    DsfinvkFieldLimits.displayName(firstName, lastName),
                    DsfinvkFieldLimits.BEDIENER_NAME,
                    "Employee name (BEDIENER_NAME)",
                )
            } else {
                DsfinvkFieldLimits.requireMaxLength(firstName, DsfinvkFieldLimits.BEDIENER_NAME, "Employee first name (BEDIENER_NAME)")
                DsfinvkFieldLimits.requireMaxLength(lastName, DsfinvkFieldLimits.BEDIENER_NAME, "Employee last name (BEDIENER_NAME)")
            }

            // Validate email format if provided. A BLANK email is the clear marker (see the class
            // KDoc: "" empties a stored field), so only a non-blank one must look like an address.
            email?.takeIf { it.isNotBlank() }?.let { emailAddress ->
                require(emailAddress.contains("@") && emailAddress.contains(".")) {
                    "Invalid email format"
                }
            }
        }
    }

    /**
     * Summary DTO for lightweight employee information display.
     *
     * Contains only essential information for lists and summaries
     * without detailed employment or address information.
     */
    @Serializable
    data class EmployeeInfoSummaryDto(
        val id: Int,
        val employeeId: String,
        val fullName: String,
        val email: String?,
        val jobTitle: String?,
        val hireDate: String?,
    )

    /**
     * Basic employee information returned with login response.
     */
    @Serializable
    data class EmployeeInfo(
        val id: String,
        val username: String,
        val employeeNumber: String,
        val firstName: String? = null,
        val lastName: String? = null,
        val role: String,
        val language: String? = null,
        val trainingMode: Boolean = false,
        /**
         * The capabilities this employee's permission group confers — what the server will actually
         * enforce, so the client can gate on the same answer instead of inferring it from [role].
         *
         * **Strings, not the `Capability` enum, on the wire.** A client meeting a server that has
         * added a capability must ignore the unknown name, not fail to deserialize the whole
         * response; the client maps names to the enum with `mapNotNull`. This is the same reason
         * `permission_group_capability.capability` is a plain column with no foreign key.
         *
         * Defaults to empty so an older server's response still deserializes. An empty set means
         * "this server does not report capabilities", which a client must treat as *unknown*, not as
         * "holds nothing" — falling back to role-based gating until C2 completes.
         */
        val capabilities: Set<String> = emptySet(),
        /**
         * The code of the permission group [capabilities] come from, or null when the employee
         * belongs to none (or the server predates this field).
         *
         * Reported so a client can say *who* is signed in by the authority that actually applies —
         * an AUDITOR carrying role CASHIER must not be labelled "Kassierer" (R6-4). A seeded group
         * is translated by this code on the client; see [permissionGroupName] for custom groups.
         */
        val permissionGroupCode: String? = null,
        /**
         * The group's stored display name. Seeded names are English and editable, so a client
         * prefers its own translation for a seeded code and falls back to this for an
         * operator-made group. Null when there is no group or the server predates the field.
         */
        val permissionGroupName: String? = null,
    )
}
