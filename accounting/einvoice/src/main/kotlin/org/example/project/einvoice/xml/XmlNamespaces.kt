package org.example.project.einvoice.xml

import javax.xml.namespace.NamespaceContext

/**
 * Namespace URIs for the two EN 16931 syntaxes.
 *
 * Everything binds by URI. A sender is free to use any prefix, so matching `ram:` or `cbc:` as text
 * would reject perfectly conformant files.
 */
object XmlNamespaces {
    const val RSM = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
    const val RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
    const val UDT = "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100"

    const val UBL_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
    const val UBL_CREDIT_NOTE = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
    const val CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
    const val CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"

    fun ciiContext(): NamespaceContext = fixedContext(mapOf("rsm" to RSM, "ram" to RAM, "udt" to UDT))

    fun ublContext(): NamespaceContext = fixedContext(mapOf("inv" to UBL_INVOICE, "cn" to UBL_CREDIT_NOTE, "cbc" to CBC, "cac" to CAC))

    private fun fixedContext(byPrefix: Map<String, String>): NamespaceContext =
        object : NamespaceContext {
            override fun getNamespaceURI(prefix: String?): String = byPrefix[prefix] ?: ""

            override fun getPrefix(namespaceURI: String?): String? = byPrefix.entries.firstOrNull { it.value == namespaceURI }?.key

            override fun getPrefixes(namespaceURI: String?): MutableIterator<String> =
                byPrefix
                    .filterValues { it == namespaceURI }
                    .keys
                    .toMutableList()
                    .iterator()
        }
}
