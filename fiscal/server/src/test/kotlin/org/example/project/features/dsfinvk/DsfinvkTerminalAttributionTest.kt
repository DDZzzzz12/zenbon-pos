package org.example.project.features.dsfinvk

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.dsfinvk.data.service.DsfinvkTerminalAttribution
import org.example.project.features.dsfinvk.data.service.DsfinvkTerminalAttribution.Vorgang
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure TERMINAL_ID / TERMINAL_SERIENNR rule — DSFinV-K 2.4 Anhang E p. 85: TERMINAL_ID only
 * when the slaves.csv TERMINAL_SERIENNR was handed to the TSE as ClientID in that Vorgang.
 * The end-to-end shape (which Vorgänge count as "signed") is pinned by [DsfinvkTerminalIdExportTest].
 */
@DisplayName("DSFinV-K TERMINAL_ID attribution rule")
class DsfinvkTerminalAttributionTest {
    private fun bonkopf(
        bonId: String,
        zNr: Long = 1L,
        terminalId: String? = null,
    ) = DsfinvkExportDto.BonkopfRowDto(
        zKasseId = "K1",
        zErstellung = "2026-10-07T20:00:00",
        zNr = zNr,
        bonId = bonId,
        bonNr = 1L,
        bonTyp = "Beleg",
        bonName = null,
        terminalId = terminalId,
        bonStorno = "0",
        bonStart = null,
        bonEnde = "2026-10-07T19:00:00",
        bedienerId = "E1",
        bedienerName = null,
        umsBrutto = "1.00",
        kundeName = null,
        kundeId = null,
        kundeTyp = null,
        kundeStrasse = null,
        kundePlz = null,
        kundeOrt = null,
        kundeLand = null,
        kundeUstId = null,
        bonNotiz = null,
    )

    private fun slave(
        terminalId: String,
        serial: String?,
        zNr: Long = 1L,
    ) = DsfinvkExportDto.StammTerminalsRowDto(
        zKasseId = "K1",
        zErstellung = "2026-10-07T20:00:00",
        zNr = zNr,
        terminalId = terminalId,
        terminalBrand = null,
        terminalModel = null,
        terminalSerialNr = serial,
        terminalSwBrand = "POS",
        terminalSwVersion = null,
    )

    private fun attribute(
        transactions: List<DsfinvkExportDto.BonkopfRowDto>,
        slaves: List<DsfinvkExportDto.StammTerminalsRowDto>,
        vorgaenge: List<Vorgang>,
    ) = DsfinvkTerminalAttribution.attribute(transactions, slaves, vorgaenge)

    @Test
    fun `a Vorgang signed by a terminal of its closing names that terminal, whose serial becomes the ClientID`() {
        val result = attribute(listOf(bonkopf("B-1")), listOf(slave("TER-002", "HW-2")), listOf(Vorgang("B-1", "TER-002", "TER-002")))

        assertEquals("TER-002", result.transactions.single().terminalId)
        assertEquals("TER-002", result.stammTerminals.single().terminalSerialNr)
    }

    @Test
    fun `an unsigned Vorgang leaves TERMINAL_ID empty, even when its entity records a terminal`() {
        val result = attribute(listOf(bonkopf("B-1", terminalId = "stale")), listOf(slave("TER-002", "HW-2")), listOf(Vorgang("B-1", null, "TER-002")))

        assertNull(result.transactions.single().terminalId)
        assertEquals("HW-2", result.stammTerminals.single().terminalSerialNr, "no proof, so the stored serial stays")
    }

    @Test
    fun `a Vorgang signed by a client that is no terminal of the closing leaves TERMINAL_ID empty`() {
        val result =
            attribute(
                listOf(bonkopf("B-1")),
                listOf(slave("TER-002", "HW-2")),
                listOf(Vorgang("B-1", "CLIENT-FOR-MAINTENANCE", null)),
            )

        assertNull(result.transactions.single().terminalId)
        assertEquals("HW-2", result.stammTerminals.single().terminalSerialNr)
    }

    @Test
    fun `a terminal that signed nothing in the closing keeps its stored serial`() {
        val result =
            attribute(
                listOf(bonkopf("B-1")),
                listOf(slave("TER-001", "HW-1"), slave("TER-002", null)),
                listOf(Vorgang("B-1", "TER-001", "TER-001")),
            )

        assertEquals(listOf("TER-001", null), result.stammTerminals.map { it.terminalSerialNr })
    }

    @Test
    fun `proof is per closing - signing in one closing does not rewrite the terminal row of another`() {
        val result =
            attribute(
                listOf(bonkopf("B-1", zNr = 1L), bonkopf("B-2", zNr = 2L)),
                listOf(slave("TER-001", "HW-1", zNr = 1L), slave("TER-001", "HW-1", zNr = 2L)),
                listOf(Vorgang("B-1", "TER-001", null), Vorgang("B-2", null, null)),
            )

        assertEquals(listOf("TER-001", null), result.transactions.map { it.terminalId })
        assertEquals(listOf("TER-001", "HW-1"), result.stammTerminals.map { it.terminalSerialNr })
    }

    @Test
    fun `a Vorgang outside any closing is never attributed`() {
        val result = attribute(listOf(bonkopf("B-1", zNr = 0L)), listOf(slave("TER-001", "HW-1")), listOf(Vorgang("B-1", "TER-001", null)))

        assertNull(result.transactions.single().terminalId)
        assertEquals("HW-1", result.stammTerminals.single().terminalSerialNr)
    }

    @Test
    fun `when the recorded terminal and the signing client disagree, the signature wins`() {
        val result =
            attribute(
                listOf(bonkopf("B-1")),
                listOf(slave("TER-001", "HW-1"), slave("TER-002", "HW-2")),
                listOf(Vorgang("B-1", "TER-002", "TER-001")),
            )

        assertEquals("TER-002", result.transactions.single().terminalId)
        assertEquals(listOf("HW-1", "TER-002"), result.stammTerminals.map { it.terminalSerialNr })
    }

    private fun line(
        bonId: String,
        posTerminalId: String?,
        zNr: Long = 1L,
    ) = DsfinvkExportDto.BonposRowDto(
        zKasseId = "K1",
        zErstellung = "2026-10-07T20:00:00",
        zNr = zNr,
        bonId = bonId,
        posZeile = "1",
        gutscheinNr = null,
        artikeltext = "Cola",
        posTerminalId = posTerminalId,
        gvTyp = "Umsatz",
        gvName = null,
        inhaus = "1",
        pStorno = "0",
        agenturId = null,
        artNr = "1",
        gtin = null,
        warengrId = null,
        warengr = null,
        menge = "1.000",
        faktor = "1.000",
        einheit = "Stück",
        stkBr = "3.00",
    )

    // POS_TERMINAL_ID — Anhang E p. 93: „ID der Slave-Kasse (Terminal), an der die Position (Line)
    // erfasst wird." A reference into slaves.csv TERMINAL_ID, without the p. 85 ClientID condition.

    @Test
    fun `a line keeps its terminal code when that terminal is a slaves row of its closing`() {
        val result = DsfinvkTerminalAttribution.attributeLines(listOf(line("O-1", "TER-009")), listOf(slave("TER-009", "HW-9")))

        assertEquals("TER-009", result.single().posTerminalId)
    }

    @Test
    fun `a line naming no terminal of its closing - a database id or another closing's terminal - is emptied`() {
        val result =
            DsfinvkTerminalAttribution.attributeLines(
                listOf(line("O-1", "1"), line("O-2", "TER-002", zNr = 1L), line("O-3", null)),
                listOf(slave("TER-001", "HW-1", zNr = 1L), slave("TER-002", "HW-2", zNr = 2L)),
            )

        assertEquals(listOf(null, null, null), result.map { it.posTerminalId })
    }

    @Test
    fun `line attribution changes only POS_TERMINAL_ID and keeps the input order`() {
        val input = listOf(line("O-2", "TER-001"), line("O-1", "1"))

        val result = DsfinvkTerminalAttribution.attributeLines(input, listOf(slave("TER-001", "HW-1")))

        assertEquals(listOf(input[0], input[1].copy(posTerminalId = null)), result)
    }

    @Test
    fun `terminalIdsByBonId maps every attributed Bon to its TERMINAL_ID and skips the empty ones`() {
        val rows = listOf(bonkopf("B-1", terminalId = "TER-001"), bonkopf("KB-2"), bonkopf("O-3", terminalId = "TER-009"))

        assertEquals(mapOf("B-1" to "TER-001", "O-3" to "TER-009"), DsfinvkTerminalAttribution.terminalIdsByBonId(rows))
    }

    // entryTerminalOf — the till an order's items were entered on (POS_TERMINAL_ID of item lines).

    @Test
    fun `an order without a recorded terminal is attributed to the client that signed it`() {
        assertEquals("TER-005", DsfinvkTerminalAttribution.entryTerminalOf(Vorgang("O-1", "TER-005", null)))
    }

    @Test
    fun `an unsigned order falls back to its recorded terminal, and with neither stays unattributed`() {
        assertEquals("TER-001", DsfinvkTerminalAttribution.entryTerminalOf(Vorgang("O-1", null, "TER-001")))
        assertNull(DsfinvkTerminalAttribution.entryTerminalOf(Vorgang("O-2", null, null)))
    }

    @Test
    fun `when an order's recorded terminal and its signing client disagree, the signature wins and a warning is logged`() {
        val logger = LoggerFactory.getLogger(DsfinvkTerminalAttribution::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertEquals("TER-005", DsfinvkTerminalAttribution.entryTerminalOf(Vorgang("O-7", "TER-005", "TER-001")))
        } finally {
            logger.detachAppender(appender)
        }
        val warning = appender.list.single { it.level == Level.WARN }
        assertTrue(warning.formattedMessage.contains("O-7") && warning.formattedMessage.contains("TER-005"), warning.formattedMessage)
    }
}
