/**
 * Reusable query filter utilities for excluding training data from production queries.
 *
 * Training employees create bills, orders, cancellations, and sessions that are
 * flagged with `isTraining = true`. These must be excluded from all production
 * reports, statistics, Z-Bon calculations, and revenue aggregations.
 *
 * Usage:
 * ```kotlin
 * val bills = Bill.find { (BillTable.endTime greaterEq start) and TrainingFilter.billNotTraining() }
 * ```
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.database.tables.OrdersTable
import org.example.project.features.table.database.tables.TableSessionTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq

/**
 * Provides reusable filter expressions for excluding training data from queries.
 *
 * Each method returns an [Op] that can be combined with other query conditions
 * using the `and` operator to filter out training-mode records.
 */
object TrainingFilter {
    /** Filter expression excluding training bills from queries */
    fun billNotTraining(): Op<Boolean> = BillTable.isTraining eq false

    /** Filter expression excluding training orders from queries */
    fun orderNotTraining(): Op<Boolean> = OrdersTable.isTraining eq false

    /** Filter expression excluding training cancellations from queries */
    fun cancellationNotTraining(): Op<Boolean> = CancellationTable.isTraining eq false

    /** Filter expression excluding training sessions from queries */
    fun sessionNotTraining(): Op<Boolean> = TableSessionTable.isTraining eq false

    /** Filter expression for training-only bills */
    fun billTrainingOnly(): Op<Boolean> = BillTable.isTraining eq true

    /** Filter expression for training-only orders */
    fun orderTrainingOnly(): Op<Boolean> = OrdersTable.isTraining eq true

    /** Filter expression for training-only cancellations */
    fun cancellationTrainingOnly(): Op<Boolean> = CancellationTable.isTraining eq true

    /** Filter expression for training-only sessions */
    fun sessionTrainingOnly(): Op<Boolean> = TableSessionTable.isTraining eq true

    /** Convenience selector: picks training-only or not-training filter based on flag */
    fun billFilter(trainingOnly: Boolean): Op<Boolean> = if (trainingOnly) billTrainingOnly() else billNotTraining()

    /** Convenience selector: picks training-only or not-training filter based on flag */
    fun orderFilter(trainingOnly: Boolean): Op<Boolean> = if (trainingOnly) orderTrainingOnly() else orderNotTraining()

    /** Convenience selector: picks training-only or not-training filter based on flag */
    fun cancellationFilter(trainingOnly: Boolean): Op<Boolean> = if (trainingOnly) cancellationTrainingOnly() else cancellationNotTraining()

    /** Convenience selector: picks training-only or not-training filter based on flag */
    fun sessionFilter(trainingOnly: Boolean): Op<Boolean> = if (trainingOnly) sessionTrainingOnly() else sessionNotTraining()
}
