package org.example.project.core.network

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import org.example.project.core.auth.PermissionManager
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.data.domain.service.SyncService
import org.example.project.core.data.remote.ApprovalRequestApiClient
import org.example.project.core.data.remote.ChangeFeedApiClient
import org.example.project.core.data.remote.RotationApiClient
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.table.data.remote.BillingRequestApiClient
import kotlin.time.Clock

/**
 * Reconcile-on-connect: re-establish authoritative state after every (re)connect.
 *
 * Uses the persisted change-feed cursor ([LocalDatabaseManager.getLastSeenSeq]) to
 * pull only what changed since the last seen sequence:
 *  - if the cursor has fallen behind the server's retention window
 *    (`since < minAvailableSeq`), the local state is stale beyond repair → full sync,
 *    then jump the cursor to the high-water mark;
 *  - otherwise apply the delta and DRAIN the feed (it is capped per call), advancing
 *    the cursor page-by-page until caught up to the high-water mark.
 *
 * Then re-inject server-pending approvals + billing as messages (the VM dedups by id).
 *
 * Pending approvals are asked for only when the principal holds [Capability.SESSION_READ], the
 * capability `GET /api/auth/approvals/pending` is gated on. Only a manager-level group holds it, and
 * this runs on every connect for every principal — a cashier or a login-less order station used to
 * earn a 403 each time. The policy is read per reconcile, not at construction: this is a process-wide
 * singleton that outlives logins and step-ups.
 *
 * Best-effort: failures are logged and swallowed.
 */
class NotificationReconciler(
    private val syncService: SyncService,
    private val changeFeedApiClient: ChangeFeedApiClient,
    private val localDatabaseManager: LocalDatabaseManager,
    private val approvalApiClient: ApprovalRequestApiClient,
    private val billingApiClient: BillingRequestApiClient,
    private val permissionManager: PermissionManager,
    private val rotationApiClient: RotationApiClient,
) {
    private val logger = Logger.withTag("NotificationReconciler")
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun reconcile(emit: suspend (NotificationDto.NotificationMessage) -> Unit) {
        runCatching {
            val since = localDatabaseManager.getLastSeenSeq()
            val first = changeFeedApiClient.getChanges(since)
            if (since < first.minAvailableSeq) {
                // Offline past retention / fresh client → full sync, then jump cursor to high-water.
                syncService.performCompleteSync()
                localDatabaseManager.setLastSeenSeq(first.highWaterSeq)
            } else {
                // Delta path: apply, advance cursor, and DRAIN (the feed is capped per call).
                var resp = first
                var cursor = since
                var iterations = 0
                while (resp.entries.isNotEmpty()) {
                    if (++iterations > MAX_DRAIN_ITERATIONS) {
                        logger.w { "reconcile drain hit safety cap ($MAX_DRAIN_ITERATIONS) at cursor=$cursor; bailing" }
                        break
                    }
                    syncService.applyChangeDelta(resp.entries)
                    cursor = resp.entries.maxOf { it.seq }
                    localDatabaseManager.setLastSeenSeq(cursor)
                    if (cursor >= resp.highWaterSeq) break // caught up
                    resp = changeFeedApiClient.getChanges(cursor) // next page
                }
            }
        }.onFailure { logger.w(it) { "reconcile delta failed" } }
        if (permissionManager.accessPolicy.value.allows(Capability.SESSION_READ)) {
            runCatching {
                approvalApiClient.getPendingRequests().forEach { req ->
                    emit(
                        NotificationDto.AuthorizationNotificationDto(
                            timestamp = req.timestamp,
                            notificationType = "APPROVAL_REQUEST",
                            data = json.encodeToString(req),
                        ),
                    )
                }
            }.onFailure { logger.w(it) { "reconcile approvals fetch failed" } }
        }
        runCatching {
            billingApiClient.getPendingBillingRequests().forEach { emit(it) }
        }.onFailure { logger.w(it) { "reconcile billing requests fetch failed" } }
        if (permissionManager.accessPolicy.value.allows(Capability.TABLE_READ)) {
            runCatching {
                val snapshot = rotationApiClient.getNext()
                // A snapshot replaces the store's state outright: the server's version restarts at 0 on boot.
                emit(
                    NotificationDto.RotationNextUpdateDto(
                        timestamp = Clock.System.now().toString(),
                        version = snapshot.version,
                        next = snapshot.next,
                        isSnapshot = true,
                    ),
                )
            }.onFailure { logger.w(it) { "reconcile rotation next fetch failed" } }
        }
    }

    private companion object {
        /** Safety cap on the drain loop to avoid any pathological infinite paging. */
        const val MAX_DRAIN_ITERATIONS = 50
    }
}
