package org.example.project.core.data.database.secret

/**
 * Seals short secrets for storage in the local preferences table.
 *
 * Android implements it with a key that never leaves Android Keystore; desktop with a key kept in
 * the OS keychain.
 *
 * **Format.** Every implementation must write and read exactly this, so a value sealed by one build
 * opens on the next:
 * - AES-256-GCM with a fresh random IV of [SealedSecret.IV_LENGTH] (12) bytes per seal;
 * - an authentication tag of [SealedSecret.TAG_BITS] (128) bits;
 * - associated data = the preference key's UTF-8 bytes;
 * - output = `SealedSecret.encode(iv + ciphertextWithTag)`; [open] reads it back with [SealedSecret.decode].
 *
 * **Failures.** The contract separates two failures a caller must treat differently: a value that can
 * **never** be opened (null from [open] — the caller may discard it) and a key store that is unusable
 * **right now** (an exception from [open] — the caller must keep the value and try again later).
 * An implementation must not let a native or linkage `Error` escape (e.g. JNA's `UnsatisfiedLinkError`
 * when the keychain library is missing): report it as an exception. [SecretProtectingKeyValueStore]
 * guards against an escaping `Error` anyway, but a caller other than the store may not.
 *
 * **Thread safety.** Both methods may be called concurrently. Create a fresh `javax.crypto.Cipher` per
 * call (a `Cipher` instance is stateful), and hold any cached key in a `@Volatile` or atomic field.
 */
interface SecretCipher {
    /**
     * Seals [plaintext] bound to the preference [key] (authenticated as associated data), so a sealed
     * value copied into another row does not open. Throws when it cannot seal; never returns unsealed
     * text. May create the cipher key.
     *
     * Never called with an empty [plaintext]: the store treats `""` as "cleared" and writes it unsealed.
     * Implementations enforce this with `require(plaintext.isNotEmpty())`.
     */
    fun seal(
        key: String,
        plaintext: String,
    ): String

    /**
     * Opens a value sealed for [key].
     *
     * Returns null ONLY when the value can never be opened:
     * - it is malformed ([SealedSecret.decode] returns null);
     * - authentication fails (tampered, or sealed for a different key);
     * - the cipher key is **provably** gone, i.e. the platform can tell absence apart from
     *   unavailability. Android, API 31+: the Keystore reports the alias as not found.
     *
     * Throws when the key store is unusable right now, and whenever absence cannot be told apart from
     * unavailability: Android Keystore failing at boot, a missing alias on Android API 29–30 (where a failed
     * Keystore lookup also looks like absence), or a desktop keychain that may simply be locked, where a
     * missing entry does not prove the key was deleted. The caller then keeps the value
     * and tries again later; returning null instead would destroy a pairing on a transient failure.
     * Never creates a cipher key.
     */
    fun open(
        key: String,
        sealed: String,
    ): String?
}
