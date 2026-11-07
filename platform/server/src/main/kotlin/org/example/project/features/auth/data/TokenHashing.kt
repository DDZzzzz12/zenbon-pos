package org.example.project.features.auth.data

import java.security.MessageDigest

/** SHA-256 hex of a token string. Stored instead of the raw token so a DB leak yields no usable tokens. */
fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
