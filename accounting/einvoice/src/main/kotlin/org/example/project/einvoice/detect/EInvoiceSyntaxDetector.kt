package org.example.project.einvoice.detect

import org.example.project.einvoice.xml.XmlNamespaces
import org.w3c.dom.Document

/**
 * Identifies the syntax from the root element's namespace. Returns null when the document is simply
 * not an e-invoice — an arbitrary XML attachment is a normal thing to encounter, not a failure.
 */
object EInvoiceSyntaxDetector {
    fun detect(document: Document): EInvoiceSyntax? =
        when (document.documentElement?.namespaceURI) {
            XmlNamespaces.RSM -> EInvoiceSyntax.CII
            XmlNamespaces.UBL_INVOICE, XmlNamespaces.UBL_CREDIT_NOTE -> EInvoiceSyntax.UBL
            else -> null
        }
}
