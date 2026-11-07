package org.example.project.features.update

import org.example.project.features.update.data.crypto.TokenCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TokenCipherTest {
    private val keyA = TokenCipher.generateKeyBase64()
    private val keyB = TokenCipher.generateKeyBase64()

    @Test
    fun `round-trips a token`() {
        val cipher = TokenCipher(keyA)
        assertEquals("github_pat_example_value", cipher.decrypt(cipher.encrypt("github_pat_example_value")))
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        assertFalse(TokenCipher(keyA).encrypt("github_pat_example_value").ciphertextBase64.contains("github_pat"))
    }

    @Test
    fun `encrypting the same token twice yields different ciphertext`() {
        val cipher = TokenCipher(keyA)
        assertFalse(
            cipher.encrypt("same").ciphertextBase64 == cipher.encrypt("same").ciphertextBase64,
            "nonce is not being varied",
        )
    }

    @Test
    fun `a different host key yields null, not an exception`() {
        val sealed = TokenCipher(keyA).encrypt("github_pat_example_value")
        assertNull(TokenCipher(keyB).decrypt(sealed))
    }

    @Test
    fun `tampered ciphertext yields null`() {
        val cipher = TokenCipher(keyA)
        val sealed = cipher.encrypt("github_pat_example_value")
        // Flipping to a *different* character: a fixed "A" is a no-op on the ~1 run in 64
        // where the ciphertext already starts with one, and the test would then pass vacuously.
        val flipped = if (sealed.ciphertextBase64.startsWith("A")) "B" else "A"
        assertNull(cipher.decrypt(sealed.copy(ciphertextBase64 = sealed.ciphertextBase64.replaceRange(0, 1, flipped))))
    }

    @Test
    fun `an absent key disables the cipher rather than failing`() {
        val cipher = TokenCipher(null)
        assertFalse(cipher.isAvailable)
        assertNull(cipher.decrypt(TokenCipher(keyA).encrypt("x")))
    }
}
