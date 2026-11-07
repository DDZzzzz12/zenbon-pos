package org.example.project.features.update.data.crypto

import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the GitHub update token with AES-256-GCM before it is written to the database.
 *
 * The key comes from the host's systemd EnvironmentFile, never from the database, so the
 * ciphertext and the key never travel together: a database backup restored onto another
 * machine carries a token nobody can open.
 *
 * That restore is an ordinary, expected event, so **every** failure to decrypt -- no key,
 * the wrong host's key, a truncated or tampered value -- returns null rather than throwing.
 * The caller turns null into "re-enter your update token"; nothing here may escape as an
 * exception onto a fiscal server's boot path.
 *
 * [encrypt] is the one exception: calling it with no usable key is a programming error and
 * throws. Guard on [isAvailable] before offering the operator anything that stores a token.
 */
class TokenCipher(
    keyBase64: String?,
) {
    private val key: SecretKeySpec? = parseKey(keyBase64)

    /** False when no usable key is provisioned -- absent, blank, not base64, or the wrong length. */
    val isAvailable: Boolean get() = key != null

    /** @throws IllegalStateException when no key is provisioned; check [isAvailable] first. */
    fun encrypt(plaintext: String): SealedToken {
        val secret = key ?: error("No update-token key is provisioned; check isAvailable before encrypting.")
        val nonce = ByteArray(NONCE_BYTES).also { RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce))
        return SealedToken(
            ciphertextBase64 = ENCODER.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))),
            nonceBase64 = ENCODER.encodeToString(nonce),
        )
    }

    /** Returns null when there is no key, the key is another host's, or the value was tampered with. */
    fun decrypt(sealed: SealedToken): String? {
        val secret = key ?: return null
        return try {
            val nonce = DECODER.decode(sealed.nonceBase64)
            val ciphertext = DECODER.decode(sealed.ciphertextBase64)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            log.info("Stored update token could not be decrypted with this host's key; it must be re-entered.")
            null
        } catch (e: IllegalArgumentException) {
            log.info("Stored update token is not valid base64; it must be re-entered.")
            null
        }
    }

    companion object {
        /** Generates a key for the operator to place in the host's EnvironmentFile. */
        fun generateKeyBase64(): String = ENCODER.encodeToString(ByteArray(KEY_BYTES).also { RANDOM.nextBytes(it) })

        private val log = LoggerFactory.getLogger(TokenCipher::class.java)
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val RANDOM = SecureRandom()
        private val ENCODER: Base64.Encoder = Base64.getEncoder()
        private val DECODER: Base64.Decoder = Base64.getDecoder()

        /** Never throws: an unusable key leaves the cipher disabled, it does not fail construction. */
        fun parseKey(keyBase64: String?): SecretKeySpec? {
            val raw = keyBase64?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val bytes =
                try {
                    DECODER.decode(raw)
                } catch (e: IllegalArgumentException) {
                    log.warn("The configured update-token key is not valid base64; token storage is disabled.")
                    return null
                }
            if (bytes.size != KEY_BYTES) {
                log.warn("The configured update-token key is not $KEY_BYTES bytes; token storage is disabled.")
                return null
            }
            return SecretKeySpec(bytes, "AES")
        }
    }
}
