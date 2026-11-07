package org.example.project.banking.mt940

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mt940TokenizerTest {
    @Test
    fun `splits tags at line start and keeps their order`() {
        val tags = Mt940Tokenizer.tokenize(":20:STARTUMS\r\n:25:12030000/0000202051\r\n:28C:14/1\r\n".toByteArray())
        assertEquals(listOf("20", "25", "28C"), tags.map { it.id })
        assertEquals("STARTUMS", tags.first().value)
        assertEquals("12030000/0000202051", tags[1].value)
    }

    @Test
    fun `a line not starting with a tag continues the previous one`() {
        val tags = Mt940Tokenizer.tokenize(":86:166?00SEPA-UEBERWEISUNG\r\n?20VERWENDUNG\r\n?21SZWECK\r\n".toByteArray())
        assertEquals(1, tags.size)
        assertEquals("166?00SEPA-UEBERWEISUNG?20VERWENDUNG?21SZWECK", tags.single().value)
    }

    @Test
    fun `continuation lines are joined with no separator inserted`() {
        // A bank wraps at a fixed width, cutting an IBAN in half. Joining with a space or newline
        // would corrupt it permanently — this is the single most damaging bug in the MT940 path.
        val tags = Mt940Tokenizer.tokenize(":86:166?31DE8937040044\r\n0532013000\r\n".toByteArray())
        assertTrue(
            "DE89370400440532013000" in tags.single().value,
            "the wrapped IBAN must be rejoined intact: ${tags.single().value}",
        )
    }

    @Test
    fun `handles LF-only line endings`() {
        val tags = Mt940Tokenizer.tokenize(":20:REF\n:25:ACCOUNT\n".toByteArray())
        assertEquals(listOf("20", "25"), tags.map { it.id })
    }

    @Test
    fun `unwraps SWIFT block framing`() {
        val raw = "{1:F01BYLADEM1AXXX0000000000}{2:O9401200}{4:\r\n:20:STARTUMS\r\n:25:ACCOUNT\r\n-}"
        val tags = Mt940Tokenizer.tokenize(raw.toByteArray())
        assertEquals(listOf("20", "25"), tags.map { it.id })
        assertEquals("ACCOUNT", tags[1].value)
    }

    @Test
    fun `decodes ISO-8859-1 umlauts when the bytes are not valid UTF-8`() {
        val latin1 = ":86:166?32Müller GmbH\r\n".toByteArray(Charsets.ISO_8859_1)
        val tags = Mt940Tokenizer.tokenize(latin1)
        assertTrue("Müller GmbH" in tags.single().value, "umlaut must survive: ${tags.single().value}")
    }

    @Test
    fun `decodes UTF-8 when the bytes are valid UTF-8`() {
        val utf8 = ":86:166?32Müller GmbH\r\n".toByteArray(Charsets.UTF_8)
        assertTrue("Müller GmbH" in Mt940Tokenizer.tokenize(utf8).single().value)
    }

    @Test
    fun `a lone dash ends the message and is not glued onto the last tag`() {
        // Found by a real Sparkasse-suite export. Without this the SWIFT end-of-message marker is
        // treated as a continuation and joined — with no separator — onto the closing balance,
        // turning "12345,98" into "12345,98-", which then fails to parse and fails the whole file.
        val tags = Mt940Tokenizer.tokenize(":20:REF\r\n:62F:C170914EUR12345,98\r\n-\r\n".toByteArray())
        assertEquals("C170914EUR12345,98", tags.last().value)
    }

    @Test
    fun `content after the end-of-message marker is not parsed`() {
        val tags = Mt940Tokenizer.tokenize(":20:REF\r\n:62F:C170914EUR1,00\r\n-\r\n:61:2212191220C119,NMSC\r\n".toByteArray())
        assertEquals(listOf("20", "62F"), tags.map { it.id }, "a trailing fragment must not become an entry")
    }

    @Test
    fun `a file with no tags yields nothing`() {
        assertEquals(emptyList(), Mt940Tokenizer.tokenize("Sehr geehrte Damen und Herren,\n".toByteArray()))
    }
}
