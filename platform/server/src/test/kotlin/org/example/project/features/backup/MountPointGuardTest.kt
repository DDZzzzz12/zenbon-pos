package org.example.project.features.backup

import org.example.project.features.backup.data.service.MountPointGuard
import org.example.project.features.backup.data.service.MountVerdict
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MountPointGuardTest {
    private val root = "26 1 8:2 / / rw,relatime shared:1 - ext4 /dev/sda2 rw"

    private fun mount(
        point: String,
        fs: String,
    ) = "70 26 8:17 / $point rw,relatime shared:9 - $fs /dev/sdb1 rw"

    private fun guard(
        vararg lines: String,
        touch: (java.io.File) -> Unit = {},
    ) = MountPointGuard(mountInfo = { lines.joinToString("\n") }, touch = touch)

    @Test
    @DisplayName("a path under a mounted exfat volume is mounted")
    fun mounted() {
        val v = guard(root, mount("/media/pos/ZEN_BACKUP", "exfat")).check("/media/pos/ZEN_BACKUP/backups")
        assertEquals(MountVerdict.Mounted("/media/pos/ZEN_BACKUP", "exfat"), v)
    }

    @Test
    @DisplayName("a path covered only by the root filesystem is on the system disk")
    fun systemDisk() {
        val v = guard(root).check("/media/pos/ZEN_BACKUP")
        assertIs<MountVerdict.NotMounted>(v)
        assertTrue("system disk" in v.reason)
    }

    @Test
    @DisplayName("a systemd bind of a folder on the root disk is not a device, a real stick bound over by systemd still is")
    fun bindOnRootDevice() {
        val bind = "71 26 8:2 /media/pos /media/pos rw,relatime shared:1 - ext4 /dev/sda2 rw"
        val v = guard(root, bind).check("/media/pos/WRONG")
        assertIs<MountVerdict.NotMounted>(v)
        assertTrue("same device" in v.reason)
        val stickThenBind = "72 70 8:17 / /media/pos/ZEN_BACKUP rw - exfat /dev/sdb1 rw"
        assertEquals(MountVerdict.Mounted("/media/pos/ZEN_BACKUP", "exfat"), guard(root, bind, stickThenBind).check("/media/pos/ZEN_BACKUP"))
    }

    @Test
    @DisplayName("an autofs entry is not a mount; after the touch a real mount on top is")
    fun autofs() {
        val auto = mount("/media/pos/ZEN_BACKUP", "autofs")
        assertIs<MountVerdict.NotMounted>(guard(root, auto).check("/media/pos/ZEN_BACKUP"))
        var touched = false
        val triggering =
            MountPointGuard(
                mountInfo = { if (touched) listOf(root, auto, mount("/media/pos/ZEN_BACKUP", "exfat")).joinToString("\n") else "$root\n$auto" },
                touch = { touched = true },
            )
        assertEquals(MountVerdict.Mounted("/media/pos/ZEN_BACKUP", "exfat"), triggering.check("/media/pos/ZEN_BACKUP"))
    }

    @Test
    @DisplayName("a mount point with a space is matched through its octal escape")
    fun spaces() {
        val v = guard(root, mount("/media/pos/MY\\040STICK", "vfat")).check("/media/pos/MY STICK/backups")
        assertEquals(MountVerdict.Mounted("/media/pos/MY STICK", "vfat"), v)
    }

    @Test
    @DisplayName("the longest matching mount point wins; a sibling prefix does not match")
    fun longest() {
        val v = guard(root, mount("/media", "tmpfs"), mount("/media/pos/ZEN_BACKUP", "ext4")).check("/media/pos/ZEN_BACKUP/x")
        assertEquals(MountVerdict.Mounted("/media/pos/ZEN_BACKUP", "ext4"), v)
        assertIs<MountVerdict.NotMounted>(guard(root, mount("/media/pos/ZEN", "ext4")).check("/media/pos/ZEN_BACKUP"))
    }

    @Test
    @DisplayName("touch runs before the mount table is read")
    fun touchFirst() {
        val order = mutableListOf<String>()
        val g =
            MountPointGuard(mountInfo = {
                order += "read"
                root
            }, touch = { order += "touch" })
        g.check("/media/pos/X")
        assertEquals(listOf("touch", "read"), order)
    }
}
