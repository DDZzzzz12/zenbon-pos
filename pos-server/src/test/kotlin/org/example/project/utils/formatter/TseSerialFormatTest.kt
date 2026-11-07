package org.example.project.utils.formatter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * H7 (audit 2026-07-13): TSE_SERIAL is the 64-char hex octet string (Anhang E p. 77;
 * ELSTER Ausfüllanleitung: „ausschließlich Zahlen von 0 bis 9 oder die Buchstaben A-F").
 */
class TseSerialFormatTest {
    private val hex = "AB0912CD".repeat(8) // 64 hex chars

    @Test
    fun `base64 serial transcodes to uppercase hex`() {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val base64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(bytes)
        assertEquals(hex, TseSerialFormat.normalizeToHex(base64))
    }

    @Test
    fun `already-hex serial passes through uppercased`() {
        assertEquals(hex, TseSerialFormat.normalizeToHex(hex.lowercase()))
    }

    @Test
    fun `unparseable serial is kept verbatim`() {
        assertEquals("TSE-TEST-SERIAL", TseSerialFormat.normalizeToHex("TSE-TEST-SERIAL"))
    }

    @Test
    fun `normalizing is idempotent`() {
        // The signer normalises, and so does TseTransactionRepositoryImpl.create — a second pass
        // must change nothing. It did not hold for the simulator: "53494D2D5453452D303031" is hex
        // for SIM-TSE-001 AND valid (unpadded) Base64, so a lenient decoder invented a serial that
        // matched no entry of SIMULATED_SERIALS and blinded the month-end simulated-signature
        // check. Only a full 32-byte Base64 serial (43 chars + '=') may be decoded.
        val base64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        listOf(
            // Base64("SIM-TSE-001"), what the simulated finish returns
            "U0lNLVRTRS0wMDE=",
            // its hex form, which earlier rows were stored as
            "53494D2D5453452D303031",
            base64,
            hex,
            hex.lowercase(),
        ).forEach { serial ->
            val once = TseSerialFormat.normalizeToHex(serial)
            assertEquals(once, TseSerialFormat.normalizeToHex(once), "normalizing '$serial' twice must equal normalizing it once")
        }
    }

    @Test
    fun `the simulator's serials are never decoded`() {
        // Both forms are listed in SimulatedSignatureRepository.SIMULATED_SERIALS, and V82's
        // backfill passes exactly these through — the helper must agree with it byte for byte.
        assertEquals("U0lNLVRTRS0wMDE=", TseSerialFormat.normalizeToHex("U0lNLVRTRS0wMDE="))
        assertEquals("53494D2D5453452D303031", TseSerialFormat.normalizeToHex("53494D2D5453452D303031"))
        assertEquals("SIM-TSE-001", TseSerialFormat.normalizeToHex("SIM-TSE-001"))
    }
}
