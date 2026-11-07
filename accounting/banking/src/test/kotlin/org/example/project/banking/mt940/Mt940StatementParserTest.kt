package org.example.project.banking.mt940

import kotlinx.datetime.LocalDate
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.CreditDebit
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Mt940StatementParserTest {
    private val sta =
        (
            ":20:STARTUMS\r\n" +
                ":25:12030000/0000202051\r\n" +
                ":28C:14/1\r\n" +
                ":60F:C260101EUR1000,00\r\n" +
                ":61:2601100110C200,00N051NONREF\r\n" +
                ":86:166?00SEPA-GUTSCHRIFT?20EREF+E2E-IN-1?21SVWZ+Rechnung 2026-0042" +
                "?30COBADEFFXXX?31DE89370400440532013000?32Kunde?33 Schmidt\r\n" +
                ":61:2601210120D50,00N012//REF-OUT-1\r\n" +
                ":86:177?00SEPA-LASTSCHRIFT?20MREF+MND-77?21CRED+DE98ZZZ09999999999" +
                "?22SVWZ+Strom Januar?30INGDDEFFXXX?31DE12500105170648489890?32Stadtwerke\r\n" +
                ":62F:C260131EUR1150,00\r\n"
        ).toByteArray()

    private fun parsed() = Mt940StatementParser.parse(sta)

    /**
     * A `String.replace` that matches nothing would leave the test green while asserting the
     * untouched fixture, so every rewrite proves it changed the text before it is parsed.
     */
    private fun rewritten(
        from: String,
        to: String,
    ): ByteArray {
        val original = String(sta)
        val changed = original.replace(from, to)
        assertNotEquals(original, changed, "the fixture rewrite matched nothing: \"$from\"")
        return changed.toByteArray()
    }

    @Test
    fun `maps the statement header`() {
        val s = parsed()
        assertEquals(BankStatementFormat.MT940, s.format)
        assertEquals("14", s.statementNumber)
        assertEquals("1", s.legalSequenceNumber)
        assertEquals("EUR", s.currency)
        assertEquals(LocalDate(2026, 1, 1), s.periodStart)
        assertEquals(LocalDate(2026, 1, 31), s.periodEnd)
    }

    @Test
    fun `a legacy account identification lands in accountNumber not accountIban`() {
        val s = parsed()
        assertEquals("12030000/0000202051", s.accountNumber)
        assertEquals(null, s.accountIban)
    }

    @Test
    fun `an IBAN in tag 25 lands in accountIban`() {
        val withIban = rewritten(":25:12030000/0000202051", ":25:DE02120300000000202051")
        val s = Mt940StatementParser.parse(withIban)
        assertEquals("DE02120300000000202051", s.accountIban)
    }

    @Test
    fun `maps opening and closing balances`() {
        val s = parsed()
        assertEquals(0, BigDecimal("1000.00").compareTo(s.openingBalance))
        assertEquals(0, BigDecimal("1150.00").compareTo(s.closingBalance))
    }

    @Test
    fun `a debit opening balance is negative`() {
        val overdrawn = rewritten(":60F:C260101EUR1000,00", ":60F:D260101EUR1000,00")
        assertEquals(0, BigDecimal("-1000.00").compareTo(Mt940StatementParser.parse(overdrawn).openingBalance))
    }

    @Test
    fun `maps a credit entry with its decoded counterparty`() {
        val entry = parsed().transactions.first()
        assertEquals(CreditDebit.CREDIT, entry.creditDebit)
        assertEquals(0, BigDecimal("200.00").compareTo(entry.amount))
        assertEquals(LocalDate(2026, 1, 10), entry.valueDate)
        assertEquals(LocalDate(2026, 1, 10), entry.bookingDate)
        assertEquals("Kunde Schmidt", entry.counterpartyName)
        assertEquals("DE89370400440532013000", entry.counterpartyIban)
        assertEquals("COBADEFFXXX", entry.counterpartyBic)
        assertEquals("E2E-IN-1", entry.endToEndId)
        assertEquals("Rechnung 2026-0042", entry.remittanceUnstructured)
    }

    @Test
    fun `maps a debit entry and its bank reference`() {
        val entry = parsed().transactions[1]
        assertEquals(CreditDebit.DEBIT, entry.creditDebit)
        assertEquals(0, BigDecimal("50.00").compareTo(entry.amount))
        assertTrue(entry.amount.signum() > 0, "amount stays positive; direction lives in creditDebit")
        assertEquals(LocalDate(2026, 1, 20), entry.bookingDate)
        assertEquals("REF-OUT-1", entry.accountServicerReference)
        assertEquals("MND-77", entry.mandateId)
        assertEquals("DE98ZZZ09999999999", entry.creditorId)
    }

    @Test
    fun `the bank transaction code fields stay null for MT940`() {
        val entry = parsed().transactions.first()
        assertEquals(null, entry.bankTxDomain)
        assertEquals(null, entry.bankTxFamily)
        assertEquals(null, entry.bankTxSubFamily)
    }

    @Test
    fun `the GVC and the raw block are kept in rawEntry`() {
        val raw = parsed().transactions.first().rawEntry
        assertTrue(raw != null && "166" in raw, "the GVC has no model field and must survive in rawEntry: $raw")
    }

    @Test
    fun `an RC mark sets the reversal indicator`() {
        val reversed = rewritten(":61:2601100110C200,00N051NONREF", ":61:2601100110RC200,00N051NONREF")
        val entry = Mt940StatementParser.parse(reversed).transactions.first()
        assertEquals(true, entry.reversalIndicator)
        assertEquals(CreditDebit.CREDIT, entry.creditDebit)
    }

    @Test
    fun `supplementary details on a second line do not corrupt the bank reference`() {
        // SWIFT allows an optional 34x supplementary-details subfield on a second line. The
        // tokenizer joins continuation lines with no separator (needed for :86:), so without
        // reading only the tag's first line this text is glued onto the bank reference and stored
        // as a wrong accountServicerReference — corrupt data, not a clean refusal.
        val withSupplementary =
            String(sta).replace(
                ":61:2601210120D50,00N012//REF-OUT-1\r\n",
                ":61:2601210120D50,00N012//REF-OUT-1\r\nSUPPLEMENTARY DETAILS\r\n",
            )
        assertTrue(withSupplementary != String(sta), "the fixture rewrite must actually match")

        val entry = Mt940StatementParser.parse(withSupplementary.toByteArray()).transactions[1]
        assertEquals("REF-OUT-1", entry.accountServicerReference)
    }

    @Test
    fun `a SWIFT transfer transaction type is accepted`() {
        // SWIFT defines S and F alongside N; German statements are overwhelmingly N, but refusing
        // a legal identifier would reject a whole bank's export.
        val swiftType = String(sta).replace(":61:2601100110C200,00N051NONREF", ":61:2601100110C200,00S103NONREF")
        assertTrue(swiftType != String(sta), "the fixture rewrite must actually match")

        val entry = Mt940StatementParser.parse(swiftType.toByteArray()).transactions.first()
        assertEquals(0, BigDecimal("200.00").compareTo(entry.amount))
    }

    @Test
    fun `entries inherit a non-EUR statement currency`() {
        // Found by a real Hungarian Raiffeisen export. `:61:` carries no currency of its own, and
        // hardcoding EUR made every entry of a HUF statement wrong — the currency invariant then
        // rejected the whole file, so no non-EUR statement could be stored at all.
        val huf =
            (
                ":20:STARTUMS\r\n:25:UBRTHUHB/123456789150ABCDEF002/HUF\r\n:28C:0072\r\n" +
                    ":60F:C180417HUF25170637,10\r\n" +
                    ":61:180417CF2066637,00N527\r\n:86:166?20SVWZ+Jovairas\r\n" +
                    ":62F:C180417HUF27237274,10\r\n"
            ).toByteArray()

        val s = Mt940StatementParser.parse(huf)
        assertEquals("HUF", s.currency)
        assertEquals("HUF", s.transactions.single().currency)
    }

    @Test
    fun `a statement line hard-wrapped mid-value is still read`() {
        // Found by a real Sparkasse export, which breaks :61: after the funds code. This is NOT the
        // supplementary-details case — here the second line is the value itself, so the joined
        // reading is the correct one and the first-line reading cannot parse.
        val wrapped =
            (
                ":20:STARTUMS\r\n:25:DE02120300000000202051\r\n:28C:1/1\r\n" +
                    ":60F:C181101EUR100,00\r\n" +
                    ":61:1811261126CR\r\n30,00N062NONREF\r\n:86:166?20SVWZ+Test\r\n" +
                    ":62F:C181130EUR130,00\r\n"
            ).toByteArray()

        val entry = Mt940StatementParser.parse(wrapped).transactions.single()
        assertEquals(CreditDebit.CREDIT, entry.creditDebit)
        assertEquals(0, BigDecimal("30.00").compareTo(entry.amount))
    }

    @Test
    fun `a bare tag 28 supplies the statement number`() {
        // A Luxembourg BIL export writes `:28:` rather than `:28C:`. Matching only 28C silently
        // dropped the statement number, which reconciliation keys statement identity on.
        val bil = String(sta).replace(":28C:14/1", ":28:00115/001")
        assertTrue(bil != String(sta), "the fixture rewrite must actually match")

        val s = Mt940StatementParser.parse(bil.toByteArray())
        assertEquals("00115", s.statementNumber)
        assertEquals("001", s.legalSequenceNumber)
    }

    @Test
    fun `several statements in one file are merged`() {
        val second =
            ":20:STARTUMS\r\n:25:12030000/0000202051\r\n:28C:15/1\r\n" +
                ":60F:C260201EUR1150,00\r\n" +
                ":61:2602030203C20,00N051NONREF\r\n:86:166?20SVWZ+Februar\r\n" +
                ":62F:C260228EUR1170,00\r\n"
        val s = Mt940StatementParser.parse((String(sta) + second).toByteArray())

        assertEquals(3, s.transactions.size)
        assertEquals("14", s.statementNumber)
        assertEquals(0, BigDecimal("1000.00").compareTo(s.openingBalance))
        assertEquals(0, BigDecimal("1170.00").compareTo(s.closingBalance))
    }

    @Test
    fun `an undecodable 86 field leaves the counterparty null and keeps the text`() {
        val plain =
            rewritten(
                ":86:166?00SEPA-GUTSCHRIFT?20EREF+E2E-IN-1?21SVWZ+Rechnung 2026-0042" +
                    "?30COBADEFFXXX?31DE89370400440532013000?32Kunde?33 Schmidt",
                ":86:DAUERAUFTRAG MIETE JANUAR",
            )
        val entry = Mt940StatementParser.parse(plain).transactions.first()
        assertEquals(null, entry.counterpartyName)
        assertEquals("DAUERAUFTRAG MIETE JANUAR", entry.remittanceUnstructured)
    }
}
