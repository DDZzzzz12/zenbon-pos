package org.example.project.core.domain.fileBrowser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.domain.fileBrowser.models.FileEntry
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android implementation of FileSystemBrowser using Android file APIs.
 */
actual object FileSystemBrowser {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val pdfMimeType = "application/pdf"

    actual suspend fun listDirectory(path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val directory = File(path)
            if (!directory.exists() || !directory.isDirectory) {
                return@withContext emptyList()
            }

            directory
                .listFiles()
                ?.mapNotNull { file ->
                    try {
                        FileEntry(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            size = if (file.isFile) file.length() else 0L,
                            mimeType = if (file.isFile) getMimeType(file) else null,
                            lastModified = file.lastModified(),
                        )
                    } catch (_: Exception) {
                        null
                    }
                }?.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?: emptyList()
        }

    actual suspend fun getThumbnail(
        path: String,
        size: Int,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.isFile) return@withContext null

            try {
                when {
                    isImageFile(file) -> generateImageThumbnail(file, size)
                    isPdfFile(file) -> generatePdfThumbnail(file, size)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

    actual suspend fun readFileBytes(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                throw IllegalArgumentException("File not found: $path")
            }
            file.readBytes()
        }

    actual fun getHomeDirectory(): String = Environment.getExternalStorageDirectory().absolutePath

    actual fun getPictureDirectories(): List<String> {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        return listOfNotNull(
            pictures.absolutePath.takeIf { File(it).exists() },
            dcim.absolutePath.takeIf { File(it).exists() },
            downloads.absolutePath.takeIf { File(it).exists() },
        )
    }

    private fun getMimeType(file: File): String =
        when {
            isImageFile(file) -> "image/${file.extension.lowercase()}"
            isPdfFile(file) -> pdfMimeType
            else -> "application/octet-stream"
        }

    private fun isImageFile(file: File): Boolean = file.extension.lowercase() in imageExtensions

    private fun isPdfFile(file: File): Boolean = file.extension.lowercase() == "pdf"

    private fun generateImageThumbnail(
        file: File,
        size: Int,
    ): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(0) ?: return null
            val thumbnail = createScaledBitmap(bitmap, size, size)
            bitmapToByteArray(thumbnail)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun generatePdfThumbnail(
        file: File,
        size: Int,
    ): ByteArray? {
        val parcelFileDescriptor =
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        val pdfRenderer = PdfRenderer(parcelFileDescriptor)

        return try {
            if (pdfRenderer.pageCount == 0) return null
            val page = pdfRenderer.openPage(0)
            val bitmap =
                Bitmap.createBitmap(
                    (page.width * 150f / 72).toInt().coerceAtLeast(1),
                    (page.height * 150f / 72).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            val thumbnail = createScaledBitmap(bitmap, size, size)
            bitmapToByteArray(thumbnail)
        } catch (_: Exception) {
            null
        } finally {
            pdfRenderer.close()
            parcelFileDescriptor.close()
        }
    }

    private fun createScaledBitmap(
        source: Bitmap,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap {
        val widthRatio = maxWidth.toFloat() / source.width
        val heightRatio = maxHeight.toFloat() / source.height
        val ratio = minOf(widthRatio, heightRatio)

        val newWidth = (source.width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (source.height * ratio).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return outputStream.toByteArray()
    }
}
