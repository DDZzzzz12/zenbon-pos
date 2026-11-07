package org.example.project.features.tse.verification

import java.security.MessageDigest

/** Lower-case hex SHA-256 of [bytes] — the fingerprint a TSE archive is recorded and re-checked by. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
