package org.example.project.features.customexport

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportAggregateField
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.catalogue.ForbiddenExportTargets
import org.example.project.features.customexport.domain.catalogue.NonTraversableForeignKeys
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.rules.EmployeeRules
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Personal — the staff subject, and the one registration in this catalogue where what was **left
 * out** is the whole decision.
 *
 * Credentials were never the interesting part: `employees.password_hash` and `employees.pin` were on
 * [ForbiddenExportTargets.COLUMNS] before this subject existed. What this file pins is the rest —
 * date of birth, home address, pay, contract terms, the login name, the last-login timestamp and the
 * free-text notes — none of which is a secret and every one of which is about an identifiable person
 * who did not choose to be in a spreadsheet.
 */
@DisplayName("Personal, the staff subject")
class StaffSubjectTest {
    @Test
    fun `the subject is Personal, rooted on employees, and carries no shop`() {
        val subject = assertNotNull(ExportCatalogue.subject("personal"))

        assertEquals("Personal", subject.label)
        assertEquals(EmployeeTable, subject.root)
        assertEquals(EmployeeTable.createdAt, subject.dateColumn)
        assertEquals(
            null,
            subject.shopColumn,
            "`employees` carries no shop of its own; the decision is written down in StaffSubject's KDoc",
        )
    }

    /**
     * `EMPLOYEE_READ`, resolved from the rule module rather than restated as a constant.
     *
     * `GET /api/employees/summary` is the staff list and `GET /api/employee/info` is the
     * personnel-record list; both carry `EMPLOYEE_READ`, so an export costs what the screen costs.
     * The plausible wrong neighbour is `SELF_READ`, which every role holds for its **own** row — and
     * which `EmployeeRules` gives `/api/employees/info/active`, the shift roster a till reads. An
     * export is enumeration of every row at once, so gating it there would hand a terminal login the
     * staff directory in one file.
     */
    @Test
    fun `both staff tables are gated by the capability the staff-list route requires`() {
        listOf(EmployeeTable, EmployeeInfoTable).forEach { table ->
            val entry = assertNotNull(ExportTableRegistry.entryFor(table), "unregistered: ${table.tableName}")
            assertEquals(Capability.EMPLOYEE_READ, entry.capability, "on ${table.tableName}")
        }
        assertEquals(
            AccessLevel.Cap(Capability.EMPLOYEE_READ),
            EmployeeRules.rules["/api/employees/summary" to HttpMethod.Get],
            "which is what the staff list route itself carries",
        )
        assertEquals(
            AccessLevel.Cap(Capability.EMPLOYEE_READ),
            EmployeeRules.rules["/api/employee/info" to HttpMethod.Get],
            "and what the personnel-record list carries",
        )
        assertEquals(
            AccessLevel.Cap(Capability.SELF_READ),
            EmployeeRules.rules["/api/employees/info/active" to HttpMethod.Get],
            "the roster every till may read is SELF_READ, and is deliberately not what this costs",
        )
    }

    /** Exactly the four columns a staff list needs, in the order the picker shows them. */
    @Test
    fun `the root offers four columns and no more`() {
        val subject = assertNotNull(ExportCatalogue.subject("personal"))

        assertEquals(
            listOf("personal.employee_number", "personal.role", "personal.employment_status", "personal.created_at"),
            subject.allFields.filter { it.group == null }.map { it.key },
        )
    }

    /**
     * The personnel record contributes a name and nothing else that identifies a private life.
     *
     * Asserted as an exact list rather than as absences: a test that only says "no salary" passes
     * just as well on a registry that has grown eleven other columns.
     */
    @Test
    fun `the personnel record contributes only the name, the position and the start date`() {
        val entry = assertNotNull(ExportTableRegistry.entryFor(EmployeeInfoTable))

        assertEquals(
            listOf("first_name", "last_name", "job_title", "hire_date"),
            entry.columns.map { it.column.name },
        )
        assertEquals("Personaldaten", entry.label)
    }

    /**
     * The list of what a staff export must not carry, stated positively.
     *
     * Every name below is a real column of one of the two registered tables — resolved through
     * `Column.name` rather than typed as a string, so a rename cannot leave this test guarding a
     * column that no longer exists. Nothing offered anywhere in the catalogue may read one.
     */
    @Test
    fun `no offered field anywhere reads a private staff column`() {
        val withheld =
            listOf(
                EmployeeTable.username,
                EmployeeTable.passwordHash,
                EmployeeTable.pin,
                EmployeeTable.lastLogin,
                EmployeeTable.notes,
                EmployeeTable.personnelNumber,
                EmployeeInfoTable.dateOfBirth,
                EmployeeInfoTable.salary,
                EmployeeInfoTable.hourlyRate,
                EmployeeInfoTable.contractedHours,
                EmployeeInfoTable.street,
                EmployeeInfoTable.city,
                EmployeeInfoTable.zipCode,
                EmployeeInfoTable.email,
                EmployeeInfoTable.phoneNumber,
            ).map { "${it.table.tableName.lowercase()}.${it.name.lowercase()}" }

        val offered =
            ExportCatalogue.subjects
                .flatMap { subject -> subject.allFields.map { "${it.column.table.tableName.lowercase()}.${it.column.name.lowercase()}" } }
                .toSet()

        val leaked = withheld.filter { it in offered }
        assertEquals(emptyList(), leaked, "a private staff column is offered by the catalogue: $leaked")
    }

    /**
     * The denylist half, and the proof that it is not vacuous.
     *
     * The allowlist is what protects these columns today; the denylist is what stops a future
     * contributor registering "Gehalt" or "Geburtsdatum" in good faith. A denylist entry spelled the
     * Kotlin way (`dateOfBirth`) or against the wrong table would match nothing and report clean
     * forever, so the strings are resolved through the same accessors the guard uses and the denial
     * is asserted on the resolved value — and on a column that must stay exportable, or a rule that
     * denied everything would also pass.
     */
    @Test
    fun `the denylist matches the pay and date-of-birth columns Exposed actually reports`() {
        listOf(
            "employees.username" to EmployeeTable.username,
            "employee_info.date_of_birth" to EmployeeInfoTable.dateOfBirth,
            "employee_info.salary" to EmployeeInfoTable.salary,
            "employee_info.hourly_rate" to EmployeeInfoTable.hourlyRate,
        ).forEach { (expected, column) ->
            assertEquals(expected, "${column.table.tableName}.${column.name}")
            assertTrue(expected in ForbiddenExportTargets.COLUMNS, "not on the denylist: $expected")
            assertTrue(ForbiddenExportTargets.deniesColumn(expected), "deniesColumn does not fire on $expected")
        }
        assertTrue(
            !ForbiddenExportTargets.deniesColumn("employee_info.first_name"),
            "the staff denials are not so broad that a name is caught by them",
        )
        assertTrue(
            !ForbiddenExportTargets.deniesColumn("employees.employee_number"),
            "nor the identifier a staff list is keyed by",
        )
    }

    /**
     * Registering `employees` did not make it exportable only under its own subject.
     *
     * Three tables hold a foreign key into it — `bill.employee_id`, `kassenbuch_header.employee_id`
     * and `sales_invoice.issued_by`/`cancelled_by` — so a Kassenbeleg, a cash-book entry and an
     * outgoing invoice can now each carry the name of the person behind it. That is the
     * register-once-reachable-everywhere property working as designed, and it is worth pinning
     * because it is a *widening of who appears in other areas' files*, not a cosmetic one.
     *
     * The bound is the path-capability rule: reaching a staff column from another subject's root
     * costs `EMPLOYEE_READ` **on top of** that area's own capability, so a reader of bills who may
     * not read staff records is offered none of it.
     */
    @Test
    fun `staff columns are reachable from the fiscal subjects, and cost EMPLOYEE_READ on top`() {
        val bill = assertNotNull(ExportCatalogue.subject("bill"))
        val name =
            assertNotNull(
                bill.allFields.firstOrNull { it.key == "employees:via:employee_id.employee_info:by:employee_id.last_name" },
                "the bill's employee's surname is not offered; offered: ${bill.allFields.map { it.key }}",
            )

        // One heading, because there is now one route: `employees.info_id` is declared
        // non-traversable, so the personnel record is reached only over its authoritative link.
        assertEquals("Personal → Personaldaten", name.group)
        assertEquals(
            setOf(Capability.BILL_READ, Capability.EMPLOYEE_READ),
            assertNotNull(bill.capabilityRequirements()[name.key]),
        )
        assertTrue(
            bill.offeringsVisibleTo(setOf(Capability.BILL_READ)).none { it.key == name.key },
            "a reader of bills who may not read staff records must not be offered a staff column",
        )
        assertTrue(
            bill.offeringsVisibleTo(setOf(Capability.BILL_READ, Capability.EMPLOYEE_READ)).any { it.key == name.key },
            "and holding both must offer it, or the assertion above passes by absence",
        )
    }

    /**
     * The same hop out of the cash book and the outgoing invoice, so the reach is stated in full.
     *
     * The **direct** routes are the ones a reader should think about: one foreign key each on `bill`
     * and `kassenbuch_header`, and two on `sales_invoice` because who issued an invoice and who
     * cancelled it are different people. The second assertion is the part that surprises: at two hops
     * a bill also reaches the staff row *through the invoice it clears*, so a Kassenbeleg export can
     * name the person who issued the receivable as well as the person who rang the sale. Both are
     * gated by `EMPLOYEE_READ` on top of the other two areas' capabilities.
     */
    @Test
    fun `the cash book and the outgoing invoice reach the staff row by their own foreign keys`() {
        fun directRoutes(table: org.jetbrains.exposed.v1.core.Table) =
            SchemaGraph
                .pathsFrom(table)
                .filter { it.target == EmployeeTable && it.hops.size == 1 }
                .map { it.key }

        assertEquals(listOf("employees:via:employee_id"), directRoutes(KassenbuchHeaderTable))
        assertEquals(listOf("employees:via:employee_id"), directRoutes(BillTable))
        assertEquals(
            listOf("employees:via:issued_by", "employees:via:cancelled_by"),
            directRoutes(SalesInvoiceTable),
            "two foreign keys, two routes — who issued it and who cancelled it are different people",
        )
        assertEquals(
            listOf(
                "sales_invoice:via:clears_sales_invoice_id.employees:via:issued_by",
                "sales_invoice:via:clears_sales_invoice_id.employees:via:cancelled_by",
                "sales_invoice:by:bill_id.employees:via:issued_by",
                "sales_invoice:by:bill_id.employees:via:cancelled_by",
            ),
            SchemaGraph
                .pathsFrom(BillTable)
                .filter { it.target == EmployeeTable && it.hops.size == 2 }
                .map { it.key },
            "a bill also reaches staff through the outgoing invoice it clears, and through the one " +
                "raised from it — the reach is two hops wide, not one",
        )
    }

    /**
     * The personnel record is reachable from the staff root **exactly once**, over its reliable link.
     *
     * `employees.info_id` and `employee_info.employee_id` are one relation modelled from both ends.
     * The walker, reading structure alone, saw two, and the picker showed the same four columns twice
     * — "Personaldaten" and "Personaldaten (alternative Verknüpfung)" — a choice with nothing behind
     * it. `NonTraversableForeignKeys` bans the back-reference, so one route remains.
     *
     * The surviving one is `by:employee_id`, and which one survives is the substance of the fix
     * rather than a coin toss: that column is NOT NULL and unique (V90), and it is what every
     * `EmployeeInfoRepositoryImpl` lookup filters on. `info_id` is nullable, written only after the
     * record exists and cleared by `ON DELETE SET NULL`, so the dropped route was the one that could
     * show blank names for a person the surviving route finds.
     */
    @Test
    fun `the personnel record is reachable by exactly one to-one route, the authoritative one`() {
        val paths = SchemaGraph.pathsFrom(EmployeeTable).filter { it.target == EmployeeInfoTable }

        assertEquals(listOf("employee_info:by:employee_id"), paths.map { it.key })
        assertEquals(Cardinality.ONE, paths.single().cardinality)
        assertEquals(listOf("Personaldaten"), paths.map { it.label })
        assertEquals(
            EmployeeInfoTable.employeeId,
            paths
                .single()
                .hops
                .single()
                .to,
            "the route that survived must be the one joining on employee_info.employee_id",
        )
    }

    /**
     * The four personnel columns did not go away with the duplicate group.
     *
     * The failure mode of over-correcting this defect is losing the name entirely — a staff list with
     * no first or last name is worse than one showing them twice. So the columns are asserted by key,
     * under the one surviving prefix.
     */
    @Test
    fun `the name, position and start date are still offered, under the one surviving prefix`() {
        val subject = assertNotNull(ExportCatalogue.subject("personal"))

        assertEquals(
            listOf(
                "employee_info:by:employee_id.first_name",
                "employee_info:by:employee_id.last_name",
                "employee_info:by:employee_id.job_title",
                "employee_info:by:employee_id.hire_date",
            ),
            subject.allFields.filter { it.group != null }.map { it.key },
        )
        assertEquals(
            listOf("Personaldaten"),
            subject.allFields.mapNotNull { it.group }.distinct(),
            "one heading over the personnel columns, not two",
        )
    }

    /**
     * `employees.info_id` is not walked from **any** root, in either direction.
     *
     * The ban is on the foreign key, not on one subject's view of it. A future subject rooted on
     * `employee_info`, or a longer path passing through it, must not quietly reacquire the route —
     * hence the sweep over every registered table rather than over the staff subject alone.
     */
    @Test
    fun `the back-reference is never traversed, from any root`() {
        val banned = NonTraversableForeignKeys.SEGMENTS
        assertEquals(
            setOf("employee_info:via:info_id", "employees:by:info_id"),
            banned,
            "both directions of employees.info_id, because a ban on a key is not a ban on a direction",
        )

        val walked =
            ExportTableRegistry.entries
                .map { it.table }
                .flatMap { SchemaGraph.pathsFrom(it) }
                .flatMap { it.key.split(".") }
                .toSet()

        assertEquals(
            emptySet(),
            walked intersect banned,
            "a banned edge reappeared in a path key",
        )
        assertTrue(
            "employee_info:by:employee_id" in walked,
            "and the surviving route must actually be walked, or the assertion above passes by " +
                "the graph having stopped producing paths at all",
        )
    }

    /**
     * The ban is a **declaration**, not a structural rule — and the cash-book pair is what proves it.
     *
     * `kassenbuch_header` holds `cash_book_id` and `counterparty_book_id`: two foreign keys into one
     * table, structurally identical to the `employee_info` case and semantically its opposite. The
     * entry's own book and the Gegenkasse of a Geldtransit are different rows answering different
     * questions, and an operator needs both. A fix that deduped paths by target table would have
     * taken one of them silently.
     */
    @Test
    fun `the two cash books a Kassenbuch entry reaches survive the ban`() {
        val books = SchemaGraph.pathsFrom(KassenbuchHeaderTable).filter { it.key.startsWith("cash_book:") }

        assertEquals(
            listOf(
                "cash_book:via:cash_book_id" to "Kasse",
                "cash_book:via:counterparty_book_id" to "Gegenkasse (Geldtransit)",
            ),
            books.map { it.key to it.label },
            "two foreign keys into one table that are genuinely two relations; the personnel fix " +
                "must not have generalised into dropping one of them",
        )
    }

    /**
     * **The staff list offers no summary figures at all**, and this is the assertion that says so.
     *
     * Registering `employees` made them derivable without anybody deciding to: `sales_invoice`
     * declares aggregates and holds two foreign keys into `employees`, so the graph produced
     * per-employee Umsatz, Anzahl Ausgangsrechnungen and Erste/Letzte, each twinned by Zeitraum, plus
     * rollups through `bill` and `kassenbuch_header` — forty cells in all.
     *
     * A per-head revenue league table is Leistungs- und Verhaltenskontrolle under § 87 Abs. 1 Nr. 6
     * BetrVG. [ExportSubject.offersAggregates] is therefore false on this subject and on no other; see
     * its KDoc before flipping it.
     *
     * This flag is **not** the whole rule any more, and it is not redundant either. The same figures
     * were reachable from Kassenbelege and Kassenbuch, whose roots hold a foreign key into `employees`;
     * those routes are closed per-path by `ExportTable.aggregatesAreMonitoring` and asserted in
     * `MonitoringAggregateSuppressionTest`. That rule cannot reach this subject — a path never
     * re-enters its own root, so no path from `employees` has `employees` on a hop — which is exactly
     * why the flag stays.
     *
     * Asserted against the **real** catalogue, and the previously-offered keys are named as literals:
     * an emptiness check alone would keep passing if the whole graph stopped producing aggregates for
     * some unrelated reason, and would keep passing just as quietly if these came back under new keys.
     * The literals make the test notice either.
     */
    @Test
    fun `the staff list offers no aggregate at all, because a per-head figure is Leistungsdaten`() {
        val subject = assertNotNull(ExportCatalogue.subject("personal"))

        assertFalse(subject.offersAggregates, "the flag is what the rest of this test rests on")
        assertEquals(emptyList(), subject.allAggregates, "no aggregate cell may be offered on a staff list")
        assertTrue(
            subject.allOfferings.none { it is ExportAggregateField },
            "and none may reach the picker through the combined list either",
        )
        assertTrue(
            subject.capabilityRequirements().keys.none { it.contains('~') },
            "an aggregate key left in the capability map would be a key the save check still accepts",
        )
        assertEquals(
            emptyList(),
            subject.offeringsVisibleTo(Capability.entries.toSet()).filter { it is ExportAggregateField },
            "not even a caller holding every capability is offered one",
        )

        // The four figures that used to be offered directly off `sales_invoice.issued_by`, named so
        // that this test fails if any of them returns.
        val gone =
            listOf(
                "sales_invoice:by:issued_by~sum@period",
                "sales_invoice:by:issued_by~count@period",
                "sales_invoice:by:issued_by~first",
                "sales_invoice:by:cancelled_by~sum",
                "kassenbuch_header:by:employee_id.kassenbuch_line_item:by:header_id~sum",
            )
        val offered = subject.allOfferings.map { it.key }.toSet()
        assertEquals(
            emptyList(),
            gone.filter { it in offered },
            "these were offered before the suppression and must not come back",
        )
        // They are still *derivable* — the graph is unchanged, which is the whole reason the
        // suppression has to be a decision the subject states rather than an absence.
        assertEquals(
            gone.sorted(),
            subject.suppressedAggregates
                .map { it.key }
                .filter { it in gone }
                .sorted(),
            "the cells are withheld, not unreachable: the runner needs them by name to say why",
        )
    }

    /**
     * The **flag** is this subject's, not a global switch — the assertion that would have caught a fix
     * that turned every aggregate in the catalogue off.
     *
     * "Still offers its aggregates" now means *its own*: Kassenbelege and Kassenbuch each withhold the
     * sixteen cells that reach a figure through `employees`, by the per-path rule rather than by this
     * flag, and keep everything else. So this test asserts the flag is false nowhere else **and** that
     * the aggregates a fiscal subject is actually for — its own line and VAT rollups — survived. What
     * the per-path rule takes is pinned in `MonitoringAggregateSuppressionTest`, not here.
     */
    @Test
    fun `every other subject still offers its aggregates`() {
        val others = ExportCatalogue.subjects.filter { it.key != "personal" }

        assertTrue(others.all { it.offersAggregates }, "Personal is the only subject that declines them all")
        assertTrue(
            others.any { it.allAggregates.isNotEmpty() },
            "at least one other subject must actually carry aggregates, or the assertion above is empty",
        )
        val kassenbuch = assertNotNull(ExportCatalogue.subject("kassenbuch"))
        assertTrue(
            "kassenbuch_line_item:by:header_id~sum" in kassenbuch.allAggregates.map { it.key },
            "offered: ${kassenbuch.allAggregates.map { it.key }}",
        )
        val partner = assertNotNull(ExportCatalogue.subject("partner"))
        assertTrue(
            partner.allAggregates.isNotEmpty(),
            "the partner subject's outgoing-invoice summaries are untouched by a staff-list decision",
        )
    }
}
