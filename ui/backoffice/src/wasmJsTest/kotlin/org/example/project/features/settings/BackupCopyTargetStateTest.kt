package org.example.project.features.settings

import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.features.settings.presentation.state.BackupRestoreState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCopyTargetStateTest {
    private val stick = BackupRestoreDto.BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, "/media/pos/ZEN_BACKUP")

    private fun loaded(
        copyTo: BackupRestoreDto.BackupCopyTargetDto? = stick,
        status: BackupCopyStatus? = null,
    ) = BackupRestoreState().withConfig(
        BackupRestoreDto.BackupConfigDto(
            defaultBackupPath = "/var/lib/zenbon/backups",
            copyTo = copyTo,
            copyRetentionCount = 5,
            lastCopyStatus = status,
        ),
    )

    @Test
    fun savingWithoutEditingSendsTheExistingTargetUnchanged() {
        val state = loaded()
        assertEquals(stick, state.copyTargetForSave())
        assertFalse(state.isConfigDirty)
        assertEquals(5, state.copyRetentionCount)
    }

    @Test
    fun editingTheFieldSendsTheNewPathAndMakesTheFormDirty() {
        val edited = loaded().copy(copyToPath = "/media/pos/OTHER")
        assertEquals(BackupRestoreDto.BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, "/media/pos/OTHER"), edited.copyTargetForSave())
        assertTrue(edited.isConfigDirty)
        assertNull(loaded().copy(copyToPath = "").copyTargetForSave())
    }

    @Test
    fun warningFollowsTheLastCopyStatus() {
        assertTrue(loaded(status = BackupCopyStatus.FAILED).copyWarning)
        assertTrue(loaded(status = BackupCopyStatus.SKIPPED_NOT_MOUNTED).copyWarning)
        assertFalse(loaded(status = BackupCopyStatus.OK).copyWarning)
        assertFalse(loaded(status = null).copyWarning)
    }

    @Test
    fun aJobRunUpdatesTheCopyRecordButKeepsTheDraft() {
        val draft = loaded().copy(copyToPath = "/media/pos/OTHER")
        val afterRun =
            draft.withRunRecord(
                BackupRestoreDto.BackupConfigDto(
                    copyTo = stick,
                    lastBackupAt = "2026-05-16T00:47:00Z",
                    lastCopyStatus = BackupCopyStatus.SKIPPED_NOT_MOUNTED,
                    lastCopyError = "device not mounted",
                ),
            )
        assertEquals("/media/pos/OTHER", afterRun.copyToPath)
        assertEquals("2026-05-16T00:47:00Z", afterRun.lastBackupAt)
        assertTrue(afterRun.copyWarning)
        assertEquals(BackupCopyStatus.SKIPPED_NOT_MOUNTED, afterRun.savedConfig?.lastCopyStatus)
        assertTrue(afterRun.isConfigDirty)
    }

    @Test
    fun relativeOrInsideBackupFolderPathsAreRefusedBeforeSending() {
        assertTrue(loaded().copy(copyToPath = "media/stick").copyPathInvalid)
        assertTrue(loaded().copy(copyToPath = "/media/../etc").copyPathInvalid)
        assertTrue(loaded().copy(copyToPath = "/var/lib/zenbon/backups/usb").copyPathInvalid)
        assertFalse(loaded().copy(copyToPath = "/media/pos/ZEN_BACKUP").copyPathInvalid)
        assertFalse(loaded().copy(copyToPath = "").copyPathInvalid)
    }

    @Test
    fun theRetentionFieldCanBeClearedAndRetyped() {
        val cleared = loaded().withCopyRetentionInput("")
        assertEquals("", cleared.copyRetentionInput)
        assertEquals(5, cleared.copyRetentionCount, "nothing valid was typed: the last valid count stays")
        assertTrue(cleared.copyRetentionInvalid)

        val retyped = cleared.withCopyRetentionInput("1").withCopyRetentionInput("14")
        assertEquals("14", retyped.copyRetentionInput)
        assertEquals(14, retyped.copyRetentionCount)
        assertFalse(retyped.copyRetentionInvalid)
        assertTrue(retyped.isConfigDirty)
    }

    @Test
    fun theRetentionFieldKeepsOnlyDigitsAndReplacesAnOutOfRangeNumber() {
        assertEquals("3", loaded().withCopyRetentionInput("a3b").copyRetentionInput)
        val tooBig = loaded().withCopyRetentionInput("400")
        assertEquals("365", tooBig.copyRetentionInput)
        assertEquals(365, tooBig.copyRetentionCount)
        val zero = loaded().withCopyRetentionInput("0")
        assertEquals("1", zero.copyRetentionInput)
        assertEquals(1, zero.copyRetentionCount)
    }

    @Test
    fun anEmptyRetentionOnlyBlocksSavingWhileACopyTargetIsSet() {
        assertTrue(loaded().withCopyRetentionInput("").copyRetentionInvalid)
        assertFalse(loaded(copyTo = null).withCopyRetentionInput("").copyRetentionInvalid)
    }
}
