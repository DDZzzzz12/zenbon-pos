package org.example.project.features.zBon.domain.models

import org.example.project.features.zBon.database.tables.ZBonStammTerminalTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * One terminal as it existed when a Kassenabschluss was taken (DSFinV-K Stamm_Terminals /
 * `slaves.csv`, audit round-7 C3). See [ZBonStammTerminalTable] for the rationale.
 */
class ZBonStammTerminal(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ZBonStammTerminal>(ZBonStammTerminalTable)

    /** The closing this snapshot row belongs to. */
    var zBon by ZBon referencedOn ZBonStammTerminalTable.zBonId

    /** TERMINAL_ID — join key referenced by `bonkopf`. */
    var terminalId by ZBonStammTerminalTable.terminalId

    /** TERMINAL_BRAND at closing time. */
    var terminalBrand by ZBonStammTerminalTable.terminalBrand

    /** TERMINAL_MODELL at closing time. */
    var terminalModell by ZBonStammTerminalTable.terminalModell

    /** TERMINAL_SERIENNR at closing time. */
    var terminalSeriennr by ZBonStammTerminalTable.terminalSeriennr

    /** TERMINAL_SW_BRAND at closing time. */
    var terminalSwBrand by ZBonStammTerminalTable.terminalSwBrand

    /** TERMINAL_SW_VERSION at closing time. */
    var terminalSwVersion by ZBonStammTerminalTable.terminalSwVersion
}
