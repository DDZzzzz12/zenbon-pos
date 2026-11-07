/**
 * Configuration for automatic EntryLog creation in catching().
 *
 * Defines what type and operation to log, plus a lambda to extract
 * entity info from the success result. Used by services to declaratively
 * configure entry logging when wrapping operations with catching() or catchingDbQuery().
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.log

import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType

/**
 * Configuration for automatic entry log creation after successful operations.
 *
 * @param T The type of the success result data
 * @property logType The entity type to log (PRODUCT, CATEGORY, etc.)
 * @property logOperation The operation to log (CREATE, UPDATE, DELETE)
 * @property extractLogData Lambda to extract entity info from the success result
 */
data class EntryLogConfig<T>(
    val logType: ChangeEntityType,
    val logOperation: LogOperation,
    val extractLogData: (T) -> EntryLogData,
)
