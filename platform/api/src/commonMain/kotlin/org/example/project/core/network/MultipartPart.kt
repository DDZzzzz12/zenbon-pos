/**
 * Multipart form data parts for HTTP file uploads.
 *
 * Provides a platform-agnostic representation of multipart
 * form data for file uploads and form submissions.
 *
 * @since 1.0.0
 */
package org.example.project.core.network

/**
 * Sealed class representing parts of a multipart form request.
 *
 * Supports both text fields and file uploads in multipart forms.
 *
 * Example usage:
 * ```kotlin
 * val parts = listOf(
 *     MultipartPart.Text("username", "JohnDoe"),
 *     MultipartPart.Text("description", "Monthly report upload"),
 *     MultipartPart.File(
 *         name = "file",
 *         filename = "report.csv",
 *         content = fileBytes,
 *         contentType = "text/csv"
 *     )
 * )
 * ```
 */
sealed class MultipartPart {
    /**
     * Text field in a multipart form.
     *
     * @property name The form field name
     * @property value The text value
     */
    data class Text(
        val name: String,
        val value: String,
    ) : MultipartPart()

    /**
     * File attachment in a multipart form.
     *
     * @property name The form field name
     * @property filename The filename to use in Content-Disposition
     * @property content The file content as bytes
     * @property contentType The MIME type of the file
     */
    data class File(
        val name: String,
        val filename: String,
        val content: ByteArray,
        val contentType: String = "application/octet-stream",
    ) : MultipartPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as File

            if (name != other.name) return false
            if (filename != other.filename) return false
            if (!content.contentEquals(other.content)) return false
            if (contentType != other.contentType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + content.contentHashCode()
            result = 31 * result + contentType.hashCode()
            return result
        }
    }
}
