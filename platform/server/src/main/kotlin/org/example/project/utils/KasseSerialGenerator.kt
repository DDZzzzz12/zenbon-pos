package org.example.project.utils

import java.security.SecureRandom

object KasseSerialGenerator {
    private const val SAFE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFIX = "ZBP"
    private const val RANDOM_LENGTH = 8

    fun generate(): String {
        val random = SecureRandom()
        val chars = CharArray(RANDOM_LENGTH) { SAFE_ALPHABET[random.nextInt(SAFE_ALPHABET.length)] }
        return "$PREFIX-${String(chars)}"
    }
}
