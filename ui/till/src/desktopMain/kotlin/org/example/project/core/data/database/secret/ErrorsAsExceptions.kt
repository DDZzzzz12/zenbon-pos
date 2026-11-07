package org.example.project.core.data.database.secret

/**
 * Runs [block] and reports an `Error` it throws as an [IllegalStateException], as [SecretCipher] requires: a missing
 * keychain binding surfaces as JNA's `UnsatisfiedLinkError` or a `NoClassDefFoundError`, and every caller must be able
 * to treat that as "unusable right now". A [VirtualMachineError] (out of memory, stack overflow) still propagates.
 */
internal inline fun <T> errorsAsExceptions(block: () -> T): T =
    try {
        block()
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Error) {
        throw IllegalStateException("The local-secrets key store failed: ${e::class.java.name}", e)
    }
