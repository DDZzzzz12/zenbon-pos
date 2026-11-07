package org.example.project.features.backup.data.service

import java.io.File

/**
 * Decides whether a backup copy target lies on a real mounted device.
 *
 * An unmounted mount point is just an empty folder on the root filesystem, so a copy would silently
 * fill the system disk. The guard therefore refuses a path whose longest covering mount is `/` and
 * one covered only by an `autofs` entry (`x-systemd.automount` before the device was ever touched).
 * A mount on the same device as `/` is refused too: under `ProtectSystem=strict` systemd bind-mounts
 * the service's writable folders onto themselves, and such a bind is no separate disk.
 * Touching the path first lets an automount trigger; the mount table is read afterwards.
 */
class MountPointGuard(
    private val mountInfo: () -> String = { File("/proc/self/mountinfo").readText() },
    private val touch: (File) -> Unit = { it.listFiles() },
) {
    fun check(path: String): MountVerdict {
        val canonical =
            try {
                File(path).canonicalPath
            } catch (e: java.io.IOException) {
                return MountVerdict.NotMounted("path cannot be resolved: ${e.message}")
            }
        runCatching { touch(File(canonical)) }
        val entries =
            try {
                parse(mountInfo())
            } catch (e: java.io.IOException) {
                return MountVerdict.NotMounted("mount table unreadable: ${e.message}")
            }
        val covering = entries.filter { covers(it.mountPoint, canonical) }
        val longest = covering.maxOfOrNull { it.mountPoint.length } ?: return MountVerdict.NotMounted("no mount covers $canonical")
        // Several mounts on one point stack; the last line is the one on top.
        val top = covering.last { it.mountPoint.length == longest }
        val rootDevice = entries.lastOrNull { it.mountPoint == "/" }?.device
        return when {
            top.mountPoint == "/" -> MountVerdict.NotMounted("$canonical is on the system disk (no device mounted there)")
            top.fsType == "autofs" -> MountVerdict.NotMounted("${top.mountPoint} is an automount and no device is present")
            top.device == rootDevice ->
                MountVerdict.NotMounted(
                    "${top.mountPoint} is on the same device as the system disk (a bind mount, not a separate device)",
                )
            else -> MountVerdict.Mounted(top.mountPoint, top.fsType)
        }
    }

    private data class Entry(
        val mountPoint: String,
        val fsType: String,
        val device: String,
    )

    private fun parse(text: String): List<Entry> =
        text
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(" - ")
                if (separator < 0) return@mapNotNull null
                val head = line.substring(0, separator).split(' ')
                val tail = line.substring(separator + 3).split(' ')
                if (head.size < 5 || tail.isEmpty()) return@mapNotNull null
                Entry(decode(head[4]), tail[0], head[2])
            }.toList()

    private fun covers(
        mountPoint: String,
        path: String,
    ): Boolean = mountPoint == "/" || path == mountPoint || path.startsWith("$mountPoint/")

    /** mountinfo escapes space, tab, newline and backslash as `\040`, `\011`, `\012`, `\134`. */
    private fun decode(raw: String): String =
        OCTAL.replace(raw) {
            it.groupValues[1]
                .toInt(8)
                .toChar()
                .toString()
        }

    private companion object {
        val OCTAL = Regex("""\\([0-7]{3})""")
    }
}
