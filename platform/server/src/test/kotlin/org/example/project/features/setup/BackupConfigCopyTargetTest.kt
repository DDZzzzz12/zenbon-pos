package org.example.project.features.setup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.BackupConfigJson
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.domain.dto.backup.BackupRestoreDto.BackupCopyTargetDto
import org.example.project.core.domain.dto.backup.BackupRestoreDto.UpdateBackupConfigDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.setup.database.tables.ConfigKey
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupConfigCopyTargetTest {
    private val stick = BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, "/media/pos/ZEN_BACKUP")

    private val stored: JsonObject =
        buildJsonObject {
            put("defaultBackupPath", "/var/lib/zenbon/backups")
            put("lastBackupAt", "2026-11-01T03:00:00Z")
            put("lastCopyAt", "2026-11-01T03:00:05Z")
            put("lastCopyStatus", "OK")
            put("lastCopyError", null as String?)
            put("copyToKind", "MOUNTED_PATH")
            put("copyToPath", "/media/pos/ZEN_BACKUP")
            put("copyRetentionCount", 5)
        }

    @Test
    @DisplayName("saving the settings keeps lastBackupAt and lastCopy fields")
    fun savingKeepsRecord() {
        val saved = BackupConfigJson.updated(stored, UpdateBackupConfigDto(copyTo = stick, copyRetentionCount = 9))
        val dto = BackupConfigJson.toDto(saved)
        assertEquals("2026-11-01T03:00:00Z", dto.lastBackupAt)
        assertEquals("2026-11-01T03:00:05Z", dto.lastCopyAt)
        assertEquals(BackupCopyStatus.OK, dto.lastCopyStatus)
        assertNull(dto.lastCopyError)

        val failed =
            stored.toMutableMap().apply {
                put("lastCopyStatus", JsonPrimitive("FAILED"))
                put("lastCopyError", JsonPrimitive("device not mounted"))
            }
        val again = BackupConfigJson.toDto(BackupConfigJson.updated(JsonObject(failed), UpdateBackupConfigDto()))
        assertEquals(BackupCopyStatus.FAILED, again.lastCopyStatus)
        assertEquals("device not mounted", again.lastCopyError)
    }

    @Test
    @DisplayName("copyTo round-trips through update and get; no target clears it")
    fun roundTrip() {
        val dto = BackupConfigJson.toDto(BackupConfigJson.updated(null, UpdateBackupConfigDto(copyTo = stick, copyRetentionCount = 9)))
        assertEquals(stick, dto.copyTo)
        assertEquals(9, dto.copyRetentionCount)
        val cleared = BackupConfigJson.toDto(BackupConfigJson.updated(stored, UpdateBackupConfigDto(copyTo = null, copyRetentionCount = 7)))
        assertNull(cleared.copyTo)
        assertNull(BackupConfigJson.copyTarget(buildJsonObject { put("copyToPath", "") }))
    }

    @Test
    @DisplayName("the last copy record goes when the copy target is cleared or changed, and stays when it is the same")
    fun copyRecordFollowsTheTarget() {
        val same = BackupConfigJson.toDto(BackupConfigJson.updated(stored, UpdateBackupConfigDto(copyTo = stick, copyRetentionCount = 9)))
        assertEquals(BackupCopyStatus.OK, same.lastCopyStatus)
        assertEquals("2026-11-01T03:00:05Z", same.lastCopyAt)

        val cleared = BackupConfigJson.toDto(BackupConfigJson.updated(stored, UpdateBackupConfigDto(copyTo = null, copyRetentionCount = 7)))
        assertNull(cleared.lastCopyStatus)
        assertNull(cleared.lastCopyAt)
        assertNull(cleared.lastCopyError)
        assertEquals("2026-11-01T03:00:00Z", cleared.lastBackupAt, "the local backup record is not about the copy target")

        val other = BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, "/media/pos/OTHER")
        val changed = BackupConfigJson.toDto(BackupConfigJson.updated(stored, UpdateBackupConfigDto(copyTo = other, copyRetentionCount = 7)))
        assertNull(changed.lastCopyStatus)

        val older = BackupConfigJson.toDto(BackupConfigJson.updated(stored, UpdateBackupConfigDto()))
        assertEquals(BackupCopyStatus.OK, older.lastCopyStatus, "an older client leaves the target, so its record stays")
    }

    @Test
    @DisplayName("a save from a client that predates the copy target keeps the stored target and retention")
    fun olderClientKeepsTheTarget() {
        val kept = BackupConfigJson.toDto(BackupConfigJson.updated(stored, UpdateBackupConfigDto(autoEnabled = true)))
        assertEquals(stick, kept.copyTo)
        assertEquals(5, kept.copyRetentionCount)
        assertTrue(kept.autoEnabled, "the other fields are still saved")

        val fresh = BackupConfigJson.toDto(BackupConfigJson.updated(null, UpdateBackupConfigDto()))
        assertNull(fresh.copyTo)
        assertEquals(7, fresh.copyRetentionCount, "nothing stored yet: the default")
    }

    @Test
    @DisplayName("validate refuses a relative copy path, retention 0, and a copy path inside the backup folder")
    fun validation() {
        fun update(
            path: String = stick.path,
            retention: Int = 7,
            backup: String? = "/var/lib/zenbon/backups",
        ) = UpdateBackupConfigDto(
            defaultBackupPath = backup,
            copyTo = BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, path),
            copyRetentionCount = retention,
        )
        update().validate()
        assertFailsWith<IllegalArgumentException> { update(path = "media/stick").validate() }
        assertFailsWith<IllegalArgumentException> { update(retention = 0).validate() }
        assertFailsWith<IllegalArgumentException> { update(retention = 366).validate() }
        assertFailsWith<IllegalArgumentException> { update(path = "/var/lib/zenbon/backups").validate() }
        assertFailsWith<IllegalArgumentException> { update(path = "/var/lib/zenbon/backups/usb/").validate() }
        assertFailsWith<IllegalArgumentException> { update(path = "/media/pos/../etc").validate() }
        // a sibling that merely shares the prefix is fine
        update(path = "/var/lib/zenbon/backups-copy").validate()
    }

    @Test
    @DisplayName("BACKUP_COPY_PATH foreign path is listed for review; use-default clears the target")
    fun restoredPathEntry() {
        val base = "/var/lib/zenbon"
        val setting = RestoredPathSetting.BACKUP_COPY_PATH
        assertEquals(ConfigKey.BACKUP_CONFIG, RestoredPathPolicy.configKeyOf(setting))
        assertEquals("copyToPath", RestoredPathPolicy.fieldOf(setting))
        val configured = RestoredPathPolicy.configuredPaths(mapOf(ConfigKey.BACKUP_CONFIG.key to stored))
        assertEquals("/media/pos/ZEN_BACKUP", configured[setting])
        assertTrue(RestoredPathPolicy.foreignPaths(configured, base).containsKey(setting))
        assertEquals("", RestoredPathPolicy.defaultPath(setting, base))
        assertEquals("", RestoredPathPolicy.storedDefault(setting, base))
        assertTrue(setting.keepAllowed)
    }
}
