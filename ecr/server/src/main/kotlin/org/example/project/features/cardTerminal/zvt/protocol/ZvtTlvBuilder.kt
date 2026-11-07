/**
 * TLV (Tag-Length-Value) container builder for ZVT protocol.
 *
 * Constructs TLV containers for inclusion in BMP field 06.
 * Supports building both primitive and constructed (nested) TLV entries
 * using BER-TLV encoding.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

import java.io.ByteArrayOutputStream

/**
 * Builder for constructing TLV containers used in ZVT BMP field 06.
 *
 * Provides a fluent API for adding TLV entries and serializing
 * them into a byte array suitable for the TLV_CONTAINER BMP field.
 *
 * Usage:
 * ```kotlin
 * val tlvData = ZvtTlvBuilder()
 *     .addTag(0x1F04, byteArrayOf(0x01))
 *     .addTag(0x1F07, "Terminal".toByteArray())
 *     .build()
 * ```
 */
class ZvtTlvBuilder {
    private val entries = mutableListOf<TlvEntry>()

    /**
     * Adds a primitive TLV entry with a single-byte tag.
     *
     * @param tag The tag byte (0x00-0xFF, must not have bits 1-5 all set to 1)
     * @param value The tag value bytes
     * @return This builder for chaining
     */
    fun addTag(
        tag: Byte,
        value: ByteArray,
    ): ZvtTlvBuilder {
        entries.add(TlvEntry(byteArrayOf(tag), value, isConstructed = false))
        return this
    }

    /**
     * Adds a primitive TLV entry with a multi-byte tag specified as an integer.
     *
     * The tag integer is converted to its minimal byte representation.
     * For example: 0x1F04 → [0x1F, 0x04], 0xDF01 → [0xDF, 0x01]
     *
     * @param tagInt The tag as an integer (e.g., 0x1F04, 0xDF01)
     * @param value The tag value bytes
     * @return This builder for chaining
     */
    fun addTag(
        tagInt: Int,
        value: ByteArray,
    ): ZvtTlvBuilder {
        entries.add(TlvEntry(intToTagBytes(tagInt), value, isConstructed = false))
        return this
    }

    /**
     * Adds a constructed (nested) TLV entry containing child TLV entries.
     *
     * The children are serialized into the constructed tag's value.
     *
     * @param tagInt The constructed tag as an integer
     * @param builder A builder lambda to add child entries
     * @return This builder for chaining
     */
    fun addConstructed(
        tagInt: Int,
        builder: ZvtTlvBuilder.() -> Unit,
    ): ZvtTlvBuilder {
        val childBuilder = ZvtTlvBuilder()
        childBuilder.builder()
        val childData = childBuilder.build()
        entries.add(TlvEntry(intToTagBytes(tagInt), childData, isConstructed = true))
        return this
    }

    /**
     * Adds a pre-built [TlvEntry] to the builder.
     *
     * @param entry The TLV entry to add
     * @return This builder for chaining
     */
    fun addEntry(entry: TlvEntry): ZvtTlvBuilder {
        entries.add(entry)
        return this
    }

    /**
     * Serializes all added TLV entries into a byte array.
     *
     * Each entry is written as: [tag bytes] [BER length] [value bytes]
     *
     * @return The serialized TLV container bytes
     */
    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            // Write tag bytes
            out.write(entry.tag)
            // Write BER-encoded length
            out.write(ZvtLengthEncoding.encodeTlvLength(entry.value.size))
            // Write value
            out.write(entry.value)
        }
        return out.toByteArray()
    }

    /**
     * Returns the number of entries currently in the builder.
     */
    fun size(): Int = entries.size

    /**
     * Converts an integer tag value to its byte representation.
     *
     * Strips leading zero bytes to produce minimal representation.
     * Single-byte tags: 0x04 → [0x04]
     * Two-byte tags: 0x1F04 → [0x1F, 0x04]
     * Three-byte tags: 0xDF0101 → [0xDF, 0x01, 0x01]
     */
    private fun intToTagBytes(tagInt: Int): ByteArray =
        when {
            tagInt <= 0xFF -> byteArrayOf(tagInt.toByte())
            tagInt <= 0xFFFF ->
                byteArrayOf(
                    ((tagInt shr 8) and 0xFF).toByte(),
                    (tagInt and 0xFF).toByte(),
                )
            else ->
                byteArrayOf(
                    ((tagInt shr 16) and 0xFF).toByte(),
                    ((tagInt shr 8) and 0xFF).toByte(),
                    (tagInt and 0xFF).toByte(),
                )
        }

    companion object {
        /**
         * Creates a TLV container from a map of tag integers to value bytes.
         *
         * @param tagMap Map of tag integer → value bytes
         * @return Serialized TLV container bytes
         */
        fun fromMap(tagMap: Map<Int, ByteArray>): ByteArray {
            val builder = ZvtTlvBuilder()
            for ((tag, value) in tagMap) {
                builder.addTag(tag, value)
            }
            return builder.build()
        }
    }
}
