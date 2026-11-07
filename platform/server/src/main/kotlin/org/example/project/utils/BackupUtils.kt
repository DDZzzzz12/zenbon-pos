/**
 * Utility functions for database backup and restore operations.
 *
 * Provides cross-platform support for:
 * - Detecting external drives on Linux and Windows
 * - Identifying TSE devices to exclude from backup locations
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils

import java.io.File

/**
 * Utility object for backup-related operations.
 */
object BackupUtils {
    /**
     * Checks if the system is Windows.
     */
    fun isWindowsSystem(): Boolean {
        val osName = System.getProperty("os.name") ?: ""
        return osName.lowercase().startsWith("windows")
    }

    /**
     * Checks if a directory is a TSE device by looking for TSE-specific files.
     * Uses the same detection logic as TseLocator.
     *
     * TSE devices contain files like:
     * - TSE_INFO.DAT / tse_info.dat
     * - TSE_COMM.DAT / tse_comm.dat
     * - TSE_TAR.001 / tse_tar.001
     */
    fun isTseDevice(directory: File): Boolean {
        if (!directory.isDirectory || !directory.canRead()) return false

        val tseFiles =
            listOf(
                "TSE_INFO.DAT",
                "tse_info.dat",
                "TSE_COMM.DAT",
                "tse_comm.dat",
                "TSE_TAR.001",
                "tse_tar.001",
            )

        return tseFiles.any { filename ->
            File(directory, filename).exists()
        }
    }

    /**
     * Gets a list of external drives available on the system.
     * Supports both Linux and Windows.
     * Excludes TSE devices.
     *
     * @return List of pairs (display name, absolute path)
     */
    fun getExternalDrives(): List<Pair<String, String>> =
        if (isWindowsSystem()) {
            getWindowsDrives()
        } else {
            getLinuxDrives()
        }

    /**
     * Gets external drives on Windows.
     * Lists all available drive letters except C: (system drive) and TSE devices.
     */
    private fun getWindowsDrives(): List<Pair<String, String>> {
        val drives = mutableListOf<Pair<String, String>>()

        File
            .listRoots()
            ?.filter { drive ->
                drive.exists() &&
                    drive.canRead() &&
                    !drive.absolutePath.equals("C:\\", ignoreCase = true) &&
                    !isTseDevice(drive)
            }?.sortedBy { it.absolutePath }
            ?.forEach { drive ->
                val driveLetter = drive.absolutePath.trimEnd('\\')
                drives.add("📁 $driveLetter (Drive)" to drive.absolutePath)
            }

        return drives
    }

    /**
     * Gets external drives on Linux.
     * Checks /media/<username>/ and /mnt/ for mounted drives.
     */
    private fun getLinuxDrives(): List<Pair<String, String>> {
        val drives = mutableListOf<Pair<String, String>>()
        val username = System.getProperty("user.name") ?: return drives

        // Check /media/<username>/ for user-mounted drives (common on Ubuntu/Debian)
        val mediaUserDir = File("/media/$username")
        if (mediaUserDir.exists() && mediaUserDir.isDirectory) {
            mediaUserDir
                .listFiles()
                ?.filter { drive ->
                    drive.isDirectory &&
                        drive.canRead() &&
                        !isTseDevice(drive)
                }?.sortedBy { it.name.lowercase() }
                ?.forEach { drive ->
                    drives.add("📁 ${drive.name} (External)" to drive.absolutePath)
                }
        }

        // Check /mnt/ for manually mounted drives
        val mntDir = File("/mnt")
        if (mntDir.exists() && mntDir.isDirectory) {
            mntDir
                .listFiles()
                ?.filter { drive ->
                    drive.isDirectory &&
                        drive.canRead() &&
                        !isTseDevice(drive)
                }?.sortedBy { it.name.lowercase() }
                ?.forEach { drive ->
                    drives.add("📁 ${drive.name} (/mnt)" to drive.absolutePath)
                }
        }

        return drives
    }
}
