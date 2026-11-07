package org.example.project.banking

import org.example.project.banking.model.BankStatementFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DefaultBankStatementParserTest {
    private val parser = DefaultBankStatementParser()

    private val camt =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
          <BkToCstmrStmt><Stmt>
            <Id>S-1</Id>
            <Acct><Id><IBAN>DE02120300000000202051</IBAN></Id><Ccy>EUR</Ccy></Acct>
            <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">100.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
            <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">150.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
            <Ntry><Amt Ccy="EUR">50.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><ValDt><Dt>2026-01-10</Dt></ValDt></Ntry>
          </Stmt></BkToCstmrStmt>
        </Document>
        """.trimIndent().toByteArray()

    @Test
    fun `parses a camt 053 upload`() {
        val result = parser.parse(camt, "application/xml")
        val success = assertIs<BankStatementParseResult.Success>(result)
        assertEquals(BankStatementFormat.CAMT053, success.statement.format)
        assertEquals(1, success.statement.transactions.size)
    }

    @Test
    fun `a PDF carries no statement and is not an error`() {
        assertEquals(BankStatementParseResult.NoStatementFound, parser.parse("%PDF-1.7\n".toByteArray(), "application/pdf"))
    }

    @Test
    fun `an XRechnung is not claimed as a statement`() {
        val ubl = """<?xml version="1.0"?><Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>"""
        assertEquals(BankStatementParseResult.NoStatementFound, parser.parse(ubl.toByteArray(), "application/xml"))
    }

    @Test
    fun `a statement whose balances do not reconcile is refused`() {
        val broken = String(camt).replace("<Amt Ccy=\"EUR\">50.00</Amt>", "<Amt Ccy=\"EUR\">49.00</Amt>").toByteArray()
        val failure = assertIs<BankStatementParseResult.Failure>(parser.parse(broken, "application/xml"))
        assertTrue("closing balance" in failure.reason, "reason should name the discrepancy: ${failure.reason}")
        assertTrue(failure.balancesDoNotReconcile, "the caller must be able to tell the operator it is a balance error")
    }

    @Test
    fun `malformed XML that claims to be camt is a failure, not a crash`() {
        val truncated = """<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt>"""
        val failure = assertIs<BankStatementParseResult.Failure>(parser.parse(truncated.toByteArray(), "application/xml"))
        assertFalse(failure.balancesDoNotReconcile, "an unreadable file is not a balance error")
    }

    @Test
    fun `a hostile doctype is refused rather than resolved`() {
        val xxe =
            """<?xml version="1.0"?><!DOCTYPE f [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
                """<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt>&x;</BkToCstmrStmt></Document>"""
        assertIs<BankStatementParseResult.Failure>(parser.parse(xxe.toByteArray(), "application/xml"))
    }

    private val mt940 =
        (
            ":20:STARTUMS\r\n:25:DE02120300000000202051\r\n:28C:14/1\r\n" +
                ":60F:C260101EUR1000,00\r\n" +
                ":61:2601100110C50,00N051NONREF\r\n:86:166?20SVWZ+Test\r\n" +
                ":62F:C260131EUR1050,00\r\n"
        ).toByteArray()

    @Test
    fun `parses an MT940 upload`() {
        val success = assertIs<BankStatementParseResult.Success>(parser.parse(mt940, "application/x-swift-mt940"))
        assertEquals(BankStatementFormat.MT940, success.statement.format)
        assertEquals(1, success.statement.transactions.size)
    }

    @Test
    fun `an MT940 whose balances do not reconcile is refused`() {
        val original = String(mt940)
        val broken = original.replace(":62F:C260131EUR1050,00", ":62F:C260131EUR1051,00")
        assertNotEquals(original, broken, "the fixture rewrite matched nothing")
        val failure = assertIs<BankStatementParseResult.Failure>(parser.parse(broken.toByteArray(), "application/x-swift-mt940"))
        assertTrue(failure.balancesDoNotReconcile)
    }
}
