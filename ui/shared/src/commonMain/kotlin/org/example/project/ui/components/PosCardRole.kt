package org.example.project.ui.components

/**
 * Where a column's cell goes when [PosAdaptiveTable] renders a row as a card.
 *
 * [Detail] is a `label: value` line of its own. [Meta] cells share one wrapping line under the title
 * (`label value · label value`), which keeps a card with many short facts to about three lines
 * (NEW-06) without dropping any of them.
 */
enum class PosCardRole { Title, Trailing, Detail, Meta }
