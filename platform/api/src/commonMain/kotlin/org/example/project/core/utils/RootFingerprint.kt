package org.example.project.core.utils

/**
 * The out-of-band "Prüfcode" of a server's root certificate: the first 128 bits of its SHA-256,
 * written as 26 Crockford Base32 characters in groups of four (`K7QF-3MXA-9TD2-P8WC-4HNB-6R`).
 *
 * A till that is handed this code (QR or typed) can fetch the root over a not-yet-trusted
 * connection and accept it only if it hashes to the code. 128 bits keep a second root with the same
 * code out of reach. The 26 characters carry 130 bits: the two spare bits are zero and must be zero
 * when parsed, so exactly one spelling of a code is valid.
 */
object RootFingerprint {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val DIGEST_BYTES = 16
    private const val CODE_LENGTH = 26
    private const val GROUP = 4

    /** The Prüfcode for a SHA-256 [digest] of the certificate's DER bytes. */
    fun fromDigest(digest: ByteArray): String {
        require(digest.size >= DIGEST_BYTES) { "digest must hold at least $DIGEST_BYTES bytes" }
        val chars = StringBuilder(CODE_LENGTH)
        var buffer = 0
        var bits = 0
        for (i in 0 until DIGEST_BYTES) {
            buffer = (buffer shl 8) or (digest[i].toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                chars.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) chars.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return chars.chunked(GROUP).joinToString("-")
    }

    /**
     * The canonical form (upper case, no separators) of a typed or scanned [input], or null when it is
     * not a valid Prüfcode. Tolerates case, spaces, dashes and the Crockford look-alikes (O→0, I/L→1).
     */
    fun normalize(input: String): String? {
        val cleaned =
            input
                .filterNot { it == '-' || it.isWhitespace() }
                .uppercase()
                .map {
                    when (it) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> it
                    }
                }.joinToString("")
        if (cleaned.length != CODE_LENGTH || cleaned.any { it !in ALPHABET }) return null
        val lastValue = ALPHABET.indexOf(cleaned.last())
        if (lastValue and 0b11 != 0) return null // the two spare bits are always zero
        return cleaned
    }

    /** Whether [code] is a valid Prüfcode of the certificate whose SHA-256 is [digest]. */
    fun matches(
        code: String,
        digest: ByteArray,
    ): Boolean {
        val given = normalize(code) ?: return false
        return given == fromDigest(digest).replace("-", "")
    }
}
