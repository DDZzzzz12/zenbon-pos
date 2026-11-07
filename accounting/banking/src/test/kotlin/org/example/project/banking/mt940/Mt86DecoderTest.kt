package org.example.project.banking.mt940

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Mt86DecoderTest {
    @Test
    fun `decodes counterparty name from the two name subfields`() {
        val f = Mt86Decoder.decode("166?00SEPA-GUTSCHRIFT?32Muster Handels?33 GmbH & Co KG")
        assertEquals("Muster Handels GmbH & Co KG", f.counterpartyName)
        assertEquals("SEPA-GUTSCHRIFT", f.postingText)
        assertEquals("166", f.gvc)
    }

    @Test
    fun `decodes counterparty bic and iban`() {
        val f = Mt86Decoder.decode("166?30COBADEFFXXX?31DE89370400440532013000")
        assertEquals("COBADEFFXXX", f.counterpartyBic)
        assertEquals("DE89370400440532013000", f.counterpartyIban)
    }

    @Test
    fun `concatenates the remittance subfields in order with no separator`() {
        val f = Mt86Decoder.decode("166?20SVWZ+Rechnung 20?2126-0042 Kunde?2200123")
        assertEquals("Rechnung 2026-0042 Kunde00123", f.remittance)
    }

    @Test
    fun `extracts the SEPA reference tags`() {
        val raw = "166?20EREF+E2E-99?21KREF+K-1?22MREF+MND-77?23CRED+DE98ZZZ09999999999?24SVWZ+Miete Januar"
        val f = Mt86Decoder.decode(raw)
        assertEquals("E2E-99", f.endToEndId)
        assertEquals("MND-77", f.mandateId)
        assertEquals("DE98ZZZ09999999999", f.creditorId)
        assertEquals("Miete Januar", f.remittance)
    }

    @Test
    fun `a SEPA tag value spanning a subfield boundary is rejoined intact`() {
        // The bank wrapped mid-IBAN. Joining with any separator would corrupt it.
        val f = Mt86Decoder.decode("166?20EREF+E2E-1?21SVWZ+Ueberweisung DE8937040044?220532013000")
        assertEquals("Ueberweisung DE89370400440532013000", f.remittance)
    }

    @Test
    fun `text resembling a tag inside a remittance value is not treated as a tag`() {
        val f = Mt86Decoder.decode("166?20SVWZ+Rechnung fuer EREFERENZ Nummer 5")
        assertEquals("Rechnung fuer EREFERENZ Nummer 5", f.remittance)
    }

    @Test
    fun `a tag-like token inside the remittance text does not fabricate a reference`() {
        // "Zahlung XEREF+ABC" cut at EREF+ would invent an end-to-end ID the bank never sent AND
        // truncate the purpose. Tags are matched in the DK's canonical order, so nothing that sorts
        // before SVWZ+ can match after it.
        val f = Mt86Decoder.decode("166?20SVWZ+Zahlung XEREF+ABC")
        assertNull(f.endToEndId, "EREF+ after SVWZ+ is free text, not a tag")
        assertEquals("Zahlung XEREF+ABC", f.remittance)
    }

    @Test
    fun `a real tag glued to the previous subfield still matches`() {
        // Banks wrap ?2x at a fixed width and the tokenizer rejoins with no separator, so a genuine
        // EREF+ routinely lands flush against the previous line's last character. A boundary rule
        // would drop this.
        val f = Mt86Decoder.decode("166?20EREF+E2E-1?21XSVWZ+Miete")
        assertEquals("E2E-1X", f.endToEndId)
        assertEquals("Miete", f.remittance)
    }

    @Test
    fun `a tag the DK places after SVWZ still terminates the remittance`() {
        val f = Mt86Decoder.decode("166?20SVWZ+Miete Januar?21ABWA+Mueller")
        assertEquals("Miete Januar", f.remittance)
    }

    @Test
    fun `a repeated tag keeps its first occurrence`() {
        // Matches splitSubfields, which is deliberately first-wins for a repeated subfield key.
        val f = Mt86Decoder.decode("166?20SVWZ+Teil eins?21SVWZ+Teil zwei")
        assertEquals("Teil einsSVWZ+Teil zwei", f.remittance)
    }

    @Test
    fun `uses the high remittance subfields too`() {
        val f = Mt86Decoder.decode("166?20SVWZ+Teil eins?60 Teil zwei?61 Teil drei")
        assertEquals("Teil eins Teil zwei Teil drei", f.remittance)
    }

    @Test
    fun `a field with no subfield structure is not decoded at all`() {
        val raw = "DAUERAUFTRAG MIETE JANUAR 2026"
        val f = Mt86Decoder.decode(raw)
        assertNull(f.counterpartyName, "a partially-recognised field must be treated as unrecognised")
        assertNull(f.counterpartyIban)
        assertNull(f.endToEndId)
        assertNull(f.gvc)
        assertEquals(raw, f.remittance, "the raw text survives whole as the remittance")
    }

    @Test
    fun `a field with a GVC but no subfields is not decoded either`() {
        val f = Mt86Decoder.decode("166 SONSTIGER TEXT OHNE SUBFELDER")
        assertNull(f.counterpartyName)
        assertEquals("166 SONSTIGER TEXT OHNE SUBFELDER", f.remittance)
    }

    @Test
    fun `an empty field yields nothing`() {
        val f = Mt86Decoder.decode("")
        assertNull(f.remittance)
        assertNull(f.counterpartyName)
    }
}
