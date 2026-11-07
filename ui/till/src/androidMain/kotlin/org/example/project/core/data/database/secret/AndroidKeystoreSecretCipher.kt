package org.example.project.core.data.database.secret

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretCipher] whose AES-256 key is generated inside Android Keystore and can never be exported.
 * A copied database file or a backup therefore carries nothing that opens the stored secrets.
 *
 * No user authentication is required for the key: the till must read its device token at every
 * start, unattended. Never add `setUserAuthenticationRequired` or `setUnlockedDeviceRequired` to the key spec:
 * either would lock the till out of its own token at boot. The key is removed when the app is uninstalled,
 * together with the database.
 *
 * **Never openable (null from [open]).** Only these outcomes:
 * - the value is malformed;
 * - `doFinal` throws [AEADBadTagException]. Keystore maps a failed tag check (`KM_ERROR_VERIFICATION_FAILED`) to it
 *   and to nothing else;
 * - `Cipher.init` throws [KeyPermanentlyInvalidatedException]. Keystore 2 also raises it when the alias
 *   disappeared between the lookup and the init;
 * - on API 31+ (Keystore 2), the Keystore daemon answered `KEY_NOT_FOUND` for the alias. That is the only case in
 *   which its `getKey` returns null.
 *
 * **Everything else propagates as "unusable right now"**, so the store keeps the value. That includes:
 * - a missing alias on API 29–30, reported as [KeyStoreException]. The legacy Keystore's `contains` returns false on a
 *   `RemoteException`, and its `getKey` returns null whenever `contains` is false, so a transient daemon failure looks
 *   exactly like absence. Neither `containsAlias` (the same call) nor a second lookup (only a narrower window) tells
 *   them apart, so absence is never provable there;
 * - `IllegalBlockSizeException`, Keystore's catch-all for any other `doFinal` failure, transient ones included;
 * - `BadPaddingException` (`KM_ERROR_INVALID_ARGUMENT`), which cannot be proven permanent;
 * - `UnrecoverableKeyException`, which Keystore 2 throws from the key lookup for any failure other than "not found".
 *   It also carries Keystore 2's rewrapped "permanently invalidated" error with the cause stripped
 *   (`AndroidKeyStoreSpi.engineGetKey`), so it cannot be classified and is left to propagate;
 * - `IllegalStateException`, which Keystore 2 throws when the Keystore service cannot be reached.
 *
 * `KeyStore.load(null)` succeeding proves nothing on either stack: it never contacts the daemon.
 * Keeping an unopenable value only costs a re-pair; discarding a readable one destroys a pairing.
 * The Keystore provider reports its failures as exceptions, so no `Error` needs wrapping.
 *
 * **Thread safety.** Each call creates its own [Cipher]. No key is cached: every call looks the alias
 * up again, so a deleted key is noticed at once. Key generation is serialized by [KEY_GENERATION_LOCK],
 * which [seal] takes only when its lock-free lookup finds no key. Without it, two first seals could each
 * generate a key under the alias, and the second key would silently replace the one the first value was sealed with.
 *
 * **Known limitations.**
 * - On API 29–30, a failed lookup inside [seal] reads as "alias absent", so `generateKey()` replaces the live key and
 *   every value sealed before it then fails its tag check and is discarded. Public API offers no way to close this.
 * - A rejected key blob is not recovered: after an OTA or a secure-hardware reset, `Cipher.init` can fail with
 *   `InvalidKeyException("Keystore operation failed", cause)` on every call, so the till can no longer store secrets.
 *   A bounded recovery inside [seal] on API 33+ is possible, but its error classification is unverified; the remedy is
 *   to clear the app's data and re-pair.
 */
class AndroidKeystoreSecretCipher(
    private val alias: String = "pos_local_secrets_v1",
) : SecretCipher {
    override fun seal(
        key: String,
        plaintext: String,
    ): String {
        require(plaintext.isNotEmpty()) { "An empty value is never sealed" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyForSealing()) }
        // Keystore requires randomized encryption: it generates the IV itself and rejects a caller-supplied one.
        val iv = checkNotNull(cipher.iv) { "Keystore returned no IV" }
        check(iv.size == SealedSecret.IV_LENGTH) { "Keystore returned a ${iv.size}-byte IV" }
        cipher.updateAAD(key.encodeToByteArray())
        return SealedSecret.encode(iv + cipher.doFinal(plaintext.encodeToByteArray()))
    }

    override fun open(
        key: String,
        sealed: String,
    ): String? {
        val bytes = SealedSecret.decode(sealed) ?: return null
        val secretKey = existingKey()
        if (secretKey == null) {
            // Only Keystore 2 (API 31+) proves absence: its getKey returns null solely on KEY_NOT_FOUND. The legacy
            // Keystore also returns null when the daemon call fails, so there the value must be kept, not discarded.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw KeyStoreException(
                    "Local-secrets key alias not found; API ${Build.VERSION.SDK_INT} cannot tell absence from a failed Keystore lookup",
                )
            }
            return null
        }
        return try {
            val spec = GCMParameterSpec(SealedSecret.TAG_BITS, bytes, 0, SealedSecret.IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey, spec) }
            cipher.updateAAD(key.encodeToByteArray())
            cipher.doFinal(bytes, SealedSecret.IV_LENGTH, bytes.size - SealedSecret.IV_LENGTH).decodeToString()
        } catch (_: AEADBadTagException) {
            null
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    /** Lock-free when the key exists; a missing key takes [KEY_GENERATION_LOCK] and is looked up again before generating. */
    private fun keyForSealing(): SecretKey = existingKey() ?: synchronized(KEY_GENERATION_LOCK) { existingKey() ?: generateKey() }

    /**
     * The key under [alias], or null when `getKey` returns null. On API 31+ that means the Keystore daemon answered
     * `KEY_NOT_FOUND`; on API 29–30 it can also mean a failed daemon call, so null never proves absence there.
     * `load(null)` never contacts the daemon, so a successful load proves nothing either.
     */
    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    private fun generateKey(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256

        /** Shared by every instance: they all generate into the same app-wide Keystore namespace. */
        val KEY_GENERATION_LOCK = Any()
    }
}
