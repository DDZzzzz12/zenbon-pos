/**
 * TLV (Tag-Length-Value) container parser for ZVT protocol.
 *
 * TLV containers appear inside BMP field 06 and carry extensible data
 * using BER-TLV encoding. Tags can be 1-3 bytes, lengths use BER encoding,
 * and values can be primitive or constructed (nested TLV).
 *
 * Tag encoding follows ISO/IEC 8825 (BER):
 * - Single-byte tag: bits 1-5 are not all 1s
 * - Multi-byte tag: first byte has bits 1-5 all set to 1 (0x1F),
 *   subsequent bytes have bit 8 set (continuation) until last byte
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

/**
 * Represents a single TLV (Tag-Length-Value) entry parsed from a TLV container.
 *
 * @property tag The tag identifier bytes
 * @property value The raw value bytes
 * @property isConstructed Whether this TLV contains nested TLV entries
 */
data class TlvEntry(
    val tag: ByteArray,
    val value: ByteArray,
    val isConstructed: Boolean = false,
) {
    /**
     * Returns the tag as a hex string for easy identification.
     * Example: tag bytes [0xDF, 0x01] → "DF01"
     */
    val tagHex: String
        get() = tag.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    /**
     * Returns the tag as a single integer for comparison and lookup.
     * Supports tags up to 3 bytes.
     */
    val tagInt: Int
        get() = tag.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }

    /**
     * Parses constructed TLV value into nested TLV entries.
     *
     * @return List of nested TLV entries, or empty if not constructed
     */
    fun children(): List<TlvEntry> = if (isConstructed) ZvtTlvParser.parse(value) else emptyList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlvEntry) return false
        return tag.contentEquals(other.tag) && value.contentEquals(other.value) && isConstructed == other.isConstructed
    }

    override fun hashCode(): Int {
        var result = tag.contentHashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + isConstructed.hashCode()
        return result
    }

    override fun toString(): String = "TLV(tag=$tagHex, len=${value.size}, constructed=$isConstructed)"
}

/**
 * Parser for BER-TLV containers used in ZVT BMP field 06.
 *
 * Parses a byte array containing one or more TLV entries, handling
 * multi-byte tags, BER length encoding, and constructed (nested) tags.
 */
object ZvtTlvParser {
    /**
     * Parses a TLV container byte array into a list of TLV entries.
     *
     * @param data The raw TLV container bytes
     * @param offset Starting offset in the array
     * @param length Number of bytes to parse (defaults to remaining array)
     * @return List of parsed [TlvEntry] objects
     */
    fun parse(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): List<TlvEntry> {
        val entries = mutableListOf<TlvEntry>()
        var pos = offset
        val end = offset + length

        while (pos < end) {
            // Skip padding bytes (0x00)
            if (data[pos] == 0x00.toByte()) {
                pos++
                continue
            }

            // Parse tag
            val (tag, tagBytesConsumed) = parseTag(data, pos)
            pos += tagBytesConsumed

            if (pos >= end) break

            // Parse length
            val (valueLength, lengthBytesConsumed) = ZvtLengthEncoding.decodeTlvLength(data, pos)
            pos += lengthBytesConsumed

            if (pos + valueLength > end) break

            // Extract value
            val value = data.copyOfRange(pos, pos + valueLength)
            pos += valueLength

            // Determine if tag is constructed (bit 6 of first tag byte)
            val isConstructed = (tag[0].toInt() and 0x20) != 0

            entries.add(TlvEntry(tag, value, isConstructed))
        }

        return entries
    }

    /**
     * Finds a TLV entry by tag hex string in a list of entries.
     *
     * Searches recursively through constructed entries.
     *
     * @param entries The list of TLV entries to search
     * @param tagHex The tag hex string to find (e.g., "DF01", "1F04")
     * @return The matching [TlvEntry], or null if not found
     */
    fun findByTag(
        entries: List<TlvEntry>,
        tagHex: String,
    ): TlvEntry? {
        val target = tagHex.uppercase()
        for (entry in entries) {
            if (entry.tagHex == target) return entry
            if (entry.isConstructed) {
                val found = findByTag(entry.children(), target)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Converts a list of TLV entries into a flat map of tagHex → value bytes.
     *
     * Constructed entries are recursively flattened. If the same tag appears
     * multiple times, the last occurrence wins.
     *
     * @param entries The TLV entries to flatten
     * @return Map of tag hex string to value bytes
     */
    fun toMap(entries: List<TlvEntry>): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        for (entry in entries) {
            map[entry.tagHex] = entry.value
            if (entry.isConstructed) {
                map.putAll(toMap(entry.children()))
            }
        }
        return map
    }

    /**
     * Parses a BER tag from the data array.
     *
     * Tag encoding:
     * - If bits 1-5 of the first byte are all 1s (0x1F mask), the tag
     *   continues in subsequent bytes where bit 8 indicates continuation.
     * - Otherwise, it's a single-byte tag.
     *
     * @param data The byte array
     * @param offset Starting position
     * @return Pair of (tag bytes, number of bytes consumed)
     */
    private fun parseTag(
        data: ByteArray,
        offset: Int,
    ): Pair<ByteArray, Int> {
        require(offset < data.size) { "Offset $offset exceeds data size ${data.size}" }
        val firstByte = data[offset].toInt() and 0xFF

        // Check if this is a multi-byte tag (bits 1-5 all set to 1)
        if ((firstByte and 0x1F) == 0x1F) {
            // Multi-byte tag
            var pos = offset + 1
            while (pos < data.size) {
                val b = data[pos].toInt() and 0xFF
                pos++
                if ((b and 0x80) == 0) {
                    // Last byte of tag (bit 8 is 0)
                    break
                }
            }
            val tagBytes = data.copyOfRange(offset, pos)
            return Pair(tagBytes, pos - offset)
        } else {
            // Single-byte tag
            return Pair(byteArrayOf(data[offset]), 1)
        }
    }
}
