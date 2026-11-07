/**
 * Browse TSE log entries operation.
 *
 * Reads log entries from the TSE store using firstEntry(), lastEntry(),
 * and entryForId() methods. Each entry contains the raw log message
 * (TR-03153 ASN.1 DER encoded) and process data.
 *
 * Endpoint: POST /admin/entries/browse
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param direction Browse direction: "first", "last", or "byId"
 * @param entryId Entry ID for "byId" direction
 * @param count Number of entries to fetch (max 100)
 * @since 2.1.0
 */
package org.example.project.tse.operations.admin.entries

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.utils.Tr03153LogMessageParser

class BrowseEntriesOperation(
    adminPin: String,
    private val direction: String = "first",
    private val entryId: Long? = null,
    private val count: Int = 20
) : BaseTseOperation<TseDto.LogEntryBrowseResultDto>(adminPin) {

    /**
     * Browse TSE log entries.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with browse result or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.LogEntryBrowseResultDto {
        logger.info("Browsing TSE entries: direction=$direction, entryId=$entryId, count=$count")

        val store = getWormStore(tseStatus)

        val entries = mutableListOf<TseDto.LogEntryDto>()
        val maxCount = count.coerceIn(1, 100)

        // Get initial entry based on direction
        val initialEntry = when (direction) {
            "first" -> store.firstEntry()
            "last" -> store.lastEntry()
            "byId" -> store.entryForId(entryId ?: 0)
            else -> store.firstEntry()
        }

        try {
            var entry = initialEntry
            var fetched = 0

            while (entry.isValid && fetched < maxCount) {
                try {
                    val id = entry.id()
                    val type = try { entry.type().name } catch (_: Exception) { "UNKNOWN" }

                    // Read log message
                    val logMessageBytes = try {
                        entry.readLogMessage() ?: ByteArray(0)
                    } catch (_: Exception) {
                        ByteArray(0)
                    }

                    // Read process data
                    val processDataBytes = try {
                        entry.readProcessData() ?: ByteArray(0)
                    } catch (_: Exception) {
                        ByteArray(0)
                    }

                    // Try to decode process data as UTF-8
                    val processDataText = try {
                        if (processDataBytes.isNotEmpty()) {
                            String(processDataBytes, Charsets.UTF_8)
                        } else null
                    } catch (_: Exception) {
                        null
                    }

                    // Parse TR-03153 log message
                    val parsed = try {
                        if (logMessageBytes.isNotEmpty()) {
                            Tr03153LogMessageParser.parse(logMessageBytes)
                        } else null
                    } catch (_: Exception) {
                        null
                    }

                    entries.add(
                        TseDto.LogEntryDto(
                            id = id,
                            type = type,
                            logMessageHex = logMessageBytes.toHexString(),
                            processDataHex = processDataBytes.toHexString(),
                            processDataText = processDataText,
                            logMessageSize = logMessageBytes.size.toLong(),
                            processDataSize = processDataBytes.size.toLong(),
                            parsed = parsed
                        )
                    )

                    fetched++

                    // Move to next entry
                    entry.next()
                } catch (e: Exception) {
                    logger.warn("Error reading entry: ${e.message}")
                    break
                }
            }

            // Check if there are more entries
            val hasMore = entry.isValid && fetched >= maxCount

            logger.info("Browse completed: fetched $fetched entries, hasMore=$hasMore")

            return TseDto.LogEntryBrowseResultDto(
                entries = entries,
                totalCount = null,
                hasMore = hasMore
            )
        } finally {
            try {
                initialEntry.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Convert byte array to hex string.
     */
    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
