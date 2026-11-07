/**
 * Enumeration of data change operations for WebSocket notifications.
 *
 * Used in [DataChangeNotificationDto] to describe the type of mutation
 * that occurred on a server entity.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.websocket

/**
 * Types of data mutation operations that trigger client re-synchronization.
 */
enum class DataChangeOperation {
    /** A new entity was created */
    CREATED,

    /** An existing entity was updated */
    UPDATED,

    /** An entity was deleted */
    DELETED,

    DEACTIVATED,
}
