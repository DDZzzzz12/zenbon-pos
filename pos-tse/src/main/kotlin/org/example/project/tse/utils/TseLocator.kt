package org.example.project.tse.utils

import com.secureflashcard.wormapi.WormStore
import org.slf4j.LoggerFactory
import java.io.File

/**
 * TSE Hardware Locator and Detection Utility.
 *
 * Based on the production TSE connector TseLocator class,
 * provides automatic TSE device detection and hardware validation.
 *
 * @since 1.0.0
 */
object TseLocator {
    private val logger = LoggerFactory.getLogger(TseLocator::class.java)

    /**
     * Automatically finds and validates TSE device.
     *
     * @return TSE mount point if found and validated, null otherwise
     */
    fun findAndValidateTse(): String? {
        logger.info("Starting TSE device detection...")

        val mountPoint = findTseDevice()
        if (mountPoint != null) {
            logger.info("TSE device found at: $mountPoint")

            if (validateTseHardware(mountPoint)) {
                logger.info("TSE hardware validated successfully at: $mountPoint")
                return mountPoint
            } else {
                logger.warn("TSE device found but hardware validation failed at: $mountPoint")
            }
        } else {
            logger.debug("No TSE device found during auto-detection")
        }

        return null
    }

    /**
     * Finds TSE device based on operating system.
     *
     * @return TSE mount point if found, null otherwise
     */
    fun findTseDevice(): String? {
        logger.info("Locating TSE device automatically...")
        return if (isWindowsSystem()) {
            findTseInWindows()
        } else {
            findTseInLinux()
        }
    }

    /**
     * Validates TSE hardware at the specified mount point.
     *
     * This method only verifies that the TSE is present and readable.
     * It does NOT run self-test or require login - that is handled by
     * the initialization flow (TseInitializationManager).
     *
     * @param mountPoint The mount point to validate
     * @return true if TSE hardware is accessible
     */
    fun validateTseHardware(mountPoint: String): Boolean {
        return try {
            logger.info("Validating TSE hardware at mount point: $mountPoint")

            val wormStore = WormStore(mountPoint)

            wormStore.use { store ->
                // info() works without self-test or login
                val info = store.info()

                // Convert byte array to hex string (serial number is binary data)
                val serialNumber = info.tseSerialNumber().joinToString("") { "%02X".format(it) }
                val initState = info.initializationState()
                val hasPassedSelfTest = info.hasPassedSelfTest()
                val hasChangedPin = info.hasChangedAdminPin()
                val hasChangedPuk = info.hasChangedPuk()
                val hasChangedTimeAdminPin = info.hasChangedTimeAdminPin()
                val remainingSignatures = info.remainingSignatures()
                val isDevelopmentFirmware = info.isDevelopmentFirmware
                val tseDescription = info.tseDescription()
                val maxRegisterdClient = info.maxRegisteredClients()

                logger.info("TSE hardware validated:")
                logger.info("  - Serial: $serialNumber")
                logger.info("  - Initialization state: $initState")
                logger.info("  - Self-test passed: $hasPassedSelfTest")
                logger.info("  - Changed-pin: $hasChangedPin")
                logger.info("  - Changed-puk: $hasChangedPuk")
                logger.info("  - Changed-time-admin-pin: $hasChangedTimeAdminPin")
                logger.info("  - Remaining signatures: $remainingSignatures")
                logger.info("  - Tse-description: $tseDescription")
                logger.info("  - Max registered clients: $maxRegisterdClient")
                logger.info("  - IsDevelopmentFirmware: $isDevelopmentFirmware")

                // TSE is valid if we can read the serial number
                serialNumber.isNotEmpty()
            }

        } catch (e: Exception) {
            when {
                e.javaClass.name.contains("WormException") -> {
                    logger.warn("TSE hardware not available: ${e.message}")
                }
                e is java.io.FileNotFoundException -> {
                    logger.warn("TSE device not found at mount point: $mountPoint")
                }
                else -> {
                    logger.error("Error validating TSE hardware", e)
                }
            }
            false
        }
    }

    /**
     * Checks if the system is Windows.
     */
    private fun isWindowsSystem(): Boolean {
        val osName = System.getProperty("os.name")
        return osName.lowercase().startsWith("windows")
    }

    /**
     * Gets all directories in the specified path.
     */
    private fun getDirsInPath(path: String): List<String> {
        val directories = File(path).listFiles { file -> file.isDirectory }
        val fullPaths = mutableListOf<String>()
        directories?.forEach { dir ->
            fullPaths.add(dir.path)
        }
        return fullPaths
    }

    /**
     * Checks if a specific file exists in the given path.
     */
    private fun checkExistenceOfFile(path: String, fileName: String): Boolean {
        val tempFile = File(path + File.separator + fileName)
        return tempFile.exists()
    }

    /**
     * Checks for TSE device files in a specific directory.
     */
    private fun checkForTseDeviceInDir(path: String): Boolean {
        return checkExistenceOfFile(path, "TSE_INFO.DAT") ||
               checkExistenceOfFile(path, "tse_info.dat") ||
               checkExistenceOfFile(path, "TSE_COMM.DAT") ||
               checkExistenceOfFile(path, "tse_comm.dat") ||
               checkExistenceOfFile(path, "TSE_TAR.001") ||
               checkExistenceOfFile(path, "tse_tar.001")
    }

    /**
     * Finds TSE device on Linux systems.
     *
     * Searches common mount points:
     * - /mnt/ (traditional mount point)
     * - /mount/ (alternative mount point)
     * - /media/user/device (modern desktop environments like GNOME/KDE)
     *
     * For /media, searches two levels deep since removable devices are mounted
     * under /media/username/device-label (e.g., /media/dz/SWISSBIT)
     */
    private fun findTseInLinux(): String? {
        // Traditional mount points - search one level deep
        val traditionalRoots = arrayOf("/mnt", "/mount")
        for (root in traditionalRoots) {
            for (subdir in getDirsInPath(root)) {
                if (checkForTseDeviceInDir(subdir)) {
                    logger.info("Found TSE device at: $subdir")
                    return subdir
                }
            }
        }

        // /media - search two levels deep for modern desktop environments
        // Structure: /media/username/device-label
        val mediaRoot = "/media"
        for (userDir in getDirsInPath(mediaRoot)) {
            // Check direct child (e.g., /media/SWISSBIT - unlikely but possible)
            if (checkForTseDeviceInDir(userDir)) {
                logger.info("Found TSE device at: $userDir")
                return userDir
            }
            // Check user's mounted devices (e.g., /media/dz/SWISSBIT)
            for (deviceDir in getDirsInPath(userDir)) {
                if (checkForTseDeviceInDir(deviceDir)) {
                    logger.info("Found TSE device at: $deviceDir")
                    return deviceDir
                }
            }
        }

        return null
    }

    /**
     * Finds TSE device on Windows systems.
     *
     * Enumerates all available filesystem roots (drive letters) and checks each one
     * for TSE marker files. Prioritizes removable drives (USB, SD card, microSD)
     * since TSE devices are typically on removable media, then falls back to
     * fixed drives as a secondary search.
     *
     * For each drive, searches:
     * - The drive root itself (e.g., `E:\`)
     * - One level of subdirectories (e.g., `E:\SWISSBIT\`)
     */
    private fun findTseInWindows(): String? {
        val roots = File.listRoots()
        if (roots.isNullOrEmpty()) {
            logger.warn("No filesystem roots found on Windows")
            return null
        }

        logger.info("Detected ${roots.size} drive(s): ${roots.joinToString { it.path }}")

        // Partition into removable and fixed drives — check removable first
        val (removable, fixed) = roots.partition { isRemovableDrive(it) }

        logger.info("Removable drives: ${removable.joinToString { it.path }.ifEmpty { "none" }}")
        logger.info("Fixed drives: ${fixed.joinToString { it.path }}")

        // Search removable drives first (most likely location for TSE)
        for (drive in removable) {
            val result = searchDriveForTse(drive)
            if (result != null) return result
        }

        // Fall back to fixed drives
        for (drive in fixed) {
            val result = searchDriveForTse(drive)
            if (result != null) return result
        }

        return null
    }

    /**
     * Searches a single Windows drive for TSE marker files.
     *
     * Checks the drive root and one level of subdirectories.
     *
     * @param drive The drive root (e.g., `E:\`)
     * @return TSE mount point if found, null otherwise
     */
    private fun searchDriveForTse(drive: File): String? {
        val drivePath = drive.path

        // Check drive root
        if (checkForTseDeviceInDir(drivePath)) {
            logger.info("Found TSE device at drive root: $drivePath")
            return drivePath
        }

        // Check one level of subdirectories
        for (subdir in getDirsInPath(drivePath)) {
            if (checkForTseDeviceInDir(subdir)) {
                logger.info("Found TSE device at: $subdir")
                return subdir
            }
        }

        return null
    }

    /**
     * Determines if a drive is removable (USB stick, SD card, microSD, etc.).
     *
     * Uses [java.io.File.getTotalSpace] and [java.io.File.getFreeSpace] heuristics:
     * a root with zero total space is typically an empty/unmounted drive letter,
     * while a root that is not readable or has a very small total space is likely removable.
     *
     * Note: Java's standard library does not expose drive type directly.
     * This heuristic treats any non-system, small-capacity drive as potentially removable.
     *
     * @param root The filesystem root to check
     * @return true if the drive appears to be removable media
     */
    private fun isRemovableDrive(root: File): Boolean {
        return try {
            // Skip drives that don't exist or aren't readable
            if (!root.exists() || !root.canRead()) return false

            val totalSpace = root.totalSpace

            // Empty drive (no media inserted) — skip
            if (totalSpace == 0L) return false

            // Drives under 256 GB are likely removable (TSE devices are typically 4-32 GB)
            val maxRemovableSize = 256L * 1024 * 1024 * 1024
            totalSpace < maxRemovableSize
        } catch (e: SecurityException) {
            logger.debug("Cannot determine drive type for ${root.path}: ${e.message}")
            false
        }
    }
}
