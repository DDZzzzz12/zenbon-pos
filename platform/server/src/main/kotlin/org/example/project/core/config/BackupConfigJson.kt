package org.example.project.core.config

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind

/**
 * The one definition of how `BACKUP_CONFIG` is rebuilt from a settings update and read back.
 *
 * Saving the settings rewrites the whole object, so every field the settings form does not own
 * (`lastBackupAt`, `lastCopyAt`, `lastCopyStatus`, `lastCopyError`) is carried over from the stored
 * row here — in one place, so the two services that save it cannot diverge and wipe the record.
 */
object BackupConfigJson {
    private const val DEFAULT_COPY_RETENTION = 7
    private val CARRIED_OVER = listOf("lastBackupAt")

    /** What the last copy attempt reported; it describes one target, so it goes when the target changes. */
    private val COPY_RECORD = listOf("lastCopyAt", "lastCopyStatus", "lastCopyError")

    fun updated(
        existing: JsonObject?,
        request: BackupRestoreDto.UpdateBackupConfigDto,
    ): JsonObject {
        // A request without copyRetentionCount comes from a client that predates the copy target: keep what is stored.
        val keepCopy = request.copyRetentionCount == null
        val newPath = if (keepCopy) text(existing, "copyToPath") else request.copyTo?.path
        val sameTarget = (text(existing, "copyToPath") ?: "") == (newPath ?: "")
        return buildJsonObject {
            put("defaultBackupPath", request.defaultBackupPath)
            CARRIED_OVER.forEach { key -> put(key, existing?.get(key) ?: JsonNull) }
            COPY_RECORD.forEach { key -> put(key, if (sameTarget) existing?.get(key) ?: JsonNull else JsonNull) }
            put("autoEnabled", request.autoEnabled)
            put("cadence", request.cadence.name)
            put("retentionCount", request.retentionCount)
            put("runHour", request.runHour)
            put("maxUploadBytes", request.maxUploadBytes)
            if (keepCopy) {
                put("copyToKind", existing?.get("copyToKind") ?: JsonNull)
                put("copyToPath", existing?.get("copyToPath") ?: JsonNull)
                put("copyRetentionCount", existing?.get("copyRetentionCount") ?: JsonPrimitive(DEFAULT_COPY_RETENTION))
            } else {
                put("copyToKind", request.copyTo?.kind?.name)
                put("copyToPath", request.copyTo?.path)
                put("copyRetentionCount", request.copyRetentionCount)
            }
        }
    }

    /** The stored copy target; null when no path is stored (blank or `null`). */
    fun copyTarget(json: JsonObject?): BackupRestoreDto.BackupCopyTargetDto? {
        val path = text(json, "copyToPath")?.takeIf { it.isNotBlank() } ?: return null
        val kind =
            text(json, "copyToKind")
                ?.let { raw -> BackupCopyTargetKind.entries.firstOrNull { it.name == raw } }
                ?: BackupCopyTargetKind.MOUNTED_PATH
        return BackupRestoreDto.BackupCopyTargetDto(kind, path)
    }

    /** The copy record fields of [json] (status unknown values read as null). */
    fun copyStatus(json: JsonObject?): BackupCopyStatus? = text(json, "lastCopyStatus")?.let { raw -> BackupCopyStatus.entries.firstOrNull { it.name == raw } }

    fun toDto(json: JsonObject): BackupRestoreDto.BackupConfigDto =
        BackupRestoreDto.BackupConfigDto(
            defaultBackupPath = text(json, "defaultBackupPath"),
            lastBackupAt = text(json, "lastBackupAt"),
            autoEnabled = (json["autoEnabled"] as? JsonPrimitive)?.contentOrNull == "true",
            cadence =
                text(json, "cadence")
                    ?.let { raw -> BackupRestoreDto.BackupCadence.entries.firstOrNull { it.name == raw } }
                    ?: BackupRestoreDto.BackupCadence.DAILY,
            retentionCount = (json["retentionCount"] as? JsonPrimitive)?.intOrNull ?: 7,
            runHour = (json["runHour"] as? JsonPrimitive)?.intOrNull ?: 3,
            maxUploadBytes = (json["maxUploadBytes"] as? JsonPrimitive)?.longOrNull ?: (2L * 1024 * 1024 * 1024),
            copyTo = copyTarget(json),
            copyRetentionCount = (json["copyRetentionCount"] as? JsonPrimitive)?.intOrNull ?: 7,
            lastCopyAt = text(json, "lastCopyAt"),
            lastCopyStatus = copyStatus(json),
            lastCopyError = text(json, "lastCopyError"),
        )

    private fun text(
        json: JsonObject?,
        key: String,
    ): String? = (json?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
}
