package org.example.project.export.csv

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The byte-level contract with Excel.
 *
 * Asserted as bytes, not as a decoded String: a decode re-applies the writer's own assumption, which
 * is exactly how an encoding defect survived every test of the DATEV writer in this same module.
 */
class SpreadsheetCsvWriterTest {
    @Test
    fun `the file opens with a UTF-8 BOM`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf("a")) }.toByteArray()

        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3).toByteArray())
    }

    /** Without the BOM Excel reads the file as the system code page and mangles every umlaut. */
    @Test
    fun `an umlaut is written as UTF-8 after the BOM`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf("Müller")) }.toByteArray()

        assertContentEquals(
            byteArrayOf(0x4D, 0xC3.toByte(), 0xBC.toByte(), 0x6C, 0x6C, 0x65, 0x72),
            bytes.drop(3).take(7).toByteArray(),
        )
    }

    @Test
    fun `fields are semicolon separated and lines are CRLF terminated`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf("a", "b")) }.toByteArray()

        assertEquals("a;b\r\n", bytes.drop(3).toByteArray().toString(Charsets.UTF_8))
    }

    /**
     * A value carrying the delimiter, a quote or a newline must not be able to invent a column or a
     * row — an export whose row count depends on its content is not an export.
     */
    @Test
    fun `a value containing a delimiter, a quote or a newline is quoted and escaped`() {
        val bytes =
            SpreadsheetCsvWriter()
                .apply { writeRow(listOf("a;b", "say \"hi\"", "one\ntwo")) }
                .toByteArray()

        assertEquals(
            "\"a;b\";\"say \"\"hi\"\"\";\"one\ntwo\"\r\n",
            bytes.drop(3).toByteArray().toString(Charsets.UTF_8),
        )
    }

    /** A leading `=`, `+`, `-` or `@` makes Excel evaluate the cell as a formula. */
    @Test
    fun `a value that Excel would treat as a formula is neutralised`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf("=1+1", "@SUM(A1)")) }.toByteArray()
        val text = bytes.drop(3).toByteArray().toString(Charsets.UTF_8)

        assertTrue(text.startsWith("'=1+1;'@SUM(A1)"), text)
    }

    /**
     * The whole point of [CsvValue]: a rendered amount must stay a number in Excel.
     *
     * Guarding it would prefix every negative amount with an apostrophe, which Excel imports as
     * text — a column of amounts that will not sum is exactly the defect this distinction prevents.
     */
    @Test
    fun `a rendered numeric value is not formula-guarded`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf(CsvValue.Numeric("-12,50"))) }.toByteArray()

        assertEquals("-12,50\r\n", bytes.drop(3).toByteArray().toString(Charsets.UTF_8))
    }

    /** The other half of the pair: operator-entered text keeps the guard, leading `-` included. */
    @Test
    fun `operator-entered text starting with a formula lead is still guarded`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf(CsvValue.Text("-1+1"))) }.toByteArray()

        assertEquals("\'-1+1\r\n", bytes.drop(3).toByteArray().toString(Charsets.UTF_8))
    }

    /** A [Numeric] still cannot be allowed to invent a column — only the formula guard is skipped. */
    @Test
    fun `a numeric value carrying the delimiter is still quoted`() {
        val bytes = SpreadsheetCsvWriter().apply { writeRow(listOf(CsvValue.Numeric("1;2"))) }.toByteArray()

        assertEquals("\"1;2\"\r\n", bytes.drop(3).toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun `the BOM is written once, not per row`() {
        val bytes =
            SpreadsheetCsvWriter()
                .apply {
                    writeRow(listOf("a"))
                    writeRow(listOf("b"))
                }.toByteArray()

        assertEquals(1, bytes.toList().windowed(3).count { it == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) })
    }
}
