package org.example.project.core.domain.fileBrowser

import org.example.project.core.domain.fileBrowser.models.FileEntry

/**
 * Platform-specific file system browser for listing directories and reading files.
 *
 * Provides a singleton instance that works across platforms:
 * - Desktop: java.io.File for file system access
 * - Android: File API + MediaStore for gallery + SAF for external storage
 */
expect object FileSystemBrowser {
    /**
     * List directory contents.
     * @param path Directory path to list
     * @return List of file entries (directories first, then files)
     */
    suspend fun listDirectory(path: String): List<FileEntry>

    /**
     * Generate a thumbnail for an image or PDF file.
     * @param path File path
     * @param size Target size in pixels (thumbnail will be size x size)
     * @return ByteArray of JPEG thumbnail or null if generation failed
     */
    suspend fun getThumbnail(
        path: String,
        size: Int,
    ): ByteArray?

    /**
     * Read entire file as bytes.
     * @param path File path
     * @return File content as ByteArray
     */
    suspend fun readFileBytes(path: String): ByteArray

    /**
     * Get the user's home directory path.
     * @return Home directory path
     */
    fun getHomeDirectory(): String

    /**
     * Get a list of picture directories on the device.
     * @return List of picture directory paths
     */
    fun getPictureDirectories(): List<String>
}
