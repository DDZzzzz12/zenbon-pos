package org.example.project.testutils.fakes

import org.example.project.core.data.database.secret.SealedSecret
import org.example.project.core.data.database.secret.SecretCipher

/**
 * Reversible stand-in for a real cipher that models the [SecretCipher] contract without any crypto.
 *
 * It "seals" as `enc:v1:<key>|<reversed plaintext>`, so tests can see a value was sealed and that it is
 * bound to its key: [open] returns null when the embedded key differs, as an authentication failure would.
 * - [canOpen] = false simulates a value that can never be opened (cipher key lost) — [open] returns null;
 * - [openError] set makes [open] throw it: an exception simulates a key store unusable right now
 *   (Keystore at boot, locked keychain), an `Error` a native keychain binding failing;
 * - [canSeal] = false makes [seal] throw.
 */
class FakeSecretCipher : SecretCipher {
    var canOpen = true
    var openError: Throwable? = null
    var canSeal = true

    override fun seal(
        key: String,
        plaintext: String,
    ): String {
        if (!canSeal) throw IllegalStateException("seal unavailable")
        return SealedSecret.PREFIX + key + SEPARATOR + plaintext.reversed()
    }

    override fun open(
        key: String,
        sealed: String,
    ): String? {
        openError?.let { throw it }
        if (!canOpen || !SealedSecret.isSealed(sealed)) return null
        val body = sealed.removePrefix(SealedSecret.PREFIX)
        val separator = body.indexOf(SEPARATOR)
        if (separator < 0 || body.substring(0, separator) != key) return null
        return body.substring(separator + 1).reversed()
    }

    private companion object {
        const val SEPARATOR = '|'
    }
}
