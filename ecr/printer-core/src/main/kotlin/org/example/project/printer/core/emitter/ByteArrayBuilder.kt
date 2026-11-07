/**
 * Efficient byte array composition utility for ESC/POS command building.
 *
 * Provides a mutable builder that collects byte arrays and text chunks,
 * then produces a single combined byte array. Used internally by
 * [EscPosEmitter][org.example.project.printer.core.emitter.EscPosEmitter]
 * to construct printer commands without repeated array copying.
 *
 * @since 1.0.0
 */
package org.example.project.printer.core.emitter

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Builder for composing ESC/POS byte arrays from commands and text.
 *
 * Wraps a [ByteArrayOutputStream] for efficient sequential appending.
 * Call [build] to produce the final byte array.
 *
 * @property charset Character encoding used when converting strings to bytes
 */
class ByteArrayBuilder(
    private val charset: Charset = charset("Cp850"),
) {
    private val buffer = ByteArrayOutputStream(1024)

    /**
     * Append raw bytes to the buffer.
     *
     * @param bytes Byte array to append
     * @return This builder for chaining
     */
    fun append(bytes: ByteArray): ByteArrayBuilder {
        buffer.write(bytes)
        return this
    }

    /**
     * Append a string encoded with the configured charset. Typographic characters the charset
     * cannot encode are first transliterated to ASCII by [PrintTextTransliterator].
     *
     * @param text Text to encode and append
     * @return This builder for chaining
     */
    fun append(text: String): ByteArrayBuilder {
        buffer.write(PrintTextTransliterator.transliterate(text, charset).toByteArray(charset))
        return this
    }

    /**
     * Append a single byte to the buffer.
     *
     * @param byte The byte value to append
     * @return This builder for chaining
     */
    fun append(byte: Byte): ByteArrayBuilder {
        buffer.write(byte.toInt())
        return this
    }

    /**
     * Build the final byte array from all appended data.
     *
     * @return Combined byte array containing all appended commands and text
     */
    fun build(): ByteArray = buffer.toByteArray()

    companion object {
        /**
         * Combine multiple byte arrays into one.
         *
         * @param arrays Variable number of byte arrays to concatenate
         * @return Single byte array containing all input arrays in order
         */
        fun combine(vararg arrays: ByteArray): ByteArray {
            val total = arrays.sumOf { it.size }
            val result = ByteArray(total)
            var offset = 0
            for (arr in arrays) {
                arr.copyInto(result, offset)
                offset += arr.size
            }
            return result
        }
    }
}

/**
 * DSL function to build an ESC/POS byte array using [ByteArrayBuilder].
 *
 * @param charset Character encoding for text conversion
 * @param block Builder lambda to append commands and text
 * @return Final combined byte array
 */
fun buildEscPos(
    charset: Charset = charset("Cp850"),
    block: ByteArrayBuilder.() -> Unit,
): ByteArray = ByteArrayBuilder(charset).apply(block).build()
