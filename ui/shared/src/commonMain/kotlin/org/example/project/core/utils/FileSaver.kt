/**
 * Platform-specific file saving and sharing utility.
 *
 * Provides a common interface for saving and sharing files across
 * different platforms (Desktop, Android, iOS) with appropriate
 * platform-specific implementations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

/**
 * Platform-specific file saver object (singleton).
 *
 * Implementations handle file saving/sharing appropriate for each platform:
 * - Desktop: File chooser dialog to save to disk
 * - Android: Share via intent or save to downloads
 * - iOS: Share sheet or save to files
 */
expect object FileSaver {
    /**
     * Save a file to disk with a file chooser dialog (Desktop)
     * or save to downloads folder (Mobile).
     *
     * @param data File content as ByteArray
     * @param filename Suggested filename with extension
     * @param mimeType MIME type of the file (e.g., "text/csv")
     * @param onSuccess Callback invoked when file is saved successfully with file path
     * @param onError Callback invoked when save fails with error message
     * @param onCancelled Callback invoked when the user dismisses the save dialog (Desktop only; Android
     *   and the browser have no dialog to cancel). When null, a cancel is reported through [onError] as
     *   before, so callers that predate this parameter keep their behaviour.
     */
    fun saveFile(
        data: ByteArray,
        filename: String,
        mimeType: String = "text/csv",
        onSuccess: (filePath: String) -> Unit = {},
        onError: (error: String) -> Unit = {},
        onCancelled: (() -> Unit)? = null,
    )

    /**
     * Share a file using platform-specific sharing mechanism.
     *
     * - Desktop: Opens file location in file manager
     * - Android: Shares via Android share sheet
     * - iOS: Shares via iOS share sheet
     *
     * @param data File content as ByteArray
     * @param filename Filename with extension
     * @param mimeType MIME type of the file
     * @param onSuccess Callback invoked when share is initiated
     * @param onError Callback invoked when share fails
     */
    fun shareFile(
        data: ByteArray,
        filename: String,
        mimeType: String = "text/csv",
        onSuccess: () -> Unit = {},
        onError: (error: String) -> Unit = {},
    )
}
