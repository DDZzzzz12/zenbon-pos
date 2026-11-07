package org.example.project.core.data.local

import app.cash.sqldelight.ColumnAdapter
import org.example.project.money.Decimal

/**
 * Stores money as exact decimal TEXT.
 *
 * SQLite has no decimal type. TEXT is the only storage class that preserves both the value and
 * its scale — REAL is a Double (the defect `:platform-money` exists to remove) and INTEGER cents pins
 * scale at 2, discarding the sub-cent precision the server holds in DECIMAL(19,4).
 *
 * [decode] deliberately does NOT swallow a parse failure. A malformed stored value is a data
 * problem that should surface, unlike a display, where `formatMoneyForDisplay` degrades to a
 * placeholder.
 */
object DecimalColumnAdapter : ColumnAdapter<Decimal, String> {
    override fun decode(databaseValue: String): Decimal = Decimal.parse(databaseValue)

    override fun encode(value: Decimal): String = value.toPlainString()
}
