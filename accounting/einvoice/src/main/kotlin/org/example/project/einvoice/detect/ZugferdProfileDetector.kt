package org.example.project.einvoice.detect

import org.example.project.einvoice.xml.XmlNamespaces
import org.example.project.securexml.XPathReader
import org.w3c.dom.Document

/**
 * Reads the ZUGFeRD/Factur-X profile from the CII document context.
 *
 * Returns null when no profile URN is present — a bare XRechnung has none, and absence must not be
 * confused with the MINIMUM profile.
 */
object ZugferdProfileDetector {
    private val read = XPathReader(XmlNamespaces.ciiContext())

    private const val PROFILE =
        "/rsm:CrossIndustryInvoice/rsm:ExchangedDocumentContext" +
            "/ram:GuidelineSpecifiedDocumentContextParameter/ram:ID"

    fun detect(document: Document): ZugferdProfile? = read.string(document, PROFILE)?.let(ZugferdProfile::fromUrn)
}
