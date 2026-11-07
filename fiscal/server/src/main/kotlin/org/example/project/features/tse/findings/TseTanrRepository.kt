package org.example.project.features.tse.findings

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.example.project.utils.formatter.TseSerialFormat
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select

/** Every NORMAL-mode TANR the POS recorded, keyed by hex serial. Requires an ambient transaction. */
class TseTanrRepository {
    fun rows(): List<TanrRow> =
        TseTransactionTable
            .select(
                TseTransactionTable.tseSerialHex,
                TseTransactionTable.tseSerialNumber,
                TseTransactionTable.transactionNumber,
                TseTransactionTable.startTime,
                TseTransactionTable.clientId,
            ).where { (TseTransactionTable.tseMode eq TseMode.NORMAL) and TseTransactionTable.transactionNumber.isNotNull() }
            .mapNotNull { r ->
                val serial =
                    r[TseTransactionTable.tseSerialHex]
                        ?: TseSerialFormat.normalizeToHex(r[TseTransactionTable.tseSerialNumber])
                val tanr = r[TseTransactionTable.transactionNumber]
                if (serial.isNullOrBlank() ||
                    tanr == null
                ) {
                    null
                } else {
                    TanrRow(serial.uppercase(), tanr, r[TseTransactionTable.startTime], r[TseTransactionTable.clientId])
                }
            }

    fun knownTanrsBySerial(): Map<String, Set<Long>> = rows().bySerial()

    companion object {
        /** [rows] as TANR sets keyed by hex serial — the shape [knownTanrsBySerial] returns. */
        fun List<TanrRow>.bySerial(): Map<String, Set<Long>> = groupBy({ it.serialHex }, { it.tanr }).mapValues { it.value.toSet() }
    }
}
