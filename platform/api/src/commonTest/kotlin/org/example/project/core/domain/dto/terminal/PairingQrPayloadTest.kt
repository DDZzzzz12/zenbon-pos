package org.example.project.core.domain.dto.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingQrPayloadTest {
    @Test
    fun `parses a valid serverUrl and code payload`() {
        val parsed = PairingQrPayload.parse("""{"serverUrl":"http://192.168.1.10:8080/","code":"123456"}""")
        assertEquals("http://192.168.1.10:8080/", parsed?.serverUrl)
        assertEquals("123456", parsed?.code)
    }

    @Test
    fun `ignores unknown extra fields`() {
        val parsed = PairingQrPayload.parse("""{"serverUrl":"http://h/","code":"9","extra":"x"}""")
        assertEquals("http://h/", parsed?.serverUrl)
        assertEquals("9", parsed?.code)
    }

    @Test
    fun `returns null for non-json`() {
        assertNull(PairingQrPayload.parse("V0;SN;processType;data;42"))
    }

    @Test
    fun `returns null when a required field is blank`() {
        assertNull(PairingQrPayload.parse("""{"serverUrl":"","code":"123456"}"""))
        assertNull(PairingQrPayload.parse("""{"serverUrl":"http://h/","code":""}"""))
    }

    @Test
    fun `encode produces compact serverUrl-then-code JSON`() {
        val encoded = PairingQrPayload(serverUrl = "http://192.168.1.10:8080/", code = "123456").encode()
        assertEquals("""{"serverUrl":"http://192.168.1.10:8080/","code":"123456"}""", encoded)
    }

    @Test
    fun `encode then parse round-trips to an equal payload`() {
        val original = PairingQrPayload(serverUrl = "http://host:8080/", code = "000123")
        assertEquals(original, PairingQrPayload.parse(original.encode()))
    }

    @Test
    fun `rootFp is carried in the JSON and survives a round trip`() {
        val original = PairingQrPayload("https://192.168.1.10/", "000123", rootFp = "000G-40R4-0M30-E209-185G-R38E-1W")
        assertEquals(
            """{"serverUrl":"https://192.168.1.10/","code":"000123","rootFp":"000G-40R4-0M30-E209-185G-R38E-1W"}""",
            original.encode(),
        )
        assertEquals(original, PairingQrPayload.parse(original.encode()))
    }

    @Test
    fun `a payload without rootFp parses with a null fingerprint`() {
        assertNull(PairingQrPayload.parse("""{"serverUrl":"https://h/","code":"1"}""")?.rootFp)
    }
}
