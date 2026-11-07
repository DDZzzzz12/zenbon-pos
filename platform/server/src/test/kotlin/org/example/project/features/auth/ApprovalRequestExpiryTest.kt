package org.example.project.features.auth

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.features.auth.data.ApprovalRequestExpirySweeper
import org.example.project.features.auth.data.TemporarySessionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A request nobody answers must not leave the till silent forever.
 *
 * `PendingRequest.isExpired()` is a 3-minute rule that only ever ran when somebody happened to call
 * `getRequest`, and nothing ever swept the map: an unanswered request sat `PENDING` in memory and
 * the requesting device was never told. That is the same silence a server restart produces
 * (E2E-2026-09-20 #8) — and the far more common case, because a manager who simply does not look at
 * the tablet produces it every time.
 *
 * The sweep is the observable contract: it claims each stale request exactly once and hands it back,
 * so its caller can notify that request's device with the same plumbing `denyRequest` uses.
 */
@DisplayName("Approval requests — unanswered requests expire and are handed back once")
class ApprovalRequestExpiryTest {
    /** The store is a process-wide object; each test cleans up only the ids it created. */
    @AfterEach
    fun removeOwnRequests() {
        listOf("REQ-STALE", "REQ-LIVE", "REQ-DONE").forEach {
            TemporarySessionStore.removeRequest(it)
            TemporarySessionStore.removeDeferredAction(it)
        }
    }

    @Test
    fun `a request past its window is expired and returned`() {
        addRequest("REQ-STALE", ageMillis = 200_000)

        val expired = TemporarySessionStore.expireStaleRequests()

        assertEquals(listOf("REQ-STALE"), expired.map { it.requestId })
        assertEquals(
            AuthDto.RequestStatus.EXPIRED,
            expired.single().status,
            "the swept request carries its new status, so the notice can say why",
        )
    }

    @Test
    fun `a stale request is handed back only once so the till is told once`() {
        addRequest("REQ-STALE", ageMillis = 200_000)

        TemporarySessionStore.expireStaleRequests()
        val second = TemporarySessionStore.expireStaleRequests()

        assertTrue(second.isEmpty(), "a second sweep must not re-notify an already-expired request")
    }

    @Test
    fun `a live request is left alone`() {
        addRequest("REQ-LIVE", ageMillis = 1_000)

        assertTrue(TemporarySessionStore.expireStaleRequests().isEmpty())
        assertEquals(
            AuthDto.RequestStatus.PENDING,
            TemporarySessionStore.getRequest("REQ-LIVE")!!.status,
            "a request still inside its window stays pending",
        )
    }

    @Test
    fun `an already-decided request is not swept`() {
        addRequest("REQ-DONE", ageMillis = 200_000)
        TemporarySessionStore.claimRequest(
            requestId = "REQ-DONE",
            newStatus = AuthDto.RequestStatus.APPROVED,
            managerId = "mgr-1",
        )

        assertTrue(
            TemporarySessionStore.expireStaleRequests().isEmpty(),
            "a decision already taken is not an expiry, even once the window has passed",
        )
    }

    /**
     * `DeferredActionExecutor.notifyExpired` — which sends `APPROVAL_EXPIRED` to the requesting
     * device and drops the stored lambda — was written and then never called by anything. Sweeping
     * without it would flip the status in memory and still leave the till silent and the lambda
     * resident.
     */
    @Test
    fun `sweeping an expired request notifies its device and drops the stored action`() {
        addRequest("REQ-STALE", ageMillis = 200_000)
        TemporarySessionStore.addDeferredAction(
            TemporarySessionStore.DeferredAction(
                requestId = "REQ-STALE",
                employeeId = "emp-1",
                deviceId = "device-1",
                operation = "CANCEL_ORDER_ITEMS",
                actionExecutor = { "never runs" },
            ),
        )

        runBlocking { ApprovalRequestExpirySweeper.sweep() }

        assertEquals(
            AuthDto.RequestStatus.EXPIRED,
            TemporarySessionStore.getRequest("REQ-STALE")!!.status,
        )
        assertNull(
            TemporarySessionStore.getDeferredAction("REQ-STALE"),
            "an expired request must not leave its lambda resident in the store",
        )
    }

    private fun addRequest(
        requestId: String,
        ageMillis: Long,
    ) = TemporarySessionStore.addRequest(
        TemporarySessionStore.PendingRequest(
            requestId = requestId,
            employeeId = "emp-1",
            deviceId = "device-1",
            operation = "CANCEL_ORDER_ITEMS",
            capabilities = emptySet(),
            details = emptyMap(),
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - ageMillis),
        ),
    )
}
