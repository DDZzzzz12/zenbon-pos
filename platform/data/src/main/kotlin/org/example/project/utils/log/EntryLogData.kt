/**
 * Data extracted from a successful operation for entry logging.
 *
 * Contains the entity information needed to create an EntryLog record.
 * Produced by EntryLogConfig.extractLogData lambda after a successful service operation.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.log

/**
 * Entity information for entry log creation.
 *
 * @property entityId The ID of the affected entity
 * @property entityName The name or description of the entity
 * @property message Optional detailed description of the operation
 */
data class EntryLogData(
    val entityId: String,
    val entityName: String,
    val message: String? = null,
)
