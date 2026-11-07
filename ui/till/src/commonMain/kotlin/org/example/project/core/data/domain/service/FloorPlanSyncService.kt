/**
 * Service interface for floor plan data synchronization.
 *
 * Handles synchronization of tables and room elements between local database
 * and server API, including batch updates, creates, and deletes.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.service

import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement

/**
 * Floor plan synchronization service.
 *
 * Provides methods for syncing floor plan changes (tables and room elements)
 * to the server and updating local database with server responses.
 */
interface FloorPlanSyncService {
    /**
     * Synchronizes all floor plan changes to the server.
     *
     * Handles:
     * - Creating new tables and elements (negative IDs)
     * - Updating existing tables and elements (updated flag)
     * - Deleting removed tables and elements
     *
     * @param newTables Tables to create on server (negative IDs)
     * @param updatedTables Tables to update on server
     * @param deletedTables Tables to delete from server
     * @param newElements Room elements to create on server (negative IDs)
     * @param updatedElements Room elements to update on server
     * @param deletedElements Room elements to delete from server
     * @return Result containing sync statistics or error
     */
    suspend fun syncFloorPlanChanges(
        newTables: List<RTable>,
        updatedTables: List<RTable>,
        deletedTables: List<RTable>,
        newElements: List<RoomElement>,
        updatedElements: List<RoomElement>,
        deletedElements: List<RoomElement>,
    ): FloorPlanSyncResult
}

/**
 * Result of floor plan synchronization operation.
 *
 * Contains statistics about what was synced and any errors encountered.
 */
data class FloorPlanSyncResult(
    /** Number of tables created on server */
    val tablesCreated: Int,
    /** Number of tables updated on server */
    val tablesUpdated: Int,
    /** Number of tables deleted from server */
    val tablesDeleted: Int,
    /** Number of room elements created on server */
    val elementsCreated: Int,
    /** Number of room elements updated on server */
    val elementsUpdated: Int,
    /** Number of room elements deleted from server */
    val elementsDeleted: Int,
    /** Map of old (negative) table IDs to new server-assigned IDs */
    val newTableIds: Map<Long, Long>,
    /** Map of old (negative) element IDs to new server-assigned IDs */
    val newElementIds: Map<Long, Long>,
    /** List of errors encountered during sync (non-fatal) */
    val errors: List<String>,
) {
    /** Whether the sync completed successfully without errors */
    val isSuccess: Boolean get() = errors.isEmpty()
}
