package org.example.project.core.domain.enums.employee

import kotlinx.serialization.Serializable

/**
 * Enum representing the role/position of an employee in the organization.
 *
 * Used for role-based access control and determining which operations
 * an employee is authorized to perform.
 */
@Serializable
enum class EmployeeRole(
    val id: Int,
    val code: String,
    val displayName: String,
    val description: String,
) {
    // ================================
    // CORE ROLES (Employee Position-Based Access)
    // ================================

    /** Role for terminal accounts - limited to essential POS operations only. Also the system operator for login-less order stations. */
    TERMINAL_DEVICE(
        1,
        "TERMINAL_DEVICE",
        "Terminal Device",
        "Basic POS operations only. Also the system operator for login-less order stations.",
    ),

    /** Role for cashiers - standard cashier operations with some restrictions */
    CASHIER(
        2,
        "CASHIER",
        "Cashier",
        "All basic operations plus discounts, returns, cash drawer management. Cannot modify prices or cancel orders.",
    ),

    /** Role for managers - full operational control with limited admin access */
    MANAGER(
        4,
        "MANAGER",
        "Manager",
        "Full operational control including employee management, inventory control, reporting. Cannot modify system settings.",
    ),

    /** Role for system administrators - complete system access */
    ADMIN(
        6,
        "ADMIN",
        "Administrator",
        "Complete system access including configuration, backups, system setup, all operations.",
    ),
    ;

    companion object {
        /**
         * The role [role] denotes, resolving the two spellings this project has retired and
         * answering [TERMINAL_DEVICE] — the **least-privileged** constant — for anything else.
         *
         * Deliberately total, and used for three different inputs:
         *  - a request body's role string,
         *  - a JWT `role` claim (`RouteAuthorizationPlugin`),
         *  - and, since the read fix, the **stored `employees.role` column** itself
         *    (`EmployeeTable.role` maps it with `customEnumeration`, because
         *    `enumerationByName` throws on a value renamed out from under it and a restore can
         *    reload pre-rename rows *after* migrations have run).
         *
         * Because the last of those is a live authorization input, the `else` branch must stay the
         * least-privileged value — never `CASHIER`, never `ADMIN`. It is not on its own what makes
         * an unrecognised role safe: capabilities come from the employee's `permission_group`, not
         * from this enum (see `GroupCapabilityResolver`, which fails closed to the empty set). But
         * a widening default here would undo that in every place the role is still read directly.
         *
         * `TERMINAL` is V79's pre-rename spelling of [TERMINAL_DEVICE]; `BASIC_EMPLOYEE` is V42's
         * pre-rename spelling of `TERMINAL`. Both are mapped so a legacy value *reads*; neither is
         * rewritten in storage — `RestoreRepairs.normalizeLegacyTerminalRole` records why
         * `BASIC_EMPLOYEE`, which used to mean a person rather than a device, must not be.
         *
         * **No enum entry is added for either.** `CapabilityBundles.DEFINITIONS` is generated from
         * `EmployeeRole.entries`, so a new constant would seed a new `permission_group` whose code
         * equals the legacy role string — and V85's back-fill joins `permission_group ON code =
         * employees.role`, which would then *attach* the legacy row to it and hand it capabilities.
         * A retired entry here grants access; the `ChangeEntityType` retired-spelling pattern does
         * not transfer to this enum.
         */
        fun getRole(role: String?): EmployeeRole =
            when (role?.uppercase()) {
                "TERMINAL", "TERMINAL_DEVICE", "BASIC_EMPLOYEE" -> TERMINAL_DEVICE
                "CASHIER" -> CASHIER
                "MANAGER" -> MANAGER
                "ADMIN" -> ADMIN
                else -> TERMINAL_DEVICE
            }

        fun isManagerOrAdmin(role: String?) = getRole(role).isManagerOrHigher()
    }
}

fun EmployeeRole.isManagerOrHigher() = this == EmployeeRole.MANAGER || this == EmployeeRole.ADMIN

fun EmployeeRole.isCashier() = this == EmployeeRole.CASHIER

fun EmployeeRole.isTerminal() = this == EmployeeRole.TERMINAL_DEVICE
