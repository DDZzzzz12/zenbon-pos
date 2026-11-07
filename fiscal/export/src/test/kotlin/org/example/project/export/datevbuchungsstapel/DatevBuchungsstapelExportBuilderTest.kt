package org.example.project.export.datevbuchungsstapel

import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DatevBuchungsstapelExportBuilderTest {
    private val sampleConfig =
        BuchungsstapelFileConfig(
            consultantNumber = "29098",
            clientNumber = "55003",
            wirtschaftsjahrBeginn = LocalDate.of(2026, 1, 1),
            sachkontenlaenge = 4,
            festschreibung = false,
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 30),
            bezeichnung = "Kassenbuch 2026-04",
        )

    private val sampleRow =
        BuchungsstapelRowInput(
            umsatz = BigDecimal("100.00"),
            belegDate = LocalDate.of(2026, 4, 21),
            belegNr = "1234",
            buchungstext = "Barverkauf",
            konto = 1000,
            gegenkonto = 8400,
        )

    private val fixedNow = LocalDateTime.of(2026, 4, 21, 14, 30, 12, 345_000_000)

    @Test
    fun `output is three CRLF-terminated rows`() {
        val bytes =
            DatevBuchungsstapelExportBuilder.build(
                sampleConfig,
                rows = listOf(sampleRow),
                now = fixedNow,
            )
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        // exactly three \r\n sequences → metadata, column names, single data row
        val crlfCount = text.split("\r\n").size - 1
        assertEquals(3, crlfCount)
    }

    @Test
    fun `metadata row has 30 semicolons (31 cells)`() {
        val bytes =
            DatevBuchungsstapelExportBuilder.build(
                sampleConfig,
                rows = listOf(sampleRow),
                now = fixedNow,
            )
        val firstRow = String(bytes, StandardCharsets.ISO_8859_1).split("\r\n")[0]
        assertEquals(30, firstRow.count { it == ';' })
    }

    @Test
    fun `column-names row verbatim matches the hardcoded constant`() {
        val bytes =
            DatevBuchungsstapelExportBuilder.build(
                sampleConfig,
                rows = listOf(sampleRow),
                now = fixedNow,
            )
        val secondRow = String(bytes, StandardCharsets.ISO_8859_1).split("\r\n")[1]
        assertEquals(DatevBuchungsstapelExportBuilder.COLUMN_NAMES_ROW, secondRow)
    }

    @Test
    fun `data row has 124 semicolons (125 cells)`() {
        val bytes =
            DatevBuchungsstapelExportBuilder.build(
                sampleConfig,
                rows = listOf(sampleRow),
                now = fixedNow,
            )
        val thirdRow = String(bytes, StandardCharsets.ISO_8859_1).split("\r\n")[2]
        assertEquals(124, thirdRow.count { it == ';' })
    }

    @Test
    fun `multiple data rows produce N plus 2 rows total`() {
        val bytes =
            DatevBuchungsstapelExportBuilder.build(
                sampleConfig,
                rows = listOf(sampleRow, sampleRow, sampleRow),
                now = fixedNow,
            )
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val lineCount = text.split("\r\n").size - 1 // trailing empty after last CRLF
        assertEquals(5, lineCount)
    }

    @Test
    fun `output matches checked-in golden file byte-for-byte`() {
        val actual =
            DatevBuchungsstapelExportBuilder.build(
                sampleConfig,
                rows = listOf(sampleRow),
                now = fixedNow,
            )
        val expected =
            this::class.java
                .getResourceAsStream(
                    "/datevbuchungsstapel/expected_minimal_EXTF_Buchungsstapel.csv",
                )?.readBytes()
                ?: run {
                    // Helpful fallback: dump the actual output so it can be inspected
                    // and the missing resource fixed.
                    val dump = File("/tmp/actual_EXTF_Buchungsstapel.csv")
                    dump.writeBytes(actual)
                    error(
                        "Golden file missing at classpath:/datevbuchungsstapel/" +
                            "expected_minimal_EXTF_Buchungsstapel.csv — dumped actual to ${dump.absolutePath}",
                    )
                }
        assertContentEquals(
            expected,
            actual,
            "generated CSV must match golden fixture byte-for-byte",
        )
    }
}
