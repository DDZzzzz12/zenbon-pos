package org.example.project.features.customexport.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A saved, re-runnable custom export: a subject, a chosen set of fields, and the filters to run them
 * with.
 *
 * This is the point of the feature. Ticking fields once and getting a CSV is a report; saving the
 * selection so the same extract can be produced next month, by someone who did not build it, is what
 * an operator actually asked for.
 *
 * Shop-scoped, like every other operator-authored record — a definition names columns of one shop's
 * data and has no meaning outside it.
 *
 * ## Why [subjectKey] is a string and not a foreign key
 *
 * The export catalogue ([`ExportSubject`]/[`ExportField`]) lives in Kotlin, not in a table, so there
 * is nothing here for the database to reference. That is deliberate: a subject carries a query source
 * with joins and a set of predicates, none of which is expressible as data.
 *
 * The consequence is that a key stored here can go stale when the catalogue changes — and it must
 * **not** be "fixed" with a constraint, because the constraint cannot exist. A definition whose
 * subject or field keys no longer resolve is reported **broken** to the operator rather than run with
 * whatever still resolves; silently dropping a column would hand someone a CSV that looks right and
 * is missing data.
 */
object ExportDefinitionTable : LongIdTable("export_definition", "id") {
    val shopId = reference("shop_id", ShopInfoTable)

    /** The operator's own name for the export ("Kreditoren mit USt-IdNr."), shown in the list. */
    val name = varchar("name", 100)

    /** `ExportSubject.key`. A catalogue key, not a foreign key — see the class KDoc. */
    val subjectKey = varchar("subject_key", 100)

    /**
     * Who built it, for the list column. Nullable and free-form rather than a reference to
     * `employees`: the definition outlives the employee, and losing the author's name is a smaller
     * loss than either blocking the deletion or cascading the definition away with them.
     */
    val createdByEmployeeId = varchar("created_by_employee_id", 100).nullable()

    /** Saved bounds for the subject's `dateColumn`. Null means "no bound on this side". */
    val dateFrom = datetime("date_from").nullable()
    val dateTo = datetime("date_to").nullable()

    /**
     * The `ExportToggle` keys switched on, serialized. Text rather than a child table because the set
     * is small, always read whole, and never queried across definitions.
     */
    val enabledToggles = text("enabled_toggles").nullable()

    /** `clientDefault`, never `default(value)` — the latter freezes every row to server-start time. */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }
    val updatedAt = datetime("updated_at").nullable()

    init {
        index("idx_export_definition_shop", false, shopId)
    }
}
