package org.example.project.core.domain.fileBrowser.models

/**
 * Represents a file or directory entry in the file system.
 *
 * @property name Display name of the file or directory
 * @property path Full path to the file or directory
 * @property isDirectory True if this entry is a directory
 * @property size Size in bytes (0 for directories)
 * @property mimeType MIME type string (e.g., "image/jpeg", "application/pdf")
 * @property lastModified Last modification timestamp in milliseconds
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val mimeType: String? = null,
    val lastModified: Long = 0L,
)
