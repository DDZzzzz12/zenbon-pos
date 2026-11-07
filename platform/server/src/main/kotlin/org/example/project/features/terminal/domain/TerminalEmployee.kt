package org.example.project.features.terminal.domain

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.infrastructure.errors.PosException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq

/**
 * The single shared, login-less employee every device-token request is attributed to.
 *
 * A login-less order station has no person behind it, yet every order still needs an `employeeId`
 * for fiscal attribution — so exactly one row, username [USERNAME], stands in for all of them.
 * Both the device-principal path (`getDeviceTokenClaims`) and the station-session exchange
 * (`StationSessionServiceImpl`) must resolve the SAME row: one username literal and one lookup, so
 * a later edit cannot leave the two disagreeing. A missing row is a broken install rather than a
 * client error, hence the 500 — the row is provisioned at setup and no caller could have acted
 * differently.
 */
object TerminalEmployee {
    /** Username of the shared login-less employee. */
    const val USERNAME = "terminal-device"

    /**
     * Employee number of the shared login-less employee.
     *
     * Same literal `V79__release_tse_audit_terminal.sql` step 6a/6b writes, so the migration path
     * and the fresh-install seeding in `SchemaManager` produce the identical row.
     */
    const val EMPLOYEE_NUMBER = "TERMINAL-DEVICE"

    /**
     * What the shared device account is CALLED wherever a human-readable operator name is shown.
     *
     * Not a person's name — there is no person. It names the thing that booked the order: a station
     * running without a login. It exists because the account has no `employee_info` row (a personnel
     * record belongs to a human, and the staff-export exclusion assumes the device has none), so
     * every "first last, else username" fallback in the codebase resolved to the raw technical
     * username `terminal-device` — lowercase developer text in, among other places, the DSFinV-K
     * `BEDIENER_NAME` column of a German fiscal export.
     *
     * German by intent, and deliberately NOT routed through the UI translation system: DSFinV-K
     * Anhang E defines `BEDIENER_NAME` as the *unternehmensinterner* name of the operator, so it is
     * one fixed internal label per install, not a per-viewer rendering. Every other consumer (Z-Bon
     * employee summaries, the statistics operator rollups) reads the same server-produced string, so
     * one constant keeps the fiscal export and the screens saying the same thing about the same
     * account.
     *
     * Distinct from [EMPLOYEE_NUMBER] on purpose: `BEDIENER_ID` stays `TERMINAL-DEVICE` (audit
     * BKOPF-9 — the two Bonkopf fields must not be the same value), and [EMPLOYEE_NUMBER] must never
     * change because it is already in shipped fiscal exports.
     */
    const val DISPLAY_NAME = "Terminal (ohne Anmeldung)"

    /**
     * [username] itself, unless it is the shared device account — then [DISPLAY_NAME].
     *
     * Deliberately keyed on the device account alone rather than on "this employee has no personnel
     * record": a colleague whose `employee_info` row is missing is still a person, and labelling them
     * a terminal would put a false operator on a receipt. Their username-as-name fallback is a
     * cosmetic gap; the device's was a wrong kind of value.
     */
    fun displayName(username: String): String = if (isDeviceAccount(username)) DISPLAY_NAME else username

    /**
     * True when this username is the shared device account rather than a person.
     *
     * The **username** is the key, not the role and not the employee number, because it is the key
     * the rest of the system already resolves the account by: [findRow] looks it up by username, and
     * `V79` steps 6a/6b claim it by username. Anything that identifies the account differently from
     * the way it is resolved can disagree with it, and a staff list that disagrees is one that either
     * shows a device or hides a colleague.
     *
     *  - **Role is too broad.** `TERMINAL_DEVICE` is a role a human can in principle be given — `V79`
     *    renamed the old per-person `TERMINAL` role onto it, so upgraded installs may well hold
     *    people carrying it. Filtering on the role would delete those colleagues from the staff list
     *    and from every export of it.
     *  - **Employee number is decoration.** [EMPLOYEE_NUMBER] is normalized onto the row for display
     *    and is not the column any lookup filters on; nothing enforces that a person cannot be given
     *    it, and nothing breaks if the account carries a different one.
     *
     * There is exactly one such row by construction: the username column is unique, and both the
     * migration and [org.example.project.database.SchemaManager] adopt-or-create against it.
     */
    fun isDeviceAccount(username: String): Boolean = username == USERNAME

    /**
     * The same test as [isDeviceAccount], as a SQL predicate over [EmployeeTable].
     *
     * Belongs in the query rather than in a filter over the results: a roster read is paged, so
     * dropping the row afterwards would silently shorten one page, and the custom-export runner
     * builds SQL and counts what the database returned.
     */
    fun isNotDeviceAccount(): Op<Boolean> = EmployeeTable.username neq USERNAME

    /**
     * The shared employee row, or null when the install has not provisioned it.
     *
     * The one place the row is looked up. Seeding needs "is it there?" while the request paths need
     * "it must be there", and two lookups written separately are exactly how the username literal
     * would drift apart again — so [requireRow] is this function plus a throw.
     *
     * Requires an ambient Exposed transaction.
     */
    fun findRow(): Employee? = Employee.find { EmployeeTable.username eq USERNAME }.firstOrNull()

    /**
     * The shared employee row, or a 500 if it is not provisioned.
     *
     * Requires an ambient Exposed transaction — both call sites already open one via `dbQuery`,
     * so the query stays non-suspending and each keeps its own transaction semantics.
     */
    fun requireRow(): Employee =
        findRow()
            ?: throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "Login-less terminal-employee is not provisioned",
            )
}
