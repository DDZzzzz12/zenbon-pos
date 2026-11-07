package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertFailsWith

class VatIdFormatTest {
    @Test
    fun nullAndBlankPass_theFieldIsOptional() {
        VatIdFormat.requireValid(null)
        VatIdFormat.requireValid("")
        VatIdFormat.requireValid("   ")
    }

    @Test
    fun validGermanVatIdPasses() {
        VatIdFormat.requireValid("DE123456789")
        VatIdFormat.requireValid("de123456789")
    }

    @Test
    fun validNonGermanVatIdsPass() {
        VatIdFormat.requireValid("ATU12345678")
        VatIdFormat.requireValid("FR12345678901")
        VatIdFormat.requireValid("NL999999999B01")
    }

    @Test
    fun germanSteuernummerIsRejected() {
        // The STAM-8 defect: a Steuernummer typed into the VAT-ID field.
        assertFailsWith<IllegalArgumentException> { VatIdFormat.requireValid("12/345/67890") }
        assertFailsWith<IllegalArgumentException> { VatIdFormat.requireValid("1234567890") }
        Unit
    }

    @Test
    fun deWithWrongDigitCountIsRejected() {
        assertFailsWith<IllegalArgumentException> { VatIdFormat.requireValid("DE12345678") }
        assertFailsWith<IllegalArgumentException> { VatIdFormat.requireValid("DE1234567890") }
        Unit
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<IllegalArgumentException> { VatIdFormat.requireValid("!!!") }
        assertFailsWith<IllegalArgumentException> { VatIdFormat.requireValid("D1") }
        Unit
    }
}
