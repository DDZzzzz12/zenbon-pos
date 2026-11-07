/**
 * BCD (Binary-Coded Decimal) packed encoding and decoding for ZVT protocol.
 *
 * The ZVT protocol uses BCD encoding for numeric fields such as amounts,
 * trace numbers, passwords, currency codes, dates, and times.
 * BCD packs two decimal digits per byte (nibble-based), with leading zeros
 * for fixed-width fields.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.protocol

/**
 * Codec for BCD (Binary-Coded Decimal) packed encoding used in ZVT protocol.
 *
 * BCD encoding stores two decimal digits per byte. Each nibble (4 bits)
 * represents one decimal digit (0-9). This is used extensively in ZVT
 * for amounts, trace numbers, currency codes, dates, and times.
 */
object ZvtBcdCodec {
    /**
     * Encodes a long value into a BCD byte array of the specified length.
     *
     * The value is right-aligned with leading zero-padding to fill
     * the requested number of bytes.
     *
     * @param value The numeric value to encode (must be non-negative)
     * @param byteCount The number of output bytes (each holds 2 digits)
     * @return BCD-encoded byte array
     * @throws IllegalArgumentException if value is negative or too large for byteCount
     */
    fun encode(
        value: Long,
        byteCount: Int,
    ): ByteArray {
        require(value >= 0) { "BCD value must be non-negative, got: $value" }
        val maxDigits = byteCount * 2
        val digits = value.toString()
        require(digits.length <= maxDigits) {
            "Value $value requires ${digits.length} digits but only $maxDigits available in $byteCount bytes"
        }

        val padded = digits.padStart(maxDigits, '0')
        val result = ByteArray(byteCount)
        for (i in 0 until byteCount) {
            val highNibble = padded[i * 2] - '0'
            val lowNibble = padded[i * 2 + 1] - '0'
            result[i] = ((highNibble shl 4) or lowNibble).toByte()
        }
        return result
    }

    /**
     * Decodes a BCD byte array into a long value.
     *
     * Each byte contributes two decimal digits. Leading zeros are
     * implicitly handled by the numeric conversion.
     *
     * @param data The BCD-encoded byte array
     * @return The decoded numeric value
     * @throws IllegalArgumentException if any nibble contains an invalid BCD digit (>9)
     */
    fun decode(data: ByteArray): Long {
        var result = 0L
        for (byte in data) {
            val unsigned = byte.toInt() and 0xFF
            val highNibble = (unsigned shr 4) and 0x0F
            val lowNibble = unsigned and 0x0F
            require(highNibble <= 9) { "Invalid BCD high nibble: $highNibble in byte 0x${unsigned.toString(16).padStart(2, '0')}" }
            require(lowNibble <= 9) { "Invalid BCD low nibble: $lowNibble in byte 0x${unsigned.toString(16).padStart(2, '0')}" }
            result = result * 100 + highNibble * 10 + lowNibble
        }
        return result
    }

    /**
     * Encodes an amount in minor currency units (cents) into 6-byte BCD.
     *
     * ZVT BMP field 04 uses 6-byte BCD for amounts.
     * Example: 12345 cents (€123.45) → `00 00 00 01 23 45`
     *
     * @param amountCents Amount in minor currency units (e.g., cents)
     * @return 6-byte BCD-encoded amount
     */
    fun encodeAmount(amountCents: Long): ByteArray = encode(amountCents, 6)

    /**
     * Decodes a 6-byte BCD amount into minor currency units (cents).
     *
     * @param data 6-byte BCD-encoded amount
     * @return Amount in minor currency units
     */
    fun decodeAmount(data: ByteArray): Long {
        require(data.size == 6) { "Amount BCD must be 6 bytes, got: ${data.size}" }
        return decode(data)
    }

    /**
     * Encodes a trace number into 3-byte BCD.
     *
     * ZVT BMP field 0B uses 3-byte BCD for trace numbers (000000-999999).
     *
     * @param traceNumber The trace number to encode
     * @return 3-byte BCD-encoded trace number
     */
    fun encodeTraceNumber(traceNumber: Int): ByteArray = encode(traceNumber.toLong(), 3)

    /**
     * Decodes a 3-byte BCD trace number.
     *
     * @param data 3-byte BCD-encoded trace number
     * @return The decoded trace number
     */
    fun decodeTraceNumber(data: ByteArray): Int {
        require(data.size == 3) { "Trace number BCD must be 3 bytes, got: ${data.size}" }
        return decode(data).toInt()
    }

    /**
     * Encodes a password into 3-byte BCD.
     *
     * ZVT BMP field 3D uses 3-byte BCD for passwords (000000-999999).
     *
     * @param password The 6-digit password to encode
     * @return 3-byte BCD-encoded password
     */
    fun encodePassword(password: Int): ByteArray = encode(password.toLong(), 3)

    /**
     * Decodes a 3-byte BCD password.
     *
     * @param data 3-byte BCD-encoded password
     * @return The decoded password value
     */
    fun decodePassword(data: ByteArray): Int {
        require(data.size == 3) { "Password BCD must be 3 bytes, got: ${data.size}" }
        return decode(data).toInt()
    }

    /**
     * Encodes a currency code into 2-byte BCD.
     *
     * ZVT BMP field 49 uses 2-byte BCD for ISO 4217 currency codes.
     * Example: EUR = 978 → `09 78`
     *
     * @param currencyCode ISO 4217 numeric currency code
     * @return 2-byte BCD-encoded currency code
     */
    fun encodeCurrency(currencyCode: Int): ByteArray = encode(currencyCode.toLong(), 2)

    /**
     * Decodes a 2-byte BCD currency code.
     *
     * @param data 2-byte BCD-encoded currency code
     * @return ISO 4217 numeric currency code
     */
    fun decodeCurrency(data: ByteArray): Int {
        require(data.size == 2) { "Currency BCD must be 2 bytes, got: ${data.size}" }
        return decode(data).toInt()
    }

    /**
     * Encodes a time value (HHMMSS) into 3-byte BCD.
     *
     * ZVT BMP field 0C uses 3-byte BCD for time.
     *
     * @param hour Hour (0-23)
     * @param minute Minute (0-59)
     * @param second Second (0-59)
     * @return 3-byte BCD-encoded time
     */
    fun encodeTime(
        hour: Int,
        minute: Int,
        second: Int,
    ): ByteArray {
        require(hour in 0..23) { "Hour must be 0-23, got: $hour" }
        require(minute in 0..59) { "Minute must be 0-59, got: $minute" }
        require(second in 0..59) { "Second must be 0-59, got: $second" }
        val timeValue = hour.toLong() * 10000 + minute * 100 + second
        return encode(timeValue, 3)
    }

    /**
     * Decodes a 3-byte BCD time into hour, minute, second triple.
     *
     * @param data 3-byte BCD-encoded time (HHMMSS)
     * @return Triple of (hour, minute, second)
     */
    fun decodeTime(data: ByteArray): Triple<Int, Int, Int> {
        require(data.size == 3) { "Time BCD must be 3 bytes, got: ${data.size}" }
        val value = decode(data).toInt()
        val hour = value / 10000
        val minute = (value % 10000) / 100
        val second = value % 100
        return Triple(hour, minute, second)
    }

    /**
     * Encodes a date value (MMDD) into 2-byte BCD.
     *
     * ZVT BMP field 0D uses 2-byte BCD for date.
     *
     * @param month Month (1-12)
     * @param day Day (1-31)
     * @return 2-byte BCD-encoded date
     */
    fun encodeDate(
        month: Int,
        day: Int,
    ): ByteArray {
        require(month in 1..12) { "Month must be 1-12, got: $month" }
        require(day in 1..31) { "Day must be 1-31, got: $day" }
        val dateValue = month.toLong() * 100 + day
        return encode(dateValue, 2)
    }

    /**
     * Decodes a 2-byte BCD date into month and day pair.
     *
     * @param data 2-byte BCD-encoded date (MMDD)
     * @return Pair of (month, day)
     */
    fun decodeDate(data: ByteArray): Pair<Int, Int> {
        require(data.size == 2) { "Date BCD must be 2 bytes, got: ${data.size}" }
        val value = decode(data).toInt()
        val month = value / 100
        val day = value % 100
        return Pair(month, day)
    }

    /**
     * Encodes a BCD string (arbitrary digit string) into packed BCD bytes.
     *
     * If the digit string has an odd number of digits, it is left-padded with '0'.
     *
     * @param digits String of decimal digits
     * @return Packed BCD byte array
     */
    fun encodeDigitString(digits: String): ByteArray {
        require(digits.all { it.isDigit() }) { "BCD digit string must contain only digits: $digits" }
        val padded = if (digits.length % 2 != 0) "0$digits" else digits
        val result = ByteArray(padded.length / 2)
        for (i in result.indices) {
            val high = padded[i * 2] - '0'
            val low = padded[i * 2 + 1] - '0'
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }

    /**
     * Decodes packed BCD bytes into a digit string.
     *
     * @param data Packed BCD byte array
     * @return String of decimal digits (with leading zeros preserved)
     */
    fun decodeDigitString(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (byte in data) {
            val unsigned = byte.toInt() and 0xFF
            sb.append((unsigned shr 4) and 0x0F)
            sb.append(unsigned and 0x0F)
        }
        return sb.toString()
    }
}
