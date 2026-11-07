/**
 * Room element type enumeration for floor plan layout elements.
 *
 * Defines the different types of structural and decorative elements
 * that can be placed on a restaurant floor plan for visualization
 * and layout planning.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.room

import kotlinx.serialization.Serializable

/**
 * Enum representing different types of room layout elements.
 *
 * Used for categorizing and rendering floor plan elements with
 * appropriate visual representations and interaction behaviors.
 */
@Serializable
enum class RoomElementType {
    /** Wall element - structural boundary rendered as thick line/rectangle */
    WALL,

    /** Door element - entry/exit point rendered with arc/swing indicator */
    DOOR,

    /** Window element - opening rendered as double line or glass effect */
    WINDOW,

    /** Counter/bar element - service area rendered as rectangular zone */
    COUNTER,

    /** Decorative element - custom shapes, plants, or text labels */
    DECORATION,
}
