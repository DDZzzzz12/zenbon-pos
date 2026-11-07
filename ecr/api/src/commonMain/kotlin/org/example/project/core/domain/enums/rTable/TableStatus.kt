/**
 * Table status enumeration for restaurant table management.
 *
 * Defines the current operational status of restaurant tables,
 * used for tracking availability and service state.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.rTable

/**
 * Enum representing the current status of a restaurant table.
 *
 * Used to track table availability and manage seating operations.
 */
enum class TableStatus {
    /** Table is available for new customers */
    FREE,

    /** Table is currently occupied by customers */
    OCCUPIED,

    /** Table is reserved for future customers */
    RESERVED,
}
