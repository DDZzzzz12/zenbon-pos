package org.example.project.banking.detect

import org.example.project.banking.model.BankStatementFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BankStatementFormatDetectorTest {
    @Test
    fun `detects camt 053 from the root namespace`() {
        val xml =
            """<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt/></Document>"""
        assertEquals(BankStatementFormat.CAMT053, BankStatementFormatDetector.detect(xml.toByteArray(), "application/xml"))
    }

    @Test
    fun `does not claim an XRechnung or any other XML`() {
        val ubl =
            """<?xml version="1.0"?><Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>"""
        assertNull(BankStatementFormatDetector.detect(ubl.toByteArray(), "application/xml"))
    }

    @Test
    fun `detects MT940 from a leading transaction reference tag`() {
        val sta = ":20:STARTUMS\r\n:25:12030000/0000202051\r\n:28C:14/1\r\n"
        assertEquals(BankStatementFormat.MT940, BankStatementFormatDetector.detect(sta.toByteArray(), "text/plain"))
    }

    @Test
    fun `detects MT940 behind SWIFT block framing`() {
        val sta = "{1:F01BYLADEM1AXXX0000000000}{2:O9401200}{4:\r\n:20:STARTUMS\r\n:25:12030000/0000202051\r\n-}"
        assertEquals(BankStatementFormat.MT940, BankStatementFormatDetector.detect(sta.toByteArray(), "text/plain"))
    }

    @Test
    fun `does not claim arbitrary text`() {
        assertNull(BankStatementFormatDetector.detect("Sehr geehrte Damen und Herren,\n".toByteArray(), "text/plain"))
    }

    @Test
    fun `does not claim a PDF`() {
        assertNull(BankStatementFormatDetector.detect("%PDF-1.7\n%????\n".toByteArray(), "application/pdf"))
    }

    @Test
    fun `does not claim an empty file`() {
        assertNull(BankStatementFormatDetector.detect(ByteArray(0), "application/octet-stream"))
    }
}
