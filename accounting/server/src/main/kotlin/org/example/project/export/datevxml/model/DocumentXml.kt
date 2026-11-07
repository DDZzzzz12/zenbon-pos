/**
 * XML model classes for DATEV document.xml manifests.
 *
 * These models map to the DATEV XSD at namespace
 * `http://xml.datev.de/bedi/tps/document/v06.0` and are written
 * via [javax.xml.stream.XMLStreamWriter] in the generator classes.
 *
 * Extension elements use `xsi:type` discrimination (cashLedger,
 * accountsPayableLedger, File). Each extension type is a separate
 * data class used by the generators to write the correct XML output.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.datevxml.model

/**
 * Container object for DATEV document.xml model classes.
 *
 * These are plain data holders — XML generation is handled by
 * XMLStreamWriter in the generator classes.
 */
object DocumentXml {
    /** DATEV document namespace URI */
    const val NS_DOC = "http://xml.datev.de/bedi/tps/document/v06.0"

    /** XSI namespace URI for type declarations */
    const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"

    /** Schema location value for document.xml */
    const val SCHEMA_LOCATION = "http://xml.datev.de/bedi/tps/document/v06.0 Document_v060.xsd"

    /**
     * Root element `<archive>` of the document.xml manifest.
     *
     * @property version Schema version (fixed "6.0")
     * @property generatingSystem Software identifier
     * @property header Document header with timestamp and metadata
     * @property content Content block containing document entries
     */
    data class ArchiveXml(
        val version: String = "6.0",
        val generatingSystem: String,
        val header: HeaderXml,
        val content: ContentXml,
    )

    /**
     * The `<header>` element with export metadata.
     *
     * @property date ISO timestamp of generation (YYYY-MM-DD'T'HH:mm:ss)
     * @property description Human-readable export description
     * @property consultantNumber Optional DATEV Beraternummer (max 7 chars)
     * @property clientNumber Optional DATEV Mandantennummer (max 5 chars)
     * @property clientName Shop/business name (max 36 chars)
     */
    data class HeaderXml(
        val date: String,
        val description: String? = null,
        val consultantNumber: String? = null,
        val clientNumber: String? = null,
        val clientName: String,
    )

    /**
     * The `<content>` element wrapping all document entries.
     *
     * Per XSD, `<content>` directly contains `<document>` elements (no wrapper).
     *
     * @property documents List of document entries (one per ledger file)
     */
    data class ContentXml(
        val documents: List<DocumentEntryXml>,
    )

    /**
     * A `<document>` element representing one ledger file and its extensions.
     *
     * @property extensions List of extension elements (ledger reference + optional PDFs)
     */
    data class DocumentEntryXml(
        val extensions: List<ExtensionXml>,
    )

    /**
     * Base interface for `<extension>` elements within a document.
     *
     * Each implementation represents a different `xsi:type` value.
     */
    sealed interface ExtensionXml

    /**
     * `<extension xsi:type="cashLedger">` referencing a cash ledger XML file.
     *
     * Per XSD, `<property>` elements are direct children (no `<properties>` wrapper).
     *
     * @property datafile The ledger XML filename in the ZIP
     * @property properties Property elements with period and account info
     */
    data class CashLedgerExtensionXml(
        val datafile: String,
        val properties: List<PropertyXml> = emptyList(),
    ) : ExtensionXml

    /**
     * `<extension xsi:type="accountsPayableLedger">` referencing an AP ledger XML file.
     *
     * @property datafile The ledger XML filename in the ZIP
     * @property properties Property elements with period and ledger name info
     */
    data class ApLedgerExtensionXml(
        val datafile: String,
        val properties: List<PropertyXml> = emptyList(),
    ) : ExtensionXml

    /**
     * `<extension xsi:type="File">` referencing a linked PDF document.
     *
     * @property name The PDF filename in the ZIP
     */
    data class FileExtensionXml(
        val name: String,
    ) : ExtensionXml

    /**
     * A `<property>` element carrying key-value metadata.
     *
     * @property key The property key identifier ("1"=period, "2"=konto, "3"=name)
     * @property value The property value (period "YYYY-MM", account number, or name)
     */
    data class PropertyXml(
        val key: String,
        val value: String,
    )
}
