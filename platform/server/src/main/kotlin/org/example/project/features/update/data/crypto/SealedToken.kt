package org.example.project.features.update.data.crypto

/**
 * A GitHub PAT at rest: ciphertext and the nonce it was sealed with, both base64.
 *
 * Neither half is secret -- the key is not stored beside them, but in the systemd
 * EnvironmentFile -- so a database backup carries nothing usable on another host.
 */
data class SealedToken(
    val ciphertextBase64: String,
    val nonceBase64: String,
)
