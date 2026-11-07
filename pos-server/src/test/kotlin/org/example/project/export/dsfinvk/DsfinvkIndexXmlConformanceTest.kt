package org.example.project.export.dsfinvk

import org.example.project.export.dsfinvk.builder.DsfinvkIndexXmlGenerator
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DsfinvkIndexXmlConformanceTest {
    private data class Col(
        val name: String,
        val type: String,
        val size: String,
    )

    private data class Table(
        val url: String,
        val name: String,
        val cols: List<Col>,
    )

    private fun parseTables(xml: ByteArray): List<Table> {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val doc = dbf.newDocumentBuilder().parse(xml.inputStream())
        val tables = doc.getElementsByTagName("Table")
        return (0 until tables.length).map { i ->
            val t = tables.item(i) as Element
            val cols = t.getElementsByTagName("VariableColumn")
            Table(
                url = t.getElementsByTagName("URL").item(0).textContent,
                name = t.getElementsByTagName("Name").item(0).textContent,
                cols =
                    (0 until cols.length).map { j ->
                        val c = cols.item(j) as Element
                        val name = c.getElementsByTagName("Name").item(0).textContent
                        val numeric = c.getElementsByTagName("Numeric").length > 0
                        val acc = c.getElementsByTagName("Accuracy").let { if (it.length > 0) it.item(0).textContent else "" }
                        val len = c.getElementsByTagName("MaxLength").let { if (it.length > 0) it.item(0).textContent else "" }
                        Col(name, if (numeric) "Numeric" else "AlphaNumeric", if (numeric) acc else len)
                    },
            )
        }
    }

    @Test
    fun `generated index_xml matches the reference GDPdU DataSet structure`() {
        val reference = javaClass.getResourceAsStream("/dsfinvk/reference_index.xml")!!.readBytes()
        val generated =
            DsfinvkIndexXmlGenerator.generateIndexXml(
                shopName = "Test",
                startDate = "2026-01-01",
                endDate = "2026-01-31",
                transactionCount = 0,
                zBonCount = 0,
            )
        assertTrue(
            generated.decodeToString().contains("<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">"),
            "index.xml must declare the GDPdU DTD",
        )
        assertTrue(
            generated.decodeToString().contains("<Version>1.0</Version>"),
            "index.xml must declare DataSet Version 1.0",
        )
        assertEquals(
            parseTables(reference),
            parseTables(generated),
            "generated table/column schema must match the reference exactly",
        )
    }
}
