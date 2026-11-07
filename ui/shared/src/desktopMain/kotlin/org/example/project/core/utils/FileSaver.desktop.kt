/**
 * Desktop (JVM) implementation of FileSaver.
 *
 * Uses Swing FileChooser for file save dialogs and opens
 * file location in system file manager after saving.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop implementation of FileSaver using Swing components.
 *
 * Provides native file save dialogs and file manager integration
 * for desktop applications.
 */
actual object FileSaver {
    /**
     * Save file using Swing FileChooser dialog.
     *
     * Opens a native file save dialog where the user can choose
     * the destination folder and filename. Writes the file to disk
     * and optionally opens the containing folder.
     *
     * @param data File content as ByteArray
     * @param filename Default filename shown in dialog
     * @param mimeType MIME type (used to suggest file extension filter)
     * @param onSuccess Called with saved file absolute path
     * @param onError Called with error message if save fails
     * @param onCancelled Called when the chooser is dismissed; when null the cancel goes to [onError]
     */
    actual fun saveFile(
        data: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: (filePath: String) -> Unit,
        onError: (error: String) -> Unit,
        onCancelled: (() -> Unit)?,
    ) {
        try {
            val fileChooser =
                JFileChooser().apply {
                    dialogTitle = "Save File"
                    selectedFile = File(filename)

                    // Add file filter based on extension
                    val extension = filename.substringAfterLast('.', "")
                    if (extension.isNotEmpty()) {
                        fileFilter =
                            FileNameExtensionFilter(
                                "${extension.uppercase()} Files",
                                extension,
                            )
                    }
                }

            val result = fileChooser.showSaveDialog(null)

            if (result == JFileChooser.APPROVE_OPTION) {
                val file = fileChooser.selectedFile

                // Ensure file has correct extension
                val finalFile =
                    if (!file.name.contains('.')) {
                        val extension = filename.substringAfterLast('.', "")
                        File(file.absolutePath + ".$extension")
                    } else {
                        file
                    }

                // Write data to file
                finalFile.writeBytes(data)

                onSuccess(finalFile.absolutePath)

                println("File saved successfully: ${finalFile.absolutePath}")
            } else if (onCancelled != null) {
                onCancelled()
            } else {
                onError("File save cancelled by user")
            }
        } catch (e: Exception) {
            onError("Failed to save file: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Share file by saving it and opening the containing folder.
     *
     * On desktop, "sharing" means saving the file to a temporary
     * location and opening the folder in the system file manager.
     *
     * @param data File content as ByteArray
     * @param filename Filename to save
     * @param mimeType MIME type
     * @param onSuccess Called when folder is opened
     * @param onError Called if operation fails
     */
    actual fun shareFile(
        data: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        try {
            // Save to Downloads folder or temp directory
            val downloadsDir = File(System.getProperty("user.home"), "Downloads")
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val file = File(downloadsDir, filename)
            file.writeBytes(data)

            // Open the containing folder
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(downloadsDir)
                    onSuccess()
                } else {
                    onError("Desktop file manager not supported")
                }
            } else {
                // File saved but couldn't open folder
                onSuccess()
            }

            println("File saved to Downloads: ${file.absolutePath}")
        } catch (e: Exception) {
            onError("Failed to share file: ${e.message}")
            e.printStackTrace()
        }
    }
}
