package org.example.project.features.auditlog.presentation

/**
 * The entity id a failed audit row carries when its call site could not name the entity.
 *
 * Mirrors `ExceptionConverter.FAILED_ENTITY_ID` (core-data), which this module cannot depend on.
 */
private const val FAILED_ENTITY_ID = "FAILED"

/**
 * The entity name EVERY failed audit row carries — also a row that names its entity, such as a
 * refused invoice cancel, whose `entityId` is the invoice (E2E 2026-09-27). Mirrors
 * `ExceptionConverter.FAILED_ENTITY_NAME` (core-data).
 */
private const val FAILED_ENTITY_NAME = "Operation failed due to error"

/**
 * What the collapsed audit row shows, and whether it should read as a failure.
 *
 * Extracted from the composable so the decision is testable: a Compose `color` and a conditional
 * string are not directly assertable, but this is.
 *
 * @property text the headline to render, or `null` when the row has nothing to show.
 * @property isFailure whether the row records a failed operation and should be styled as an error.
 */
data class AuditRowHeadline(
    val text: String?,
    val isFailure: Boolean,
)

/**
 * Decides the collapsed audit row's headline.
 *
 * A failed row's [entityName] is the constant "Operation failed due to error", which makes every
 * failure look identical until it is expanded; its [reason] holds the actual error, so that is
 * promoted to the headline instead.
 *
 * Failure is keyed on the two markers `logErrorToAudit` writes: `entityId == "FAILED"` (a row that
 * could not name its entity) or the constant failure [entityName] (every failed row, including one
 * whose `entityId` names the entity it failed on). Do NOT sniff the `"ERROR: "` prefix on [reason]:
 * that is a message format, not a contract, and a successful row may carry a reason of its own (a
 * cancellation reason, for instance).
 */
fun auditRowHeadline(
    entityId: String,
    entityName: String?,
    reason: String?,
): AuditRowHeadline {
    val failed = entityId == FAILED_ENTITY_ID || entityName == FAILED_ENTITY_NAME
    return AuditRowHeadline(
        text = if (failed) reason ?: entityName else entityName,
        isFailure = failed,
    )
}
