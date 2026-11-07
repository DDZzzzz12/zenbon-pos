package org.example.project.core.data.database.secret

/**
 * Every local-secrets key readable right now, as [DesktopMasterKey.load] found them.
 *
 * Not meant for comparison: `equals` on the [ByteArray]s is by identity. The arrays belong to whoever holds this value.
 *
 * @property keys candidate keys, each [DesktopMasterKey.KEY_BYTES] long: the keychain key first, then the file key. A
 *   garbled or wrong-size stored key is not a candidate.
 * @property primaryUsable a keychain slot exists and its read did not throw. Only then may a new key be written to it.
 * @property fallbackReadable the key file's read did not throw.
 * @property complete every slot was read and no read threw. A result served from the key file alone while the keychain
 *   cannot be asked ([DesktopMasterKey.loadFallbackOnly]) is incomplete although no read threw: the keychain was not read.
 *   Only a build without java-keyring's classes has no keychain slot at all; that is deterministic, so it still counts as
 *   complete. A keychain backend that cannot be opened right now is a slot whose read throws ([UnavailableKeySlot]), so it
 *   does not. Only a complete result may conclude that a value will never open.
 * @property sealingKeySlot the [KeySlot.description] of the slot the first of [keys] came from — the key
 *   [DesktopSecretCipher] seals with. Null exactly when [keys] is empty. For logs; never key material.
 */
data class LoadedKeys(
    val keys: List<ByteArray>,
    val primaryUsable: Boolean,
    val fallbackReadable: Boolean,
    val complete: Boolean,
    val sealingKeySlot: String?,
) {
    init {
        require(keys.all { it.size == DesktopMasterKey.KEY_BYTES }) { "Every local-secrets key must be ${DesktopMasterKey.KEY_BYTES} bytes" }
        require(fallbackReadable || !complete) { "A load whose key file could not be read is never complete" }
        require((sealingKeySlot != null) == keys.isNotEmpty()) { "The sealing key's slot is named exactly when a key was loaded" }
    }
}
