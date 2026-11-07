package org.example.project.einvoice.detect

import org.example.project.einvoice.xml.XmlNamespaces
import org.example.project.securexml.SecureXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZugferdProfileDetectorTest {
    private fun cii(profileUrn: String?) =
        SecureXml.parse(
            """
            <rsm:CrossIndustryInvoice xmlns:rsm="${XmlNamespaces.RSM}" xmlns:ram="${XmlNamespaces.RAM}">
              <rsm:ExchangedDocumentContext>
                ${
                if (profileUrn == null) {
                    ""
                } else {
                    "<ram:GuidelineSpecifiedDocumentContextParameter><ram:ID>$profileUrn</ram:ID></ram:GuidelineSpecifiedDocumentContextParameter>"
                }
            }
              </rsm:ExchangedDocumentContext>
            </rsm:CrossIndustryInvoice>
            """.trimIndent().toByteArray(),
        )

    private fun corpusBytes(resourceName: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/corpus/$resourceName")) {
            "corpus fixture missing: $resourceName"
        }.readBytes()

    // --- ZugferdProfile.fromUrn -------------------------------------------------------------------

    @Test
    fun `MINIMUM is detected from its urn`() {
        assertEquals(ZugferdProfile.MINIMUM, ZugferdProfile.fromUrn("urn:factur-x.eu:1p0:minimum"))
    }

    /**
     * The two booking-only profiles. If either stops being recognised — a typo'd URN makes
     * [ZugferdProfile.fromUrn] return null — the conformance gate never fires and a booking aid is
     * silently accepted as an e-invoice, which is the exact failure the gate exists to prevent.
     */
    @Test
    fun `MINIMUM and BASIC_WL are not EN16931 conformant`() {
        assertFalse(ZugferdProfile.MINIMUM.isEn16931Conformant)
        assertFalse(ZugferdProfile.BASIC_WL.isEn16931Conformant)
    }

    @Test
    fun `BASIC EN16931 and EXTENDED are conformant`() {
        assertTrue(ZugferdProfile.BASIC.isEn16931Conformant)
        assertTrue(ZugferdProfile.EN16931.isEn16931Conformant)
        assertTrue(ZugferdProfile.EXTENDED.isEn16931Conformant)
    }

    /**
     * `#compliant#` (BASIC, a CIUS) and `#conformant#` (EXTENDED, an extension) are different words
     * in different URNs and trivial to transpose. Swapping them must not yield the profile you meant.
     *
     * They degrade to [ZugferdProfile.EN16931] rather than to null, because the prefix fallback sees
     * a URN that still starts with the EN 16931 base — which is the intended treatment of an unknown
     * national extension. That is safe here: both transposed forms are EN 16931 derivatives, so the
     * conformance gate still passes them, and the only loss is the finer profile label.
     */
    @Test
    fun `transposing compliant and conformant loses the specific profile`() {
        assertEquals(
            ZugferdProfile.EN16931,
            ZugferdProfile.fromUrn("urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:basic"),
        )
        assertEquals(
            ZugferdProfile.EN16931,
            ZugferdProfile.fromUrn("urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:extended"),
        )
    }

    /**
     * ZUGFeRD 2.0's `urn:zugferd.de:2p0:*` spelling was replaced in 2.1 and is deliberately not
     * declared. Pinned so that a future decision to support 2.0 is a conscious one.
     */
    @Test
    fun `the superseded ZUGFeRD 2_0 basicwl urn is not recognised`() {
        assertNull(ZugferdProfile.fromUrn("urn:zugferd.de:2p0:basicwl"))
    }

    @Test
    fun `an unrecognised urn yields null`() {
        assertNull(ZugferdProfile.fromUrn("urn:example:not-a-profile"))
    }

    /**
     * The case a real KoSIT release upgrade will produce: a future xrechnung suffix version. It must
     * still resolve to XRECHNUNG via the longest-prefix fallback, not fall through to plain EN16931.
     */
    @Test
    fun `a future xrechnung version suffix still resolves to XRECHNUNG`() {
        val futureUrn = "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.1"
        assertEquals(ZugferdProfile.XRECHNUNG, ZugferdProfile.fromUrn(futureUrn))
    }

    // --- ZugferdProfileDetector.detect ------------------------------------------------------------

    @Test
    fun `detect reads MINIMUM from a synthetic document`() {
        assertEquals(ZugferdProfile.MINIMUM, ZugferdProfileDetector.detect(cii("urn:factur-x.eu:1p0:minimum")))
    }

    /** Absence of the profile element must not be confused with the MINIMUM profile. */
    @Test
    fun `detect returns null when no profile urn is present`() {
        assertNull(ZugferdProfileDetector.detect(cii(null)))
    }

    @Test
    fun `detect resolves the real xrechnung-cii corpus fixture to XRECHNUNG`() {
        val document = SecureXml.parse(corpusBytes("xrechnung-cii.xml"))
        assertEquals(ZugferdProfile.XRECHNUNG, ZugferdProfileDetector.detect(document))
    }

    @Test
    fun `detect resolves the zugferd-minimum pdf payload to MINIMUM`() {
        val payload = checkNotNull(EInvoicePayloadLocator.locate(corpusBytes("zugferd-minimum.pdf"), "application/pdf"))
        val document = SecureXml.parse(payload)
        assertEquals(ZugferdProfile.MINIMUM, ZugferdProfileDetector.detect(document))
    }

    @Test
    fun `detect resolves the zugferd-sample pdf payload to EN16931`() {
        val payload = checkNotNull(EInvoicePayloadLocator.locate(corpusBytes("zugferd-sample.pdf"), "application/pdf"))
        val document = SecureXml.parse(payload)
        assertEquals(ZugferdProfile.EN16931, ZugferdProfileDetector.detect(document))
    }

    /**
     * The three profiles that had no fixture. These files are **ours**, so they cannot prove the URN
     * is the correct one — the external research recorded on [ZugferdProfile] does that. They are a
     * regression guard: a later edit to the enum cannot silently stop these profiles from resolving.
     */
    @Test
    fun `detect resolves the hand-authored BASIC_WL fixture`() {
        assertEquals(ZugferdProfile.BASIC_WL, detectFromCorpus("profile-basicwl.xml"))
    }

    @Test
    fun `detect resolves the hand-authored BASIC fixture`() {
        assertEquals(ZugferdProfile.BASIC, detectFromCorpus("profile-basic.xml"))
    }

    @Test
    fun `detect resolves the hand-authored EXTENDED fixture`() {
        assertEquals(ZugferdProfile.EXTENDED, detectFromCorpus("profile-extended.xml"))
    }

    /** Every declared profile must be reachable from a corpus file, not only from a string literal. */
    @Test
    fun `every declared profile is covered by a corpus fixture`() {
        val detected =
            setOf(
                ZugferdProfileDetector.detect(SecureXml.parse(corpusBytes("xrechnung-cii.xml"))),
                detectFromPdf("zugferd-minimum.pdf"),
                detectFromPdf("zugferd-sample.pdf"),
                detectFromCorpus("profile-basicwl.xml"),
                detectFromCorpus("profile-basic.xml"),
                detectFromCorpus("profile-extended.xml"),
            )
        assertEquals(ZugferdProfile.entries.toSet(), detected)
    }

    private fun detectFromCorpus(resourceName: String): ZugferdProfile? = ZugferdProfileDetector.detect(SecureXml.parse(corpusBytes(resourceName)))

    private fun detectFromPdf(resourceName: String): ZugferdProfile? {
        val payload = checkNotNull(EInvoicePayloadLocator.locate(corpusBytes(resourceName), "application/pdf"))
        return ZugferdProfileDetector.detect(SecureXml.parse(payload))
    }
}
