package org.example.project.features.dsfinvk.data.service

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.slf4j.LoggerFactory

/**
 * Which terminal recorded a Vorgang — `TERMINAL_ID` in `transactions.csv` (Bonkopf) and
 * `TERMINAL_SERIENNR` in `slaves.csv` (Stamm_Terminals), plus `POS_TERMINAL_ID` in `lines.csv`
 * (Bonpos), which follows its own rule — see [attributeLines].
 *
 * The rule is DSFinV-K 2.4, Anhang E p. 85 (Bonkopf, TERMINAL_ID, Besonderheiten):
 * „Die TERMINAL_ID darf in der Datei Bonkopf.csv nur angegeben werden, wenn die zugehörige
 * TERMINAL_SERIENNR in diesem abzusichernden Vorgang als ClientID an die TSE übergeben wurde."
 * TERMINAL_ID is the join key into `slaves.csv` (Anhang E p. 73: „Sie wird nur zur Referenzierung
 * innerhalb eines Kassenabschlusses verwendet"), and the spec itself equates a Kasse's serial number
 * with its TSE ClientID (Anhang I p. 123, QR code: „Seriennummer (Client-Id) der Kasse").
 *
 * Every till signs under its own TSE client named after its terminal code (`terminals.terminal_id`,
 * e.g. `TER-002`), which is also the slaves.csv TERMINAL_ID. So:
 *
 *  * **slaves.csv** — a terminal's TERMINAL_SERIENNR is its terminal code when **this closing's own
 *    exported Vorgänge prove** it signed as that client. Proof comes from the immutable
 *    `tse_transaction` rows linked to the closing's Vorgänge, never from the live `terminals` table
 *    or its `tse_client_registered` flag, so re-exporting a closing cannot change it after the
 *    fact. Without proof the row keeps the snapshotted hardware serial — nothing is invented.
 *  * **transactions.csv** — TERMINAL_ID is the signing client when that client is a TERMINAL_ID of
 *    the same closing's slaves.csv (whose TERMINAL_SERIENNR is then that client by the rule above).
 *    In every other case it stays empty: unsigned or failed, still pending, SIMULATION (no TSE was
 *    involved), signed by a client that is no terminal of that closing, or a Vorgang outside any
 *    closing.
 *
 * The signature is the authority, not the entity's recorded terminal: when the two disagree the
 * signing client wins and a warning is logged (audit BKOPF-7 / STAM-10, 2026-10-07).
 */
object DsfinvkTerminalAttribution {
    private val logger = LoggerFactory.getLogger(DsfinvkTerminalAttribution::class.java)

    /**
     * One exported Vorgang's TSE evidence.
     *
     * @property bonId the Vorgang's BON_ID, exactly as `transactions.csv` carries it.
     * @property signingClientId the ClientID a real TSE signed this Vorgang under — see
     *   [signingClientOf]; null when no real TSE signature exists.
     * @property recordedTerminalCode the terminal the entity itself records (`bill.terminal`,
     *   `order.terminal`), used only to detect disagreement; null when the entity records none.
     */
    data class Vorgang(
        val bonId: String,
        val signingClientId: String?,
        val recordedTerminalCode: String?,
    )

    /** The two files after attribution. */
    data class Attributed(
        val transactions: List<DsfinvkExportDto.BonkopfRowDto>,
        val stammTerminals: List<DsfinvkExportDto.StammTerminalsRowDto>,
    )

    /**
     * The ClientID a real TSE signed [tx] under, or null when [tx] is not genuine Absicherung:
     * absent, SIMULATION/DISABLED mode, not FINISHED, unsigned, or without a recorded client.
     * The same NORMAL-only rule every `transactions_tse.csv` producer applies (STAM-4/5).
     */
    fun signingClientOf(tx: TseTransaction?): String? {
        if (tx == null || tx.tseMode != TseMode.NORMAL) return null
        if (tx.state != TseTransactionState.FINISHED) return null
        if (tx.signature.isNullOrBlank()) return null
        return tx.clientId?.takeIf { it.isNotBlank() }
    }

    /**
     * Fills TERMINAL_SERIENNR and TERMINAL_ID per the rule in the class KDoc. Rows are returned in
     * their input order; only those two fields ever change.
     */
    fun attribute(
        transactions: List<DsfinvkExportDto.BonkopfRowDto>,
        stammTerminals: List<DsfinvkExportDto.StammTerminalsRowDto>,
        vorgaenge: List<Vorgang>,
    ): Attributed {
        val signingClientByBonId = signingClientsByBonId(vorgaenge)
        val provenByZNr = provenClientsByZNr(transactions, signingClientByBonId)
        val slaves = stammTerminals.map { it.withProvenSerial(provenByZNr[it.zNr].orEmpty()) }
        val attributableByZNr =
            slaves
                .filter { it.terminalSerialNr == it.terminalId }
                .groupBy({ it.zNr }, { it.terminalId })
                .mapValues { (_, ids) -> ids.toSet() }
        val bonkopf = transactions.map { it.withTerminalId(signingClientByBonId[it.bonId], attributableByZNr[it.zNr].orEmpty()) }
        return Attributed(bonkopf, slaves)
    }

    /**
     * Keeps a `lines.csv` POS_TERMINAL_ID only when it names a slaves.csv TERMINAL_ID of the same
     * closing; every other value is emptied. Rows are returned in their input order and only that
     * field ever changes.
     *
     * DSFinV-K 2.4 Anhang E p. 93 (Bonpos, POS_TERMINAL_ID): „ID der Slave-Kasse (Terminal), an der
     * die Position (Line) erfasst wird." It is therefore a reference into `slaves.csv` TERMINAL_ID,
     * the terminal code — never the `terminals` primary key, which matches nothing there. Unlike the
     * Bonkopf TERMINAL_ID (p. 85) it carries no ClientID condition: a line is recorded on a
     * terminal whether or not that terminal signed the Vorgang.
     *
     * Producers fill it two ways: a line recording an ordered item names the terminal the item was
     * entered on ([entryTerminalOf]: the order's signing client, else its recorded terminal — the
     * same value on the order line, the bill line that settles it, its item-discount Rabatt line,
     * its storno and its cancellation; an item with no order at all was entered at the till that
     * settled it and takes its own Bon's TERMINAL_ID, see [OrderLineTerminals.of]); a GV line applied at settlement (promotion Rabatt,
     * Gutschein, Forderung, Trinkgeld) and a Kassenbuch line name the terminal the Bon was settled
     * on, i.e. that Bon's attributed TERMINAL_ID from [terminalIdsByBonId].
     */
    fun attributeLines(
        lines: List<DsfinvkExportDto.BonposRowDto>,
        stammTerminals: List<DsfinvkExportDto.StammTerminalsRowDto>,
    ): List<DsfinvkExportDto.BonposRowDto> {
        val terminalsByZNr = stammTerminals.groupBy({ it.zNr }, { it.terminalId }).mapValues { (_, ids) -> ids.toSet() }
        return lines.map { line ->
            val code = line.posTerminalId ?: return@map line
            if (code in terminalsByZNr[line.zNr].orEmpty()) return@map line
            logger.info(
                "DSFinV-K POS_TERMINAL_ID: Vorgang {} (Z_NR {}) names terminal '{}', which is no terminal of that closing — left empty",
                line.bonId,
                line.zNr,
                code,
            )
            line.copy(posTerminalId = null)
        }
    }

    /**
     * The terminal an order's items were entered on — the POS_TERMINAL_ID of every line recording
     * one of its items (order, bill, storno and cancellation lines, and the item-discount Rabatt).
     *
     * The order's own TSE signature decides: [Vorgang.signingClientId] (see [signingClientOf]) when
     * a real TSE signed the order, otherwise the terminal the order records. Historical orders carry
     * no `terminal_id` at all, yet every one was signed under its till's client, so the signature is
     * what attributes them. When both exist and disagree the signature wins and a warning is logged,
     * as for TERMINAL_ID. The result is a candidate only: [attributeLines] still empties it unless it
     * is a slaves.csv terminal of the line's closing.
     */
    fun entryTerminalOf(order: Vorgang): String? {
        val signed = order.signingClientId
        val recorded = order.recordedTerminalCode
        if (signed != null && recorded != null && signed != recorded) {
            logger.warn(
                "DSFinV-K POS_TERMINAL_ID: order {} records terminal '{}' but was signed by TSE client '{}' — the signature wins",
                order.bonId,
                recorded,
                signed,
            )
        }
        return signed ?: recorded
    }

    /**
     * BON_ID → the attributed transactions.csv TERMINAL_ID, for every Bon that has one — the
     * terminal a Bon was settled on, used as the POS_TERMINAL_ID of its GV and Kassenbuch lines.
     */
    fun terminalIdsByBonId(transactions: List<DsfinvkExportDto.BonkopfRowDto>): Map<String, String> =
        transactions.mapNotNull { row -> row.terminalId?.let { row.bonId to it } }.toMap()

    private fun signingClientsByBonId(vorgaenge: List<Vorgang>): Map<String, String> =
        vorgaenge
            .onEach(::warnOnDisagreement)
            .mapNotNull { v -> v.signingClientId?.let { v.bonId to it } }
            .toMap()

    private fun warnOnDisagreement(v: Vorgang) {
        val signed = v.signingClientId ?: return
        val recorded = v.recordedTerminalCode ?: return
        if (signed == recorded) return
        logger.warn(
            "DSFinV-K TERMINAL_ID: Vorgang {} records terminal '{}' but was signed by TSE client '{}' — the signature wins",
            v.bonId,
            recorded,
            signed,
        )
    }

    /** Per Z_NR, the clients a closing's own Vorgänge prove signed. */
    private fun provenClientsByZNr(
        transactions: List<DsfinvkExportDto.BonkopfRowDto>,
        signingClientByBonId: Map<String, String>,
    ): Map<Long, Set<String>> =
        transactions
            .mapNotNull { row -> signingClientByBonId[row.bonId]?.let { row.zNr to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, clients) -> clients.toSet() }

    private fun DsfinvkExportDto.StammTerminalsRowDto.withProvenSerial(proven: Set<String>): DsfinvkExportDto.StammTerminalsRowDto =
        if (terminalId in proven) copy(terminalSerialNr = terminalId) else this

    private fun DsfinvkExportDto.BonkopfRowDto.withTerminalId(
        signingClient: String?,
        attributable: Set<String>,
    ): DsfinvkExportDto.BonkopfRowDto {
        if (signingClient == null) return copy(terminalId = null)
        if (signingClient in attributable) return copy(terminalId = signingClient)
        logger.info(
            "DSFinV-K TERMINAL_ID: Vorgang {} (Z_NR {}) was signed by TSE client '{}', which is no terminal of that closing — TERMINAL_ID left empty",
            bonId,
            zNr,
            signingClient,
        )
        return copy(terminalId = null)
    }
}
