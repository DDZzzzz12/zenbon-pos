package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.terminal.domain.TerminalEmployee

/**
 * Personal — the staff list: who works here, in what role, since when.
 *
 * The root is [EmployeeTable], which holds no name at all: the first and last name live in
 * `employee_info`, one to-one hop away, and arrive through the graph under the "Personaldaten"
 * heading. Rooting the subject there instead would have been the shorter route to a name and the
 * wrong one — `employees.info_id` is nullable and a personnel record is optional, so a list rooted on
 * `employee_info` would silently omit every member of staff nobody has filled the form in for. Rooted
 * here, they appear with the name cells blank, which is the honest answer.
 *
 * ## This subject exports personal data
 *
 * Everything below is about identifiable people, so the registry entry for both tables is deliberately
 * the narrowest in the catalogue — see [org.example.project.features.customexport.domain.catalogue.ExportTableRegistry],
 * which states column by column what was left out and why. Date of birth, pay and address are not
 * offered; the first two are additionally denied in
 * [org.example.project.features.customexport.domain.catalogue.ForbiddenExportTargets].
 *
 * ## The capability is [Capability.EMPLOYEE_READ]
 *
 * `EmployeeRules` gives `GET /api/employees/summary` — the staff list — and `GET /api/employee/info`
 * — the personnel-record list — exactly that capability, so an export costs what the screen costs.
 * Deliberately **not** `SELF_READ`, which every role holds for its own row: an export is every row at
 * once, and gating enumeration behind the self-service rung would hand a till login the staff
 * directory in one CSV.
 *
 * ## One route to the personnel record, and it is the reliable one
 *
 * `employees.info_id` and `employee_info.employee_id` describe one relation from both ends, and both
 * are to-one, so the graph used to offer "Personaldaten" twice — once as `employee_info:via:info_id`,
 * once as `employee_info:by:employee_id` — four identical columns under two headings, with no
 * difference an operator could act on.
 *
 * `employees.info_id` is now declared non-traversable in
 * [org.example.project.features.customexport.domain.catalogue.NonTraversableForeignKeys], so only
 * `employee_info:by:employee_id` survives. That is the authoritative side: it is NOT NULL and unique,
 * and it is the column every repository lookup filters on, whereas `info_id` is nullable, written
 * only after the record exists and cleared by `ON DELETE SET NULL` — the forward route could show
 * blank names for a person the backward route finds.
 *
 * This is **not** a general rule that two routes to one table collapse into one. `kassenbuch` keeps
 * both of its routes into `cash_book`, because the entry's own book and the Gegenkasse of a
 * Geldtransit are different relations that happen to share a table; selecting a column from each is
 * still refused at run by `requireOneRoutePerTable`.
 *
 * ## This subject offers no aggregates at all
 *
 * [ExportSubject.offersAggregates] is false here, and nowhere else in the catalogue. Registering
 * `employees` made `sales_invoice` reachable — it declares aggregates and holds two foreign keys into
 * `employees` — so the graph derived per-employee revenue summaries: Umsatz, Anzahl
 * Ausgangsrechnungen, Erste/Letzte, each twinned by Zeitraum, plus rollups reached through `bill` and
 * `kassenbuch_header`. Nobody asked for them; they fell out of the foreign keys.
 *
 * A per-employee revenue league table is **Leistungs- und Verhaltenskontrolle** under § 87 Abs. 1
 * Nr. 6 BetrVG — a means of monitoring performance and conduct, which the works council co-determines.
 * A staff list answers "who works here, in what role, since when" and summarises nothing about a
 * person's output; that is what this subject is, not an accident of how it is implemented.
 *
 * What is *not* suppressed: staff **names** on a bill, cash-book or invoice export. Who booked a
 * receipt is operational fact, already on the screen the export mirrors.
 *
 * The flag closes this subject's front door only. The same figures were reachable from Kassenbelege
 * and Kassenbuch, whose roots hold a foreign key into `employees` — a different root, the same file.
 * Those routes are closed by [org.example.project.features.customexport.domain.catalogue.ExportTable.aggregatesAreMonitoring],
 * set on the `employees` entry, which suppresses any aggregate whose path crosses that table. Both
 * rules are needed and neither implies the other; [ExportSubject.offersAggregates] says why.
 *
 * Whoever flips this flag back is introducing Leistungsdaten into a file that leaves the building. It
 * wants a Betriebsvereinbarung behind it, not a one-line change — see [ExportSubject.offersAggregates],
 * which also states how the suppression is enforced on stored definitions.
 *
 * ## The shared device account is not a member of staff
 *
 * `employees` holds one row that is not a person: the login-less `terminal-device` account, which
 * exists only so an order station with nobody logged in has a real employee id to attribute its
 * orders to. It has an empty password hash, so it cannot be logged into, and there is exactly one of
 * it however many stations a shop runs.
 *
 * A [ExportSubject.basePredicate] drops it, so it can never be a row in this file — not a toggle,
 * because a staff list containing a device is not a variant somebody might want; it is wrong, and an
 * operator reading the CSV cannot tell it from a colleague. It would also silently raise a headcount
 * by one.
 *
 * This says nothing about the account's appearance on the *fiscal* subjects. `Kassenbelege`,
 * `Kassenbuch` and `Ausgangsrechnungen` all offer staff columns over their own foreign key into
 * `employees`, and on a receipt booked at an unattended station the device account IS the recorded
 * operator — the `BEDIENER` the fiscal record names. Suppressing it there would misreport who took
 * the money.
 *
 * ## No shop column
 *
 * `employees` carries no shop of its own — staff are not per-shop data in this schema. Stated rather
 * than omitted, as every other subject states it: a null `shopColumn` must always be a decision
 * somebody wrote down.
 */
object StaffSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "personal",
            label = "Personal",
            root = EmployeeTable,
            // The record's creation. `employee_info.hire_date` is the day somebody actually started,
            // but it is nullable, lives on the optional personnel record and is a `date` rather than a
            // `datetime` — none of which a Zeitraum bound can be built on. So the filter ranges over
            // the one timestamp every row has, and the Eintrittsdatum is offered as a column instead.
            dateColumn = EmployeeTable.createdAt,
            shopColumn = null,
            // A device is not a member of staff. See the heading above.
            basePredicate = { TerminalEmployee.isNotDeviceAccount() },
            // Deliberate, and the only false in the catalogue. See the heading above before changing it.
            offersAggregates = false,
        )
}
