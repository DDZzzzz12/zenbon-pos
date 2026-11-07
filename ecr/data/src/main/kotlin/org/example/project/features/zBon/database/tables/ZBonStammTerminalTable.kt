package org.example.project.features.zBon.database.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Per-closing Stamm_Terminals snapshot — DSFinV-K Tz. 3.2 (p.23), audit round-7 C3.
 *
 * `slaves.csv` used to be produced from the LIVE `terminals` table for every Z_NR, so re-branding
 * a tablet, upgrading a client, or simply registering a new terminal changed the bytes of every
 * already-delivered Kassenabschluss — and made closings claim terminals that did not exist yet.
 * The Stammdaten belong to the closing, so the closing carries its own copy.
 *
 * One row per (closing, terminal) captured at closing time. `Z_KASSE_ID`, `Z_NR` and
 * `Z_ERSTELLUNG` are NOT stored: they are join keys derived from the parent closing and the
 * Kassen-Seriennummer, and duplicating them here would let them drift out of the joins.
 * [terminalId] IS stored — it is the TERMINAL_ID join key that `bonkopf` references, and the SET
 * of terminal ids present at a closing is itself the closing-scoped fact being recorded.
 *
 * Whether a snapshot exists at all is decided by [ZBonTable.stammKasseSnapshotAt], never by the
 * presence of rows here: a closing may legitimately have had zero terminals.
 */
object ZBonStammTerminalTable : LongIdTable("zbon_stamm_terminals", "id") {
    /** The closing this snapshot row belongs to. */
    val zBonId = reference("zbon_id", ZBonTable, onDelete = ReferenceOption.CASCADE)

    /** TERMINAL_ID — the human-readable client id (`terminals.terminal_id`), a join key. */
    val terminalId = varchar("terminal_id", 50)

    /** TERMINAL_BRAND at closing time. */
    val terminalBrand = varchar("terminal_brand", 50).nullable()

    /** TERMINAL_MODELL at closing time. */
    val terminalModell = varchar("terminal_modell", 50).nullable()

    /** TERMINAL_SERIENNR at closing time. */
    val terminalSeriennr = varchar("terminal_seriennr", 70).nullable()

    /** TERMINAL_SW_BRAND at closing time. NOT NULL — `terminals.terminal_sw_brand` is not nullable. */
    val terminalSwBrand = varchar("terminal_sw_brand", 50)

    /** TERMINAL_SW_VERSION at closing time. */
    val terminalSwVersion = varchar("terminal_sw_version", 50).nullable()

    init {
        index(false, zBonId)
    }
}
