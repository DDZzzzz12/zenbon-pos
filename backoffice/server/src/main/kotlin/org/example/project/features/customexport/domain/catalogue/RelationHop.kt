package org.example.project.features.customexport.domain.catalogue

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * One join in a path: from a column already in the query to a column of [target].
 *
 * Always applied as a LEFT join — a partner with no creditor role is still a partner, and an inner
 * join would silently drop exactly the rows an operator is most likely hunting for.
 */
data class RelationHop(
    val target: Table,
    val from: Column<*>,
    val to: Column<*>,
)
