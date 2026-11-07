package org.example.project.utils

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Utility for server-side image processing.
 */
object ImageUtils {
    /**
     * Resize and compress an image to JPEG.
     *
     * Scales the image down to [maxWidth] pixels wide (preserving aspect ratio)
     * and compresses as JPEG at the given [quality] (0.0–1.0).
     * If the image is already smaller than [maxWidth], it is only re-compressed.
     *
     * @param imageBytes Raw image bytes (JPEG, PNG, or WebP)
     * @param maxWidth Maximum width in pixels
     * @param quality JPEG compression quality (0.0 = max compression, 1.0 = max quality)
     * @return Compressed JPEG bytes
     */
    fun compressImage(
        imageBytes: ByteArray,
        maxWidth: Int = 800,
        quality: Float = 0.80f,
    ): ByteArray {
        val originalImage =
            ImageIO.read(ByteArrayInputStream(imageBytes))
                ?: throw IllegalArgumentException("Unable to read image")

        val originalWidth = originalImage.width
        val originalHeight = originalImage.height

        // Calculate new dimensions preserving aspect ratio
        val (newWidth, newHeight) =
            if (originalWidth > maxWidth) {
                val scale = maxWidth.toDouble() / originalWidth
                maxWidth to (originalHeight * scale).toInt()
            } else {
                originalWidth to originalHeight
            }

        // Resize using high-quality bilinear interpolation
        val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = resized.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null)
        g2d.dispose()

        // Compress as JPEG
        val outputStream = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val writeParam =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
        writer.output = ImageIO.createImageOutputStream(outputStream)
        writer.write(null, IIOImage(resized, null, null), writeParam)
        writer.dispose()

        return outputStream.toByteArray()
    }
}
