package org.example.project.features.terminal.data.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Issues device tokens at pairing. Plaintext returned to the client once; only the hash is stored. */
object DeviceTokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { encoder.encodeToString(it) }

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
