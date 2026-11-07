package org.example.project.utils

import org.example.project.BuildConfig
import java.io.File

/** Supported host platforms for the server. */
enum class OSType {
    LINUX, // Debian, Ubuntu
    WINDOWS,
    UNKNOWN,
}

/**
 * Utility object for OS-aware file path handling.
 *
 * Provides cross-platform default paths for application data storage.
 * Supported platforms: Debian/Ubuntu Linux, Windows.
 */
object OsUtils {
    /**
     * Detects the current operating system.
     *
     * Only Debian/Ubuntu-based Linux and Windows are supported.
     *
     * @return OSType enum representing the detected OS
     */
    fun detectOS(): OSType {
        val osName = System.getProperty("os.name").lowercase()

        return when {
            osName.contains("windows") -> OSType.WINDOWS
            osName.contains("linux") -> OSType.LINUX
            else -> OSType.UNKNOWN
        }
    }

    /**
     * Returns the application data directory in the user's home folder.
     *
     * Creates a simple folder structure directly in the user's home directory:
     * - All platforms: ~/POS/ (e.g., /Users/dz/POS or C:\Users\dz\POS)
     *
     * @param appName The application name to use in the path
     * @return Absolute path to the application data directory
     */
    fun getAppDataDirectory(appName: String = BuildConfig.APP_NAME): String {
        val override = System.getProperty(DATA_DIR_VARIABLE) ?: System.getenv(DATA_DIR_VARIABLE)
        return resolveAppDataDirectory(override, System.getProperty("user.home"), appName)
    }

    /** Environment variable / system property that moves the whole data directory (e.g. `/var/lib/zenbon`). */
    const val DATA_DIR_VARIABLE: String = "ZENBON_DATA_DIR"

    internal fun resolveAppDataDirectory(
        override: String?,
        userHome: String,
        appName: String,
    ): String {
        val explicit = override?.trim().orEmpty()
        if (explicit.isEmpty()) return "$userHome${File.separator}$appName"
        return if (explicit.length > 1) explicit.trimEnd('/', '\\') else explicit
    }

    /** `LOG_DIR` when set, otherwise `<base>/.data/log` as before. */
    fun resolveLogDirectory(
        logDirOverride: String?,
        appDataDirectory: String,
    ): String {
        val explicit = logDirOverride?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit
        return "$appDataDirectory${File.separator}.data${File.separator}log"
    }

    /**
     * Returns the OS-appropriate default storage directory for documents.
     *
     * @param subDirectory Optional subdirectory within the app data folder
     * @return Absolute path to the storage directory
     */
    fun getDefaultStorageDirectory(subDirectory: String = "documents"): String = "${getAppDataDirectory()}${File.separator}$subDirectory"

    /**
     * Returns the default log directory in the user's home folder.
     *
     * @return Absolute path to the log directory (~/POS/logs)
     */
    fun getDefaultLogDirectory(): String = "${getAppDataDirectory()}${File.separator}logs"

    /**
     * Returns the default cache directory in the user's home folder.
     *
     * @return Absolute path to the cache directory (~/POS/cache)
     */
    fun getDefaultCacheDirectory(): String = "${getAppDataDirectory()}${File.separator}cache"

    /**
     * Ensures a directory exists, creating it if necessary.
     *
     * @param path The directory path to ensure exists
     * @return true if the directory exists or was created successfully
     */
    fun ensureDirectoryExists(path: String): Boolean {
        val dir = File(path)
        return if (dir.exists()) {
            dir.isDirectory
        } else {
            dir.mkdirs()
        }
    }

    /**
     * Normalizes a path to use the correct separator for the current OS.
     *
     * @param path The path to normalize
     * @return Normalized path with correct separators
     */
    fun normalizePath(path: String): String = path.replace("/", File.separator).replace("\\", File.separator)

    /**
     * Resolves a path that may be relative or absolute.
     * If relative, it's resolved against the app data directory.
     *
     * @param path The path to resolve
     * @return Absolute path
     */
    fun resolvePath(path: String): String {
        val file = File(path)
        return if (file.isAbsolute) {
            path
        } else {
            "${getAppDataDirectory()}${File.separator}$path"
        }
    }
}
