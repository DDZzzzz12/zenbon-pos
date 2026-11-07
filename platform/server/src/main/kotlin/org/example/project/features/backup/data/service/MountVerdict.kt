package org.example.project.features.backup.data.service

/** Result of [MountPointGuard.check]: the path sits on a real mounted filesystem, or it does not. */
sealed interface MountVerdict {
    data class Mounted(
        val mountPoint: String,
        val fsType: String,
    ) : MountVerdict

    data class NotMounted(
        val reason: String,
    ) : MountVerdict
}
