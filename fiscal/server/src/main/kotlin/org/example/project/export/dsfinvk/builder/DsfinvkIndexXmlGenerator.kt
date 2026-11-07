/**
 * XML generator for the DSFinV-K index.xml (GDPdU DataSet master descriptor).
 *
 * Emits a GDPdU `DataSet` document: the XML declaration, the
 * `<!DOCTYPE DataSet SYSTEM "gdpdu-01-09-2004.dtd">` line, and a `<Media>` block
 * listing all 20 CSV `<Table>` descriptors with their ordered `<VariableColumn>`
 * schema. Auditor software parses every CSV through this descriptor, so the
 * structure must match the DSFinV-K reference exactly.
 *
 * A manual/streamed writer is used so it can emit the schema-faithful GDPdU
 * DataSet descriptor including the DOCTYPE line. Per-column `<Description>` text
 * is intentionally omitted — it is optional in the GDPdU DTD (`Description?`) and
 * not needed for CSV parsing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.builder

import org.example.project.export.dsfinvk.model.DsfinvkIndexXml
import org.example.project.export.dsfinvk.model.StammTseCertificate

/**
 * Generator for the DSFinV-K index.xml GDPdU DataSet descriptor.
 */
object DsfinvkIndexXmlGenerator {
    /**
     * Generates the GDPdU `DataSet` index.xml content.
     *
     * The shop/period/statistics arguments are retained for call-site
     * compatibility but are not part of the descriptor — that data belongs in
     * `cashpointclosing.csv`, not `index.xml`.
     *
     * @param shopName Name of the shop/business (unused by the descriptor)
     * @param startDate Export period start date (unused by the descriptor)
     * @param endDate Export period end date (unused by the descriptor)
     * @param transactionCount Total number of transactions (unused by the descriptor)
     * @param zBonCount Total number of Z-reports (unused by the descriptor)
     * @param certificateFieldCount Number of `TSE_ZERTIFIKAT_*` fields the Stamm_TSE table must
     *   declare — MUST equal what tse.csv emits (both derive it from [StammTseCertificate.fieldCount]
     *   over the same Stamm_TSE rows, audit R2). Defaults to the baseline I/II schema.
     * @return XML content as ByteArray (UTF-8)
     */
    @Suppress("UNUSED_PARAMETER")
    fun generateIndexXml(
        shopName: String,
        startDate: String,
        endDate: String,
        transactionCount: Int,
        zBonCount: Int,
        certificateFieldCount: Int = StammTseCertificate.MIN_FIELDS,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        sb.append("<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">\r\n")
        sb.append("<DataSet>\r\n")
        sb.append("  <Version>1.0</Version>\r\n")
        sb.append("  <DataSupplier>\r\n    <Name />\r\n    <Location />\r\n")
        sb.append("    <Comment>Datentraegerueberlassung nach GDPdU vom 12.11.2010</Comment>\r\n")
        sb.append("  </DataSupplier>\r\n")
        sb.append("  <Media>\r\n    <Name>CD Nummer 1</Name>\r\n")
        DsfinvkIndexXml.tables(certificateFieldCount).forEach { table ->
            sb.append("    <Table>\r\n")
            sb.append("      <URL>${table.url}</URL>\r\n")
            sb.append("      <Name>${table.name}</Name>\r\n")
            sb.append("      <Description>${table.url}</Description>\r\n")
            sb.append("      <UTF8 />\r\n")
            sb.append("      <DecimalSymbol>,</DecimalSymbol>\r\n")
            sb.append("      <DigitGroupingSymbol>.</DigitGroupingSymbol>\r\n")
            sb.append("      <Range>\r\n        <From>2</From>\r\n      </Range>\r\n")
            sb.append("      <VariableLength>\r\n")
            sb.append("        <ColumnDelimiter>;</ColumnDelimiter>\r\n")
            sb.append("        <RecordDelimiter>&#xD;&#xA;</RecordDelimiter>\r\n")
            sb.append("        <TextEncapsulator>\"</TextEncapsulator>\r\n")
            table.columns.forEach { col ->
                sb.append("        <VariableColumn>\r\n")
                sb.append("          <Name>${col.name}</Name>\r\n")
                when (val t = col.type) {
                    is DsfinvkIndexXml.Alpha -> {
                        sb.append("          <AlphaNumeric />\r\n")
                        sb.append("          <MaxLength>${t.maxLength}</MaxLength>\r\n")
                    }
                    is DsfinvkIndexXml.Numeric -> {
                        if (t.accuracy != null) {
                            sb.append("          <Numeric>\r\n            <Accuracy>${t.accuracy}</Accuracy>\r\n          </Numeric>\r\n")
                        } else {
                            sb.append("          <Numeric />\r\n")
                        }
                    }
                }
                sb.append("        </VariableColumn>\r\n")
            }
            sb.append("      </VariableLength>\r\n    </Table>\r\n")
        }
        sb.append("  </Media>\r\n</DataSet>\r\n")
        // UTF-8 BOM: the official reference index.xml starts with EF BB BF (audit CONT-5).
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            sb.toString().toByteArray(Charsets.UTF_8)
    }
}
