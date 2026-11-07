package org.example.project.core.domain.fileBrowser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.example.project.core.domain.fileBrowser.models.FileEntry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import javax.imageio.ImageIO

/**
 * Desktop implementation of FileSystemBrowser using JVM file APIs.
 */
actual object FileSystemBrowser {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
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
            FileInputStream(file).use { it.readBytes() }
        }

    actual fun getHomeDirectory(): String = System.getProperty("user.home")

    actual fun getPictureDirectories(): List<String> {
        val pictures = File(System.getProperty("user.home"), "Pictures")
        return if (pictures.exists() && pictures.isDirectory) {
            listOf(pictures.absolutePath)
        } else {
            emptyList()
        }
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
        val originalImage: BufferedImage = ImageIO.read(file) ?: return null

        val widthRatio = size.toFloat() / originalImage.width
        val heightRatio = size.toFloat() / originalImage.height
        val ratio = minOf(widthRatio, heightRatio)

        val newWidth = (originalImage.width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (originalImage.height * ratio).toInt().coerceAtLeast(1)

        val thumbnail = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = thumbnail.createGraphics()
        graphics.drawImage(
            originalImage.getScaledInstance(newWidth, newHeight, java.awt.Image.SCALE_SMOOTH),
            0,
            0,
            newWidth,
            newHeight,
            null,
        )
        graphics.dispose()

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(thumbnail, "jpg", outputStream)
        return outputStream.toByteArray()
    }

    private fun generatePdfThumbnail(
        file: File,
        size: Int,
    ): ByteArray? {
        val document = Loader.loadPDF(file)

        return try {
            val renderer = PDFRenderer(document)
            val image = renderer.renderImageWithDPI(0, 150f)
            val outputStream = ByteArrayOutputStream()
            ImageIO.write(image, "jpg", outputStream)
            outputStream.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            document.close()
        }
    }
}
