package org.example.project.utils.formatter

import org.slf4j.LoggerFactory

/**
 * TSE_SERIAL must be the hex-encoded hash octet string: Anhang E p. 77 „Seriennummer der TSE
 * (Entspricht laut TR-03153 Abschnitt 7.5. dem Hashwert des im Zertifikat enthaltenen
 * Schlüssels; Octet-String in Hexadezimal-Darstellung)"; the ELSTER Ausfüllanleitung demands
 * 64 chars 0-9/A-F. The Swissbit driver delivers the raw serial bytes Base64-encoded —
 * transcode at the pos-server seam (audit H7; pos-tse itself is a protected module).
 */
object TseSerialFormat {
    private val logger = LoggerFactory.getLogger(TseSerialFormat::class.java)
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Exactly a 32-byte serial in Base64 — the only shape that may be decoded, and the same test
     * V82's backfill applies, so the helper and the migration cannot disagree.
     *
     * A looser test made this function NON-idempotent, and it is called twice on the same value
     * (the signer normalises, then `TseTransactionRepositoryImpl.create` does): Java's decoder
     * accepts unpadded input, so `53494D2D5453452D303031` — hex for the simulator's "SIM-TSE-001"
     * — decoded a second time into the fabricated `E77E3DE0…`, which matches no entry of
     * `SimulatedSignatureRepository.SIMULATED_SERIALS` and therefore blinded the month-end
     * no_simulated_signatures check. Real hardware was unaffected (43 chars + '=' and 64-hex both
     * settle on the first pass), which is why no suite noticed.
     */
    private val base64Serial32 = Regex("^[A-Za-z0-9+/]{43}=$")

    /**
     * A 32-byte Base64 serial → uppercase hex; already-hex values pass through uppercased;
     * anything else (the simulator's short serials in any of their forms) is kept verbatim;
     * blank stays blank. Idempotent by construction.
     */
    fun normalizeToHex(serial: String?): String? {
        if (serial.isNullOrBlank()) return serial
        if (serial.matches(hex64)) return serial.uppercase()
        if (!serial.matches(base64Serial32)) {
            logger.warn("TSE serial is neither 64-hex nor a 32-byte Base64 value: '{}' — kept verbatim", serial)
            return serial
        }
        return try {
            java.util.Base64
                .getDecoder()
                .decode(serial)
                .joinToString("") { "%02X".format(it) }
        } catch (_: IllegalArgumentException) {
            logger.warn("TSE serial looked like Base64 but did not decode: '{}' — kept verbatim", serial)
            serial
        }
    }
}
