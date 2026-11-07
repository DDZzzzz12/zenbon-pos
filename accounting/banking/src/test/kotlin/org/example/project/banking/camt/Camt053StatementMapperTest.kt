package org.example.project.banking.camt

import kotlinx.datetime.LocalDate
import org.example.project.banking.model.BankStatementFormat
import org.example.project.banking.model.CreditDebit
import org.example.project.securexml.SecureXml
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Camt053StatementMapperTest {
    /**
     * A minimal but realistic camt.053.001.02 statement: one credit from a debtor, one debit to a
     * creditor. Both directions are present because the counterparty side depends on `CdtDbtInd`,
     * and a fixture with one direction cannot catch a mapper that picks the wrong side.
     */
    private val statementXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
          <BkToCstmrStmt>
            <Stmt>
              <Id>STMT-2026-014</Id>
              <LglSeqNb>14</LglSeqNb>
              <CreDtTm>2026-01-31T23:00:00</CreDtTm>
              <FrToDt><FrDtTm>2026-01-01T00:00:00</FrDtTm><ToDtTm>2026-01-31T23:59:59</ToDtTm></FrToDt>
              <Acct>
                <Id><IBAN>DE02120300000000202051</IBAN></Id>
                <Ccy>EUR</Ccy>
                <Svcr><FinInstnId><BIC>BYLADEM1001</BIC><Nm>Musterbank</Nm></FinInstnId></Svcr>
              </Acct>
              <Bal>
                <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
                <Amt Ccy="EUR">1000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>
                <Dt><Dt>2026-01-01</Dt></Dt>
              </Bal>
              <Bal>
                <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
                <Amt Ccy="EUR">1150.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>
                <Dt><Dt>2026-01-31</Dt></Dt>
              </Bal>
              <Ntry>
                <Amt Ccy="EUR">200.00</Amt>
                <CdtDbtInd>CRDT</CdtDbtInd>
                <Sts>BOOK</Sts>
                <BookgDt><Dt>2026-01-10</Dt></BookgDt>
                <ValDt><Dt>2026-01-10</Dt></ValDt>
                <AcctSvcrRef>REF-IN-1</AcctSvcrRef>
                <BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>RCDT</Cd><SubFmlyCd>ESCT</SubFmlyCd></Fmly></Domn></BkTxCd>
                <NtryDtls><TxDtls>
                  <Refs><EndToEndId>E2E-IN-1</EndToEndId></Refs>
                  <RltdPties>
                    <Dbtr><Nm>Kunde Schmidt</Nm></Dbtr>
                    <DbtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></DbtrAcct>
                    <Cdtr><Nm>Wir Selbst GmbH</Nm></Cdtr>
                  </RltdPties>
                  <RltdAgts><DbtrAgt><FinInstnId><BIC>COBADEFFXXX</BIC></FinInstnId></DbtrAgt></RltdAgts>
                  <RmtInf><Ustrd>Rechnung</Ustrd><Ustrd> 2026-0042</Ustrd></RmtInf>
                </TxDtls></NtryDtls>
              </Ntry>
              <Ntry>
                <Amt Ccy="EUR">50.00</Amt>
                <CdtDbtInd>DBIT</CdtDbtInd>
                <Sts>BOOK</Sts>
                <RvslInd>false</RvslInd>
                <BookgDt><Dt>2026-01-20</Dt></BookgDt>
                <ValDt><Dt>2026-01-21</Dt></ValDt>
                <BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>ICDT</Cd><SubFmlyCd>ESCT</SubFmlyCd></Fmly></Domn></BkTxCd>
                <NtryDtls><TxDtls>
                  <Refs><MndtId>MND-77</MndtId></Refs>
                  <RltdPties>
                    <Dbtr><Nm>Wir Selbst GmbH</Nm></Dbtr>
                    <Cdtr><Nm>Lieferant Meier</Nm></Cdtr>
                    <CdtrAcct><Id><IBAN>DE12500105170648489890</IBAN></Id></CdtrAcct>
                  </RltdPties>
                  <RltdAgts><CdtrAgt><FinInstnId><BIC>INGDDEFFXXX</BIC></FinInstnId></CdtrAgt></RltdAgts>
                  <RmtInf><Strd><CdtrRefInf><Ref>RF18539007547034</Ref></CdtrRefInf></Strd></RmtInf>
                </TxDtls></NtryDtls>
              </Ntry>
            </Stmt>
          </BkToCstmrStmt>
        </Document>
        """.trimIndent().toByteArray()

    private fun mapped() = Camt053StatementMapper.map(SecureXml.parse(statementXml))

    @Test
    fun `maps the statement header`() {
        val s = mapped()
        assertEquals(BankStatementFormat.CAMT053, s.format)
        assertEquals("STMT-2026-014", s.statementNumber)
        assertEquals("14", s.legalSequenceNumber)
        assertEquals(LocalDate(2026, 1, 31), s.statementDate)
        assertEquals(LocalDate(2026, 1, 1), s.periodStart)
        assertEquals(LocalDate(2026, 1, 31), s.periodEnd)
        assertEquals("DE02120300000000202051", s.accountIban)
        assertEquals("BYLADEM1001", s.accountBic)
        assertEquals("Musterbank", s.bankName)
        assertEquals("EUR", s.currency)
    }

    @Test
    fun `maps opening and closing balances`() {
        val s = mapped()
        assertEquals(0, BigDecimal("1000.00").compareTo(s.openingBalance))
        assertEquals(0, BigDecimal("1150.00").compareTo(s.closingBalance))
    }

    @Test
    fun `a credit entry takes its counterparty from the debtor side`() {
        val entry = mapped().transactions.first()
        assertEquals(CreditDebit.CREDIT, entry.creditDebit)
        assertEquals(0, BigDecimal("200.00").compareTo(entry.amount))
        assertEquals("Kunde Schmidt", entry.counterpartyName)
        assertEquals("DE89370400440532013000", entry.counterpartyIban)
        assertEquals("COBADEFFXXX", entry.counterpartyBic)
        assertEquals("E2E-IN-1", entry.endToEndId)
        assertEquals("REF-IN-1", entry.accountServicerReference)
        assertEquals(LocalDate(2026, 1, 10), entry.bookingDate)
        assertEquals(LocalDate(2026, 1, 10), entry.valueDate)
    }

    @Test
    fun `a debit entry takes its counterparty from the creditor side`() {
        val entry = mapped().transactions[1]
        assertEquals(CreditDebit.DEBIT, entry.creditDebit)
        assertEquals("Lieferant Meier", entry.counterpartyName)
        assertEquals("DE12500105170648489890", entry.counterpartyIban)
        assertEquals("INGDDEFFXXX", entry.counterpartyBic)
        assertEquals("MND-77", entry.mandateId)
        assertEquals("RF18539007547034", entry.remittanceStructuredRef)
        assertEquals(LocalDate(2026, 1, 21), entry.valueDate)
    }

    @Test
    fun `multiple unstructured remittance elements are concatenated in order`() {
        assertEquals("Rechnung 2026-0042", mapped().transactions.first().remittanceUnstructured)
    }

    @Test
    fun `entry amounts are positive and the bank transaction code is mapped`() {
        val entry = mapped().transactions[1]
        assertTrue(entry.amount.signum() > 0, "amount must be positive; direction lives in creditDebit")
        assertEquals("PMNT", entry.bankTxDomain)
        assertEquals("ICDT", entry.bankTxFamily)
        assertEquals("ESCT", entry.bankTxSubFamily)
        assertEquals("BOOK", entry.status)
        assertEquals(false, entry.reversalIndicator)
    }

    @Test
    fun `the raw entry element is retained`() {
        val raw = mapped().transactions.first().rawEntry
        assertTrue(raw != null && "REF-IN-1" in raw, "rawEntry should hold the serialised Ntry: $raw")
    }

    @Test
    fun `a debit closing balance is negative`() {
        val overdrawn =
            String(statementXml)
                .replace(
                    "<Amt Ccy=\"EUR\">1150.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>",
                    "<Amt Ccy=\"EUR\">1150.00</Amt><CdtDbtInd>DBIT</CdtDbtInd>",
                ).toByteArray()
        val s = Camt053StatementMapper.map(SecureXml.parse(overdrawn))
        assertEquals(0, BigDecimal("-1150.00").compareTo(s.closingBalance))
    }

    @Test
    fun `an opening balance written as PRCD is accepted`() {
        val prcd = String(statementXml).replace("<Cd>OPBD</Cd>", "<Cd>PRCD</Cd>").toByteArray()
        val s = Camt053StatementMapper.map(SecureXml.parse(prcd))
        assertEquals(0, BigDecimal("1000.00").compareTo(s.openingBalance))
    }

    @Test
    fun `a missing booking date falls back to the value date`() {
        val noBooking =
            String(statementXml)
                .replace("<BookgDt><Dt>2026-01-10</Dt></BookgDt>", "")
                .toByteArray()
        val s = Camt053StatementMapper.map(SecureXml.parse(noBooking))
        assertEquals(LocalDate(2026, 1, 10), s.transactions.first().bookingDate)
    }

    @Test
    fun `a DtTm date is accepted wherever a Dt is`() {
        val dtTm =
            String(statementXml)
                .replace("<ValDt><Dt>2026-01-21</Dt></ValDt>", "<ValDt><DtTm>2026-01-21T08:30:00</DtTm></ValDt>")
                .toByteArray()
        val s = Camt053StatementMapper.map(SecureXml.parse(dtTm))
        assertEquals(LocalDate(2026, 1, 21), s.transactions[1].valueDate)
    }

    @Test
    fun `a document that is not camt 053 is refused`() {
        val wrong =
            """<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.052.001.02"><BkToCstmrAcctRpt/></Document>"""
                .toByteArray()
        val failure = runCatching { Camt053StatementMapper.map(SecureXml.parse(wrong)) }.exceptionOrNull()
        assertTrue(failure is CamtMappingException, "expected CamtMappingException, got $failure")
    }

    @Test
    fun `an entry with several transaction details keeps no counterparty`() {
        val batched =
            String(statementXml)
                .replace(
                    "<RmtInf><Strd><CdtrRefInf><Ref>RF18539007547034</Ref></CdtrRefInf></Strd></RmtInf>\n" +
                        "        </TxDtls></NtryDtls>",
                    "<RmtInf><Strd><CdtrRefInf><Ref>RF18539007547034</Ref></CdtrRefInf></Strd></RmtInf>\n" +
                        "        </TxDtls><TxDtls><Refs><MndtId>MND-78</MndtId></Refs></TxDtls></NtryDtls>",
                ).toByteArray()
        val entry = Camt053StatementMapper.map(SecureXml.parse(batched)).transactions[1]
        assertNull(entry.counterpartyName, "a batch has no single counterparty and must not invent one")
        assertNull(entry.mandateId)
        assertEquals(0, BigDecimal("50.00").compareTo(entry.amount), "the entry amount is still the booked amount")
    }

    @Test
    fun `a file with one Stmt per day is merged into one statement`() {
        val secondStmt =
            """
            <Stmt>
              <Id>STMT-2026-015</Id>
              <CreDtTm>2026-02-01T23:00:00</CreDtTm>
              <FrToDt><FrDtTm>2026-02-01T00:00:00</FrDtTm><ToDtTm>2026-02-01T23:59:59</ToDtTm></FrToDt>
              <Acct><Id><IBAN>DE02120300000000202051</IBAN></Id><Ccy>EUR</Ccy></Acct>
              <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">1150.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
              <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">1170.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
              <Ntry>
                <Amt Ccy="EUR">20.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>
                <ValDt><Dt>2026-02-01</Dt></ValDt>
              </Ntry>
            </Stmt>
            """.trimIndent()
        val twoDays = String(statementXml).replace("</Stmt>\n  </BkToCstmrStmt>", "</Stmt>\n$secondStmt\n  </BkToCstmrStmt>")
        assertTrue(twoDays != String(statementXml), "the fixture rewrite must actually match; otherwise this test asserts nothing")
        val s = Camt053StatementMapper.map(SecureXml.parse(twoDays.toByteArray()))

        assertEquals(3, s.transactions.size, "all entries from both statements must survive")
        assertEquals("STMT-2026-014", s.statementNumber, "the first statement's number is kept")
        assertEquals(LocalDate(2026, 1, 1), s.periodStart)
        assertEquals(LocalDate(2026, 2, 1), s.periodEnd)
        assertEquals(0, BigDecimal("1000.00").compareTo(s.openingBalance))
        assertEquals(0, BigDecimal("1170.00").compareTo(s.closingBalance))
    }
}
