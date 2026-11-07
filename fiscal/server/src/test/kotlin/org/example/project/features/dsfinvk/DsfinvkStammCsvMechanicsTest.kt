package org.example.project.features.dsfinvk

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.export.dsfinvk.builder.StammAgenturenCsvExporter
import org.example.project.export.dsfinvk.builder.StammTerminalsCsvExporter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for H7: `StammTerminalsCsvExporter` (slaves.csv) and
 * `StammAgenturenCsvExporter` (pa.csv) must use the DSFinV-K-mandated CRLF line ending
 * and must escape string fields — otherwise a `;`/`"` inside a field (e.g. a terminal
 * brand or agency name) corrupts row arity and a bare `\n` fails strict DSFinV-K validators.
 */
@DisplayName("DSFinV-K Stamm_Terminals/Stamm_Agenturen — CRLF + escaping mechanics")
class DsfinvkStammCsvMechanicsTest {
    @Test
    fun `slaves csv uses CRLF and escapes a terminal brand containing semicolon and quote`() {
        val row =
            DsfinvkExportDto.StammTerminalsRowDto(
                zKasseId = "KASSE-1",
                zErstellung = "2026-07-11T00:00:00.000+02:00",
                zNr = 1L,
                terminalId = "TERM-1",
                terminalBrand = "Acme; Corp \"XL\"",
                terminalModel = "Model-X",
                terminalSerialNr = "SN-1",
                terminalSwBrand = "AcmeSW",
                terminalSwVersion = "1.0",
            )

        val csv = StammTerminalsCsvExporter.generateCsv(listOf(row)).decodeToString()

        assertCrlfLineEndings(csv)

        val lines = csv.splitCrlf()
        val header = lines[0]
        val dataLine = lines[1]

        val headerFields = splitCsvRow(header)
        val dataFields = splitCsvRow(dataLine)
        assertEquals(
            headerFields.size,
            dataFields.size,
            "header/data column arity must match; header=$headerFields data=$dataFields",
        )

        // The offending field must be quoted with internal quotes doubled, and the
        // embedded semicolon must NOT have split the row into extra columns.
        assertTrue(
            dataFields.contains("\"Acme; Corp \"\"XL\"\"\""),
            "expected the raw brand field to be quoted with doubled quotes; got: $dataFields",
        )
    }

    @Test
    fun `pa csv uses CRLF and escapes an agency name containing semicolon and quote`() {
        val row =
            DsfinvkExportDto.StammAgenturenRowDto(
                zKasseId = "KASSE-1",
                zErstellung = "2026-07-11T00:00:00.000+02:00",
                zNr = 1L,
                agenturId = 1,
                agenturName = "Best; Agency \"GmbH\"",
                agenturStrasse = "Main St 1",
                agenturPlz = "12345",
                agenturOrt = "Berlin",
                agenturLand = "DE",
                agenturStNr = "123/456/789",
                agenturUstId = "DE123456789",
            )

        val csv = StammAgenturenCsvExporter.generateCsv(listOf(row)).decodeToString()

        assertCrlfLineEndings(csv)

        val lines = csv.splitCrlf()
        val header = lines[0]
        val dataLine = lines[1]

        val headerFields = splitCsvRow(header)
        val dataFields = splitCsvRow(dataLine)
        assertEquals(
            headerFields.size,
            dataFields.size,
            "header/data column arity must match; header=$headerFields data=$dataFields",
        )

        assertTrue(
            dataFields.contains("\"Best; Agency \"\"GmbH\"\"\""),
            "expected the raw agency name field to be quoted with doubled quotes; got: $dataFields",
        )
    }

    /** Asserts every line break in [csv] is a full `\r\n`, i.e. no bare `\n` (or lone `\r`). */
    private fun assertCrlfLineEndings(csv: String) {
        assertTrue(csv.contains("\r\n"), "expected CRLF line endings; got bytes: ${csv.toByteArray().toList()}")
        val withoutCrlf = csv.replace("\r\n", "")
        assertFalse(withoutCrlf.contains("\n"), "found a bare LF not preceded by CR: $csv")
        assertFalse(withoutCrlf.contains("\r"), "found a lone CR not followed by LF: $csv")
    }

    /** Splits CSV content on `\r\n`, dropping the trailing empty segment from the final line ending. */
    private fun String.splitCrlf(): List<String> = this.split("\r\n").filter { it.isNotEmpty() }

    /**
     * Quote-aware split of a single CSV row on `;`, keeping quoted fields (which may contain
     * `;` and doubled `""`) intact as single tokens instead of splitting on their internal `;`.
     */
    private fun splitCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < row.length && row[i + 1] == '"') {
                        current.append("\"\"")
                        i++
                    } else {
                        current.append('"')
                        inQuotes = !inQuotes
                    }
                }
                c == ';' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
