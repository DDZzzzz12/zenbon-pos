package org.example.project.features.monthlyclose.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.tse.database.tables.TseTransactionTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Counts Vorgänge (bills, Kassenbuch entries, orders, cancellations) in a window that are marked SIGNED but whose signature came from the
 * in-process simulator (serial "SIM-TSE-001"). Requires an ambient transaction.
 */
class SimulatedSignatureRepository {
    fun countSimulatedButSigned(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int {
        val bills =
            BillTable
                .innerJoin(TseTransactionTable, { BillTable.tseTransactionId }, { TseTransactionTable.id })
                .selectAll()
                .where {
                    (BillTable.endTime greaterEq start) and (BillTable.endTime less end) and
                        (BillTable.tseSigningStatus eq TseSigningStatus.SIGNED) and
                        (
                            (TseTransactionTable.tseSerialNumber inList SIMULATED_SERIALS) or
                                (TseTransactionTable.tseSerialHex inList SIMULATED_SERIALS)
                        )
                }.count()
        val entries =
            KassenbuchHeaderTable
                .innerJoin(TseTransactionTable, { KassenbuchHeaderTable.tseTransactionId }, { TseTransactionTable.id })
                .selectAll()
                .where {
                    (KassenbuchHeaderTable.createdAt greaterEq start) and (KassenbuchHeaderTable.createdAt less end) and
                        (KassenbuchHeaderTable.tseSigningStatus eq TseSigningStatus.SIGNED) and
                        (
                            (TseTransactionTable.tseSerialNumber inList SIMULATED_SERIALS) or
                                (TseTransactionTable.tseSerialHex inList SIMULATED_SERIALS)
                        )
                }.count()
        // Orders (Bestellung-V1) and cancellations are signed Vorgänge too; a live database held 6
        // simulated order signatures that a bills-and-Kassenbuch-only count missed.
        val orders =
            OrdersTable
                .innerJoin(TseTransactionTable, { OrdersTable.tseTransactionId }, { TseTransactionTable.id })
                .selectAll()
                .where {
                    (OrdersTable.orderTime greaterEq start) and (OrdersTable.orderTime less end) and
                        (OrdersTable.tseSigningStatus eq TseSigningStatus.SIGNED) and
                        (
                            (TseTransactionTable.tseSerialNumber inList SIMULATED_SERIALS) or
                                (TseTransactionTable.tseSerialHex inList SIMULATED_SERIALS)
                        )
                }.count()
        val cancellations =
            CancellationTable
                .innerJoin(TseTransactionTable, { CancellationTable.tseTransactionId }, { TseTransactionTable.id })
                .selectAll()
                .where {
                    (CancellationTable.createdAt greaterEq start) and (CancellationTable.createdAt less end) and
                        (CancellationTable.tseSigningStatus eq TseSigningStatus.SIGNED) and
                        (
                            (TseTransactionTable.tseSerialNumber inList SIMULATED_SERIALS) or
                                (TseTransactionTable.tseSerialHex inList SIMULATED_SERIALS)
                        )
                }.count()
        return (bills + entries + orders + cancellations).toInt()
    }

    companion object {
        /** Base64("SIM-TSE-001") — the serial performSimulated returns. */
        const val SIMULATED_SERIAL = "U0lNLVRTRS0wMDE="

        /**
         * Every stored form of the simulator's serial "SIM-TSE-001". Only matching the Base64 form
         * made this check report PASSED on a live database holding 21 simulated signatures: they are
         * persisted hex-encoded (`53494D2D5453452D303031`), and the start operation returns it plain.
         *
         * Matched against BOTH serial columns (V82): a pre-V82 row carries its form only in
         * `tse_serial_number`, and V82 copies exactly these short values across verbatim.
         */
        val SIMULATED_SERIALS = listOf(SIMULATED_SERIAL, "53494D2D5453452D303031", "SIM-TSE-001")
    }
}
