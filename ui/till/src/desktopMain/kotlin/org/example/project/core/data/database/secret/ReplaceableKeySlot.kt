package org.example.project.core.data.database.secret

/**
 * A [KeySlot] whose value can be written over: the OS keychain ([OsKeyringKeySlot], or [UnavailableKeySlot] while its
 * backend cannot be opened). A keychain entry has no create-only write, so [DesktopMasterKey] writes one only after its
 * read succeeded and found no key. The key file is never one of these: it can only be created
 * ([FallbackKeySlot.writeIfAbsent]).
 */
interface ReplaceableKeySlot : KeySlot {
    /** Stores [value], replacing any previous one. Throws when the slot cannot take it. */
    fun write(value: String)
}
