/**
 * Android implementation of FileSaver.
 *
 * Automatically saves files to Downloads folder and shows Android's share sheet.
 * Users can access the saved file from Downloads and optionally share it.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Android implementation of FileSaver as singleton object.
 *
 * Uses ACTION_SEND to show Android's native share sheet (bottom sheet)
 * which provides options to:
 * - Share via messaging apps (WhatsApp, Telegram, etc.)
 * - Share via email
 * - Save to Files/Downloads
 * - Use Nearby Share
 * - Upload to cloud storage (Drive, Dropbox, etc.)
 */
actual object FileSaver {
    private lateinit var appContext: Context

    /**
     * Initialize with Android application context.
     *
     * Must be called from MainActivity onCreate() with applicationContext.
     *
     * @param context Android application context
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Save file to Downloads folder and show Android share sheet.
     *
     * Two-step process:
     * 1. Automatically saves the file to Downloads folder (guaranteed)
     * 2. Shows Android share sheet with options to share the saved file
     *
     * This ensures the file is always saved while giving users the option
     * to additionally share it via apps (WhatsApp, Email, Drive, etc.).
     *
     * @param data File content as ByteArray
     * @param filename Filename for the file
     * @param mimeType MIME type of the file (e.g., "text/csv")
     * @param onSuccess Called with Downloads path when file is saved successfully
     * @param onError Called with error message if operation fails
     * @param onCancelled Never called: the file is saved to Downloads without a dialog
     */
    actual fun saveFile(
        data: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: (filePath: String) -> Unit,
        onError: (error: String) -> Unit,
        onCancelled: (() -> Unit)?,
    ) {
        if (!::appContext.isInitialized) {
            onError("FileSaver not initialized. Call FileSaver.initialize(context) in MainActivity.")
            return
        }

        try {
            // Step 1: Save to Downloads folder (guaranteed save)
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )

            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val savedFile = File(downloadsDir, filename)

            // Write data to Downloads
            FileOutputStream(savedFile).use { output ->
                output.write(data)
            }

            println("File saved to Downloads: ${savedFile.absolutePath}")

            // Step 2: Show share sheet for the saved file (optional for user)
            // Get content URI using FileProvider
            val contentUri =
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    savedFile,
                )

            // Create share intent
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_TEXT, "File saved to: ${savedFile.absolutePath}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            // Launch share chooser
            val chooser = Intent.createChooser(shareIntent, "File saved. Share via:")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)

            // Success - file saved and share sheet launched
            onSuccess(savedFile.absolutePath)
        } catch (e: Exception) {
            onError("Failed to save file: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Share file using Android share sheet (same as saveFile on Android).
     *
     * Creates a temporary file and shares it using Android's
     * share intent, allowing the user to choose how to share
     * (email, messaging, save to Drive, etc.).
     *
     * @param data File content as ByteArray
     * @param filename Filename for sharing
     * @param mimeType MIME type of the file
     * @param onSuccess Called when share intent is launched
     * @param onError Called if share fails
     */
    actual fun shareFile(
        data: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: () -> Unit,
        onError: (error: String) -> Unit,
    ) {
        if (!::appContext.isInitialized) {
            onError("FileSaver not initialized. Call FileSaver.initialize(context) in MainActivity.")
            return
        }

        try {
            // Create temporary file in cache directory
            val cacheDir = appContext.cacheDir
            val file = File(cacheDir, filename)

            // Write data to temporary file
            FileOutputStream(file).use { output ->
                output.write(data)
            }

            // Get content URI using FileProvider
            val contentUri =
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file,
                )

            // Create share intent
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            // Launch share chooser
            val chooser = Intent.createChooser(shareIntent, "Share $filename")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)

            onSuccess()

            println("Sharing file: $filename")
        } catch (e: Exception) {
            onError("Failed to share file: ${e.message}")
            e.printStackTrace()
        }
    }
}
