package org.example.project.core.browser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingCodeFromUrlTest {
    @Test
    fun `a valid code is returned and removed`() {
        assertEquals(PairingCodeFromUrl("123456", ""), parsePairingCode("?code=123456"))
    }

    @Test
    fun `kiosk is kept when the code is stripped`() {
        assertEquals(PairingCodeFromUrl("123456", "?kiosk=1"), parsePairingCode("?kiosk=1&code=123456"))
        assertEquals(PairingCodeFromUrl("123456", "?kiosk=1"), parsePairingCode("?code=123456&kiosk=1"))
    }

    @Test
    fun `a malformed code is dropped from the address bar but not returned`() {
        assertEquals(PairingCodeFromUrl(null, ""), parsePairingCode("?code=12<script>"))
        assertEquals(PairingCodeFromUrl(null, "?kiosk=1"), parsePairingCode("?kiosk=1&code=12"))
        assertEquals(PairingCodeFromUrl(null, ""), parsePairingCode("?code="))
    }

    @Test
    fun `no code leaves the query untouched`() {
        assertEquals(PairingCodeFromUrl(null, "?kiosk=1"), parsePairingCode("?kiosk=1"))
        assertEquals(PairingCodeFromUrl(null, ""), parsePairingCode(""))
        assertNull(parsePairingCode("?codex=123456").code)
    }
}
