package org.example.project.features.customexport.domain.catalogue

import org.jetbrains.exposed.v1.core.Column

/**
 * One column of a registered table, as an operator sees it.
 *
 * @property label the German heading offered in the picker. Written by hand, never derived from the
 *   column name: a German admin panel offering "Default account no" is a panel nobody uses, and
 *   deriving would also expose every internal flag and timestamp the moment a table is registered.
 * @property type how the value is rendered. [ExportFieldType.MONEY] is a security claim and must be
 *   stated deliberately — it skips the CSV formula guard. Never infer it from a decimal column: a
 *   quantity and an amount have the same SQL type and different rules.
 * @property legacyKey a key this column used to be published under, honoured when resolving a stored
 *   definition. Set it when renaming the database column; without it, every saved export naming the
 *   old key breaks. Unused until a column is actually renamed.
 */
data class ExportColumn(
    val column: Column<*>,
    val label: String,
    val type: ExportFieldType = ExportFieldType.TEXT,
    val legacyKey: String? = null,
)
