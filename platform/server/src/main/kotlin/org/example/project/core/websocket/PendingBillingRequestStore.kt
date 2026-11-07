/**
 * In-memory, bounded store of recoverable billing requests.
 *
 * Mirrors the approval-pending pattern: a billing request pushed over SSE to a
 * table owner is also parked here so a client that missed the live event (offline
 * beyond the SSE replay buffer) can recover it via reconcile-on-connect.
 *
 * Keyed by target employee id, holding at most one pending request per tableId
 * (a new request for the same table supersedes the previous one). Entries expire
 * lazily after [TTL_MILLIS]; eviction happens on read, no background thread.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.websocket

import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.utils.TestOnlyApi
import java.util.concurrent.ConcurrentHashMap

object PendingBillingRequestStore {
    /** Time-to-live for a parked billing request (2 hours). */
    private const val TTL_MILLIS: Long = 2 * 60 * 60 * 1000L

    private data class Entry(
        val request: NotificationDto.BillingRequestNotificationDto,
        val insertedAt: Long,
    )

    // employeeId -> (tableId -> Entry)
    private val byEmployee = ConcurrentHashMap<String, ConcurrentHashMap<Long, Entry>>()

    /**
     * Park a billing request for later recovery.
     *
     * A subsequent add for the same [request].tableId (same employee) supersedes
     * the previous entry.
     */
    fun add(
        targetEmployeeId: String,
        request: NotificationDto.BillingRequestNotificationDto,
    ) {
        val tables = byEmployee.computeIfAbsent(targetEmployeeId) { ConcurrentHashMap() }
        tables[request.tableId] = Entry(request, now())
    }

    /**
     * Return all non-expired pending billing requests for the given employee.
     * Expired entries are evicted lazily.
     */
    fun getForEmployee(targetEmployeeId: String): List<NotificationDto.BillingRequestNotificationDto> {
        val tables = byEmployee[targetEmployeeId] ?: return emptyList()
        val cutoff = now() - TTL_MILLIS
        val result = mutableListOf<NotificationDto.BillingRequestNotificationDto>()
        tables.entries.forEach { (tableId, entry) ->
            if (entry.insertedAt < cutoff) {
                tables.remove(tableId, entry)
            } else {
                result += entry.request
            }
        }
        return result
    }

    /**
     * Remove any pending billing request for the given table across all employees.
     * Called when the table's billing need is resolved (session closed / table freed).
     */
    fun clearForTable(tableId: Long) {
        byEmployee.values.forEach { it.remove(tableId) }
    }

    /** Test-only reset. */
    @TestOnlyApi
    fun reset() {
        byEmployee.clear()
    }

    private fun now(): Long = System.currentTimeMillis()
}
