package org.example.project.core.data.database.secret

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * `enc:v1:` + Base64(IV ‖ ciphertext ‖ tag) — the one stored form of a sealed secret.
 *
 * The prefix is what lets [SecretProtectingKeyValueStore] tell a sealed value from plaintext
 * written by a build before encryption, and the version leaves room to change the cipher later.
 * Every version shares [FAMILY_PREFIX], so this build recognises a value sealed by a newer one
 * instead of mistaking its ciphertext for legacy plaintext.
 */
@OptIn(ExperimentalEncodingApi::class)
object SealedSecret {
    /** Common to every format version, present and future. */
    const val FAMILY_PREFIX = "enc:"

    /** The format version this build writes and opens. */
    const val PREFIX = "enc:v1:"

    /** AES-GCM nonce length used by every [SecretCipher]; Android Keystore also defaults to 12. */
    const val IV_LENGTH = 12

    /** AES-GCM authentication tag length in bits, shared by every [SecretCipher]. */
    const val TAG_BITS = 128

    /** An empty value is never sealed, so a sealed secret carries at least one ciphertext byte. */
    private const val MIN_SIZE = IV_LENGTH + TAG_BITS / 8 + 1

    /** True for a value in this build's format ([PREFIX]). */
    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    /** True for a value sealed by any format version, including one newer than this build. */
    fun isFromThisFamily(value: String): Boolean = value.startsWith(FAMILY_PREFIX)

    fun encode(ivAndCiphertext: ByteArray): String = PREFIX + Base64.encode(ivAndCiphertext)

    /** The IV ‖ ciphertext ‖ tag bytes, or null when [value] is not a well-formed sealed secret. */
    fun decode(value: String): ByteArray? {
        if (!isSealed(value)) return null
        val bytes = runCatching { Base64.decode(value.removePrefix(PREFIX)) }.getOrNull() ?: return null
        return bytes.takeIf { it.size >= MIN_SIZE }
    }
}
