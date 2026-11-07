package org.example.project.features.terminal.data.service

import java.security.SecureRandom

/** Generates short, single-use numeric pairing codes. */
object PairingCodeGenerator {
    private val random = SecureRandom()

    /** 6-digit zero-padded numeric code, e.g. "048213". */
    fun newCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}
