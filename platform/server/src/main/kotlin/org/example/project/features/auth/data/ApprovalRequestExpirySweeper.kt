package org.example.project.features.auth.data

import org.slf4j.LoggerFactory

/**
 * Tells the requesting till when nobody answered its approval request.
 *
 * A request lives for three minutes. Until this sweeper existed, that window was enforced only
 * incidentally — `PendingRequest.isExpired()` ran when somebody happened to call `getRequest`, and
 * nothing ever walked the map — so an unanswered request stayed PENDING in memory and the till was
 * never told. The cashier could not distinguish "still with the manager" from "nobody answered",
 * and the deferred lambda stayed resident (E2E-2026-09-20 #8).
 *
 * `DeferredActionExecutor.notifyExpired` — which sends `APPROVAL_EXPIRED` to the requesting device
 * and drops the stored action — was already written for exactly this, and had no callers. This is
 * the caller.
 *
 * **What this does not fix:** a request in flight when the server restarts is still lost, because
 * the store is process memory and a `DeferredAction` is a lambda closed over the request scope.
 * The till now learns that *nothing happened* within the window either way, which is the outcome
 * that matters at the counter, but surviving a restart needs the request's intent persisted.
 */
object ApprovalRequestExpirySweeper {
    private val logger = LoggerFactory.getLogger(ApprovalRequestExpirySweeper::class.java)

    /**
     * Expires every request past its window and notifies each one's device, exactly once.
     *
     * Exactly-once comes from [TemporarySessionStore.expireStaleRequests], which claims each
     * request through the same atomic transition `denyRequest` uses: a manager deciding in the same
     * instant wins, and this sweep simply does not see that request.
     *
     * @return how many requests were expired.
     */
    suspend fun sweep(): Int {
        val expired = TemporarySessionStore.expireStaleRequests()
        expired.forEach { request ->
            // Per request: one device that cannot be reached must not stop the others being told.
            runCatching { DeferredActionExecutor.notifyExpired(request.requestId) }
                .onFailure { logger.warn("Failed to notify device of expired request ${request.requestId}", it) }
        }
        if (expired.isNotEmpty()) {
            logger.info("Expired ${expired.size} unanswered approval request(s): ${expired.map { it.requestId }}")
        }
        return expired.size
    }
}
