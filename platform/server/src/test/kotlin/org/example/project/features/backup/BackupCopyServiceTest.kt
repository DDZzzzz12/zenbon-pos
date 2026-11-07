package org.example.project.features.backup

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.backup.BackupRestoreDto.BackupCopyTargetDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.backup.BackupCopyTargetKind
import org.example.project.features.backup.data.service.BackupCopyServiceImpl
import org.example.project.features.backup.data.service.MountPointGuard
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupCopyServiceTest {
    @TempDir lateinit var tmp: File

    private fun mountedAt(dir: File) = MountPointGuard(mountInfo = { "70 26 8:17 / ${dir.canonicalPath} rw - exfat /dev/sdb1 rw" })

    private val notMounted = MountPointGuard(mountInfo = { "26 1 8:2 / / rw - ext4 /dev/sda2 rw" })

    private fun target(dir: File) = BackupCopyTargetDto(BackupCopyTargetKind.MOUNTED_PATH, dir.path)

    private fun archive(
        name: String = "pos_backup_new.zip",
        bytes: Int = 4096,
    ): File = File(File(tmp, "local").apply { mkdirs() }, name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun stick() = File(tmp, "stick").apply { mkdirs() }

    private fun zip(
        dir: File,
        name: String,
        age: Long,
    ) = File(dir, name).apply {
        writeBytes(ByteArray(8))
        setLastModified(1_000_000_000L + age * 1000)
    }

    @Test
    @DisplayName("guard says not mounted: skipped, target untouched")
    fun notMountedSkips() {
        val dir = stick()
        val r = runBlocking { BackupCopyServiceImpl(notMounted).copy(archive(), target(dir), 7) }
        assertEquals(BackupCopyStatus.SKIPPED_NOT_MOUNTED, r.status)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    @DisplayName("success copies identical bytes and leaves no .part")
    fun success() {
        val dir = stick()
        val a = archive()
        val r = runBlocking { BackupCopyServiceImpl(mountedAt(dir)).copy(a, target(dir), 7) }
        assertEquals(BackupCopyStatus.OK, r.status)
        assertNotNull(r.copiedAt)
        assertContentEquals(a.readBytes(), File(dir, a.name).readBytes())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    @DisplayName("an unwritable target fails with the reason and leaves no archive")
    fun failure() {
        assumeFalse(System.getProperty("user.name") == "root")
        val dir = stick().apply { setWritable(false) }
        try {
            val r = runBlocking { BackupCopyServiceImpl(mountedAt(dir)).copy(archive(), target(dir), 7) }
            assertEquals(BackupCopyStatus.FAILED, r.status)
            assertTrue(r.message.isNotBlank())
            assertFalse(File(dir, "pos_backup_new.zip").exists())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    @DisplayName("stale .part files are removed on the next run")
    fun stalePart() {
        val dir = stick()
        File(dir, "pos_backup_old.zip.part").writeBytes(ByteArray(3))
        File(dir, "photos.zip.part").writeBytes(ByteArray(3))
        runBlocking { BackupCopyServiceImpl(mountedAt(dir)).copy(archive(), target(dir), 7) }
        assertFalse(File(dir, "pos_backup_old.zip.part").exists())
        assertTrue(File(dir, "photos.zip.part").exists())
    }

    @Test
    @DisplayName("retention 2 keeps the 2 newest including the new one; pre-restore and other files stay")
    fun retention() {
        val dir = stick()
        zip(dir, "pos_backup_a.zip", 1)
        zip(dir, "pos_backup_b.zip", 2)
        zip(dir, "pos_backup_c.zip", 3)
        zip(dir, "pos_backup_d.zip", 4)
        zip(dir, "pre-restore_2026.zip", 0)
        File(dir, "notes.txt").writeText("x")
        zip(dir, "holiday-photos.zip", 5)
        val r = runBlocking { BackupCopyServiceImpl(mountedAt(dir)).copy(archive(), target(dir), 2) }
        assertEquals(BackupCopyStatus.OK, r.status)
        assertEquals(setOf("pos_backup_new.zip", "pos_backup_d.zip", "pre-restore_2026.zip", "notes.txt", "holiday-photos.zip"), dir.list()!!.toSet())
    }

    @Test
    @DisplayName("a target inside the archive's own folder fails clearly")
    fun insideLocal() {
        val a = archive()
        val inside = File(a.parentFile, "usb").apply { mkdirs() }
        val r = runBlocking { BackupCopyServiceImpl(mountedAt(inside)).copy(a, target(inside), 7) }
        assertEquals(BackupCopyStatus.FAILED, r.status)
        assertTrue("local backup folder" in r.message)
        assertEquals(0, inside.listFiles()!!.size)
    }
}
