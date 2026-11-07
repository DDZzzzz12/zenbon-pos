package org.example.project.core.data.database.secret

/**
 * [ReplaceableKeySlot] standing in for an OS keychain whose backend cannot be opened right now. java-keyring's
 * `BackendNotSupportedException` covers more than a machine without a keychain: a D-Bus session that is not up yet at
 * autostart, an unreachable KWallet, antivirus blocking JNA's library. None of those proves the keychain holds no key.
 *
 * Every [read] and [write] throws, so a load over this slot is incomplete ([LoadedKeys.complete] is false): it is never
 * cached, a value no readable key opens is kept rather than discarded, and no key is ever written here.
 */
class UnavailableKeySlot(
    override val description: String,
    private val cause: Throwable,
) : ReplaceableKeySlot {
    override fun read(): String? = throw unavailable()

    override fun write(value: String): Unit = throw unavailable()

    private fun unavailable(): IllegalStateException = IllegalStateException("$description cannot be used right now", cause)
}
