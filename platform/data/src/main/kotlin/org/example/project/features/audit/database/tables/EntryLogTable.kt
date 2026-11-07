package org.example.project.features.audit.database.tables

import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Entry log table for logging operations.
 */
object EntryLogTable : LongIdTable("entry_log") {
    /**
     * Entity type — read through [ChangeEntityType.fromStored], NOT `enumerationByName`.
     *
     * `enumerationByName` maps the column with `EnumerationNameColumnType`, whose `valueFromDB`
     * throws `IllegalStateException` on any string that is not a current enum name. The change-log
     * query selects every row with no type filter, so ONE stale row failed the entire Änderungen
     * screen with a 500 — observed live on a database restored from a pre-V80 backup, where
     * `PRINTERLOCATION` (since renamed `PRINTER_LOCATION`) still stands. A restore reloads the
     * archive's rows after migrations have run, so V80's rewrite cannot reach them.
     *
     * [ChangeEntityType.fromStored] degrades an unrecognised value to `UNKNOWN` instead, so the row
     * is rendered rather than dropped, and the six spellings this project has actually retired read
     * back with their true historical label. `toDb` writes `name`, byte-identical to what
     * `enumerationByName` wrote, so no stored value changes and no migration is needed.
     *
     * `sql` must be spelled out because `CustomEnumerationColumnType.sqlType()` has no fallback.
     * `VARCHAR(50)` is exactly what `enumerationByName(length = 50)` emitted on both H2 and
     * Postgres (`DataTypeProvider.varcharType`), so the generated DDL is unchanged and fresh
     * installs still converge with the migrated schema.
     */
    val type =
        customEnumeration<ChangeEntityType>(
            name = "type",
            sql = "VARCHAR(50)",
            fromDb = { stored: Any -> ChangeEntityType.fromStored(stored.toString()) },
            toDb = { value: ChangeEntityType -> value.name },
        )

    /** Operation — read through [LogOperation.fromStored] for the reason given on [type]. */
    val operation =
        customEnumeration<LogOperation>(
            name = "operation",
            sql = "VARCHAR(50)",
            fromDb = { stored: Any -> LogOperation.fromStored(stored.toString()) },
            toDb = { value: LogOperation -> value.name },
        )
    val entityId = varchar("entity_id", 50)
    val entityName = varchar("entity_name", 50)
    val message = varchar("message", 500).nullable()
    val userId = reference("employee_id", EmployeeTable).nullable()

    /**
     * Log timestamp — stamped PER INSERT. This is the audit trail's own clock; a frozen value
     * makes every logged change undateable.
     *
     * MUST stay `clientDefault { }`. `Column.default(value)` evaluates its argument once at
     * table-object init, which froze one wall-clock instant across every row (audit KABS-1).
     * Not `defaultExpression(CurrentDateTime)` either — that uses the DB server's timezone
     * instead of the shop-configured timeProvider.
     */
    val createdAt = datetime("created_at").clientDefault { currentTimeStamp() }

    init {
        // Query hot paths — kept in sync with Flyway V86 (matching index names so fresh
        // installs (Exposed-created schema) and existing installs (migration) converge).
        index("idx_entry_log_created_at", false, createdAt)
        index("idx_entry_log_type_op_created", false, type, operation, createdAt)
        index("idx_entry_log_entity_id", false, entityId)
    }
}
