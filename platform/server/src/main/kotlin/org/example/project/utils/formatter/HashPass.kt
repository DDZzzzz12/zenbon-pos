package org.example.project.utils.formatter

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Hashes a password using SHA-256.
 *
 * Note: In production, use bcrypt or similar for better security.
 *
 * @param password Plain text password
 * @return Hashed password
 */
suspend fun hashPassword(password: String): String {
    val hashedPw = BCrypt.withDefaults().hashToString(12, password.toCharArray())
    return hashedPw
}

suspend fun verifyPassword(
    password: String,
    hashedPw: String,
): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hashedPw).verified
