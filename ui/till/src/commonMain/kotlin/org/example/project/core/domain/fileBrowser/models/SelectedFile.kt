package org.example.project.core.domain.fileBrowser.models

/**
 * Represents a file that has been selected by the user for upload/action.
 *
 * @property path Full path to the file
 * @property name Display name of the file
 * @property bytes File content as bytes
 * @property mimeType MIME type of the file
 * @property size Size in bytes
 */
data class SelectedFile(
    val path: String,
    val name: String,
    val bytes: ByteArray,
    val mimeType: String?,
    val size: Long = bytes.size.toLong(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelectedFile) return false
        return path == other.path && name == other.name
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}
