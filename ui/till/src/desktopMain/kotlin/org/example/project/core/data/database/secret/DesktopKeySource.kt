package org.example.project.core.data.database.secret

/**
 * Where [DesktopSecretCipher] gets its keys. Every call returns fresh arrays that the caller owns.
 */
interface DesktopKeySource {
    /**
     * Every key readable right now ([DesktopMasterKey.load]), or only the key file's, as an incomplete result, while the
     * keychain cannot be asked ([DesktopMasterKey.loadFallbackOnly]). Never writes.
     */
    fun load(): LoadedKeys

    /**
     * [load]; when it finds no key, creates one ([DesktopMasterKey.loadOrCreate]) within the same keychain session — or in
     * the key file alone while the keychain cannot be asked — and returns it with the load's flags. Throws when no key can
     * be created.
     */
    fun loadOrCreate(): LoadedKeys
}
