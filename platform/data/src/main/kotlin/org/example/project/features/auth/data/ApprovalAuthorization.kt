package org.example.project.features.auth.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The manager approval an action is running under, carried on the coroutine context.
 *
 * A remote approval leaves no trace the audit writer could find on its own: unlike a temporary
 * session ([TemporarySessionStore.activeElevationFor]) it is not bound to the device, and by the
 * time the deferred action runs the request has already been claimed. `DeferredActionExecutor`
 * therefore installs this element around the action, and `ExceptionConverter` reads it when it
 * records how the operation was authorised — otherwise a manager-approved Storno is written as
 * the cashier's own `DIRECT` authority (E2E-2026-09-20 round 2, R2-1).
 *
 * @property requestId The approval request the action was parked under
 * @property managerId The approving manager's employee UUID
 * @property managerName The approving manager's name, if the claim recorded one; the audit writer
 *   resolves it from [managerId] otherwise
 */
class ApprovalAuthorization(
    val requestId: String,
    val managerId: String,
    val managerName: String?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApprovalAuthorization>
}
