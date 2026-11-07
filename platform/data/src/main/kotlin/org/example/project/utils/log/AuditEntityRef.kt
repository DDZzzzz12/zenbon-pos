/**
 * Identity of the entity an audited operation acted on.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.log

/**
 * The entity an audited operation acted on, as the call site names it.
 *
 * Supplied by `ExceptionConverter.catchingThrows(auditEntity = …)` for audited operations that have
 * no [EntryLogConfig] to borrow from — `CREATE_BILL` among them. Where an `EntryLogConfig` IS
 * present its `extractLogData` is used instead, so those call sites need no change: adding an
 * `EntryLogConfig` purely to name an audit entity would also start writing `entry_log` rows and
 * change what the Änderungen screen shows.
 *
 * **A naming lambda may return null**, and that is how a call declines: it means "this call
 * legitimately cannot name an entity", and the audit row degrades to `UNKNOWN` QUIETLY — no
 * warning, no stack trace. Use it on a normal success path whose result carries no identifier.
 * Throwing is for genuine failure only; it is warned about with a stack trace, so a routine outcome
 * routed through a throw trains readers to ignore the warning. Never reach for some other number in
 * scope instead: an id of the wrong KIND is worse than a null, because two entities' ids are both
 * integers and a wrong one silently points at a real, unrelated record.
 *
 * @property id The identifier written to `transaction_audit_log.entity_id`. Build it from a
 *   nullable source with a null-aware branch — `dto.billId?.let { AuditEntityRef(id = it.toString()) }`
 *   — never a bare `.toString()`: on a null that yields the literal string `"null"`, and no check
 *   here can tell that from a real id, so it would be written to the audit trail as one.
 * @property name Optional human-readable name written to `transaction_audit_log.entity_name`.
 *   Truncated to the column width at the write site; a clipped name still identifies the row.
 * @property fiscalRecordId Optional `transaction_audit_log.fiscal_record_id`: the id of the
 *   `tse_transaction` that signed the entity, for an operation that produced a signed Vorgang. Null
 *   when nothing was signed (yet) — never a guessed or unrelated id.
 */
data class AuditEntityRef(
    val id: String,
    val name: String? = null,
    val fiscalRecordId: String? = null,
) {
    init {
        // An empty or over-long id is not a gap an auditor can see: a blank cell reads as data, and
        // an overflow throws inside logSuccessToAudit's dbQuery, where its catch-all drops the whole
        // row. Throwing here instead degrades the row to UNKNOWN via resolveAuditEntity's
        // runCatching, which is the honest outcome. 50 = entity_id's column width.
        require(id.isNotBlank()) { "AuditEntityRef.id must not be blank" }
        require(id.length <= 50) { "AuditEntityRef.id exceeds the entity_id column (50): ${id.length}" }
    }
}
